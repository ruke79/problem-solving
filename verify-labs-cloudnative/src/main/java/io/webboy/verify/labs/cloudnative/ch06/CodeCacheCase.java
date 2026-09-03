package io.webboy.verify.labs.cloudnative.ch06;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Flags;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;

/**
 * 6장 — 계층형 컴파일·분할 코드 캐시·240MB 기본값. 책의 수치가 JDK 25 에서도 그대로인지
 * 실행 중인 JVM 에 직접 묻는다(플래그 + 메모리 풀 이름).
 */
public class CodeCacheCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-06B";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 6장 — 계층형 컴파일과 분할 코드 캐시의 기본값은 JDK 25 에서도 책과 같은가?";
    }

    @Override
    public String claim() {
        return "TieredCompilation 과 SegmentedCodeCache 는 기본 켜짐이고, ReservedCodeCacheSize 는 240MB 급이며, "
                + "코드 캐시는 non-nmethods / profiled nmethods / non-profiled nmethods 세 힙으로 나뉜다";
    }

    @Override
    protected void verify(Evidence evidence) {
        evidence.fact("TieredCompilation", Flags.value("TieredCompilation").orElse("없음"));
        evidence.fact("TieredStopAtLevel", Flags.value("TieredStopAtLevel").orElse("없음"));
        evidence.fact("SegmentedCodeCache", Flags.value("SegmentedCodeCache").orElse("없음"));
        evidence.fact("ReservedCodeCacheSize", Flags.value("ReservedCodeCacheSize").orElse("없음"));
        evidence.fact("CICompilerCount", Flags.value("CICompilerCount").orElse("없음"));

        long reserved = Long.parseLong(Flags.value("ReservedCodeCacheSize").orElse("0"));
        evidence.expect("계층형 컴파일이 기본이다", Flags.isTrue("TieredCompilation"));
        evidence.expect("분할 코드 캐시가 기본이다", Flags.isTrue("SegmentedCodeCache"));
        evidence.expect("코드 캐시 상한이 240MB 이상 241MB 미만이다 (책의 251,658,240 과 같은 급)",
                reserved >= 240L * 1024 * 1024 && reserved < 241L * 1024 * 1024);

        List<String> pools = ManagementFactory.getMemoryPoolMXBeans().stream().map(MemoryPoolMXBean::getName).toList();
        evidence.fact("메모리 풀", String.join(", ", pools));
        evidence.expect("CodeHeap 'non-nmethods' 풀이 있다", pools.contains("CodeHeap 'non-nmethods'"));
        evidence.expect("CodeHeap 'profiled nmethods' 풀이 있다", pools.contains("CodeHeap 'profiled nmethods'"));
        evidence.expect("CodeHeap 'non-profiled nmethods' 풀이 있다", pools.contains("CodeHeap 'non-profiled nmethods'"));
        evidence.note("JDK 25.0.4 의 ReservedCodeCacheSize 는 251,662,336 으로 17·21 의 251,658,240 보다 4,096 바이트 "
                + "크다(에르고노믹). 원인은 확인하지 않았다. 2판 11장의 Prometheus 출력에 보이는 "
                + "jvm_memory_committed_bytes{id=\"CodeHeap 'profiled nmethods'\"} 가 바로 이 풀들이다.");
    }
}
