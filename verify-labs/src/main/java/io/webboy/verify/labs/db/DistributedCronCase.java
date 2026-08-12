package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q148 — 노드 3대가 같은 시각에 깨어나는 분산 Cron.
 *
 * <p>락이 없으면 3번 실행된다. 락이 있으면 1번만 실행된다. 그런데 <b>락을 잡은 노드가
 * 죽으면</b> TTL 이 만료될 때까지 아무도 못 하거나, TTL 만료 후 늦게 깨어난 노드와 겹칠 수 있다 —
 * 그래서 잡 자체의 멱등성이 마지막 보험이다.
 */
@Component
public class DistributedCronCase extends VerificationCase {

    private static final int NODES = 3;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public DistributedCronCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-23";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "여러 노드에 같은 배치가 배포돼 있을 때 중복 실행을 어떻게 막습니까?";
    }

    @Override
    public String claim() {
        return "노드마다 Cron 을 걸면 같은 시각에 전부 실행된다. 원자적으로 락을 선점하면 하나만 실행되지만, 락을 잡은 노드가 죽으면 TTL 만료 전까지 아무도 실행하지 못하므로 실행 이력 감시와 잡의 멱등성이 함께 필요하다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        int withoutLock = fireAll(false);
        int withLock = fireAll(true);
        String afterCrash = crashScenario();

        evidence.fact("노드 수", NODES);
        evidence.fact("[락 없음] 실제 실행 횟수", withoutLock);
        evidence.fact("[락 있음] 실제 실행 횟수", withLock);
        evidence.fact("[락 보유 노드가 죽은 경우] 다음 노드의 실행 가능 여부", afterCrash);

        evidence.expectEquals("락이 없으면 노드 수만큼 중복 실행된다", NODES, withoutLock);
        evidence.expectEquals("락을 선점하면 정확히 한 노드만 실행한다", 1, withLock);
        evidence.expectEquals("락을 잡은 노드가 죽으면 TTL 이 만료될 때까지 아무도 실행하지 못한다",
                "TTL 만료 전: 실행 불가 / 만료 후: 실행 가능", afterCrash);

        jdbc.execute("DROP TABLE IF EXISTS cron_lock");
        jdbc.execute("DROP TABLE IF EXISTS cron_runs");

        evidence.note("Redis SETNX 든 DB 유니크 제약이든 원리는 같다 — '원자적으로 선점하고, 못 잡으면 아무것도 안 한다'. 조회 후 삽입(check-then-act)은 DB-12 에서 봤듯 동시성에서 샌다.");
        evidence.note("TTL 은 잡의 예상 실행 시간보다 조금 길게 잡는다. 너무 짧으면 실행 중인 잡의 락이 풀려 두 번째 노드가 끼어들고(그때 필요한 것이 DB-16 의 펜싱 토큰), 너무 길면 죽었을 때 공백이 길어진다.");
        evidence.note("락만으로는 '실행됐는지'를 보증하지 못한다. 마지막 정상 완료 시각을 따로 기록하고 '예상 주기를 넘겼는데 완료 기록이 없다'를 알림으로 잡아야 조용한 미실행을 발견한다.");
        evidence.note("Kubernetes CronJob 도 완전한 배타 실행을 보증하지 않는다(Pod 기동이 겹칠 수 있다). 그래서 잡 자체를 멱등하게 만드는 것이 마지막 보험이다.");
    }

    /** 노드 N 대가 동시에 깨어난다. */
    private int fireAll(boolean useLock) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS cron_lock");
        jdbc.execute("DROP TABLE IF EXISTS cron_runs");
        jdbc.execute("CREATE TABLE cron_lock (job text PRIMARY KEY, locked_by text, expires_at timestamptz)");
        jdbc.execute("CREATE TABLE cron_runs (id serial PRIMARY KEY, node text, ran_at timestamptz DEFAULT now())");

        AtomicInteger executed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(NODES);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(NODES);
        try {
            for (int i = 0; i < NODES; i++) {
                String node = "node-" + i;
                executor.submit(() -> {
                    try (Connection connection = dataSource.getConnection()) {
                        start.await();
                        if (!useLock || acquire(connection, node, 30)) {
                            try (var ps = connection.prepareStatement("INSERT INTO cron_runs (node) VALUES (?)")) {
                                ps.setString(1, node);
                                ps.executeUpdate();
                            }
                            executed.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 락 획득 실패는 정상 흐름이다
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        return executed.get();
    }

    /** 락을 잡은 노드가 죽은 뒤, TTL 만료 전후로 다음 노드가 실행할 수 있는지. */
    private String crashScenario() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS cron_lock");
        jdbc.execute("CREATE TABLE cron_lock (job text PRIMARY KEY, locked_by text, expires_at timestamptz)");

        try (Connection connection = dataSource.getConnection()) {
            acquire(connection, "node-crashed", 2);       // 2초 TTL 로 잡고 그대로 죽었다고 가정
            boolean beforeExpiry = acquire(connection, "node-next", 30);
            Thread.sleep(2_500);
            boolean afterExpiry = acquire(connection, "node-next", 30);
            return "TTL 만료 전: " + (beforeExpiry ? "실행 가능" : "실행 불가")
                    + " / 만료 후: " + (afterExpiry ? "실행 가능" : "실행 불가");
        }
    }

    /** 원자적 선점 — 없으면 INSERT, 있으면 만료된 경우에만 뺏는다. */
    private boolean acquire(Connection connection, String node, int ttlSeconds) throws Exception {
        try (var ps = connection.prepareStatement(
                "INSERT INTO cron_lock (job, locked_by, expires_at) VALUES ('nightly', ?, now() + make_interval(secs => ?)) "
                        + "ON CONFLICT (job) DO UPDATE SET locked_by = EXCLUDED.locked_by, expires_at = EXCLUDED.expires_at "
                        + "WHERE cron_lock.expires_at < now()")) {
            ps.setString(1, node);
            ps.setInt(2, ttlSeconds);
            return ps.executeUpdate() == 1;
        }
    }
}
