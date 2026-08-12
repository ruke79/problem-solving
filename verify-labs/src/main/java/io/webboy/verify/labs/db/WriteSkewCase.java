package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Q97 확장 — 락을 안 걸고도 막을 수 있는 세 번째 선택지가 SERIALIZABLE 이다.
 *
 * <p>write skew(쓰기 왜곡): 두 트랜잭션이 각자 "조건을 확인하고" 각자 다른 행을 고치는데,
 * 합치면 불변식이 깨지는 현상. 두 트랜잭션이 같은 행을 건드리지 않으므로
 * 낙관적 락(버전 충돌)도 REPEATABLE READ 도 잡지 못한다.
 *
 * <p>H2 로는 검증할 수 없던 케이스다. PostgreSQL 의 SERIALIZABLE 은 SSI(Serializable Snapshot
 * Isolation) 구현이라 이 패턴을 실제로 탐지해 한쪽을 40001 로 끊는다.
 */
@Component
public class WriteSkewCase extends VerificationCase {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public WriteSkewCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-09";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "'당직은 최소 1명' 같은 불변식을 동시성 아래에서 어떻게 지킵니까?";
    }

    @Override
    public String claim() {
        return "각자 다른 행을 고치는 write skew 는 REPEATABLE READ 로도 낙관적 락으로도 막히지 않는다 — SERIALIZABLE 이면 한쪽이 40001 로 취소되어 불변식이 지켜진다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Result repeatableRead = runPair(Connection.TRANSACTION_REPEATABLE_READ);
        Result serializable = runPair(Connection.TRANSACTION_SERIALIZABLE);

        evidence.fact("[REPEATABLE READ] 실패한 트랜잭션 수", repeatableRead.failures);
        evidence.fact("[REPEATABLE READ] 발생한 오류", repeatableRead.error);
        evidence.fact("[REPEATABLE READ] 최종 당직 인원", repeatableRead.remainingOnDuty);
        evidence.fact("[SERIALIZABLE] 실패한 트랜잭션 수", serializable.failures);
        evidence.fact("[SERIALIZABLE] 발생한 오류", serializable.error);
        evidence.fact("[SERIALIZABLE] 최종 당직 인원", serializable.remainingOnDuty);

        evidence.expectEquals("REPEATABLE READ 에서는 둘 다 커밋되어 불변식이 깨진다(당직 0명)",
                0, repeatableRead.remainingOnDuty);
        evidence.expectEquals("SERIALIZABLE 에서는 한쪽이 취소되어 당직 1명이 남는다",
                1, serializable.remainingOnDuty);
        evidence.expect("SERIALIZABLE 의 취소는 직렬화 실패(40001)다",
                serializable.error.contains("40001"));

        jdbc.execute("DROP TABLE IF EXISTS oncall_demo");

        evidence.note("write skew 는 '같은 행을 안 건드리므로' 행 단위 락도 버전 컬럼도 못 잡는다. 읽은 것과 쓴 것의 의존 관계를 추적하는 SSI 라야 잡힌다.");
        evidence.note("대가는 재시도다. SERIALIZABLE 은 충돌을 막는 게 아니라 '틀린 결과 대신 40001 을 준다' — 애플리케이션에 재시도 루프가 반드시 있어야 한다(DB-07 과 같은 원칙).");
        evidence.note("락으로 푸는 대안은 조건을 한 행에 모으는 것이다(당직 인원 수 카운터 행을 SELECT FOR UPDATE). 설계로 write skew 자체를 없애는 쪽이 더 싸다.");
        evidence.note("MySQL InnoDB 의 SERIALIZABLE 은 SSI 가 아니라 모든 SELECT 를 잠그는 방식이라 성능 특성이 전혀 다르다 — 제품을 바꾸면 다시 재야 한다.");
    }

    /** 두 트랜잭션이 각각 "당직이 2명이니 나는 빠져도 된다"고 판단하고 서로 다른 행을 끈다. */
    private Result runPair(int isolationLevel) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS oncall_demo");
        jdbc.execute("CREATE TABLE oncall_demo (name text PRIMARY KEY, on_duty boolean NOT NULL)");
        jdbc.update("INSERT INTO oncall_demo VALUES ('alice', true), ('bob', true)");

        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>("-");

        Thread a = worker("alice", isolationLevel, bothRead, done, failures, error);
        Thread b = worker("bob", isolationLevel, bothRead, done, failures, error);
        a.start();
        b.start();
        done.await(20, TimeUnit.SECONDS);
        a.join(2_000);
        b.join(2_000);

        Long remaining = jdbc.queryForObject("SELECT count(*) FROM oncall_demo WHERE on_duty", Long.class);
        return new Result(failures.get(), error.get(), remaining == null ? -1 : remaining.intValue());
    }

    private Thread worker(String name, int isolationLevel, CountDownLatch bothRead, CountDownLatch done,
                          AtomicInteger failures, AtomicReference<String> error) {
        Thread thread = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(isolationLevel);

                // 1) 조건 확인: 당직이 2명이면 내가 빠져도 된다
                int onDuty;
                try (var ps = connection.prepareStatement("SELECT count(*) FROM oncall_demo WHERE on_duty");
                     var rs = ps.executeQuery()) {
                    rs.next();
                    onDuty = rs.getInt(1);
                }

                // 2) 두 트랜잭션이 모두 '확인'을 마친 뒤에 각자 쓰기로 넘어간다
                bothRead.countDown();
                bothRead.await(5, TimeUnit.SECONDS);

                if (onDuty >= 2) {
                    try (var ps = connection.prepareStatement("UPDATE oncall_demo SET on_duty = false WHERE name = ?")) {
                        ps.setString(1, name);
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                failures.incrementAndGet();
                error.set(e.getClass().getSimpleName() + " (SQLState=" + e.getSQLState() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "skew-" + name);
        thread.setDaemon(true);
        return thread;
    }

    private record Result(int failures, String error, int remainingOnDuty) {}
}
