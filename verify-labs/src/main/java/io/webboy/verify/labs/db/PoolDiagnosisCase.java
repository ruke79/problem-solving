package io.webboy.verify.labs.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * Q18 · Q44 · Q68 (세 번 반복 출제된 질문) — 풀 고갈의 세 원인을 메트릭 모양으로 기계적으로 구분한다.
 *
 * <ul>
 *   <li>누수: 부하가 끝나도 active 가 내려오지 않고, usage(대여~반납) 표본 자체가 남지 않는다</li>
 *   <li>슬로우 쿼리: active 는 정상 복귀하지만 usage 가 길다</li>
 *   <li>용량 부족: usage 는 짧은데 pending(대기 스레드)이 쌓이고 타임아웃이 난다</li>
 * </ul>
 */
@Component
public class PoolDiagnosisCase extends VerificationCase {

    private static final int POOL_SIZE = 4;
    private static final long CONNECTION_TIMEOUT_MILLIS = 300L;
    private static final long SLOW_QUERY_MILLIS = 700L;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PoolDiagnosisCase(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:sa}") String username,
            @Value("${spring.datasource.password:}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public String id() {
        return "DB-06";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "커넥션 풀이 고갈됐습니다. 누수인지, 장시간 점유인지, 단순 용량 부족인지 어떻게 구분합니까?";
    }

    @Override
    public String claim() {
        return "active 가 부하 후에도 안 내려오면 누수, usage(대여~반납 시간)가 길면 슬로우 쿼리, usage 는 짧은데 pending 이 쌓이면 용량 부족이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Result leak = runScenario("leak", POOL_SIZE, 0L, true);
        Result slow = runScenario("slow-query", POOL_SIZE, SLOW_QUERY_MILLIS, false);
        Result undersized = runScenario("undersized", POOL_SIZE * 4, 5L, false);

        evidence.fact("풀 크기 / connectionTimeout(ms)", POOL_SIZE + " / " + CONNECTION_TIMEOUT_MILLIS);
        evidence.fact("[누수] 부하 종료 후 active", leak.activeAfterLoad);
        evidence.fact("[누수] 완료된 usage 표본 수", leak.usageSamples);
        evidence.fact("[누수] 평균 usage(ms)", leak.averageUsageMillis);
        evidence.fact("[슬로우] 부하 종료 후 active", slow.activeAfterLoad);
        evidence.fact("[슬로우] 완료된 usage 표본 수", slow.usageSamples);
        evidence.fact("[슬로우] 평균 usage(ms) / 평균 acquire(ms) / 최대 acquire(ms)",
                slow.averageUsageMillis + " / " + slow.averageAcquireMillis + " / " + slow.maxAcquireMillis);
        evidence.fact("[용량부족] 부하 종료 후 active", undersized.activeAfterLoad);
        evidence.fact("[용량부족] 평균 usage(ms) / 평균 acquire(ms) / 최대 acquire(ms)",
                undersized.averageUsageMillis + " / " + undersized.averageAcquireMillis
                        + " / " + undersized.maxAcquireMillis);
        evidence.fact("[용량부족] 관측된 최대 pending", undersized.maxPending);
        evidence.fact("[용량부족] 획득 타임아웃 횟수", undersized.timeouts);

        evidence.expect("누수: 부하가 끝나도 active 가 풀 크기 그대로 남는다", leak.activeAfterLoad >= POOL_SIZE);
        evidence.expectEquals("누수: 반납이 없으므로 usage 표본이 0이다", 0, leak.usageSamples);

        evidence.expect("슬로우 쿼리: active 는 0으로 복귀한다", slow.activeAfterLoad == 0);
        evidence.expect("슬로우 쿼리: usage 가 길다", slow.averageUsageMillis >= SLOW_QUERY_MILLIS / 2);
        evidence.expect("슬로우 쿼리: 워커가 풀 크기 이하라 대기(acquire)는 거의 없다",
                slow.maxAcquireMillis < slow.averageUsageMillis);

        evidence.expect("용량 부족: active 는 0으로 복귀한다", undersized.activeAfterLoad == 0);
        evidence.expect("용량 부족: usage 는 짧다 — 쿼리가 느린 것이 아니다", undersized.averageUsageMillis < 200);
        evidence.expect("용량 부족: 대신 대기(acquire)가 usage 보다 길다 — 이것이 두 원인을 가르는 지점이다",
                undersized.maxAcquireMillis > undersized.averageUsageMillis);
        evidence.expect("용량 부족의 대기는 슬로우 쿼리 시나리오보다 뚜렷하게 길다",
                undersized.maxAcquireMillis > slow.maxAcquireMillis);
        evidence.expectFlaky("용량 부족: 풀 지표에도 pending 이나 타임아웃으로 나타난다",
                undersized.maxPending > 0 || undersized.timeouts > 0);

        evidence.note("Micrometer 지표명: hikaricp.connections.active / .pending / .usage / .acquire. **usage 와 acquire 를 섞지 않는 것이 이 진단의 전부다** — usage 는 '빌린 뒤 반납까지', acquire 는 '빌리려고 기다린 시간'이다. 느린 쿼리는 usage 를, 용량 부족은 acquire 를 밀어 올린다.");
        evidence.note("이 랩이 실제로 저지른 실수이기도 하다. 처음에는 `getConnection()` **앞에서** 시계를 켜 대기와 사용을 합쳐 놓고 'usage' 라고 불렀다. 그러면 용량 부족 시나리오에서 대기가 usage 로 잡혀 '용량 부족은 usage 가 짧다'가 부하에 따라 깨진다 — 전건 실행에서 실제로 그렇게 흔들렸다. 지표 이름이 가리키는 구간을 정확히 재는 것이 먼저다.");
        evidence.note("누수 확정 후에는 leakDetectionThreshold 를 켜면 '빌린 시점의 스택 트레이스'가 경고 로그로 나와 범인 메서드를 직접 지목할 수 있다.");
        evidence.note("실무에서 가장 흔한 원인 3종: try-with-resources 누락, @Transactional 안쪽의 외부 API 호출, OSIV 로 뷰 렌더링까지 세션 유지.");
        evidence.note("두 원인이 겹치는 경우가 있으므로 active/pending 추이와 usage 히스토그램을 항상 함께 본다.");
    }

