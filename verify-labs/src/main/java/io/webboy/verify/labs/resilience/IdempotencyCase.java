package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class IdempotencyCase extends VerificationCase {

    private static final int CONCURRENT_RETRIES = 20;

    private final IdempotencyStore store;

    public IdempotencyCase(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public String id() {
        return "RES-01";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "재시도를 넣으면 왜 멱등성이 필요합니까? 어떻게 보장합니까?";
    }

    @Override
    public String claim() {
        return "재시도는 중복 실행을 만든다. 멱등성 키를 원자적으로 선점하면 동시 재시도에도 부수효과는 정확히 1회다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String key = "order-" + UUID.randomUUID();

        AtomicInteger unguarded = new AtomicInteger();
        runConcurrently(() -> unguarded.incrementAndGet());

        AtomicInteger guarded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(() -> {
            if (store.tryAcquire(key, Thread.currentThread().getName())) {
                guarded.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        });

        boolean laterRetryRejected = !store.tryAcquire(key, "late-retry");

        evidence.fact("동시 재시도 수", CONCURRENT_RETRIES);
        evidence.fact("멱등성 키 없음 — 부수효과 발생 횟수", unguarded.get());
        evidence.fact("멱등성 키 적용 — 실행 횟수", guarded.get());
        evidence.fact("멱등성 키 적용 — 중복으로 거부된 횟수", rejected.get());
        evidence.fact("시간차 재시도도 거부되는가", laterRetryRejected);

        evidence.expectEquals("보호 없이 재시도하면 그 수만큼 중복 실행된다", CONCURRENT_RETRIES, unguarded.get());
        evidence.expectEquals("멱등성 키가 있으면 정확히 1회만 실행된다", 1, guarded.get());
        evidence.expectEquals("나머지는 모두 중복으로 거부된다", CONCURRENT_RETRIES - 1, rejected.get());
        evidence.expect("나중에 도착한 재시도도 거부된다", laterRetryRejected);

        evidence.note("putIfAbsent 는 단일 JVM 한정이다 — 분산 환경에서는 Redis SETNX + TTL 또는 DB 유니크 제약이 원자성의 근거가 된다.");
        evidence.note("키만 선점하고 처리 중에 죽으면 영원히 막힌다 — 상태(진행중/완료/실패)와 TTL 을 함께 저장해야 한다.");
        evidence.note("HTTP 관례상 GET/PUT/DELETE 는 멱등, POST 는 아니다 — 그래서 POST 에 Idempotency-Key 헤더를 붙인다.");

        store.clear();
    }

    private void runConcurrently(Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_RETRIES);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_RETRIES);
        try {
            for (int i = 0; i < CONCURRENT_RETRIES; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        action.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
