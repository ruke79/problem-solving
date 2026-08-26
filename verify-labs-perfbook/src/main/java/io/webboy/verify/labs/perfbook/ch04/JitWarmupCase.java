package io.webboy.verify.labs.perfbook.ch04;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 4장 — 핫스팟은 "많이 실행된 코드"를 컴파일하고, 그 뒤에야 빨라진다.
 *
 * <p>책 4장의 핵심 전제다: 처음 실행되는 자바 코드는 인터프리터로 돌고, 호출 횟수가
 * 임계값을 넘으면 JIT 이 네이티브 코드로 컴파일한다. 그래서 <b>같은 메서드의 실행 시간이
 * 워밍업 전후로 자릿수가 달라진다</b> — 벤치마크에 워밍업이 필요한 이유이기도 하다(2장).
 *
 * <p>측정: 같은 메서드의 소요 시간을 <b>최초 몇 회</b>와 <b>2만 회 반복 후</b>에 각각 재서
 * 비교한다. 컴파일 시점과 배율은 JVM 이 정하므로 시간 비교는 전부 {@code expectFlaky} 다.
 */
@Component
public class JitWarmupCase extends VerificationCase {

    private static final int WARMUP_INVOCATIONS = 20_000;
    private static final int WORK_SIZE = 10_000;
    /** 워밍업 후가 최소 이만큼은 빨라야 "컴파일됐다"고 본다. 책의 사례는 수십 배지만 보수적으로 잡는다. */
    private static final int MIN_SPEEDUP = 2;

    @Override
    public String id() {
        return "PERF-04";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 4장 — JIT 컴파일러는 무엇을, 언제 컴파일하나?";
    }

    @Override
    public String claim() {
        return "자바 코드는 처음에는 인터프리터로 실행되고, 반복 실행으로 호출 횟수가 임계값을 넘으면 "
                + "네이티브 코드로 컴파일된다. 그래서 같은 메서드가 워밍업 후에 자릿수 단위로 빨라진다 — "
                + "워밍업 없는 측정은 인터프리터를 잰 것이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 컴파일 시점·배율은 JIT 이 정한다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        long[] data = ThreadLocalRandom.current().longs(WORK_SIZE).toArray();

        // 최초 실행 — 클래스 로딩 직후라 인터프리터 단계다. 첫 5회의 최솟값을 쓴다
        // (첫 1회에는 클래스 로딩·검증 비용이 섞이므로 그것만 잰다고 볼 수 없다).
        long coldMicros = Timing.min(5, data);

        // 임계값(-XX:CompileThreshold, 계층형 기본에서는 더 일찍)을 확실히 넘긴다
        long blackhole = 0;
        for (int i = 0; i < WARMUP_INVOCATIONS; i++) {
            blackhole += work(data);
        }

        long warmMicros = Timing.min(5, data);

        evidence.fact("워밍업 전 (첫 5회 최솟값)", coldMicros + " us");
        evidence.fact("워밍업 후 (2만 회 반복 뒤 5회 최솟값)", warmMicros + " us");
        evidence.fact("배율", coldMicros == 0 ? "측정 불가" : String.format("%.1f배", (double) coldMicros / Math.max(1, warmMicros)));
        evidence.fact("blackhole", Long.toHexString(blackhole));   // 죽은 코드 제거 방지

        evidence.expect("측정 해상도가 확보된다 (워밍업 전이 0us 가 아니다)", coldMicros > 0);
        evidence.expectFlaky("워밍업 후가 최소 " + MIN_SPEEDUP + "배 빨라진다",
                warmMicros * MIN_SPEEDUP <= coldMicros);
        evidence.note("절대값이 아니라 배율만 의미가 있다. 배율 자체도 JVM·장비에 좌우된다 — "
                + "이 케이스가 확인하는 것은 '워밍업 전후로 유의미하게 달라진다'는 방향뿐이다.");
    }

    private static long work(long[] data) {
        long sum = 0;
        for (long value : data) {
            sum += Long.bitCount(value * 31 + 17);
        }
        return sum;
    }

    /** 이 케이스 전용 측정 — 측정 대상 메서드가 고정이라 공용 {@code Timing} 대신 직접 잰다. */
    private static final class Timing {
        static long min(int rounds, long[] data) {
            long min = Long.MAX_VALUE;
            long blackhole = 0;
            for (int i = 0; i < rounds; i++) {
                long began = System.nanoTime();
                blackhole += work(data);
                min = Math.min(min, (System.nanoTime() - began) / 1_000L);
            }
            if (blackhole == Long.MIN_VALUE) {
                throw new IllegalStateException();   // 죽은 코드 제거 방지
            }
            return min;
        }
    }
}
