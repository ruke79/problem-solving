package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레슨 1 — 동일성과 동등성 (면접 Q1 · Q2 · Q8)
 *
 * <p>면접에서 가장 자주 나오는 주제다. 외우는 것보다 <b>한 번 실행해 보는 편이 훨씬 오래 간다.</b>
 */
@DisplayName("레슨 1. == 와 equals, 그리고 hashCode 계약")
class Lesson01_Equality {

    @Test
    @DisplayName("1-1. == 는 참조를, equals 는 값을 비교한다 (Q1)")
    void referenceVsValue() {
        String literal = "hello";
        String another = "hello";
        String created = new String("hello");

        fact("\"hello\" == \"hello\"", literal == another);
        fact("\"hello\" == new String(\"hello\")", literal == created);
        fact("literal.equals(created)", literal.equals(created));

        // 리터럴은 문자열 풀에서 공유되므로 == 도 true 가 된다 — '우연히' 맞는 것이다
        assertThat(literal == another).isTrue();
        // new 는 반드시 새 인스턴스를 만든다
        assertThat(literal == created).isFalse();
        // 값 비교는 언제나 equals
        assertThat(literal).isEqualTo(created);

        lesson("String 을 == 로 비교해서 되는 건 풀 공유 덕분이지, 그렇게 써도 된다는 뜻이 아니다");
    }

    @Test
    @DisplayName("1-2. intern() 으로 풀에 넣으면 == 가 다시 true 가 된다 (Q1)")
    void internPutsItBackInThePool() {
        String created = new String("hello");

        assertThat("hello" == created).isFalse();
        assertThat("hello" == created.intern()).isTrue();   // 풀의 인스턴스를 돌려준다

        lesson("intern() 은 '풀에 있는 같은 값의 인스턴스'를 돌려준다");
    }

    @Test
    @DisplayName("1-3. equals 만 재정의하면 HashMap 이 깨진다 (Q2) ★가장 중요")
    void equalsWithoutHashCodeBreaksHashMap() {
        // hashCode 를 재정의하지 않은 클래스 — equals 는 값으로 비교한다
        var broken = new BrokenPoint(1, 2);
        var sameValue = new BrokenPoint(1, 2);

        fact("equals 상으로 같은가", broken.equals(sameValue));
        fact("hashCode 가 같은가", broken.hashCode() == sameValue.hashCode());

        assertThat(broken).isEqualTo(sameValue);            // equals 는 같다고 한다
        // 그런데 hashCode 가 다르므로 다른 버킷에 들어간다
        Map<BrokenPoint, String> map = new HashMap<>();
        map.put(broken, "값");
        fact("put 한 뒤 get 으로 꺼낼 수 있는가", map.get(sameValue));

        assertThat(map.get(sameValue)).isNull();            // ← 넣었는데 못 꺼낸다
        assertThat(map.get(broken)).isEqualTo("값");        // 같은 인스턴스로만 꺼내진다

        lesson("equals 상 같은데 hashCode 가 다르면, put 한 값을 get 으로 못 꺼낸다");
    }

    @Test
    @DisplayName("1-4. hashCode 까지 재정의하면 정상 동작한다 (Q2)")
    void withHashCodeItWorks() {
        var point = new GoodPoint(1, 2);
        var sameValue = new GoodPoint(1, 2);

        Map<GoodPoint, String> map = new HashMap<>();
        map.put(point, "값");

        assertThat(map.get(sameValue)).isEqualTo("값");

        // Set 도 같은 원리다 — 중복 판정이 hashCode → equals 2단계로 이뤄진다
        Set<GoodPoint> set = new HashSet<>();
        set.add(point);
        set.add(sameValue);
        fact("같은 값 2개를 넣은 Set 의 크기", set.size());
        assertThat(set).hasSize(1);

        lesson("Set 의 중복 판정도 hashCode 로 버킷을 좁히고 equals 로 확인하는 2단계다");
    }

    @Test
    @DisplayName("1-5. record 는 equals·hashCode 를 자동으로 만들어 준다 (Q25)")
    void recordGeneratesBoth() {
        record Point(int x, int y) {}

        Map<Point, String> map = new HashMap<>();
        map.put(new Point(1, 2), "값");

        assertThat(map.get(new Point(1, 2))).isEqualTo("값");

        lesson("값 객체를 만들 때 record 를 쓰면 이 실수 자체가 불가능해진다");
    }

    @Test
    @DisplayName("1-6. Integer 는 -128~127 만 캐시된다 (Q8) ★함정")
    void integerCacheBoundary() {
        // valueOf 는 캐시를 쓴다. 오토박싱도 내부적으로 valueOf 를 부른다.
        Integer a = 127, b = 127;
        Integer c = 128, d = 128;

        fact("Integer 127 == 127", a == b);
        fact("Integer 128 == 128", c == d);
        fact("128 을 equals 로 비교", c.equals(d));

        assertThat(a == b).isTrue();     // 캐시 범위 안 — 같은 인스턴스
        assertThat(c == d).isFalse();    // 캐시 범위 밖 — 다른 인스턴스
        assertThat(c).isEqualTo(d);      // 값 비교는 언제나 맞다

        lesson("127 에서는 동작하고 128 에서 깨진다 — 래퍼 타입은 반드시 equals 로 비교한다");
    }

    @Test
    @DisplayName("1-7. Long·Character 도 같은 캐시를 가진다 (Q8)")
    void otherWrappersCacheToo() {
        Long l1 = 127L, l2 = 127L, l3 = 128L, l4 = 128L;
        assertThat(l1 == l2).isTrue();
        assertThat(l3 == l4).isFalse();

        // Boolean 은 값이 둘뿐이라 항상 캐시된다
        Boolean t1 = true, t2 = true;
        assertThat(t1 == t2).isTrue();

        lesson("Integer 만의 이야기가 아니다. Long·Short·Byte·Character 도 같은 경계를 갖는다");
    }

    // ── 레슨용 클래스 ──────────────────────────────────────────────

    /** equals 만 재정의하고 hashCode 를 빠뜨린 클래스. 계약 위반이다. */
    static final class BrokenPoint {
        private final int x, y;

        BrokenPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BrokenPoint p && p.x == x && p.y == y;
        }
        // hashCode() 를 일부러 재정의하지 않았다 → Object 의 신원 기반 해시를 쓴다
    }

    /** 계약을 지킨 클래스. */
    static final class GoodPoint {
        private final int x, y;

        GoodPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GoodPoint p && p.x == x && p.y == y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }
}
