package io.webboy.verify.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * docs/02 §9-4 13번 — <i>Java Performance: The Definitive Guide</i> 4장: "`final` 키워드는 성능과 무관하다.
 * 인라이닝·상수 접기 같은 최적화는 JIT 가 프로파일로 판단하지 `final` 표시로 판단하지 않는다."
 *
 * <p>같은 모양의 클래스 둘(필드가 final / 아님)과 같은 모양의 메서드 둘(final / 아님)을 같은 일로 잰다.
 * "차이가 없다" 가 결론이므로 <b>오차 범위가 겹치는지</b>를 본다 — 벽시계 케이스로는 할 수 없는 판정이다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
public class FinalFieldBenchmark {

    static final class WithFinalFields {
        private final int a;
        private final int b;
        private final int c;

        WithFinalFields(int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        final int mix(int x) {
            return (a * x + b) ^ c;
        }
    }

    static final class WithPlainFields {
        private int a;
        private int b;
        private int c;

        WithPlainFields(int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        int mix(int x) {
            return (a * x + b) ^ c;
        }
    }

    private WithFinalFields finalFields;
    private WithPlainFields plainFields;
    private int[] inputs;

    @Setup
    public void setup() {
        finalFields = new WithFinalFields(31, 17, 0x5bd1e995);
        plainFields = new WithPlainFields(31, 17, 0x5bd1e995);
        inputs = new int[1_024];
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = i * 2_654_435_761L > 0 ? i : -i;
        }
    }

    @Benchmark
    public void finalFieldsAndMethod(Blackhole bh) {
        int acc = 0;
        for (int x : inputs) {
            acc += finalFields.mix(x);
        }
        bh.consume(acc);
    }

    @Benchmark
    public void plainFieldsAndMethod(Blackhole bh) {
        int acc = 0;
        for (int x : inputs) {
            acc += plainFields.mix(x);
        }
        bh.consume(acc);
    }
}
