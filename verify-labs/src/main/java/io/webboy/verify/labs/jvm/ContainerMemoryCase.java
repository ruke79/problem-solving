package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Q174 — 컨테이너에 JVM 을 넣을 때의 메모리 설정.
 *
 * <p>Java 10+ 의 {@code UseContainerSupport} 는 cgroup 이 정한 컨테이너 메모리 한계를 읽어
 * 힙 상한을 <b>그 비율</b>로 잡는다. 이 값을 모른 채 컨테이너 메모리만 줄이면
 * "왜 힙이 이것밖에 안 잡히나" 또는 반대로 OOMKilled 가 난다.
 */
@Component
public class ContainerMemoryCase extends VerificationCase {

    @Override
    public String id() {
        return "JVM-07";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "컨테이너 환경에서 JVM 메모리 설정 시 주의할 점은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "JVM 은 컨테이너 메모리 한계를 인식해(UseContainerSupport) 힙을 그 비율(MaxRAMPercentage)로 잡는다. 힙 밖의 메타스페이스·스레드 스택·다이렉트 버퍼가 별도로 쓰이므로, 힙 상한을 컨테이너 한계에 가깝게 잡으면 JVM 은 정상인데 컨테이너가 OOMKilled 된다";
    }

    @Override
    protected void verify(Evidence evidence) {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        long maxHeapBytes = Runtime.getRuntime().maxMemory();
        long nonHeapUsed = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        boolean containerSupportOff = jvmArgs.stream().anyMatch(a -> a.contains("-XX:-UseContainerSupport"));
        boolean explicitHeap = jvmArgs.stream().anyMatch(a -> a.startsWith("-Xmx") || a.contains("MaxRAMPercentage"));

        long stackReserveBytes = (long) threads * 1024 * 1024;   // 스레드당 기본 1MB 가정
        long overheadBytes = nonHeapUsed + stackReserveBytes;

        evidence.fact("JVM 인자", jvmArgs.isEmpty() ? "(없음 — 기본값)" : String.join(" ", jvmArgs));
        evidence.fact("힙 상한 Runtime.maxMemory (MB)", maxHeapBytes / 1024 / 1024);
        evidence.fact("힙 밖 사용량 (메타스페이스·코드캐시 등, MB)", nonHeapUsed / 1024 / 1024);
        evidence.fact("스레드 수 / 스택 예약 추정 (MB)", threads + " / " + stackReserveBytes / 1024 / 1024);
        evidence.fact("힙 밖 총 추정 오버헤드 (MB)", overheadBytes / 1024 / 1024);
        evidence.fact("UseContainerSupport 를 끄고 있는가", containerSupportOff);
        evidence.fact("힙 상한을 명시하고 있는가", explicitHeap);

        evidence.expect("힙 밖에서 쓰는 메모리가 0 이 아니다 — 컨테이너 한계는 힙보다 커야 한다", overheadBytes > 0);
        evidence.expect("UseContainerSupport 는 기본으로 켜져 있다(Java 10+)", !containerSupportOff);
        evidence.expect("힙 상한을 실제로 읽을 수 있다", maxHeapBytes > 0);

        evidence.note("컨테이너 메모리 한계 = 힙 + 메타스페이스 + 코드 캐시 + 스레드 스택 + 다이렉트 버퍼 + JVM 자체. -Xmx 를 한계와 같게 잡으면 힙이 다 차기 전에 컨테이너가 먼저 죽는다(OOMKilled, 종료 코드 137).");
        evidence.note("-Xmx 를 고정값으로 박으면 컨테이너 크기를 바꿔도 따라가지 않는다. -XX:MaxRAMPercentage 로 비율 지정하면 같은 이미지가 여러 크기에서 그대로 동작한다.");
        evidence.note("JVM 의 OutOfMemoryError 와 컨테이너의 OOMKilled 는 다른 사건이다. 전자는 힙 덤프가 남지만(JVM-04·Q66), 후자는 프로세스가 즉사해 아무것도 안 남는다 — 로그가 없으면 후자를 의심한다.");
        evidence.note("스레드가 많은 애플리케이션(요청당 스레드 모델)은 스택만으로도 수백 MB 를 쓴다. 스레드 수를 줄이거나 가상 스레드로 가는 것이 메모리 관점에서도 유효하다.");
    }
}
