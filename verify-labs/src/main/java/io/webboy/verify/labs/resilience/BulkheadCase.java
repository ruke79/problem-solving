package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Q144 — Design for Failure 세 축(SPOF 제거 · 파급 차단 · 멱등성) 중 마지막 미검증 항목인 <b>벌크헤드</b>.
 *
 * <p>배의 방수 격벽에서 온 이름 그대로, 리소스 풀을 기능별로 나누면 한 기능이 폭주해도
 * 다른 기능이 산다. 여기서는 "외부 API 장애로 멈춰 버린 무거운 기능"과 "가벼운 정상 기능"을
 * 같은 풀에 넣었을 때와 나눴을 때의 <b>정상 기능 성공률</b>을 비교한다.
 */
@Component
public class BulkheadCase extends VerificationCase {

    private static final int POOL_SIZE = 4;
    private static final int QUEUE_SIZE = 4;
    private static final int SLOW_REQUESTS = 12;
    /** 빈 풀이라면 전부 받아들일 수 있는 수(코어 스레드 + 대기 큐)로 맞춘다. */
    private static final int FAST_REQUESTS = POOL_SIZE + QUEUE_SIZE;
    private static final long SLOW_MILLIS = 1_500L;

    @Override
    public String id() {
        return "RES-09";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "장애가 다른 기능으로 번지는 것을 어떻게 막습니까?";
    }

    @Override
    public String claim() {
        return "스레드 풀을 전 기능이 공유하면 외부 의존이 멈춘 한 기능이 풀을 다 점유해 무관한 기능까지 함께 죽는다. 기능별로 풀을 나누는 벌크헤드를 두면 폭주한 기능만 실패하고 나머지는 정상 응답한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 스케줄링 타이밍에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Result shared = runShared();
        Result isolated = runIsolated();

        evidence.fact("풀 크기 / 대기 큐", POOL_SIZE + " / " + QUEUE_SIZE);
        evidence.fact("느린 요청 수 / 정상 요청 수", SLOW_REQUESTS + " / " + FAST_REQUESTS);
        evidence.fact("느린 기능 1건의 처리 시간(ms)", SLOW_MILLIS);
        evidence.fact("[공유 풀] 정상 기능 성공 수", shared.fastSucceeded);
        evidence.fact("[공유 풀] 정상 기능 거절 수", shared.fastRejected);
        evidence.fact("[공유 풀] 정상 기능 최대 대기(ms)", shared.fastMaxWaitMillis);
        evidence.fact("[분리 풀] 정상 기능 성공 수", isolated.fastSucceeded);
        evidence.fact("[분리 풀] 정상 기능 거절 수", isolated.fastRejected);
        evidence.fact("[분리 풀] 정상 기능 최대 대기(ms)", isolated.fastMaxWaitMillis);

        evidence.expect("공유 풀에서는 정상 기능이 거절되거나 크게 지연된다",
                shared.fastRejected > 0 || shared.fastMaxWaitMillis >= SLOW_MILLIS);
        evidence.expectEquals("분리 풀에서는 정상 기능이 전부 성공한다", FAST_REQUESTS, isolated.fastSucceeded);
        evidence.expect("분리 풀의 정상 기능은 느린 기능의 처리 시간에 묶이지 않는다",
                isolated.fastMaxWaitMillis < SLOW_MILLIS);

        evidence.note("벌크헤드는 '전체 성공률'을 올리는 기법이 아니다 — 느린 기능은 여전히 실패한다. 올리는 것은 '무관한 기능이 함께 죽지 않는다'는 격리성이다. 이 구분을 못 하면 효과를 잘못 측정한다.");
        evidence.note("스레드 풀뿐 아니라 커넥션 풀에도 같은 원칙이 적용된다. 배치 작업과 사용자 요청이 같은 Hikari 풀을 쓰면 배치가 풀을 말려 사용자 요청이 죽는다(DB-06 의 '장시간 점유' 시나리오가 정확히 이것이다).");
        evidence.note("서킷브레이커(RES-04)와 짝이다. 벌크헤드는 피해를 가두고, 서킷브레이커는 애초에 두들기는 것을 멈춘다. 둘 다 없으면 하나의 외부 장애가 전체 장애가 된다.");
        evidence.note("풀을 나누면 총 스레드 수는 늘어난다. 무한정 나눌 수는 없으므로 '같이 죽어도 되는 기능끼리' 묶는 그룹핑이 설계 판단이다.");
    }

    /** 모든 기능이 하나의 풀을 공유한다. */
    private Result runShared() throws Exception {
        ThreadPoolExecutor pool = pool("shared");
        try {
            CountDownLatch done = new CountDownLatch(SLOW_REQUESTS);
            submitSlow(pool, done);
            Thread.sleep(200);                  // 느린 요청이 풀을 점유할 시간을 준다
            Result result = submitFast(pool);
            done.await(20, TimeUnit.SECONDS);
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 기능별로 풀을 나눈다 = 벌크헤드. */
    private Result runIsolated() throws Exception {
        ThreadPoolExecutor slowPool = pool("slow");
        ThreadPoolExecutor fastPool = pool("fast");
        try {
            CountDownLatch done = new CountDownLatch(SLOW_REQUESTS);
            submitSlow(slowPool, done);
            Thread.sleep(200);
            Result result = submitFast(fastPool);
            done.await(20, TimeUnit.SECONDS);
            return result;
        } finally {
            slowPool.shutdownNow();
            fastPool.shutdownNow();
        }
    }

    private void submitSlow(ExecutorService pool, CountDownLatch done) {
        for (int i = 0; i < SLOW_REQUESTS; i++) {
            try {
                pool.submit(() -> {
                    try {
                        Thread.sleep(SLOW_MILLIS);   // 외부 API 가 응답하지 않는 상태
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                done.countDown();
            }
        }
    }

    /** 정상 기능은 즉시 끝나는 작업이다. 성공 수와 최대 대기 시간을 잰다. */
    private Result submitFast(ExecutorService pool) throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicLong maxWait = new AtomicLong();
        CountDownLatch done = new CountDownLatch(FAST_REQUESTS);

        for (int i = 0; i < FAST_REQUESTS; i++) {
            long submittedAt = System.nanoTime();
            try {
                pool.submit(() -> {
                    maxWait.accumulateAndGet((System.nanoTime() - submittedAt) / 1_000_000L, Math::max);
                    succeeded.incrementAndGet();
                    done.countDown();
                });
            } catch (RejectedExecutionException e) {
                rejected.incrementAndGet();
                done.countDown();
            }
        }
        done.await(SLOW_MILLIS * 3, TimeUnit.MILLISECONDS);
        return new Result(succeeded.get(), rejected.get(), maxWait.get());
    }

    private ThreadPoolExecutor pool(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_SIZE),
                runnable -> {
                    Thread thread = new Thread(runnable, "bulkhead-" + name);
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    private record Result(int fastSucceeded, int fastRejected, long fastMaxWaitMillis) {}
}
