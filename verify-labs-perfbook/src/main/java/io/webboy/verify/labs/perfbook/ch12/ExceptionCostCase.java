package io.webboy.verify.labs.perfbook.ch12;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Timing;
import org.springframework.stereotype.Component;

/**
 * 12장 — 예외 비용의 실체는 예외가 아니라 스택 트레이스 수집이다.
 *
 * <p>책 12장의 측정: 얕은 스택에서는 예외를 써도 안 써도 비슷했지만(31ms vs 381ms),
 * 스택을 100단 깊게 만들자 예외 쪽만 10,673ms 로 튀었다.
 * 예외 객체 생성 시 {@code fillInStackTrace()} 가 <b>현재 스택 깊이에 비례하는</b> 일을
 * 하기 때문이다. 그래서 JDK 스스로도 일부 시스템 예외를 스택 없이 재사용한다.
 *
 * <p>측정: (a) 스택 트레이스 유무는 결정적으로 — {@code writableStackTrace=false} 예외는
 * 프레임이 0개다. (b) 깊은 스택 vs 얕은 스택의 던지기 비용은 {@code expectFlaky} 로.
 */
@Component
public class ExceptionCostCase extends VerificationCase {

    private static final int THROWS = 20_000;
    private static final int DEEP = 1024;

    @Override
    public String id() {
        return "PERF-12B";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 12장 — 예외는 비싼가? 무엇이 비싼가?";
    }

    @Override
    public String claim() {
        return "예외의 지배적 비용은 던지기가 아니라 생성 시의 스택 트레이스 수집이고, 그 비용은 "
                + "스택 깊이에 비례한다. 스택 수집을 끄면(writableStackTrace=false) 그 비용이 사라진다 — "
                + "JVM 이 일부 시스템 예외를 스택 없이 재사용하는 이유다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // (a) 결정적 — 스택 수집을 끄면 프레임이 없다
        RuntimeException withStack = new RuntimeException("with");
        StacklessException withoutStack = new StacklessException();
        RuntimeException deepException = frameCountProbe(DEEP);
        evidence.fact("일반 예외의 스택 프레임 (측정 기준점)", withStack.getStackTrace().length + "개");
        evidence.fact(DEEP + "단 재귀 아래에서 만든 예외의 스택 프레임", deepException.getStackTrace().length + "개");
        evidence.fact("writableStackTrace=false 예외의 스택 프레임", withoutStack.getStackTrace().length + "개");
        evidence.expect("일반 예외는 생성 지점의 스택을 담는다", withStack.getStackTrace().length > 0);
        // JVM 은 스택 트레이스를 MaxJavaStackTraceDepth(기본 1024) 프레임까지만 기록한다 —
        // 기준 스택이 깊은 환경에서 이 확인이 억울하게 깨지지 않도록 상한을 반영한다.
        int expectedFrames = Math.min(1024, withStack.getStackTrace().length + DEEP * 9 / 10);
        evidence.expect("깊이 " + DEEP + " 재귀는 프레임 수를 실제로 늘린다 (기대 " + expectedFrames
                        + "개 이상, JIT 인라이닝 여유 10%·기록 상한 1024 반영)",
                deepException.getStackTrace().length >= expectedFrames);
        evidence.expect("스택 수집을 끈 예외는 프레임이 0개다", withoutStack.getStackTrace().length == 0);

        // 워밍업 — JIT 이 예외 경로를 컴파일할 시간을 준다
        createAtDepth(1, THROWS, true);
        createAtDepth(DEEP, THROWS, true);
        createAtDepth(DEEP, THROWS, false);

        // (b) 스택 깊이에 비례하는 비용 — 명제가 말하는 것은 "생성 시 수집"이므로
        //     던지기·되감기를 섞지 않고 생성만 잰다. 처음에는 던지기까지 포함해 쟀는데,
        //     되감기 비용도 깊이에 비례해 남아서 수집 비용이 흐려졌다(케이스 note 참고).
        long shallowMicros = Timing.minMicros(3, () -> createAtDepth(1, THROWS, true));
        long deepMicros = Timing.minMicros(3, () -> createAtDepth(DEEP, THROWS, true));
        long deepStacklessMicros = Timing.minMicros(3, () -> createAtDepth(DEEP, THROWS, false));

        evidence.fact("기준 스택(테스트 하네스 위)에서 " + THROWS + "회 생성", shallowMicros + " us");
        evidence.fact("깊은 스택(" + DEEP + "단)에서 " + THROWS + "회 생성", deepMicros + " us");
        evidence.fact("깊은 스택 + 스택 수집 끔", deepStacklessMicros + " us");

        evidence.expect("측정 해상도가 확보된다", shallowMicros > 0 && deepStacklessMicros > 0);
        evidence.expectFlaky("깊은 스택에서의 생성이 기준 스택보다 비싸다 (최소 3배)",
                shallowMicros * 3 <= deepMicros);
        evidence.expectFlaky("스택 수집을 끄면 그 비용이 사라진다 (깊은 스택 생성의 1/3 이하)",
                deepStacklessMicros * 3 <= deepMicros);
        evidence.note("측정 범위를 정직하게 적어 둔다: 여기서 잰 것은 '생성(fillInStackTrace)' 비용이다. "
                + "던지고 받는 비용(되감기)은 스택을 꺼도 깊이에 비례해 남는다 — 처음 설계(던지기 포함)가 "
                + "INCONCLUSIVE 로 판명나서 측정을 명제에 맞게 좁혔다. 결론은 책과 같다 — 예외를 흐름 제어로 "
                + "쓰지 말라는 이유의 절반이 이 비용이고, 비용을 지우겠다고 스택을 끄면 "
                + "(-XX:-StackTraceInThrowable 포함) 이번엔 진단 정보가 사라진다. 코드를 고치는 게 먼저다.");
    }

    /** 프레임 수 확인용 — {@code depth}단 내려가 예외 하나를 만들어 온다. */
    private static RuntimeException frameCountProbe(int depth) {
        if (depth > 0) {
            return frameCountProbe(depth - 1);
        }
        return new RuntimeException("probe");
    }

    /** {@code depth}단 재귀 아래로 내려간 뒤, 그 지점에서 예외를 {@code times}회 생성만 한다. */
    private static long createAtDepth(int depth, int times, boolean withStack) {
        long blackhole = descendAndCreate(depth, times, withStack);
        if (blackhole == Long.MIN_VALUE) {
            throw new IllegalStateException();   // 죽은 코드 제거 방지
        }
        return blackhole;
    }

    private static long descendAndCreate(int remaining, int times, boolean withStack) {
        if (remaining > 0) {
            return descendAndCreate(remaining - 1, times, withStack);
        }
        long blackhole = 0;
        for (int i = 0; i < times; i++) {
            RuntimeException e = withStack ? new RuntimeException("boom") : new StacklessException();
            blackhole += System.identityHashCode(e);   // 생성이 제거되지 않게 관측한다
        }
        return blackhole;
    }

    /** {@code writableStackTrace=false} — 생성 시 스택을 걷지 않는 예외. */
    private static final class StacklessException extends RuntimeException {
        StacklessException() {
            super("stackless", null, false, false);
        }
    }
}
