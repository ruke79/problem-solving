package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Q62 — 고정 윈도의 경계 버스트와, 확인/소비 사이의 경쟁 상태. */
@Component
public class RateLimiterCase extends VerificationCase {

    private static final int LIMIT = 10;
    private static final long WINDOW_MILLIS = 1_000L;
    private static final int RACING_THREADS = 32;

    @Override
    public String id() {
        return "RES-05";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "분산 환경에서 API Rate Limit 을 어떻게 구현합니까? 고정 윈도의 문제는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "고정 윈도는 경계를 넘는 순간 제한의 2배가 통과한다. 그리고 '잔여 확인'과 '소비'가 원자적이지 않으면 제한을 넘겨 통과시킨다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        AtomicLong clock = new AtomicLong(0);

        // (1) 고정 윈도: 윈도 끝에 10건, 윈도 시작 직후에 10건 → 200ms 안에 20건
        RateLimiters.FixedWindow fixed = new RateLimiters.FixedWindow(LIMIT, WINDOW_MILLIS, clock::get);
        clock.set(900);
        int passedBeforeBoundary = drain(fixed, LIMIT + 5);
        clock.set(1_100);
        int passedAfterBoundary = drain(fixed, LIMIT + 5);
        int burstAcrossBoundary = passedBeforeBoundary + passedAfterBoundary;

        // (2) 토큰 버킷: 같은 조건에서 보충량 이상은 통과하지 못한다
        clock.set(0);
        RateLimiters.TokenBucket bucket = new RateLimiters.TokenBucket(LIMIT, LIMIT, clock::get);
        clock.set(900);
        int bucketBefore = drain(bucket, LIMIT + 5);
        clock.set(1_100);
        int bucketAfter = drain(bucket, LIMIT + 5);
        int bucketBurst = bucketBefore + bucketAfter;

        // (3) 확인/소비 비원자 vs 원자
        int nonAtomicPassed = raceNonAtomic();
        int atomicPassed = raceAtomic();

        evidence.fact("제한 / 윈도(ms)", LIMIT + " / " + WINDOW_MILLIS);
        evidence.fact("고정 윈도 - 경계 직전 통과 수", passedBeforeBoundary);
        evidence.fact("고정 윈도 - 경계 직후 통과 수", passedAfterBoundary);
        evidence.fact("고정 윈도 - 200ms 구간 총 통과 수", burstAcrossBoundary);
        evidence.fact("토큰 버킷 - 같은 구간 총 통과 수", bucketBurst);
        evidence.fact("비원자 확인/소비 - 동시 " + RACING_THREADS + "요청 중 통과 수", nonAtomicPassed);
        evidence.fact("원자적 확인/소비 - 통과 수", atomicPassed);

        evidence.expectEquals("고정 윈도는 경계를 넘는 순간 제한의 2배가 통과한다", LIMIT * 2, burstAcrossBoundary);
        evidence.expect("토큰 버킷은 같은 구간에서 훨씬 적게 통과시킨다", bucketBurst < LIMIT * 2);
        evidence.expectFlaky("확인과 소비가 분리되면 제한을 넘겨 통과한다", nonAtomicPassed > LIMIT);
        evidence.expectEquals("단일 원자 연산으로 만들면 정확히 제한만큼만 통과한다", LIMIT, atomicPassed);

        evidence.note("Redis 로 구현할 때 원자성의 근거가 Lua 스크립트다 — Redis 는 싱글 스레드로 스크립트를 실행하므로 스크립트 내부에 다른 명령이 끼어들지 않는다.");
        evidence.note("Rate Limit 을 위해 Redis 를 끼우면 Redis 가 새 단일 장애점이 된다. 장애 시 페일 오픈/페일 클로즈를 업무 성격으로 미리 정해 둔다(결제 API 는 페일 클로즈 쪽).");
        evidence.note("슬라이딩 윈도 로그·슬라이딩 윈도 카운터는 경계 버스트를 줄이지만 메모리와 계산 비용이 늘어난다.");
    }

    private int drain(RateLimiters.FixedWindow limiter, int attempts) {
        int passed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire()) {
                passed++;
            }
        }
        return passed;
    }

    private int drain(RateLimiters.TokenBucket limiter, int attempts) {
        int passed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire()) {
                passed++;
            }
        }
        return passed;
    }

    /** "남았는지 확인" 과 "소비" 사이에 다른 스레드가 끼어드는 전형적 안티패턴. */
    private int raceNonAtomic() throws Exception {
        int[] consumed = {0};
        AtomicInteger passed = new AtomicInteger();
        race(() -> {
            if (consumed[0] < LIMIT) {
                Thread.yield();                 // 확인과 소비 사이의 틈
                consumed[0] = consumed[0] + 1;
                passed.incrementAndGet();
            }
        });
        return passed.get();
    }

    private int raceAtomic() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger passed = new AtomicInteger();
        race(() -> {
            if (consumed.incrementAndGet() <= LIMIT) {
                passed.incrementAndGet();
            }
        });
        return passed.get();
    }

    private void race(Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACING_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(RACING_THREADS);
        try {
            for (int i = 0; i < RACING_THREADS; i++) {
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