    private Result runScenario(String name, int workers, long holdMillis, boolean leakConnections) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setPoolName("diagnosis-" + name);
        config.setInitializationFailTimeout(-1);

        List<Connection> leaked = Collections.synchronizedList(new ArrayList<>());
        List<Long> usage = Collections.synchronizedList(new ArrayList<>());
        List<Long> acquire = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger timeouts = new AtomicInteger();
        AtomicInteger maxPending = new AtomicInteger();

        try (HikariDataSource pool = new HikariDataSource(config)) {
            // 물리 커넥션을 미리 열어 둔다. 그러지 않으면 첫 워커의 acquire 에 '접속 생성 시간'이
            // 섞여 '풀이 비어서 기다린 시간'과 구분되지 않는다.
            primePool(pool);

            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(workers);

            for (int i = 0; i < workers; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        // acquire(대기)와 usage(대여~반납)를 따로 잰다. 예전에는 getConnection() 앞에서
                        // 시계를 켜 둘을 합쳐 놓고 'usage' 라고 불렀는데, 그러면 용량 부족 시나리오에서
                        // 대기 시간이 usage 로 잡혀 "usage 는 짧다"가 부하에 따라 깨졌다.
                        // Hikari 지표에서도 acquire 와 usage 는 별개다.
                        long requested = System.nanoTime();
                        Connection connection = pool.getConnection();
                        long acquiredAt = System.nanoTime();
                        acquire.add((acquiredAt - requested) / 1_000_000L);
                        if (leakConnections) {
                            leaked.add(connection);      // 일부러 반납하지 않는다
                        } else {
                            try (Connection held = connection) {
                                held.createStatement().execute("SELECT 1");
                                sleep(holdMillis);
                            }
                            usage.add((System.nanoTime() - acquiredAt) / 1_000_000L);
                        }
                    } catch (SQLException e) {
                        timeouts.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            Thread sampler = new Thread(() -> {
                while (done.getCount() > 0) {
                    maxPending.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                    sleep(10);
                }
            }, "pool-sampler");
            sampler.setDaemon(true);
            sampler.start();

            start.countDown();
            done.await(60, TimeUnit.SECONDS);
            executor.shutdownNow();
            sampler.join(2_000);

            sleep(100);   // 반납이 반영될 시간
            int activeAfterLoad = pool.getHikariPoolMXBean().getActiveConnections();

            for (Connection connection : leaked) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 정리 단계
                }
            }

            return new Result(activeAfterLoad, usage.size(), average(usage),
                    average(acquire), max(acquire), maxPending.get(), timeouts.get());
        }
    }

    /** 풀이 물리 커넥션을 미리 열게 한다 — 실패해도 검증을 막지 않는다(그때는 예전처럼 생성 시간이 섞일 뿐이다). */
    private void primePool(HikariDataSource pool) {
        List<Connection> primed = new ArrayList<>();
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                primed.add(pool.getConnection());
            }
        } catch (SQLException ignored) {
            // 준비 실패는 판정에 영향을 주지 않는다
        } finally {
            for (Connection connection : primed) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 정리 단계
                }
            }
        }
    }

    private long average(List<Long> samples) {
        return samples.isEmpty() ? 0
                : Math.round(samples.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private long max(List<Long> samples) {
        return samples.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Result {
        final int activeAfterLoad;
        final int usageSamples;
        final long averageUsageMillis;
        final long averageAcquireMillis;
        final long maxAcquireMillis;
        final int maxPending;
        final int timeouts;

        Result(int activeAfterLoad, int usageSamples, long averageUsageMillis,
               long averageAcquireMillis, long maxAcquireMillis, int maxPending, int timeouts) {
            this.activeAfterLoad = activeAfterLoad;
            this.usageSamples = usageSamples;
            this.averageUsageMillis = averageUsageMillis;
            this.averageAcquireMillis = averageAcquireMillis;
            this.maxAcquireMillis = maxAcquireMillis;
            this.maxPending = maxPending;
            this.timeouts = timeouts;
        }
    }
}
