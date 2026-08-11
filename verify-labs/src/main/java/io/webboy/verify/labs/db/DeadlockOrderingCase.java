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

/** Q48 — 갱신 순서가 엇갈리면 DB 레벨 데드락이 나고, 순서를 통일하면 사라진다. */
@Component
public class DeadlockOrderingCase extends VerificationCase {

    /** 통일된 순서에서는 앞 트랜잭션이 커밋할 때까지만 기다리면 되므로 넉넉하게 잡는다. */
    private static final int LOCK_TIMEOUT_MS = 5_000;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public DeadlockOrderingCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-07";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "데드락은 왜 발생하고 어떻게 회피합니까? 0으로 만들 수 있습니까?";
    }

    @Override
    public String claim() {
        return "락 획득 순서가 엇갈리면 데드락이 난다. 갱신 순서를 통일하면 구조적으로 사라지지만, 0으로 만들 수는 없으므로 재시도가 함께 필요하다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS deadlock_demo");
        jdbc.execute("CREATE TABLE deadlock_demo (id INT PRIMARY KEY, val INT)");
        jdbc.update("INSERT INTO deadlock_demo VALUES (1, 0), (2, 0)");

        // 엇갈린 순서: 서로 다른 행을 먼저 잡으므로 '둘 다 첫 락 보유' 상태를 만들 수 있다 → 교착 성립
        AtomicInteger crossFailures = new AtomicInteger();
        AtomicReference<String> crossError = new AtomicReference<>("-");
        runPair(new int[]{1, 2}, new int[]{2, 1}, true, crossFailures, crossError);

        // 통일된 순서: 첫 행이 같아 뒤 스레드는 애초에 첫 락을 못 잡는다.
        // 여기에 랑데부 배리어를 걸면 영원히 만나지 못해 '락 타임아웃'이 나는데,
        // 그것은 데드락이 아니라 배리어 때문이다. 그래서 이쪽은 배리어 없이 동시에 출발시킨다.
        AtomicInteger orderedFailures = new AtomicInteger();
        AtomicReference<String> orderedError = new AtomicReference<>("-");
        runPair(new int[]{1, 2}, new int[]{1, 2}, false, orderedFailures, orderedError);

        evidence.fact("엇갈린 순서(1→2 / 2→1) 실패 트랜잭션 수", crossFailures.get());
        evidence.fact("엇갈린 순서에서 발생한 오류", crossError.get());
        evidence.fact("통일된 순서(1→2 / 1→2) 실패 트랜잭션 수", orderedFailures.get());
        evidence.fact("통일된 순서에서 발생한 오류", orderedError.get());

        evidence.expectFlaky("엇갈린 락 순서에서는 한쪽이 실패한다", crossFailures.get() >= 1);
        evidence.expectEquals("순서를 통일하면 아무도 실패하지 않는다", 0, orderedFailures.get());

        jdbc.execute("DROP TABLE IF EXISTS deadlock_demo");

        evidence.note("DB 는 데드락을 감지해 한쪽을 롤백한다 — 애플리케이션은 그 오류를 잡아 일정 횟수 재시도해야 한다(설계로 빈도를 낮추고 구현으로 견디는 2단 구성).");
        evidence.note("의외의 원인이 인덱스 부족이다. 적절한 인덱스가 없으면 조건에 맞지 않는 행까지 락이 걸리고(InnoDB 는 갭 락 범위 확대) 원래 충돌하지 않을 트랜잭션이 부딪힌다.");
        evidence.note("조사 수단: MySQL 은 SHOW ENGINE INNODB STATUS, PostgreSQL 은 로그의 deadlock detected 항목.");
    }

    private void runPair(int[] firstOrder, int[] secondOrder, boolean rendezvousAfterFirstLock,
                         AtomicInteger failures, AtomicReference<String> error) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        Thread a = worker("dl-a", firstOrder, rendezvousAfterFirstLock, startGate, bothHoldFirstLock, done, failures, error);
        Thread b = worker("dl-b", secondOrder, rendezvousAfterFirstLock, startGate, bothHoldFirstLock, done, failures, error);
        a.start();
        b.start();
        startGate.countDown();      // 두 스레드를 같은 시점에 출발시킨다
        done.await(20, TimeUnit.SECONDS);
        a.join(2_000);
        b.join(2_000);
    }

    private Thread worker(String name, int[] order, boolean rendezvousAfterFirstLock,
                          CountDownLatch startGate, CountDownLatch bothHoldFirstLock, CountDownLatch done,
                          AtomicInteger failures, AtomicReference<String> error) {
        Thread thread = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.createStatement().execute("SET LOCK_TIMEOUT " + LOCK_TIMEOUT_MS);
                startGate.await(5, TimeUnit.SECONDS);
                update(connection, order[0]);
                if (rendezvousAfterFirstLock) {
                    bothHoldFirstLock.countDown();
                    bothHoldFirstLock.await(5, TimeUnit.SECONDS);
                }
                update(connection, order[1]);
                connection.commit();
            } catch (SQLException e) {
                failures.incrementAndGet();
                error.set(e.getClass().getSimpleName() + " (errorCode=" + e.getErrorCode() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private void update(Connection connection, int id) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE deadlock_demo SET val = val + 1 WHERE id = ?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }
}
