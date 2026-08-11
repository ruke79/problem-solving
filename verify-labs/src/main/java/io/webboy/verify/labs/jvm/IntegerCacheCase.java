package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

@Component
public class IntegerCacheCase extends VerificationCase {

    @Override
    public String id() {
        return "JVM-01";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "Integer 를 == 로 비교하면 왜 값이 같은데 false 가 나올 수 있습니까?";
    }

    @Override
    public String claim() {
        return "Integer.valueOf 는 -128~127 을 캐시하므로 그 범위는 같은 인스턴스, 범위를 벗어나면 매번 새 객체다";
    }

    @Override
    protected void verify(Evidence evidence) {
        int inRange = 127;
        int outOfRange = 128;

        Integer a = inRange;
        Integer b = inRange;
        Integer c = outOfRange;
        Integer d = outOfRange;

        evidence.fact("127 == 127 (박싱)", a == b);
        evidence.fact("128 == 128 (박싱)", c == d);
        evidence.fact("128.equals(128)", c.equals(d));
        evidence.fact("IntegerCache.high 설정값",
                String.valueOf(System.getProperty("java.lang.Integer.IntegerCache.high")));
        evidence.fact("new Integer 대체 — valueOf(127) 동일성", Integer.valueOf(127) == Integer.valueOf(127));

        evidence.expect("캐시 범위 안에서는 == 가 true", a == b);
        evidence.expect("캐시 범위 밖에서는 == 가 false", c != d);
        evidence.expect("equals 는 값 비교라 항상 true", c.equals(d));

        evidence.note("Long, Short, Byte, Character 도 같은 캐시를 가진다. Double/Float 에는 없다.");
        evidence.note("-XX:AutoBoxCacheMax 로 상한을 늘리면 이 실험의 결과가 바뀐다 — '동작하니까 맞다'가 위험한 이유다.");
        evidence.note("실무 사고 유형: DB 에서 읽은 Long id 를 == 로 비교 — 값이 작을 때만 통과하는 버그가 된다.");
    }
}
