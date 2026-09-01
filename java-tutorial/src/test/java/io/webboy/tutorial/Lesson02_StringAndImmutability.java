package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static io.webboy.tutorial.Lesson.observe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 2 — 문자열과 불변 (면접 Q3 · Q4 · Q5 · Q9 · Q16 · Q47)
 *
 * <p>"불변"이라는 한 단어가 실제로는 여러 층위를 뜻한다는 것을 확인한다.
 * 특히 <b>{@code final} 은 참조의 고정이지 내용의 불변이 아니다</b>(2-4)가 핵심이다.
 */
@DisplayName("레슨 2. 문자열과 불변")
class Lesson02_StringAndImmutability {

    @Test
    @DisplayName("2-1. String 은 바꾸면 새 인스턴스가 생긴다 (Q3)")
    void stringIsImmutable() {
        String original = "hello";
        String upper = original.toUpperCase();

        fact("원본", original);
        fact("toUpperCase 결과", upper);

        assertThat(original).isEqualTo("hello");   // 원본은 그대로다
        assertThat(upper).isEqualTo("HELLO");
        assertThat(original).isNotSameAs(upper);   // 새 인스턴스다

        lesson("String 의 모든 '변경' 메서드는 새 인스턴스를 돌려준다. 원본은 절대 안 바뀐다");
    }

    @Test
    @DisplayName("2-2. 불변이라 해시코드를 캐시할 수 있다 (Q3)")
    void immutableMeansCacheableHash() {
        String s = "some fairly long string for hashing";
        int first = s.hashCode();
        int second = s.hashCode();

        assertThat(first).isEqualTo(second);

        // 값이 안 변하니 한 번 계산한 해시를 계속 쓸 수 있다 →
        // HashMap 의 키로 String 이 빠른 이유 중 하나다
        lesson("불변이니까 해시가 안 변하고, 그래서 캐시할 수 있고, 그래서 Map 키로 빠르다");
    }

    @Test
    @DisplayName("2-3. 한 줄의 + 는 컴파일러가 이미 최적화한다 (Q5) ★함정")
    void singleLineConcatIsAlreadyOptimized() {
        String a = "a", b = "b", c = "c";

        String joined = a + b + c;   // 컴파일 후에는 StringBuilder(또는 invokedynamic) 로 바뀐다
        assertThat(joined).isEqualTo("abc");

        // 손으로 바꿔 쓴 것과 결과가 같다 — 즉 바꿔 쓸 이유가 없다
        String manual = new StringBuilder().append(a).append(b).append(c).toString();
        assertThat(joined).isEqualTo(manual);

        lesson("한 문장 안의 + 를 StringBuilder 로 바꾸는 건 이득 없이 읽기만 나빠진다");
    }

    @Test
    @DisplayName("2-4. 문제는 루프를 걸칠 때다 (Q4·Q5)")
    void theProblemIsAcrossLoopIterations() {
        int n = 2_000;

        // 나쁜 예 — 반복마다 새 String 이 생긴다
        String bad = "";
        for (int i = 0; i < n; i++) {
            bad += "x";
        }

        // 좋은 예 — 버퍼 하나를 재사용한다
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("x");
        }
        String good = sb.toString();

        assertThat(bad).isEqualTo(good);       // 결과는 같다
        assertThat(bad).hasSize(n);

        observe("생성된 중간 String 개수(개념상)", n + " vs 1");
        lesson("결과가 같아도 만들어진 쓰레기의 양이 다르다. 루프 밖에서 하나 만드는 게 정답");
    }

    @Test
    @DisplayName("2-5. final 은 참조의 고정이지 내용의 불변이 아니다 (Q9) ★함정")
    void finalFixesTheReferenceNotTheContents() {
        final List<String> list = new ArrayList<>();

        list.add("추가는 된다");        // ← final 인데도 내용은 바뀐다
        assertThat(list).hasSize(1);

        // 바꿀 수 없는 건 '다른 리스트를 가리키게 하는 것'뿐이다
        // list = new ArrayList<>();   ← 이건 컴파일 에러

        fact("final List 에 add 가 되는가", true);
        lesson("final 은 '이 변수가 다른 것을 가리키지 못한다'일 뿐이다");
    }

    @Test
    @DisplayName("2-6. 진짜 불변으로 만들려면 방어적 복사가 필요하다 (Q16) ★놓치기 쉬움")
    void defensiveCopyIsTheEasyMiss() {
        List<String> source = new ArrayList<>(List.of("a", "b"));

        var leaky = new LeakyHolder(source);
        var safe = new SafeHolder(source);

        // 밖에서 원본을 건드려 본다
        source.add("c");

        fact("방어적 복사 없는 홀더의 크기", leaky.items().size());
        fact("방어적 복사한 홀더의 크기", safe.items().size());

        assertThat(leaky.items()).hasSize(3);   // ← 밖에서 바꾼 게 그대로 들어왔다
        assertThat(safe.items()).hasSize(2);    // 생성 시점의 값을 지켰다

        // 반환할 때도 복사해야 한다 — 안 그러면 꺼내서 바꿀 수 있다
        assertThatThrownBy(() -> safe.items().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);

        lesson("불변 클래스는 '받을 때'와 '돌려줄 때' 양쪽에서 막아야 완성된다");
    }

    @Test
    @DisplayName("2-7. List.of 와 unmodifiableList 는 다르다 (Q47) ★함정")
    void listOfVsUnmodifiableList() {
        List<String> source = new ArrayList<>(List.of("a"));

        List<String> view = Collections.unmodifiableList(source);   // 원본을 감싼 '뷰'
        List<String> copy = List.copyOf(source);                    // 복사한 불변

        source.add("b");   // 원본을 바꾼다

        fact("unmodifiableList 뷰의 크기", view.size());
        fact("List.copyOf 의 크기", copy.size());

        assertThat(view).hasSize(2);   // ← 뷰라서 같이 바뀐다
        assertThat(copy).hasSize(1);   // 복사본은 안 바뀐다

        // 둘 다 '직접 수정'은 막는다
        assertThatThrownBy(() -> view.add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copy.add("x")).isInstanceOf(UnsupportedOperationException.class);

        lesson("unmodifiableList 는 '내가 못 바꾼다'일 뿐, '아무도 못 바꾼다'가 아니다");
    }

    // ── 레슨용 클래스 ──────────────────────────────────────────────

    /** 받은 리스트를 그대로 들고 있는다 — 밖에서 바꿀 수 있다. */
    record LeakyHolder(List<String> items) {}

    /** 받을 때와 돌려줄 때 모두 막는다. */
    static final class SafeHolder {
        private final List<String> items;

        SafeHolder(List<String> items) {
            this.items = List.copyOf(items);   // 받을 때 복사
        }

        List<String> items() {
            return items;                       // 이미 불변이라 그대로 돌려줘도 안전하다
        }
    }
}
