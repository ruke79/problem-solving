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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Q40 · Q73 — 무중단 스키마 변경의 "무중단"이 깨지는 지점.
 *
 * <p>{@code MSA-05} 는 Expand/Contract 의 <b>논리적 단계</b>가 각각 롤백 가능한지를 본다.
 * 이 케이스는 그 단계를 실제로 실행할 때의 <b>락 비용</b>을 잰다. H2 에서는 DDL 이 즉시
 * 끝나 버려 관측할 수 없던 부분이다.
 *
 * <ul>
 *   <li>상수 기본값의 ADD COLUMN 은 카탈로그만 고쳐 즉시 끝난다(PostgreSQL 11+)</li>
 *   <li>타입 변경은 테이블을 재작성한다 — 행 수에 비례한다</li>
 *   <li>DDL 이 락을 기다리는 동안 <b>그 뒤에 줄 선 평범한 SELECT 까지 막힌다</b></li>
 * </ul>
 */
@Component
public class DdlLockCase extends VerificationCase {

    private static final int ROWS = 200_000;
    private static final long READER_HOLD_MILLIS = 2_000L;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public DdlLockCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-11";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "무중단 스키마 변경이라고 했는데, 실제로 무엇이 서비스를 멈추게 합니까?";
    }

    @Override
    public String claim() {
        return "상수 기본값의 컬럼 추가는 즉시 끝나지만 타입 변경은 테이블을 재작성한다. 그리고 DDL 이 락을 기다리는 동안 그 뒤에 줄 선 일반 SELECT 까지 막히므로, 긴 트랜잭션이 도는 시간대의 DDL 은 그 자체로 장애다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 소요 시간과 대기 시간은 장비·타이밍에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS ddl_demo");
        jdbc.execute("CREATE TABLE ddl_demo (id int PRIMARY KEY, v int)");
        jdbc.update("INSERT INTO ddl_demo SELECT g, g FROM generate_series(1, " + ROWS + ") g");

        long addColumnMillis = timed(() ->
                jdbc.execute("ALTER TABLE ddl_demo ADD COLUMN grade int NOT NULL DEFAULT 7"));
        long rewriteMillis = timed(() ->
                jdbc.execute("ALTER TABLE ddl_demo ALTER COLUMN v TYPE bigint"));

        Blocking blocking = measureLockQueue();

        evidence.fact("행 수", ROWS);
        evidence.fact("ADD COLUMN (상수 기본값) 소요(ms)", addColumnMillis);
        evidence.fact("ALTER COLUMN TYPE int→bigint (재작성) 소요(ms)", rewriteMillis);
        evidence.fact("앞선 읽기 트랜잭션이 테이블을 붙잡은 시간(ms)", READER_HOLD_MILLIS);
        evidence.fact("DDL 뒤에 줄 선 일반 SELECT 의 대기 시간(ms)", blocking.selectWaitMillis);
        evidence.fact("그 SELECT 가 읽은 행 수", blocking.rowsRead);

        evidence.expect("상수 기본값의 컬럼 추가는 행 수와 무관하게 즉시 끝난다", addColumnMillis < 1_000);
        evidence.expectFlaky("타입 변경은 재작성이라 컬럼 추가보다 오래 걸린다", rewriteMillis > addColumnMillis);
        evidence.expectFlaky("DDL 이 락을 기다리는 동안 뒤에 온 SELECT 도 함께 막힌다",
                blocking.selectWaitMillis >= READER_HOLD_MILLIS / 2);
        evidence.expect("막혔던 SELECT 는 락이 풀린 뒤 정상적으로 완료된다", blocking.rowsRead == ROWS);

        jdbc.execute("DROP TABLE IF EXISTS ddl_demo");

        evidence.note("PostgreSQL 11 부터 상수 기본값의 ADD COLUMN 은 기존 행을 건드리지 않는다(카탈로그에만 기록). 10 이하에서는 전 행 재작성이었으므로 '버전에 따라 답이 다른' 질문이다.");
        evidence.note("가장 위험한 것은 DDL 자체의 실행 시간이 아니라 락 큐다. ACCESS EXCLUSIVE 를 기다리는 DDL 은 그 뒤의 모든 조회를 함께 세우므로, 긴 트랜잭션·장시간 조회가 도는 시간대를 피하고 lock_timeout 을 짧게 걸어 '못 잡으면 포기하고 재시도'하게 만든다.");
        evidence.note("인덱스는 CREATE INDEX CONCURRENTLY 로 만들면 쓰기를 막지 않는다. 대신 실패 시 무효 인덱스가 남으므로 pg_index.indisvalid 확인이 필요하다.");
        evidence.note("MSA-05 가 검증하는 Expand/Contract 의 각 단계는 논리적으로 롤백 가능하지만, 이 케이스가 보여주듯 '실행 시점'은 따로 골라야 한다 — 절차와 타이밍은 별개 문제다.");
    }

    /**
     * 읽기 트랜잭션 → DDL → 일반 SELECT 순으로 걸어 락 큐를 만든다.
     * PostgreSQL 의 락 큐는 FIFO 라, ACCESS EXCLUSIVE 를 기다리는 DDL 뒤의 SELECT 도 함께 막힌다.
     */
    private Blocking measureLockQueue() throws Exception {
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch ddlRequested = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicLong ignored = new AtomicLong();

        Thread reader = daemon("ddl-reader", () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                query(connection, "SELECT count(*) FROM ddl_demo");
                readerStarted.countDown();
                Thread.sleep(READER_HOLD_MILLIS);
                connection.commit();
            } finally {
                finished.countDown();
            }
        });

        Thread ddl = daemon("ddl-writer", () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                readerStarted.await(5, TimeUnit.SECONDS);
                connection.createStatement().execute("SET lock_timeout = 15000");
                ddlRequested.countDown();
                long began = System.nanoTime();
                connection.createStatement().execute("ALTER TABLE ddl_demo ADD COLUMN tail int");
                ignored.set((System.nanoTime() - began) / 1_000_000L);
            } finally {
                finished.countDown();
            }
        });

        reader.start();
        ddl.start();
        ddlRequested.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);   // DDL 이 실제로 락 큐에 들어갈 시간을 준다

        long began = System.nanoTime();
        long rows;
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("SET lock_timeout = 15000");
            rows = query(connection, "SELECT count(*) FROM ddl_demo");
        }
        long waited = (System.nanoTime() - began) / 1_000_000L;

        finished.await(20, TimeUnit.SECONDS);
        return new Blocking(waited, rows);
    }

    private long query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Thread daemon(String name, ThrowingRunnable work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private long timed(Runnable work) {
        long began = System.nanoTime();
        work.run();
        return (System.nanoTime() - began) / 1_000_000L;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Blocking(long selectWaitMillis, long rowsRead) {}
}
