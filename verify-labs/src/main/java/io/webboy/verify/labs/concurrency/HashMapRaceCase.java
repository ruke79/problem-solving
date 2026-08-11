package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HashMapRaceCase extends VerificationCase {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 20_000;
    private static final String KEY = "counter";

    @Override
    public String id() {
        return "CON-01";
    }

    @Override
    public String category() {
        return "concurrency";
    }

    @Override
    public String question() {
        return "HashMap 을 여러 스레드에서 동시에 수정하면 어떤 일이 일어납니까?";
    }

    @Override
    public String claim() {
        return "HashMap 은 스레드 안전하지 않아 갱신 손실/예외가 발생하고, ConcurrentHashMap 은 정확한 값을 보장한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        AtomicInteger unsafeErrors = new AtomicInteger();
        Map<String, Integer> unsafe = new HashMap<>();
        unsafe.put(KEY, 0);
        runConcurrently(unsafe, unsafeErrors);

        AtomicInteger safeErrors = new AtomicInteger();
        Map<String, Integer> safe = new ConcurrentHashMap<>();
        safe.put(KEY, 0);
        runConcurrently(safe, safeErrors);

        int expected = THREADS * ITERATIONS;
        Integer unsafeValue = unsafe.get(KEY);
        Integer safeValue = safe.get(KEY);

        evidence.fact("기대 합계", expected);
        evidence.fact("HashMap 결과", unsafeValue);
        evidence.fact("HashMap 손실 건수", unsafeValue == null ? "n/a" : (expected - unsafeValue));
        evidence.fact("HashMap 실행 중 예외 수", unsafeErrors.get());
        evidence.fact("ConcurrentHashMap 결과", safeValue);
        evidence.fact("ConcurrentHashMap 실행 중 예외 수", safeErrors.get());

        evidence.expectEquals("ConcurrentHashMap.merge 는 원자적이라 정확하다", expected, safeValue);
        evidence.expectEquals("ConcurrentHashMap 은 예외 없이 완주한다", 0, safeErrors.get());
        evidence.expectFlaky("HashMap 은 갱신 손실 또는 예외가 발생한다",
                unsafeValue == null || unsafeValue < expected || unsafeErrors.get() > 0);

        evidence.note("JDK 7 의 무한 루프(resize 시 링크 역전)는 JDK 8 에서 사라졌지만, 갱신 손실과 자료구조 손상은 그대로 남아 있다.");
        evidence.note("Collections.synchronizedMap 은 복합 연산(get 후 put)까지 원자화하지 못한다 — compute/merge 계열이 필요하다.");
    }

    private void runConcurrently(Map<String, Integer> map, AtomicInteger errors) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ITERATIONS; i++) {
                            map.merge(KEY, 1, Integer::sum);
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
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
