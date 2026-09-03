package io.webboy.verify.labs.cloudnative.ch13;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.PinProbe;

import java.util.List;

/**
 * 13장 — 책의 한계 목록: "JNI 호출과 synchronized 키워드는 가상 스레드를 캐리어에 피닝한다."
 * JDK 24 의 JEP 491 이 synchronized 쪽을 없앴다. 캐리어 1개 실험으로 확인한다.
 */
public class VirtualThreadPinningCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-13A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 13장 — synchronized 블록 안에서 블로킹하는 가상 스레드는 여전히 캐리어를 붙들고 있는가?";
    }

    @Override
    public String claim() {
        return "JDK 25 에서는 아니다(JEP 491, JDK 24). 캐리어가 하나뿐일 때 가상 스레드 A 가 synchronized 안에서 "
                + "600ms 자도 가상 스레드 B 는 곧바로 시작한다. JDK 21 에서는 B 가 A 가 끝날 때까지 기다렸다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 시작 지연을 재는 것이라 스케줄링 잡음이 있다 — 임계는 여유 있게 300ms
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Jvm.Result result = Jvm.run(List.of(
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1"), PinProbe.class);
        evidence.fact("프로브 출력", result.stdout().strip().replace('\n', ' '));
        evidence.expect("프로브가 정상 종료했다", result.exitCode() == 0);

        long hold = value(result, "HOLD_MS=");
        long bStart = value(result, "B_START_MS=");
        evidence.fact("A 가 synchronized 안에서 잔 시간", hold + " ms");
        evidence.fact("B 가 시작하기까지 걸린 시간", bStart + " ms");
        evidence.expectFlaky("B 는 A 가 끝나기 훨씬 전에 시작한다 (< 300ms) — synchronized 피닝이 없다", bStart < 300);
        evidence.expect("가상 스레드는 데몬이다", result.mentions("A_DAEMON=true"));
        evidence.expect("Thread.ofVirtual() 이 만든 스레드는 isVirtual()", result.mentions("A_VIRTUAL=true"));
        evidence.note("같은 프로브를 JDK 21.0.10 으로 돌리면 B_START_MS=607 (PINNED), -Djdk.tracePinnedThreads=full 에 "
                + "reason:MONITOR 스택이 찍혔다(이 세션에서 확인). 25.0.4 에서는 10ms 였다. 책의 한계 목록에서 "
                + "synchronized 항목은 지워야 하고 JNI/네이티브 프레임 피닝은 남는다.");
    }

    private static long value(Jvm.Result result, String key) {
        return result.stdout().lines()
                .filter(line -> line.startsWith(key))
                .map(line -> Long.parseLong(line.substring(key.length()).trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(key + " 가 없다: " + result.all()));
    }
}
