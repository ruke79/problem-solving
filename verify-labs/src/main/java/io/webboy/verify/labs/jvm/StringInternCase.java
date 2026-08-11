package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

@Component
public class StringInternCase extends VerificationCase {

    @Override
    public String id() {
        return "JVM-02";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "String 리터럴과 new String() 의 차이, 그리고 intern() 은 무엇을 합니까?";
    }

    @Override
    public String claim() {
        return "리터럴은 String Pool 에서 공유되고 런타임 생성 문자열은 별도 인스턴스다. intern() 은 풀의 참조를 돌려준다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String literal = "interview";
        String compileTimeConcat = "inter" + "view";
        String runtimeConcat = new StringBuilder("inter").append("view").toString();
        String explicitNew = new String("interview");

        evidence.fact("리터럴 == 컴파일타임 결합", literal == compileTimeConcat);
        evidence.fact("리터럴 == 런타임 결합", literal == runtimeConcat);
        evidence.fact("리터럴 == new String", literal == explicitNew);
        evidence.fact("리터럴 == 런타임결합.intern()", literal == runtimeConcat.intern());
        evidence.fact("equals 비교는 모두", literal.equals(runtimeConcat) && literal.equals(explicitNew));

        evidence.expect("컴파일 타임 상수 결합은 리터럴로 접힌다", literal == compileTimeConcat);
        evidence.expect("런타임 결합은 새 인스턴스다", literal != runtimeConcat);
        evidence.expect("new String 은 항상 새 인스턴스다", literal != explicitNew);
        evidence.expect("intern() 은 풀의 참조를 돌려준다", literal == runtimeConcat.intern());
        evidence.expect("equals 는 내용 비교라 모두 true", literal.equals(runtimeConcat) && literal.equals(explicitNew));

        evidence.note("Java 7 부터 String Pool 은 PermGen 이 아니라 힙에 있다 — intern() 남용의 OOM 양상이 달라졌다.");
        evidence.note("사용자 입력처럼 카디널리티가 높은 문자열에 intern() 을 쓰면 풀이 커지고 GC 대상이 되기 어려워진다.");
    }
}
