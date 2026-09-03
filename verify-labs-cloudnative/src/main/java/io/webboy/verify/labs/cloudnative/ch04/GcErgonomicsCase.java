package io.webboy.verify.labs.cloudnative.ch04;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;

/**
 * 4장·9장 — "JVM 은 기동 시 머신을 탐침해 GC 를 고른다. 서버급이 아니면 Serial 이다."
 *
 * <p>컨테이너에서 CPU 를 1개로 제한하면 G1 이 아니라 Serial 이 조용히 선택된다 — 2판 8·9장이
 * K8s 리소스 제한을 다룰 때 전제하는 사실이다. 자식 JVM 에 {@code -XX:ActiveProcessorCount} 와
 * {@code -XX:MaxRAM} 을 주어 에르고노믹 결정을 {@code -Xlog:gc} 의 첫 줄로 읽는다.
 */
public class GcErgonomicsCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-04A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 4장·9장 — CPU 가 1개인 컨테이너에서 JVM 은 어떤 GC 를 고르는가?";
    }

    @Override
    public String claim() {
        return "JVM 은 CPU 2개 이상 + 물리 메모리 1792MB 이상인 '서버급' 머신에서만 G1 을 고르고, "
                + "CPU 가 1개이면 메모리가 넉넉해도 Serial GC 를 고른다 — 컨테이너 CPU 제한이 GC 선택을 바꾼다. "
                + "-XX:MaxRAM 은 힙 크기만 바꾸지 이 판정에는 관여하지 않는다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String defaultGc = using(Jvm.version("-Xlog:gc"));
        String oneCpu = using(Jvm.version("-XX:ActiveProcessorCount=1", "-Xlog:gc"));
        String oneCpuBigRam = using(Jvm.version("-XX:ActiveProcessorCount=1", "-XX:MaxRAM=8g", "-Xlog:gc"));
        String twoCpuSmallRam = using(Jvm.version("-XX:ActiveProcessorCount=2", "-XX:MaxRAM=1g", "-Xlog:gc"));
        String twoCpu = using(Jvm.version("-XX:ActiveProcessorCount=2", "-XX:MaxRAM=2g", "-Xlog:gc"));

        evidence.fact("이 머신 기본", defaultGc);
        evidence.fact("CPU 1", oneCpu);
        evidence.fact("CPU 1 + RAM 8g", oneCpuBigRam);
        evidence.fact("CPU 2 + RAM 1g", twoCpuSmallRam);
        evidence.fact("CPU 2 + RAM 2g", twoCpu);

        evidence.expect("이 머신(4코어)의 기본은 G1 이다", defaultGc.contains("G1"));
        evidence.expect("CPU 1개면 Serial 이다", oneCpu.contains("Serial"));
        evidence.expect("CPU 1개면 MaxRAM 을 키워도 Serial 이다", oneCpuBigRam.contains("Serial"));
        evidence.expect("CPU 2개면 G1 이다 (이 머신의 물리 메모리 기준)", twoCpu.contains("G1"));
        // 첫 판의 실패에서 배운 것: -XX:MaxRAM 은 힙 크기 계산에만 쓰이고 '서버급 머신' 판정(물리 메모리 ≥ 1792MB)에는
        // 반영되지 않는다 — CPU 2 + MaxRAM=1g 도 G1 이었다. 메모리 쪽 임계값은 cgroup 메모리 한계로만 흉내낼 수 있어
        // 이 샌드박스에서는 실행으로 확인하지 못했다(문서 기준으로 남긴다).
        evidence.expect("-XX:MaxRAM 은 GC 선택을 바꾸지 않는다 (CPU 2 + MaxRAM=1g 도 G1)", twoCpuSmallRam.contains("G1"));
        evidence.note("K8s 에서 requests/limits 로 CPU 를 1 로 주면 이 JVM 은 G1 튜닝 플래그를 아무리 줘도 "
                + "Serial 로 뜬다(명시적 -XX:+UseG1GC 를 주지 않는 한). 2판 9장의 '컨테이너 안에서 JVM 은 "
                + "자기가 보는 자원으로 스스로를 구성한다'는 서술의 가장 직접적인 결과다. 메모리 임계값(1792MB)은 "
                + "cgroup 한계가 있어야 실험할 수 있어 여기서는 확인하지 않았다 — 이 케이스의 첫 판은 MaxRAM 으로 흉내내려다 "
                + "REFUTED 가 났고, 그것이 'MaxRAM 은 에르고노믹 GC 선택에 무관하다'는 관측이 됐다.");
    }

    private static String using(Jvm.Result result) {
        return result.all().lines()
                .filter(line -> line.contains("Using"))
                .map(String::trim)
                .findFirst()
                .orElse("(Using 줄 없음: " + result.all().lines().findFirst().orElse("") + ")");
    }
}
