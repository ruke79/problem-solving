package io.webboy.verify.labs.cloudnative.ch05;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.RuntimeProbe;

import java.util.List;

/**
 * 5장 — 책(JDK 21)은 ZGC 를 "비세대형이 기본, -XX:+ZGenerational 로 세대형" 으로 설명한다.
 * JDK 23 에서 세대형이 기본이 됐고(JEP 474), 24 에서 비세대형이 제거됐다(JEP 490).
 * 자식 JVM 의 GC MXBean 이름으로 어떤 ZGC 가 떴는지 읽는다 — 세대형은 Minor/Major 로 갈라진다.
 */
public class GenerationalZgcCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-05A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 5장 — JDK 25 에서 -XX:+UseZGC 는 세대형인가 비세대형인가?";
    }

    @Override
    public String claim() {
        return "JDK 25 의 ZGC 는 세대형뿐이다. -XX:+UseZGC 만 주면 'ZGC Minor/Major Cycles' 빈이 뜨고, "
                + "책의 -XX:±ZGenerational 은 '24.0 에서 제거됨' 경고와 함께 무시된다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Jvm.Result plain = Jvm.run(List.of("-XX:+UseZGC"), RuntimeProbe.class);
        String beans = gcBeans(plain);
        evidence.fact("-XX:+UseZGC 의 GC MXBean", beans);
        evidence.expect("ZGC 가 떴다(rc=0)", plain.exitCode() == 0);
        evidence.expect("세대형 빈(Minor/Major)이 보인다",
                beans.contains("ZGC Minor Cycles") && beans.contains("ZGC Major Cycles"));
        evidence.expect("비세대형 빈 이름('ZGC Cycles')은 없다", !beans.contains("[ZGC Cycles]"));

        Jvm.Result legacy = Jvm.run(List.of("-XX:+UseZGC", "-XX:+ZGenerational"), RuntimeProbe.class);
        String warning = legacy.stderr().lines().findFirst().orElse("");
        evidence.fact("-XX:+ZGenerational 에 대한 첫 stderr 줄", warning);
        evidence.expect("책의 플래그는 경고만 남기고 무시된다(rc=0, 'support was removed in 24.0')",
                legacy.exitCode() == 0 && warning.contains("Ignoring option ZGenerational"));

        Jvm.Result off = Jvm.run(List.of("-XX:+UseZGC", "-XX:-ZGenerational"), RuntimeProbe.class);
        evidence.fact("-XX:-ZGenerational 로 껐을 때의 빈", gcBeans(off));
        evidence.expect("끄려 해도 세대형이다 — 비세대형으로 돌아갈 길이 없다",
                gcBeans(off).contains("ZGC Minor Cycles"));

        evidence.note("JDK 21 에서 같은 프로브를 돌리면 -XX:+UseZGC 는 [ZGC Cycles][ZGC Pauses], "
                + "+ZGenerational 은 Minor/Major 로 나왔다(이 세션에서 21.0.10 으로 확인). 책의 서술은 21 기준으로 맞고 "
                + "25 에서는 '세대형만 있다'로 고쳐 읽어야 한다. 모니터링 대시보드의 GC 빈 이름도 같이 바뀐다.");
    }

    private static String gcBeans(Jvm.Result result) {
        return result.stdout().lines()
                .filter(line -> line.startsWith("GC_BEANS="))
                .map(line -> line.substring("GC_BEANS=".length()))
                .findFirst()
                .orElse("(GC_BEANS 없음: " + result.all() + ")");
    }
}
