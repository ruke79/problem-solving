package io.webboy.verify.labs.perfbook.ch08;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.ChildJvm;
import io.webboy.verify.labs.perfbook.probe.GarbageProbe;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <i>Optimizing Java</i> 1판(2018) 8장 「GC 로깅」 — 책의 "필수 GC 플래그" 표는 Java 8 것이라 JDK 17 에서는 셋이 기동을
 * 막는다(00-검토 §2). 이 케이스는 §7 의 제안대로 <b>같은 정보를 통합 로깅(-Xlog)으로 만들어 읽는 법</b>을 실행으로 남긴다.
 *
 * <p>자식 JVM 을 {@code -Xlog:gc -Xmx32m} 으로 띄워 G1 Young 일시정지 줄을 파싱한다 —
 * {@code [0.449s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 18M->4M(32M) 6.684ms}.
 */
@Component
public class UnifiedGcLoggingCase extends VerificationCase {

    // 첫 판은 원인 괄호를 [^\d]*? 로 건너뛰려다 "(G1 Evacuation Pause)" 의 숫자 1 에 걸려 한 줄도 못 읽었다 —
    // 파서를 짤 때는 실제 로그 한 줄을 테스트에 박아 두라는 교훈. 형식: GC(0) Pause Young (Normal) (G1 Evacuation Pause) 18M->4M(32M) 6.684ms
    private static final Pattern PAUSE = Pattern.compile(
            "GC\\((\\d+)\\) Pause (Young|Full).*?(\\d+)M->(\\d+)M\\((\\d+)M\\) ([\\d.]+)ms");

    @Override
    public String id() {
        return "PERF-08A";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "Optimizing Java 8장 — 책의 GC 로그 플래그는 지금 JVM 에서 무엇이 되고, 같은 정보는 어떻게 읽는가?";
    }

    @Override
    public String claim() {
        return "책의 -XX:+PrintGCDetails/-Xloggc 는 경고 후 -Xlog 로 대체되고 -XX:+PrintGCTimeStamps 는 기동을 막는다. "
                + "지금은 -Xlog:gc 한 줄이면 'GC(n) Pause Young … 18M->4M(32M) 6.7ms' 형식으로 전후 힙과 일시정지 시간을 준다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // GC 횟수·시간은 환경 의존 — 형식과 '전 > 후' 관계만 단정한다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // ① 책의 플래그 — 무엇이 되는가
        ChildJvm.Result details = ChildJvm.version("-XX:+PrintGCDetails");
        ChildJvm.Result timeStamps = ChildJvm.version("-XX:+PrintGCTimeStamps");
        ChildJvm.Result tenuring = ChildJvm.version("-XX:+PrintTenuringDistribution");
        evidence.fact("-XX:+PrintGCDetails", firstLine(details));
        evidence.fact("-XX:+PrintGCTimeStamps", firstLine(timeStamps));
        evidence.fact("-XX:+PrintTenuringDistribution", firstLine(tenuring));
        evidence.expect("PrintGCDetails 는 경고 후 -Xlog:gc* 로 대체된다 (기동은 된다)",
                details.exitCode() == 0 && details.mentions("deprecated") && details.mentions("-Xlog:gc*"));
        evidence.expect("PrintGCTimeStamps 는 Unrecognized 로 기동을 막는다",
                timeStamps.exitCode() != 0 && timeStamps.mentions("Unrecognized"));
        evidence.expect("PrintTenuringDistribution 도 기동을 막는다",
                tenuring.exitCode() != 0 && tenuring.mentions("Unrecognized"));

        // ② 통합 로깅으로 같은 정보 읽기
        ChildJvm.Result run = ChildJvm.run(List.of("-Xlog:gc", "-Xmx32m", "-XX:+UseG1GC"), GarbageProbe.class);
        evidence.expect("프로브가 정상 종료했다", run.exitCode() == 0 && run.mentions("ALLOCATED_BYTES="));
        int pauses = 0;
        int shrinking = 0;
        double maxPauseMs = 0;
        String sample = null;
        for (String line : run.stdout().lines().toList()) {
            Matcher m = PAUSE.matcher(line);
            if (m.find()) {
                pauses++;
                if (Integer.parseInt(m.group(3)) > Integer.parseInt(m.group(4))) {
                    shrinking++;
                }
                maxPauseMs = Math.max(maxPauseMs, Double.parseDouble(m.group(6)));
                if (sample == null) {
                    sample = line.trim();
                }
            }
        }
        evidence.fact("첫 GC 줄", sample == null ? "(없음)" : sample);
        evidence.fact("파싱된 일시정지 수 / 힙이 줄어든 수", pauses + " / " + shrinking);
        evidence.fact("가장 긴 일시정지", String.format("%.3f ms", maxPauseMs));
        evidence.expect("-Xlog:gc 줄이 'GC(n) Pause … XM->YM(ZM) Nms' 형식으로 파싱된다", pauses >= 1);
        evidence.expect("모든 일시정지에서 후 힙 < 전 힙 (가비지가 회수됐다)", pauses > 0 && shrinking == pauses);
        evidence.expectFlaky("32MB 힙에 768MB 를 흘리면 Young GC 가 열 번은 넘는다", pauses >= 10);
        evidence.note("책 8장의 본질(로그에서 전후 힙·일시정지·원인을 읽는 법)은 그대로다. 바뀐 것은 만드는 명령이다 — "
                + "운영 권장은 -Xlog:gc*,gc+age=trace:file=gc.log:time,uptime,level,tags:filecount=5,filesize=10M (00-검토 §2). "
                + "책이 기댄 Censum 은 단종됐고, 이 형식은 GCeasy·GCViewer·JFR(jdk.GCPhasePause)로 읽는다.");
    }

    private static String firstLine(ChildJvm.Result result) {
        return result.all().lines().findFirst().orElse("(출력 없음)").trim() + " [rc=" + result.exitCode() + "]";
    }
}
