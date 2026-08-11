package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class VolatileVisibilityCase extends VerificationCase {

    private static final long DEADLINE_MILLIS = 3_000L;
    private static final long SIGNAL_AFTER_MILLIS = 300L;

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
        plainFlag = true;
        long plainElapsed = spinUntilFlagCleared(false);

        volatileFlag = true;
        long volatileElapsed = spinUntilFlagCleared(true);

        boolean plainStoppedEarly = plainElapsed < DEADLINE_MILLIS - 500;
        boolean volatileStoppedEarly = volatileElapsed < DEADLINE_MILLIS - 500;

        evidence.fact("일반 필드 루프 종료까지(ms)", plainElapsed);
        evidence.fact("volatile 필드 루프 종료까지(ms)", volatileElapsed);
        evidence.fact("타임아웃 한계(ms)", DEADLINE_MILLIS);
        evidence.fact("신호 시각(ms)", SIGNAL_AFTER_MILLIS);
        evidence.fact("일반 필드가 신호를 받았는가", plainStoppedEarly);

        evidence.expect("volatile 필드 변경은 다른 스레드에 즉시 보인다", volatileStoppedEarly);
        evidence.expectFlaky("일반 필드는 변경이 보이지 않아 루프가 타임아웃까지 돈다", !plainStoppedEarly);

        evidence.note("이 결과는 JIT 최적화(호이스팅) 여부에 달려 있다 — 인터프리터 모드나 짧은 실행에서는 보일 수도 있다.");
        evidence.note("'우연히 동작함'은 정확성 근거가 아니다. JMM 이 보장하지 않는 것에 의존하면 안 된다는 것이 이 실험의 요점이다.");
        evidence.note("volatile 은 count++ 같은 복합 연산의 원자성은 보장하지 않는다 — CON-02 참고.");
    }

    private long spinUntilFlagCleared(boolean useVolatile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
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
        Thread.sleep(SIGNAL_AFTER_MILLIS);
        if (useVolatile) {
            volatileFlag = false;
        } else {
            plainFlag = false;
        }
        spinner.join(DEADLINE_MILLIS + 2_000L);
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
