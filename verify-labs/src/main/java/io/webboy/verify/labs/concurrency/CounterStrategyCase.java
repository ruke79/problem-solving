package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

@Component
public class CounterStrategyCase extends VerificationCase {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 200_000;

    private long guarded;

    @Override
    public String id() {
        return "CON-02";
    }

    @Override
    public String category() {
        return "concurrency";
    }

    @Override
    public String question() {
        return "카운터를 synchronized, AtomicLong, LongAdder 로 구현할 때의 차이는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "셋 다 정확하지만 경합이 심할수록 LongAdder 가 유리하다 — 셀 분산으로 CAS 재시도를 줄이기 때문이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        long expected = (long) THREADS * ITERATIONS;

        Object lock = new Object();
        guarded = 0;
        long syncMillis = race(i -> {
            synchronized (lock) {
                guarded++;
            }
        });
        long syncValue = guarded;

        AtomicLong atomic = new AtomicLong();
        long atomicMillis = race(i -> atomic.incrementAndGet());

        LongAdder adder = new LongAdder();
        long adderMillis = race(i -> adder.increment());

        evidence.fact("스레드 수 / 스레드당 증가", THREADS + " / " + ITERATIONS);
        evidence.fact("기대 합계", expected);
        evidence.fact("synchronized 결과 / 소요(ms)", syncValue + " / " + syncMillis);
        evidence.fact("AtomicLong 결과 / 소요(ms)", atomic.get() + " / " + atomicMillis);
        evidence.fact("LongAdder 결과 / 소요(ms)", adder.sum() + " / " + adderMillis);
        evidence.fact("가용 코어", Runtime.getRuntime().availableProcessors());

        evidence.expectEquals("synchronized 는 정확하다", expected, syncValue);
        evidence.expectEquals("AtomicLong 은 정확하다", expected, atomic.get());
        evidence.expectEquals("LongAdder 는 정확하다", expected, adder.sum());
        evidence.expectFlaky("높은 경합에서 LongAdder 가 AtomicLong 보다 빠르다", adderMillis <= atomicMillis);

        evidence.note("LongAdder 는 sum() 이 스냅샷이 아니다 — 정확한 순간값이 필요하면 AtomicLong 을 쓴다.");
        evidence.note("이 측정은 JMH 가 아니라 단발 벽시계 측정이므로 절대값이 아니라 '자릿수'만 신뢰한다.");
    }

    private long race(Consumer<Integer> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ITERATIONS; i++) {
                            work.accept(i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            long began = System.nanoTime();
            start.countDown();
            done.await(60, TimeUnit.SECONDS);
            return (System.nanoTime() - began) / 1_000_000L;
        } finally {
            pool.shutdownNow();
        }
    }
}
