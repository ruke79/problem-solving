package io.webboy.verify.labs.cloudnative.ch04;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Flags;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.RuntimeProbe;

import java.util.List;

/**
 * 4장·8장 — "기본 최대 힙은 보이는 메모리의 1/4 이다." 컨테이너 메모리 한계의 1/4 만 힙으로 쓰고
 * 나머지는 메타스페이스·코드 캐시·스레드 스택·네이티브가 쓴다는 2판 8장 「이미지 빌드」의 전제.
 */
public class ContainerMemoryCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-04B";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 4장·8장 — 컨테이너에 메모리 2GB 를 주면 JVM 힙은 얼마가 되는가?";
    }

    @Override
    public String claim() {
        return "MaxRAMPercentage 기본값 25 때문에 힙 상한은 보이는 메모리의 1/4(2GB → 약 512MB)이고, "
                + "-XX:MaxRAMPercentage=50 으로 올리면 절반(약 1GB)이 된다 — 컨테이너 한계의 나머지는 힙 밖 메모리 몫이다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        evidence.fact("MaxRAMPercentage 기본값", Flags.value("MaxRAMPercentage").orElse("없음"));
        evidence.fact("UseContainerSupport", Flags.value("UseContainerSupport").orElse("없음"));
        evidence.expect("MaxRAMPercentage 기본값은 25 다", Flags.value("MaxRAMPercentage").orElse("").startsWith("25"));
        evidence.expect("컨테이너 인식(UseContainerSupport)은 기본 켜짐이다", Flags.isTrue("UseContainerSupport"));

        long quarter = maxMemoryMb(List.of("-XX:MaxRAM=2g"));
        long half = maxMemoryMb(List.of("-XX:MaxRAM=2g", "-XX:MaxRAMPercentage=50"));
        evidence.fact("MaxRAM=2g 일 때 Runtime.maxMemory()", quarter + " MB");
        evidence.fact("MaxRAM=2g + MaxRAMPercentage=50", half + " MB");
        // 힙은 리전/카드 크기에 맞춰 정렬되고 maxMemory 는 서바이버 하나를 빼고 보고하므로 ±12% 로 본다
        evidence.expect("2GB 의 1/4 근처(450~560MB)다", quarter >= 450 && quarter <= 560);
        evidence.expect("50% 로 올리면 1GB 근처(900~1100MB)다", half >= 900 && half <= 1100);
        evidence.note("K8s limits.memory 를 2Gi 로 주고 -Xmx 를 안 주면 힙은 512MB 남짓이다. 힙을 더 쓰고 싶으면 "
                + "-Xmx 보다 -XX:MaxRAMPercentage 가 컨테이너 크기 변경에 따라오므로 낫다 — 단 75% 를 넘기면 "
                + "메타스페이스·코드 캐시·스레드 스택이 한계를 넘겨 OOM 킬을 부를 수 있다(2판 12장의 JFR 버퍼 경고와 같은 종류).");
    }

    private static long maxMemoryMb(List<String> jvmArgs) throws Exception {
        Jvm.Result result = Jvm.run(jvmArgs, RuntimeProbe.class);
        return result.stdout().lines()
                .filter(line -> line.startsWith("MAX_MEMORY_MB="))
                .map(line -> Long.parseLong(line.substring("MAX_MEMORY_MB=".length()).trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("프로브 출력에 MAX_MEMORY_MB 가 없다: " + result.all()));
    }
}
