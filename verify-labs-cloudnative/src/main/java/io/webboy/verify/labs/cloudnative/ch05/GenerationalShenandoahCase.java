package io.webboy.verify.labs.cloudnative.ch05;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Flags;
import io.webboy.verify.labs.cloudnative.Jvm;

/**
 * 5장 — 책은 Shenandoah 를 "단일 세대 동시 수집기" 로 설명한다. JDK 25 에서 세대형 모드가
 * 정식이 됐다(JEP 521). 21 에서는 {@code ShenandoahGCMode=generational} 이 'Unknown' 이었다.
 */
public class GenerationalShenandoahCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-05B";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 5장 — Shenandoah 에 세대형 모드가 있는가?";
    }

    @Override
    public String claim() {
        return "JDK 25 부터 -XX:ShenandoahGCMode=generational 이 실험 플래그 없이 받아들여지고 "
                + "기동 로그에 'Mode: Generational' 이 찍힌다. 기본 모드는 여전히 단일 세대(satb)다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Jvm.Result plain = Jvm.version("-XX:+UseShenandoahGC", "-Xlog:gc,gc+init");
        if (plain.exitCode() != 0) {
            // 이 JDK 빌드에 Shenandoah 가 빠져 있으면(일부 벤더 빌드) 판정할 수 없다
            evidence.fact("-XX:+UseShenandoahGC", plain.all().lines().findFirst().orElse(""));
            evidence.expectFlaky("이 JDK 빌드에는 Shenandoah 가 들어 있어야 한다 — 지금 빌드에는 없다", false);
            return;
        }
        evidence.fact("기본 ShenandoahGCMode (부모 JVM 의 플래그 값)", Flags.value("ShenandoahGCMode").orElse("없음"));
        evidence.fact("-XX:+UseShenandoahGC 기동 로그", modeLine(plain));

        Jvm.Result generational = Jvm.version("-XX:+UseShenandoahGC",
                "-XX:ShenandoahGCMode=generational", "-Xlog:gc,gc+init");
        evidence.fact("ShenandoahGCMode=generational 기동 로그", modeLine(generational));
        evidence.expect("기본 모드는 satb(단일 세대)다", "satb".equals(Flags.value("ShenandoahGCMode").orElse("")));
        evidence.expect("generational 모드가 실험 플래그 없이 기동된다(rc=0)", generational.exitCode() == 0);
        evidence.expect("기동 로그에 'Mode: Generational' 이 찍힌다", generational.mentions("Mode: Generational"));
        evidence.note("JDK 21.0.10 에서 같은 플래그는 'Unknown -XX:ShenandoahGCMode option' 으로 기동에 실패했다"
                + "(이 세션에서 확인). 책의 Shenandoah 절은 21 기준으로 맞고, 25 에서는 G1·ZGC·Shenandoah 셋 다 세대형이 있다.");
    }

    private static String modeLine(Jvm.Result result) {
        return result.all().lines()
                .filter(line -> line.contains("Using") || line.contains("Mode:"))
                .map(String::trim)
                .reduce((a, b) -> a + " | " + b)
                .orElse("(로그 없음)");
    }
}
