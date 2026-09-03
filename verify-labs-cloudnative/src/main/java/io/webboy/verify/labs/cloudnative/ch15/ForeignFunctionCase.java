package io.webboy.verify.labs.cloudnative.ch15;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.FfmProbe;

import java.util.List;

/**
 * 15장 — 책은 Panama(FFM)를 "미래" 절에 둔다. JDK 22 에서 정식(JEP 454)이 됐고, 24 의 JEP 472 는
 * 네이티브 접근을 "제한된 메서드" 로 묶어 기본 경고를 켰다(JNI 도 같은 규칙). 자식 JVM 으로 확인한다.
 */
public class ForeignFunctionCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-15B";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 15장 — Project Panama 의 FFM API 는 JDK 25 에서 어떤 상태인가?";
    }

    @Override
    public String claim() {
        return "java.lang.foreign 은 정식 API 라 프리뷰 없이 libc strlen 을 부를 수 있지만, --enable-native-access 없이 부르면 "
                + "'restricted method' 경고가 나고(JEP 472), 옵션을 주면 조용하다 — 네이티브 접근이 명시적 허가제로 바뀌는 중이다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Jvm.Result plain = Jvm.run(List.of(), FfmProbe.class);
        Jvm.Result enabled = Jvm.run(List.of("--enable-native-access=ALL-UNNAMED"), FfmProbe.class);

        evidence.fact("기본 — stdout", plain.stdout().strip());
        evidence.fact("기본 — stderr 첫 줄", plain.stderr().lines().findFirst().orElse("(없음)"));
        evidence.fact("--enable-native-access=ALL-UNNAMED — stderr", enabled.stderr().isBlank() ? "(조용)" : enabled.stderr().strip());

        evidence.expect("FFM 다운콜이 프리뷰 없이 동작한다 (STRLEN=5)", plain.exitCode() == 0 && plain.mentions("STRLEN=5"));
        evidence.expect("허가 없이 부르면 '제한된 메서드' 경고가 난다",
                plain.mentions("A restricted method in java.lang.foreign.Linker has been called"));
        evidence.expect("경고는 '앞으로는 차단된다'고 예고한다",
                plain.mentions("Restricted methods will be blocked in a future release"));
        evidence.expect("--enable-native-access 를 주면 경고가 없다",
                enabled.exitCode() == 0 && enabled.mentions("STRLEN=5") && !enabled.mentions("restricted method"));
        evidence.note("JDK 21 에서는 Linker 가 프리뷰 API 라 --enable-preview 없이 컴파일조차 되지 않았다(이 세션에서 확인). "
                + "책 15장의 '언젠가 JNI 를 대체' 는 22 에 실현됐고, 1판 검토 §5 의 Panama 예측 항목도 이 결과로 갱신된다. "
                + "Spring Boot·Netty 같은 프레임워크가 JNI 를 쓰면 같은 경고가 뜨므로 실행 스크립트에 --enable-native-access 를 넣는 일이 늘 것이다.");
    }
}
