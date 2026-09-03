package io.webboy.verify.labs.perfbook.ch10;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.ChildJvm;
import io.webboy.verify.labs.perfbook.probe.DeoptProbe;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <i>Optimizing Java</i> 1판(2018) 10장 — "C2 의 추측 최적화(단형 디스패치)는 가드로 보호되고, 가드가 깨지면 즉시
 * 역최적화한다." 00-검토 §7 의 제안("역최적화 관측 — 컴파일 로그로 결정적 판정 가능")을 실행으로 옮긴 것이다.
 *
 * <p>{@code -Xbatch -XX:+PrintCompilation} 으로 프로브를 띄운다. 단형으로 컴파일된 {@code total()} 이 새 타입을 만나면
 * 컴파일 로그에 {@code made not entrant} 가 찍히고, 이어서 다시 컴파일된다.
 */
@Component
public class DeoptimizationCase extends VerificationCase {

    @Override
    public String id() {
        return "PERF-10D";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "Optimizing Java 10장 — 단형 호출 지점에 새 타입이 나타나면 JIT 는 무엇을 하는가?";
    }

    @Override
    public String claim() {
        return "C2 는 '이 호출 지점의 수신 타입은 하나' 라고 추측해 인라인하고 klass 비교 가드를 둔다. 두 번째 구현이 나타나면 "
                + "가드가 깨져 컴파일된 코드가 'made not entrant' 로 폐기되고(역최적화) 프로파일을 다시 모아 재컴파일한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // JIT 결정에 달려 있다 — 컴파일 임계값·큐 상태에 따라 로그 모양이 조금씩 다르다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        ChildJvm.Result result = ChildJvm.run(List.of("-Xbatch", "-XX:+PrintCompilation"), DeoptProbe.class);
        evidence.expect("프로브가 두 단계를 모두 마쳤다",
                result.exitCode() == 0 && result.mentions("PHASE=monomorphic-warm") && result.mentions("PHASE=after-new-type"));

        List<String> lines = result.stdout().lines().toList();
        int warmMarker = indexOf(lines, "PHASE=monomorphic-warm");
        int doneMarker = indexOf(lines, "PHASE=after-new-type");
        List<String> totalLines = lines.stream().filter(l -> l.contains("DeoptProbe::total")).toList();
        long compiledBefore = lines.subList(0, Math.max(0, warmMarker)).stream()
                .filter(l -> l.contains("DeoptProbe::total") && !l.contains("made not entrant")).count();
        List<String> notEntrantAfter = lines.subList(Math.max(0, warmMarker), Math.max(warmMarker, doneMarker)).stream()
                .filter(l -> l.contains("DeoptProbe::total") && l.contains("made not entrant")).toList();
        long recompiledAfter = lines.subList(Math.max(0, warmMarker), Math.max(warmMarker, doneMarker)).stream()
                .filter(l -> l.contains("DeoptProbe::total") && !l.contains("made not entrant")).count();

        evidence.fact("total() 관련 컴파일 로그 줄 수", totalLines.size());
        evidence.fact("새 타입 이전에 total() 이 컴파일된 횟수(계층 포함)", compiledBefore);
        evidence.fact("새 타입 이후 'made not entrant' 줄", notEntrantAfter.isEmpty() ? "(없음)" : notEntrantAfter.get(0).trim());
        evidence.fact("새 타입 이후 total() 재컴파일 횟수", recompiledAfter);
        evidence.expect("워밍업 중 total() 이 컴파일됐다", compiledBefore >= 1);
        evidence.expectFlaky("새 타입이 나타난 뒤 total() 의 컴파일 코드가 폐기됐다 (made not entrant)", !notEntrantAfter.isEmpty());
        evidence.expectFlaky("폐기된 뒤 다시 컴파일됐다", recompiledAfter >= 1);
        evidence.note("PrintCompilation 의 열은 시각(ms)·순번·(플래그)·레벨·메서드(바이트 수) 이고 'made not entrant' 는 그 컴파일 "
                + "단위가 더 이상 새 호출을 받지 않는다는 뜻이다(1판 9장). 2판 6장은 이 로그 형식을 유지하지만 단형 디스패치·가드 설명은 "
                + "뺐다 — 그래서 이 케이스의 배경은 1판 10장이다. -Xbatch 는 컴파일을 동기화해 로그 순서를 읽기 쉽게 하려는 것이지 운영 플래그가 아니다.");
    }

    private static int indexOf(List<String> lines, String marker) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i;
            }
        }
        return -1;
    }
}
