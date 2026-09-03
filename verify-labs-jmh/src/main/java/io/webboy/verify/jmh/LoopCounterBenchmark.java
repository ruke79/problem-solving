package io.webboy.verify.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * <i>Optimizing Java</i> 1판 10장 「루프 언롤링」의 JMH 예제를 그대로 옮긴 것 — "int 카운터 루프는 언롤되고
 * 세이프포인트 폴이 빠지지만 long 카운터 루프는 그렇지 않아 int 쪽이 64% 더 많은 연산을 한다"(JDK 8 수치).
 *
 * <p>2판은 이 절을 뺐다. 지금 JDK 에서도 그런지 보려고 남긴다 — 결과가 책과 달라도 그것이 정보다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(1)
public class LoopCounterBenchmark {

    private static final int MAX = 1_000_000;
    private long[] data;

    @Setup
    public void createData() {
        data = new long[MAX];
        Random random = new Random(42);
        for (int i = 0; i < MAX; i++) {
            data[i] = random.nextLong();
        }
    }

    @Benchmark
    public long intStride1() {
        long sum = 0;
        for (int i = 0; i < MAX; i++) {
            sum += data[i];
        }
        return sum;
    }

    @Benchmark
    public long longStride1() {
        long sum = 0;
        for (long l = 0; l < MAX; l++) {
            sum += data[(int) l];
        }
        return sum;
    }
}
