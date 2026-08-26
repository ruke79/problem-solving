package io.webboy.verify.labs.perfbook.ch09;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 9장 — 거짓 공유(false sharing): 다른 변수라도 같은 캐시 라인이면 서로를 느리게 한다.
 *
 * <p>책 9장의 사례는 스레드 4개가 <b>서로 다른</b> volatile 변수를 갱신하는데도
 * 변수들이 같은 캐시 라인에 있다는 이유로 7.1초짜리 작업이 128.3초가 되는 것이었다.
 * CPU 는 변수가 아니라 캐시 라인(통상 64바이트) 단위로 소유권을 주고받기 때문이다.
 *
 * <p>측정: 스레드 4개가 각자 자기 슬롯만 증가시키는 {@link AtomicLongArray} 를 두 벌 돌린다 —
 * (a) 슬롯이 인접(0·1·2·3, 한 캐시 라인 안), (b) 슬롯 간격 8(64바이트, 라인 분리).
 * 배열이므로 필드와 달리 <b>메모리 인접이 보장된다</b>(JVM 은 필드 순서를 재배치할 수 있다).
 *
 * <p>4코어 컨테이너라 책의 18배는 안 나온다 — 방향(분리가 더 빠르다)만 확인한다.
 */
@Component
public class FalseSharingCase extends VerificationCase {

    private static final int THREADS = 4;
    private static final int INCREMENTS = 3_000_000;
    private static final int PADDING_STRIDE = 8;   // long 8개 = 64바이트 = 통상적 캐시 라인
    private static final int ROUNDS = 5;

    @Override
    public String id() {
        return "PERF-09A";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 9장 — 스레드마다 다른 변수를 쓰는데 왜 느려지나? (거짓 공유)";
    }

    @Override
    public String claim() {
        return "CPU 캐시는 변수가 아니라 캐시 라인 단위로 소유권을 옮긴다. 스레드들이 서로 다른 변수를 "
                + "갱신해도 같은 캐시 라인에 있으면 라인 소유권을 뺏고 빼앗기며 느려진다. "
                + "패딩으로 라인을 분리하면 사라진다 — @Contended 가 하는 일이 이것이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // 워밍업
        run(1);
        run(PADDING_STRIDE);

        long adjacentMicros = Long.MAX_VALUE;
        long paddedMicros = Long.MAX_VALUE;
        // 몰아서 재면 순간 부하가 한쪽만 손해 보게 한다 — 회차마다 번갈아 잰다 (CON-02 의 교훈)
        for (int round = 0; round < ROUNDS; round++) {
            adjacentMicros = Math.min(adjacentMicros, run(1));
            paddedMicros = Math.min(paddedMicros, run(PADDING_STRIDE));
        }

        evidence.fact("코어 수", Runtime.getRuntime().availableProcessors());
        evidence.fact("인접 슬롯 (같은 캐시 라인)", adjacentMicros + " us");
        evidence.fact("간격 8 슬롯 (라인 분리)", paddedMicros + " us");
        evidence.fact("배율", String.format("%.2f배", (double) adjacentMicros / Math.max(1, paddedMicros)));

        evidence.expect("측정 해상도가 확보된다", adjacentMicros > 0 && paddedMicros > 0);
        evidence.expectFlaky("캐시 라인을 분리하면 빨라진다 (최소 1.3배)",
                paddedMicros * 13 <= adjacentMicros * 10);
        evidence.note("책의 사례(단일 캐시 라인의 volatile 4개)는 18배였다. 여기는 4코어 컨테이너 + "
                + "AtomicLongArray 라 배율이 훨씬 작다 — 확인하는 것은 방향이다. "
                + "필드가 아니라 배열을 쓰는 이유: 필드는 JVM 이 재배치할 수 있어 '인접'을 보장 못 한다.");
    }

    /** 스레드 {@code THREADS}개가 각자 {@code slot = index * stride} 만 증가시킨다. */
    private static long run(int stride) throws Exception {
        AtomicLongArray counters = new AtomicLongArray(THREADS * stride);
        CyclicBarrier barrier = new CyclicBarrier(THREADS + 1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int slot = t * stride;
            Thread thread = new Thread(() -> {
                await(barrier);
                for (int i = 0; i < INCREMENTS; i++) {
                    counters.incrementAndGet(slot);
                }
            });
            thread.start();
            threads.add(thread);
        }
        await(barrier);                       // 전원 준비 후 동시에 출발
        long began = System.nanoTime();
        for (Thread thread : threads) {
            thread.join();
        }
        long micros = (System.nanoTime() - began) / 1_000L;

        long sum = 0;
        for (int t = 0; t < THREADS; t++) {
            sum += counters.get(t * stride);
        }
        if (sum != (long) THREADS * INCREMENTS) {
            throw new IllegalStateException("증가 손실: " + sum);   // 측정 자체가 틀렸다면 실패시킨다
        }
        return micros;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
