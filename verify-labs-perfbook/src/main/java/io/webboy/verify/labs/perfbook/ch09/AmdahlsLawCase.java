package io.webboy.verify.labs.perfbook.ch09;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 9장 — 암달의 법칙: 직렬 구간이 남아 있으면 스레드를 늘려도 그 이상 빨라지지 않는다.
 *
 * <p>책 9장은 스레드 풀 크기를 논하며 이 법칙을 든다: 작업의 비율 F 가 직렬이면
 * 스레드 N개의 속도 향상은 {@code 1 / (F + (1-F)/N)} 을 넘을 수 없고,
 * N 을 무한히 늘려도 상한은 {@code 1/F} 다.
 *
 * <p>측정: 절반(F=0.5)이 직렬인 작업을 스레드 1개와 4개로 돌려 실측 속도 향상을
 * 이론 상한(1/F = 2.0)·이론 예측(N=4 에서 1.6)과 대조한다.
 */
@Component
public class AmdahlsLawCase extends VerificationCase {

    private static final int THREADS = 4;
    private static final int TOTAL_ITEMS = 32_000_000;
    private static final double SERIAL_FRACTION = 0.5;
    private static final int ROUNDS = 3;

    @Override
    public String id() {
        return "PERF-09B";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 9장 — 스레드를 4배로 늘리면 4배 빨라지나? (암달의 법칙)";
    }

    @Override
    public String claim() {
        return "작업의 F 비율이 직렬이면 스레드 N개의 속도 향상은 1/(F+(1-F)/N) 이 상한이고, "
                + "N 을 아무리 늘려도 1/F 를 넘지 못한다. 절반이 직렬인 작업은 코어를 무한히 부어도 2배가 한계다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        int serialItems = (int) (TOTAL_ITEMS * SERIAL_FRACTION);
        int parallelItems = TOTAL_ITEMS - serialItems;

        // 워밍업
        runWith(1, serialItems, parallelItems);
        runWith(THREADS, serialItems, parallelItems);

        long single = Long.MAX_VALUE;
        long multi = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            single = Math.min(single, runWith(1, serialItems, parallelItems));
            multi = Math.min(multi, runWith(THREADS, serialItems, parallelItems));
        }

        double measured = (double) single / Math.max(1, multi);
        double predicted = 1.0 / (SERIAL_FRACTION + (1 - SERIAL_FRACTION) / THREADS);
        double ceiling = 1.0 / SERIAL_FRACTION;

        evidence.fact("직렬 비율 F", SERIAL_FRACTION);
        evidence.fact("스레드 1개", single + " us");
        evidence.fact("스레드 " + THREADS + "개", multi + " us");
        evidence.fact("실측 속도 향상", String.format("%.2f배", measured));
        evidence.fact("이론 예측 (N=" + THREADS + ")", String.format("%.2f배", predicted));
        evidence.fact("이론 상한 (1/F)", String.format("%.2f배", ceiling));

        evidence.expect("측정 해상도가 확보된다", single > 0 && multi > 0);
        evidence.expectFlaky("스레드를 늘리면 빨라지긴 한다 (1.15배 이상)", measured >= 1.15);
        evidence.expectFlaky("그러나 이론 상한 1/F 를 넘지 못한다 (측정 여유 10%)",
                measured <= ceiling * 1.10);
        evidence.note("4코어라 N=4 까지만 확인한다. '코어를 더 부어도 2배가 한계'라는 부분은 "
                + "이 장비에서는 외삽이다 — 실측한 것은 상한 공식이 N=4 에서 성립한다는 것까지다.");
    }

    /**
     * 직렬 구간은 호출 스레드가 혼자 처리하고, 병렬 구간만 {@code threads}개로 나눈다.
     * 작업 항목은 전 구간 동일하다 — 나누는 방식만 다르다.
     */
    private static long runWith(int threads, int serialItems, int parallelItems) throws Exception {
        long began = System.nanoTime();

        long serialSum = process(0, serialItems);           // 직렬 구간

        List<Thread> workers = new ArrayList<>();
        long[] partial = new long[threads];
        int chunk = parallelItems / threads;
        for (int t = 0; t < threads; t++) {
            int index = t;
            int from = serialItems + t * chunk;
            int to = (t == threads - 1) ? serialItems + parallelItems : from + chunk;
            Thread worker = new Thread(() -> partial[index] = process(from, to));
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) {
            worker.join();
        }

        long micros = (System.nanoTime() - began) / 1_000L;
        long sum = serialSum;
        for (long p : partial) {
            sum += p;
        }
        if (sum == Long.MIN_VALUE) {
            throw new IllegalStateException();   // 죽은 코드 제거 방지
        }
        return micros;
    }

    private static long process(int from, int to) {
        long sum = 0;
        for (int i = from; i < to; i++) {
            sum += Long.bitCount(i * 2654435761L);
        }
        return sum;
    }
}
