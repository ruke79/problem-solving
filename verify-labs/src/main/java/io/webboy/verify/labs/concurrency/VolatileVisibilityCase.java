package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class VolatileVisibilityCase extends VerificationCase {

    private static final long DEADLINE_MILLIS = 3_000L;
    /**
     * 신호를 늦게 보낼수록 스핀 루프가 컴파일될 시간이 늘어난다.
     * 300ms 로는 워밍업을 넣고도 8회 중 1회가 컴파일 전에 신호를 받아 재현에 실패했다.
     */
    private static final long SIGNAL_AFTER_MILLIS = 800L;

    /**
     * 측정 전에 같은 루프를 한 번 돌려 JIT 이 컴파일하게 만든다.
     *
     * <p>이게 없으면 장비가 바쁠 때 <b>신호가 오는 300ms 안에 컴파일이 끝나지 않아</b>
     * 인터프리터가 매번 필드를 다시 읽고, 그러면 일반 필드도 변경이 보여 '안 보인다'가 재현되지 않는다.
     * 실제로 8회 중 2회가 그렇게 흔들렸고, 그때 관측값은 예외 없이 {@code plain = 300ms}(=신호 시각)였다.
     */
    private static final long WARMUP_MILLIS = 600L;

    private boolean plainFlag = true;
    private volatile boolean volatileFlag = true;

    @Override
    public String id() {
        return "CON-03";
    }

    @Override
    public String category() {
        return "concurrency";
    }

    @Override
    public String question() {
        return "volatile 은 무엇을 보장하고 무엇을 보장하지 않습니까?";
    }

    @Override
    public String claim() {
        return "volatile 은 가시성과 재정렬 금지를 보장한다(원자성은 아니다). 일반 필드는 다른 스레드에서 변경이 보이지 않을 수 있다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // 신호를 보내지 않고 한 번씩 돌려 두 루프를 컴파일시킨다(위 WARMUP_MILLIS 설명 참고).
        long warmupPlain = spin(false, WARMUP_MILLIS, false);
        long warmupVolatile = spin(true, WARMUP_MILLIS, false);

        plainFlag = true;
        long plainElapsed = spin(false, DEADLINE_MILLIS, true);

        volatileFlag = true;
        long volatileElapsed = spin(true, DEADLINE_MILLIS, true);

        boolean plainStoppedEarly = plainElapsed < DEADLINE_MILLIS - 500;
        boolean volatileStoppedEarly = volatileElapsed < DEADLINE_MILLIS - 500;

        evidence.fact("워밍업 스핀(ms) — 일반 / volatile", warmupPlain + " / " + warmupVolatile);
        evidence.fact("일반 필드 루프 종료까지(ms)", plainElapsed);
        evidence.fact("volatile 필드 루프 종료까지(ms)", volatileElapsed);
        evidence.fact("타임아웃 한계(ms)", DEADLINE_MILLIS);
        evidence.fact("신호 시각(ms)", SIGNAL_AFTER_MILLIS);
        evidence.fact("일반 필드가 신호를 받았는가", plainStoppedEarly);

        evidence.expect("volatile 필드 변경은 다른 스레드에 즉시 보인다", volatileStoppedEarly);
        evidence.expectFlaky("일반 필드는 변경이 보이지 않아 루프가 타임아웃까지 돈다", !plainStoppedEarly);

        evidence.note("이 결과는 JIT 최적화(호이스팅) 여부에 달려 있다 — 인터프리터 모드나 짧은 실행에서는 보일 수도 있다. 그래서 이 케이스는 측정 전에 같은 루프를 " + WARMUP_MILLIS + "ms 돌려 컴파일을 끝내 둔다. 워밍업을 빼면 장비가 바쁠 때 신호(" + SIGNAL_AFTER_MILLIS + "ms)보다 컴파일이 늦어, 일반 필드인데도 변경이 보여 재현이 실패한다 — 실제로 8회 중 2회가 그랬고 그때 값은 정확히 " + SIGNAL_AFTER_MILLIS + "ms 였다.");
        evidence.note("뒤집어 말하면, 이 '안 보임'은 JVM 이 보장하는 동작이 아니라 **최적화가 만들어 낸 관측 가능한 증상**이다. 그래서 판정을 `expectFlaky` 로 둔다 — 안 보이는 것이 재현되지 않았다고 해서 답변이 틀린 것은 아니다.");
        evidence.note("'우연히 동작함'은 정확성 근거가 아니다. JMM 이 보장하지 않는 것에 의존하면 안 된다는 것이 이 실험의 요점이다.");
        evidence.note("volatile 은 count++ 같은 복합 연산의 원자성은 보장하지 않는다 — CON-02 참고.");
    }

    /**
     * @param durationMillis 스핀 상한
     * @param signal         {@link #SIGNAL_AFTER_MILLIS} 뒤에 플래그를 내릴지 (워밍업에서는 내리지 않는다)
     */
    private long spin(boolean useVolatile, long durationMillis, boolean signal) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        Thread spinner = new Thread(() -> {
            long spins = 0;
            if (useVolatile) {
                while (volatileFlag && System.nanoTime() < deadline) {
                    spins++;
                }
            } else {
                while (plainFlag && System.nanoTime() < deadline) {
                    spins++;
                }
            }
            if (spins < 0) {
                throw new IllegalStateException("최적화 방지용 — 도달하지 않는다");
            }
        }, "visibility-spinner");
        spinner.setDaemon(true);

        long began = System.nanoTime();
        spinner.start();
        if (signal) {
            Thread.sleep(SIGNAL_AFTER_MILLIS);
            if (useVolatile) {
                volatileFlag = false;
            } else {
                plainFlag = false;
            }
        }
        spinner.join(durationMillis + 2_000L);
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
