package io.webboy.verify.labs.cloudnative.ch05;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Flags;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.HeaderProbe;

import java.util.List;

/**
 * 4장·5장 — 책은 객체 헤더를 "마크 워드 + klass 워드(압축 시 12바이트)" 로 설명한다.
 * JDK 25 에서 컴팩트 객체 헤더(JEP 519)가 정식 기능이 됐다 — 헤더 8바이트, 필드 없는 객체 16 → 8.
 * 자식 JVM 둘(기본 / +UseCompactObjectHeaders)에서 객체당 바이트를 실측한다.
 */
public class CompactObjectHeadersCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-05C";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 4·5장 — JDK 25 의 컴팩트 객체 헤더는 객체 하나를 얼마나 줄이는가?";
    }

    @Override
    public String claim() {
        return "기본 헤더로는 new Object() 가 16바이트, -XX:+UseCompactObjectHeaders 로는 8바이트다. "
                + "플래그는 25 에서 실험 표시 없이 받아들여지지만 기본은 아직 꺼져 있다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 사용 힙 차이로 어림하는 측정이라 잡음이 있다 — 자릿수만 본다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        evidence.fact("UseCompactObjectHeaders (부모 JVM 기본값)", Flags.value("UseCompactObjectHeaders").orElse("플래그 없음"));
        evidence.expect("플래그가 존재하고 기본은 false 다",
                "false".equals(Flags.value("UseCompactObjectHeaders").orElse("")));

        double plain = bytesPerObject(List.of("-Xmx512m", "-XX:+UseSerialGC"));
        double compact = bytesPerObject(List.of("-Xmx512m", "-XX:+UseSerialGC", "-XX:+UseCompactObjectHeaders"));
        evidence.fact("기본 헤더 — 객체당 바이트", String.format("%.1f", plain));
        evidence.fact("컴팩트 헤더 — 객체당 바이트", String.format("%.1f", compact));
        evidence.expectFlaky("기본 헤더로는 16바이트 근처(14~18)다", plain >= 14 && plain <= 18);
        evidence.expectFlaky("컴팩트 헤더로는 8바이트 근처(6~10)다", compact >= 6 && compact <= 10);
        evidence.expectFlaky("컴팩트 헤더가 절반 수준이다", compact * 1.6 < plain);
        evidence.note("필드가 없는 객체는 헤더가 전부라 차이가 가장 극적이다. 필드가 있는 보통 객체는 4바이트 "
                + "(12 → 8)가 줄고 8바이트 정렬에 따라 0 또는 8바이트가 준다. 2판 4장의 객체 레이아웃 그림(마크·klass)은 "
                + "25 의 기본값에서는 그대로이고, 이 플래그를 켠 JVM 에서는 klass 포인터가 마크 워드 안으로 들어간다.");
    }

    private static double bytesPerObject(List<String> jvmArgs) throws Exception {
        Jvm.Result result = Jvm.run(jvmArgs, HeaderProbe.class);
        return result.stdout().lines()
                .filter(line -> line.startsWith("BYTES_PER_OBJECT="))
                .map(line -> Double.parseDouble(line.substring("BYTES_PER_OBJECT=".length()).trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("프로브 출력이 없다: " + result.all()));
    }
}
