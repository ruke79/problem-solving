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
import java.util.function.IntUnaryOperator;

/**
 * docs/02 §9-4 14번 — <i>Java Performance: The Definitive Guide</i> 12장: "람다와 익명 클래스는 성능이 같다
 * (87.2 vs 87.9 µs)." 람다는 `invokedynamic` + `LambdaMetafactory` 로 첫 호출에 클래스를 만들고, 익명 클래스는
 * 컴파일 시점에 클래스가 있다 — <b>정상 상태의 호출 비용</b>이 같은지를 본다(생성 비용은 별개다).
 *
 * <p>호출 지점을 단형으로 유지하려고 람다 벤치마크와 익명 클래스 벤치마크를 따로 둔다(한 루프에 섞으면 양형이 된다).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
public class LambdaVsAnonymousBenchmark {

    private IntUnaryOperator lambda;
    private IntUnaryOperator anonymous;
    private int[] inputs;

    @Setup
    public void setup() {
        lambda = x -> (x * 31) ^ (x >>> 3);
        anonymous = new IntUnaryOperator() {
            @Override
            public int applyAsInt(int x) {
                return (x * 31) ^ (x >>> 3);
            }
        };
        inputs = new int[1_024];
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = i * 7;
        }
    }

    @Benchmark
    public void lambda(Blackhole bh) {
        int acc = 0;
        for (int x : inputs) {
            acc += lambda.applyAsInt(x);
        }
        bh.consume(acc);
    }

    @Benchmark
    public void anonymousClass(Blackhole bh) {
        int acc = 0;
        for (int x : inputs) {
            acc += anonymous.applyAsInt(x);
        }
        bh.consume(acc);
    }

    /** 대조군 — 인터페이스 없이 직접 계산. 둘 다 여기에 얼마나 가까운지가 "인라인됐는가" 의 단서다. */
    @Benchmark
    public void directCall(Blackhole bh) {
        int acc = 0;
        for (int x : inputs) {
            acc += (x * 31) ^ (x >>> 3);
        }
        bh.consume(acc);
    }
}
