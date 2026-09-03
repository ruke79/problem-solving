package io.webboy.verify.labs.cloudnative.ch15;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.UnsafeProbe;

import java.util.List;

/**
 * 13장·15장 — "Unsafe 를 메서드/Var 핸들과 FFM 으로 대체하는 중이다." JDK 23(JEP 471)이 메모리 접근 메서드를
 * 제거 예정 폐기했고 24(JEP 498)가 호출 시 경고를 기본으로 켰다. 자식 JVM 에서 실제 경고와 차단을 본다.
 */
public class UnsafeDeprecationCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-15A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 13장·15장 — sun.misc.Unsafe 의 메모리 접근은 JDK 25 에서 어떤 상태인가?";
    }

    @Override
    public String claim() {
        return "동작은 하지만 첫 호출에 '터미널 폐기' 경고가 나고, --sun-misc-unsafe-memory-access=deny 를 주면 "
                + "UnsupportedOperationException 으로 막히며, =allow 를 주면 조용하다 — 제거 전 마지막 단계다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Jvm.Result plain = Jvm.run(List.of(), UnsafeProbe.class);
        Jvm.Result deny = Jvm.run(List.of("--sun-misc-unsafe-memory-access=deny"), UnsafeProbe.class);
        Jvm.Result allow = Jvm.run(List.of("--sun-misc-unsafe-memory-access=allow"), UnsafeProbe.class);

        evidence.fact("기본 — stdout", plain.stdout().strip());
        evidence.fact("기본 — stderr 첫 줄", plain.stderr().lines().findFirst().orElse("(없음)"));
        evidence.fact("deny — rc / 예외", deny.exitCode() + " / "
                + deny.stderr().lines().filter(l -> l.contains("Exception")).findFirst().orElse("(없음)"));
        evidence.fact("allow — stderr", allow.stderr().isBlank() ? "(조용)" : allow.stderr().lines().findFirst().orElse(""));

        evidence.expect("기본값에서는 아직 동작한다 (UNSAFE_READ=42)", plain.exitCode() == 0 && plain.mentions("UNSAFE_READ=42"));
        evidence.expect("기본값에서 '터미널 폐기' 경고가 난다",
                plain.mentions("A terminally deprecated method in sun.misc.Unsafe has been called"));
        evidence.expect("deny 면 UnsupportedOperationException 으로 막힌다",
                deny.exitCode() != 0 && deny.mentions("UnsupportedOperationException"));
        evidence.expect("allow 면 경고가 없다", allow.exitCode() == 0 && !allow.mentions("terminally deprecated"));
        evidence.note("JDK 21 에는 --sun-misc-unsafe-memory-access 옵션이 없고(Unrecognized option) 경고도 없다(이 세션에서 확인). "
                + "1판 12장의 Unsafe 절('사실상 표준')과 2판 13장('대체하는 중')을 지나 25 는 '기본 경고' 단계다. "
                + "JEP 498 의 로드맵대로면 다음은 기본 deny, 그다음은 제거다. AtomicInteger 자체는 아직 "
                + "jdk.internal.misc.Unsafe 를 쓴다 — 그것은 내부 API 라 이 폐기와 무관하다(PERF-15A).");
    }
}
