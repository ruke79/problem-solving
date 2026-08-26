package io.webboy.verify.labs.perfbook.appendixa;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * 부록 A — 책의 플래그 권고 중 일부는 전제 자체가 사라졌다.
 *
 * <p>책(JDK 7/8 시절)의 권고 세 개를 <b>지금 실행 중인 JVM 에 직접 물어</b> 확인한다.
 *
 * <ul>
 *   <li><b>편향 락</b> — "스레드 풀에서는 {@code -XX:-BiasedLocking} 이 도움이 된다":
 *       JDK 15 에서 기본 비활성화 + 폐기(JEP 374), 18 에서 제거. 끄라는 권고를 따를 것이 없다.</li>
 *   <li><b>{@code AggressiveOpts}</b> — "테스트해 볼 수 있다": JDK 12 에서 제거. 옵션 자체가 없다.</li>
 *   <li><b>{@code StringTableSize}</b> — "기본 1009 라 인터닝이 많으면 키워라":
 *       JDK 11+ 기본값이 65536 이다. 책의 극적 수치(2.3시간)는 1009 시절의 것이다.</li>
 * </ul>
 *
 * <p>플래그 존재/기본값 확인이라 결정적이지만, <b>JDK 버전에 결부된 명제</b>다 —
 * 이 랩의 툴체인(Java 17)이 아닌 곳에서는 단정하지 않고 INCONCLUSIVE 로 남긴다.
 */
@Component
public class VanishedFlagsCase extends VerificationCase {

    @Override
    public String id() {
        return "PERF-A01";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 부록 A — 이 책의 플래그 권고는 지금 JVM 에서도 유효한가?";
    }

    @Override
    public String claim() {
        return "유명한 책의 튜닝 권고라도 런타임 버전을 확인하지 않으면 틀린 말이 된다. "
                + "편향 락은 기본 비활성(JDK 15+), AggressiveOpts 는 제거(JDK 12+), "
                + "StringTableSize 기본값은 1009 → 65536 — 책의 전제 셋이 현행 JDK 에서 사라졌다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // Java 17 이 아닌 환경에서는 판정하지 않는 게이트가 있다
    }

    @Override
    protected void verify(Evidence evidence) {
        String specVersion = System.getProperty("java.specification.version");
        evidence.fact("java.version", System.getProperty("java.version"));
        evidence.fact("java.vm.name", System.getProperty("java.vm.name"));

        boolean hotspot = System.getProperty("java.vm.name", "").contains("HotSpot")
                || System.getProperty("java.vm.name", "").contains("OpenJDK");
        if (!"17".equals(specVersion) || !hotspot) {
            evidence.expectFlaky("이 명제는 HotSpot Java 17 기준으로만 단정한다 — 지금 환경이 아니다", false);
            evidence.note("다른 JDK 에서는 기본값·플래그 존재 여부가 다를 수 있다. "
                    + "그것이 바로 이 케이스의 주장이기도 하다 — 버전을 확인하고 말하라.");
            return;
        }

        HotSpotDiagnosticMXBean diagnostics =
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

        // ① 편향 락 — 플래그는 아직 있지만(17 은 폐기 단계) 기본이 꺼짐이다
        String biasedLocking = diagnostics.getVMOption("UseBiasedLocking").getValue();
        evidence.fact("UseBiasedLocking 기본값", biasedLocking);
        evidence.expect("편향 락은 기본으로 꺼져 있다 — 책의 '끄라'는 권고가 기본값이 됐다",
                "false".equals(biasedLocking));

        // ② AggressiveOpts — 옵션 자체가 없다
        boolean aggressiveOptsGone;
        try {
            diagnostics.getVMOption("AggressiveOpts");
            aggressiveOptsGone = false;
        } catch (IllegalArgumentException e) {
            aggressiveOptsGone = true;
        }
        evidence.fact("AggressiveOpts", aggressiveOptsGone ? "옵션이 존재하지 않는다" : "아직 존재한다");
        evidence.expect("AggressiveOpts 는 제거됐다", aggressiveOptsGone);

        // ③ StringTableSize — 기본값이 책 시절의 65배다
        String stringTableSize = diagnostics.getVMOption("StringTableSize").getValue();
        evidence.fact("StringTableSize 기본값", stringTableSize + " (책 시절: 1009)");
        evidence.expect("StringTableSize 기본값이 65536 이다", "65536".equals(stringTableSize));

        // 덤 — 책이 "끌 이유가 전혀 없다"고 한 것은 그대로 기본값이다
        String compressedOops = diagnostics.getVMOption("UseCompressedOops").getValue();
        evidence.fact("UseCompressedOops", compressedOops);
        evidence.expect("압축 oop 은 여전히 기본으로 켜져 있다 (32GB 미만 힙)", "true".equals(compressedOops));

        evidence.note("확인 방법 자체가 부록 A 의 교훈이다 — 문서가 아니라 실행 중인 JVM "
                + "(HotSpotDiagnosticMXBean) 에 물었다. notes/java-performance/A-튜닝-플래그-요약.md 와 "
                + "docs/02 §9-2 가 이 케이스의 배경이다.");
    }
}
