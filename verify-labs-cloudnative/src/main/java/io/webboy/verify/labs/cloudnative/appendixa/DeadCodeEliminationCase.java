package io.webboy.verify.labs.cloudnative.appendixa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Timing;

/**
 * 부록 A — "결과를 쓰지 않으면 JIT 가 계산 자체를 없앨 수 있다(죽은 코드 제거). 그래서 JMH 는 Blackhole 을 둔다."
 * 같은 루프를 결과를 버리는 버전과 결과를 돌려주는 버전으로 재서, 버리는 쪽이 '너무 빨라지는' 것을 본다.
 */
public class DeadCodeEliminationCase extends VerificationCase {

    private static final int N = 20_000_000;
    private static volatile double sink;

    @Override
    public String id() {
        return "CN-A01";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 부록 A — 결과를 쓰지 않는 벤치마크 루프는 정말 JIT 에 지워지는가?";
    }

    @Override
    public String claim() {
        return "부작용도 없고 결과도 쓰지 않는 계산 루프는 C2 가 통째로 제거해 시간이 0 에 가깝게 나온다. "
                + "결과를 volatile 필드나 반환값으로 '소비'해야 실제 계산 시간이 잡힌다 — JMH Blackhole 의 존재 이유";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // JIT 결정에 달려 있다 — 제거되지 않는 환경이 있을 수 있다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // 워밍업 — 두 메서드가 모두 C2 까지 가게 충분히 부른다
        for (int i = 0; i < 20; i++) {
            discarded(N / 10);
            sink = consumed(N / 10);
        }
        long discardedMicros = Timing.minMicros(5, () -> discarded(N));
        long consumedMicros = Timing.minMicros(5, () -> sink = consumed(N));

        evidence.fact("반복 횟수", N);
        evidence.fact("결과를 버리는 루프 최솟값", discardedMicros + " µs");
        evidence.fact("결과를 소비하는 루프 최솟값", consumedMicros + " µs");
        evidence.fact("배율", String.format("%.0f배", consumedMicros / (double) Math.max(1, discardedMicros)));
        evidence.expectFlaky("버리는 루프는 소비하는 루프의 1/10 미만이다 (죽은 코드 제거)", discardedMicros * 10 < consumedMicros);
        evidence.expectFlaky("소비하는 루프는 실제로 시간이 든다 (≥ 1ms)", consumedMicros >= 1_000);
        evidence.note("이것이 verify-labs-perfbook 이 JMH 가 아닌 이유(docs/02 §4)이자, 이번에 verify-labs-jmh 를 "
                + "따로 만든 이유다. 시간 비교 케이스는 반드시 결과를 소비한다(PERF-12*, CN-07A 의 sink).");
    }

    private static void discarded(int n) {
        double sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += Math.sqrt(i) * 0.5;
        }
        // sum 을 쓰지 않는다 — 의도적
    }

    private static double consumed(int n) {
        double sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += Math.sqrt(i) * 0.5;
        }
        return sum;
    }
}
