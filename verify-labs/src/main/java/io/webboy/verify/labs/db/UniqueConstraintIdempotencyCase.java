package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q61 · Q100 — 멱등성의 근거를 애플리케이션 메모리가 아니라 DB 제약으로 옮긴다.
 *
 * <p>{@code RES-01} 은 같은 명제를 {@code ConcurrentHashMap} 으로 검증한다. 그것은
 * <b>단일 프로세스 안에서만</b> 유효한 보장이라 {@code docs/02-정직한-고지.md} §5 에 한계로 적어 두었다.
 * PostgreSQL 이 들어오면서 그 한계를 실제로 메울 수 있게 됐다 — 서로 다른 커넥션 20개가
 * 동시에 같은 멱등 키로 들어와도 유니크 제약이 정확히 1건만 통과시킨다.
 */
@Component
public class UniqueConstraintIdempotencyCase extends VerificationCase {

    private static final int CONCURRENT_RETRIES = 20;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public UniqueConstraintIdempotencyCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-12";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "재시도로 같은 요청이 여러 번 들어와도 결제가 한 번만 되게 하려면 어떻게 합니까?";
    }

    @Override
    public String claim() {
        return "멱등 키에 유니크 제약을 걸면 인스턴스가 여러 대여도 부수효과는 정확히 1회다 — 조회 후 삽입(check-then-act)은 동시성에서 새고, 23505 를 '이미 처리됨'으로 해석하는 것이 정석이다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Result withConstraint = attemptAll(true);
        Result withoutConstraint = attemptAll(false);

        evidence.fact("동시 재시도 수", CONCURRENT_RETRIES);
        evidence.fact("[유니크 제약 있음] 성공한 삽입 수", withConstraint.inserted);
        evidence.fact("[유니크 제약 있음] 중복으로 거절된 수", withConstraint.duplicates);
        evidence.fact("[유니크 제약 있음] 거절 SQLState", withConstraint.error);
        evidence.fact("[유니크 제약 있음] 실제 결제 실행 횟수", withConstraint.sideEffects);
        evidence.fact("[제약 없이 조회 후 삽입] 실제 결제 실행 횟수", withoutConstraint.sideEffects);

        evidence.expectEquals("유니크 제약이 있으면 부수효과는 정확히 1회다", 1, withConstraint.sideEffects);
        evidence.expectEquals("나머지 재시도는 전부 중복으로 거절된다",
                CONCURRENT_RETRIES - 1, withConstraint.duplicates);
        evidence.expect("중복 거절은 unique_violation(23505)이다", withConstraint.error.contains("23505"));
        evidence.expect("제약 없이 '조회 후 삽입' 하면 중복 실행이 발생한다", withoutConstraint.sideEffects > 1);

        jdbc.execute("DROP TABLE IF EXISTS idem_demo");

        evidence.note("RES-01 은 같은 명제를 단일 JVM 의 ConcurrentHashMap 으로 검증한다. 인스턴스가 늘면 그 보장은 사라지지만, 이 케이스의 유니크 제약은 인스턴스 수와 무관하게 성립한다 — 분산 환경에서 믿을 것은 DB 제약이나 Redis SET NX 다.");
        evidence.note("23505 를 '오류'로 처리해 500 을 내면 클라이언트가 다시 재시도한다. '이미 처리된 요청'으로 해석해 최초 결과를 그대로 돌려주는 것이 멱등 API 의 계약이다.");
        evidence.note("INSERT ... ON CONFLICT DO NOTHING 으로 예외 없이 같은 효과를 낼 수 있다. 다만 '내가 넣은 것인지 남이 넣은 것인지'를 RETURNING 으로 구분해야 응답을 만들 수 있다.");
        evidence.note("멱등 키 행은 무한히 쌓이므로 보존 기간을 정해 파티션(DB-08)이나 배치로 지운다.");
    }

    private Result attemptAll(boolean withUniqueConstraint) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS idem_demo");
        jdbc.execute("CREATE TABLE idem_demo (idempotency_key text " + (withUniqueConstraint ? "UNIQUE" : "") + ", charged_at timestamptz DEFAULT now())");

        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_RETRIES);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_RETRIES);
        try {
            for (int i = 0; i < CONCURRENT_RETRIES; i++) {
                executor.submit(() -> {
                    try (Connection connection = dataSource.getConnection()) {
                        start.await();
                        if (withUniqueConstraint) {
                            claimByConstraint(connection, inserted, duplicates, errors);
                        } else {
                            claimByCheckThenAct(connection, inserted);
                        }
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName());
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Long rows = jdbc.queryForObject("SELECT count(*) FROM idem_demo", Long.class);
        String error = errors.isEmpty() ? "-" : errors.get(0);
        return new Result(inserted.get(), duplicates.get(), rows == null ? -1 : rows.intValue(), error);
    }

    /** 정석: 삽입을 먼저 시도하고, 유니크 위반이면 '이미 처리됨'으로 해석한다. */
    private void claimByConstraint(Connection connection, AtomicInteger inserted,
                                   AtomicInteger duplicates, List<String> errors) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT INTO idem_demo (idempotency_key) VALUES ('order-42')")) {
            ps.executeUpdate();
            inserted.incrementAndGet();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                duplicates.incrementAndGet();
                errors.add("SQLState=" + e.getSQLState() + " (unique_violation)");
            } else {
                throw e;
            }
        }
    }

    /** 흔한 실수: 있는지 보고 없으면 넣는다. 두 문장 사이에 다른 트랜잭션이 끼어든다. */
    private void claimByCheckThenAct(Connection connection, AtomicInteger inserted) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT count(*) FROM idem_demo WHERE idempotency_key = 'order-42'");
             var rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) > 0) {
                return;
            }
        }
        try (var ps = connection.prepareStatement("INSERT INTO idem_demo (idempotency_key) VALUES ('order-42')")) {
            ps.executeUpdate();
            inserted.incrementAndGet();
        }
    }

    /** 삽입에 성공한 횟수 = 실제 결제가 실행된 횟수. */
    private record Result(int inserted, int duplicates, int sideEffects, String error) {}
}
