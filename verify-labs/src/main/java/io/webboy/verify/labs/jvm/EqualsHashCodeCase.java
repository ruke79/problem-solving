package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Component
public class EqualsHashCodeCase extends VerificationCase {

    /** equals 는 재정의했지만 hashCode 는 하지 않은 잘못된 클래스. */
    static final class EqualsOnly {
        private final String value;

        EqualsOnly(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EqualsOnly other && Objects.equals(value, other.value);
        }
    }

    /** 해시 계산에 쓰이는 필드가 가변인 클래스. */
    static final class MutableKey {
        private int value;

        MutableKey(int value) {
            this.value = value;
        }

        void setValue(int value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MutableKey other && value == other.value;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }
    }

    @Override
    public String id() {
        return "JVM-03";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "equals 만 재정의하고 hashCode 를 재정의하지 않으면 어떤 문제가 생깁니까?";
    }

    @Override
    public String claim() {
        return "해시 기반 컬렉션이 버킷을 잘못 찾아 중복 저장/조회 실패가 발생한다. 키 필드가 가변이어도 같은 문제가 생긴다";
    }

    @Override
    protected void verify(Evidence evidence) {
        Set<EqualsOnly> brokenSet = new HashSet<>();
        brokenSet.add(new EqualsOnly("same"));
        brokenSet.add(new EqualsOnly("same"));
        boolean brokenContains = brokenSet.contains(new EqualsOnly("same"));

        MutableKey key = new MutableKey(1);
        Set<MutableKey> mutableSet = new HashSet<>();
        mutableSet.add(key);
        boolean foundBeforeMutation = mutableSet.contains(key);
        key.setValue(2);
        boolean foundAfterMutation = mutableSet.contains(key);
        boolean stillIterable = mutableSet.iterator().hasNext();

        evidence.fact("equals 만 재정의 — 같은 값 2개 넣은 뒤 size", brokenSet.size());
        evidence.fact("equals 만 재정의 — contains 결과", brokenContains);
        evidence.fact("가변 키 — 변경 전 contains", foundBeforeMutation);
        evidence.fact("가변 키 — 변경 후 contains", foundAfterMutation);
        evidence.fact("가변 키 — 원소는 여전히 남아 있는가", stillIterable);

        evidence.expectEquals("hashCode 미재정의 시 논리적으로 같은 객체가 중복 저장된다", 2, brokenSet.size());
        evidence.expect("hashCode 미재정의 시 contains 가 실패한다", !brokenContains);
        evidence.expect("삽입 직후에는 조회된다", foundBeforeMutation);
        evidence.expect("키 필드를 바꾸면 자기 자신도 못 찾는다", !foundAfterMutation);
        evidence.expect("원소는 사라지지 않고 유령처럼 남는다", stillIterable);

        evidence.note("JPA 엔티티에서 흔한 사고다 — 생성 시 null 이던 @Id 를 hashCode 에 쓰면 영속화 후 Set 에서 사라진다.");
        evidence.note("해시 컬렉션의 키는 불변으로 만드는 것이 원칙이다.");
    }
}
