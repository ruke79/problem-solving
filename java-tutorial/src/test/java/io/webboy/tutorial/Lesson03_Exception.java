package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 3 — 예외 (면접 Q18 · Q19 · Q20 · Q115 · Q143)
 *
 * <p>3-2 의 <b>{@code finally} 안의 {@code return} 이 예외를 지운다</b>가 이 레슨의 핵심이다.
 * 실무에서 원인 조사를 가장 어렵게 만드는 패턴이다.
 */
@DisplayName("레슨 3. 예외 — 삼키지 않기")
class Lesson03_Exception {

    @Test
    @DisplayName("3-1. try 의 return 값은 finally 전에 확정된다 (Q20)")
    void returnValueIsFixedBeforeFinally() {
        assertThat(returnThenMutate()).isEqualTo(1);

        lesson("finally 에서 값을 바꿔도, 이미 확정된 반환값은 안 바뀐다 (기본형의 경우)");
    }

    private int returnThenMutate() {
        int value = 1;
        try {
            return value;      // 여기서 1 이 확정된다
        } finally {
            value = 2;         // 확정된 반환값에는 영향이 없다
        }
    }

    @Test
    @DisplayName("3-2. finally 의 return 은 예외를 통째로 삼킨다 (Q20) ★가장 위험")
    void returnInFinallySwallowsTheException() {
        // 예외를 던지는데도 정상 반환된다 — 원인이 완전히 사라진다
        int result = swallowsEverything();
        fact("예외를 던졌는데 돌아온 값", result);
        assertThat(result).isEqualTo(-1);

        // 같은 코드에서 finally 의 return 만 없애면 예외가 제대로 나온다
        assertThatThrownBy(this::propagatesProperly)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("진짜 원인");

        lesson("finally 안에서는 return 도 throw 도 쓰지 않는다 — 원인이 지워진다");
    }

    @SuppressWarnings("finally")
    private int swallowsEverything() {
        try {
            throw new IllegalStateException("진짜 원인");
        } finally {
            return -1;   // ← 예외를 덮어쓴다. 절대 이렇게 쓰지 않는다
        }
    }

    private int propagatesProperly() {
        try {
            throw new IllegalStateException("진짜 원인");
        } finally {
            // 정리만 한다. return 도 throw 도 하지 않는다
        }
    }

    @Test
    @DisplayName("3-3. try-with-resources 는 역순으로 닫는다 (Q19)")
    void resourcesCloseInReverseOrder() {
        List<String> closed = new ArrayList<>();

        try (var first = new Recorder("첫째", closed);
             var second = new Recorder("둘째", closed)) {
            first.use();
            second.use();
        }

        fact("닫힌 순서", closed);
        assertThat(closed).containsExactly("둘째", "첫째");   // 선언의 역순

        lesson("나중에 연 것부터 닫는다 — 의존 관계가 있어도 안전한 순서다");
    }

    @Test
    @DisplayName("3-4. close 에서 난 예외는 원인을 지우지 않는다 (Q19) ★핵심")
    void closeExceptionBecomesSuppressed() {
        assertThatThrownBy(() -> {
            try (var r = new FailingOnClose()) {
                throw new IllegalStateException("본문의 진짜 원인");
            }
        })
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("본문의 진짜 원인")            // ← 원래 예외가 살아 있다
        .satisfies(e -> {
            fact("억제된 예외 수", e.getSuppressed().length);
            fact("억제된 예외", e.getSuppressed()[0].getMessage());
            assertThat(e.getSuppressed()).hasSize(1);
        });

        lesson("close 의 예외는 '억제 예외'로 매달린다. 옛날 finally 방식은 원인을 덮어썼다");
    }

    @Test
    @DisplayName("3-5. 예외를 감쌀 때 cause 를 빠뜨리면 추적이 끊긴다 (Q143)")
    void alwaysPassTheCause() {
        Exception original = new IllegalArgumentException("근본 원인");

        var withoutCause = new RuntimeException("래핑만 함");
        var withCause = new RuntimeException("래핑 + 원인", original);

        fact("cause 를 안 넘긴 예외의 원인", withoutCause.getCause());
        fact("cause 를 넘긴 예외의 원인", withCause.getCause().getMessage());

        assertThat(withoutCause.getCause()).isNull();       // ← 원인이 사라졌다
        assertThat(withCause.getCause()).isSameAs(original);

        lesson("커스텀 예외로 감쌀 때 cause 를 안 넘기면, 스택 트레이스가 거기서 끊긴다");
    }

    @Test
    @DisplayName("3-6. 예외 비용의 실체는 스택 트레이스 수집이다 (Q115)")
    void theCostIsStackTraceCollection() {
        var withStack = new RuntimeException("보통 예외");
        var withoutStack = new StacklessException();

        fact("보통 예외의 스택 프레임 수", withStack.getStackTrace().length);
        fact("스택 수집을 끈 예외의 프레임 수", withoutStack.getStackTrace().length);

        assertThat(withStack.getStackTrace()).isNotEmpty();
        assertThat(withoutStack.getStackTrace()).isEmpty();

        lesson("비싼 건 던지기가 아니라 생성 시의 스택 수집이고, 그 비용은 스택 깊이에 비례한다");
        lesson("다만 대책은 스택을 끄는 게 아니라 예외를 흐름 제어에 쓰지 않는 것이다");
    }

    @Test
    @DisplayName("3-7. 체크 예외와 언체크 예외의 구분 기준 (Q18)")
    void checkedVsUnchecked() {
        // 언체크 — 프로그램의 버그. 호출자가 할 수 있는 게 없다
        assertThatThrownBy(() -> List.of(1, 2).get(5))
                .isInstanceOf(IndexOutOfBoundsException.class);

        // 체크 — 호출자가 대안을 취할 수 있는 상황(파일 없음, 통신 실패 등)
        //       컴파일러가 처리를 강제한다
        assertThatThrownBy(this::mayFail)
                .isInstanceOf(Exception.class)
                .hasMessage("호출자가 대응할 수 있는 상황");

        lesson("기준은 '호출하는 쪽이 회복할 수 있는가' 하나다");
    }

    private void mayFail() throws Exception {
        throw new Exception("호출자가 대응할 수 있는 상황");
    }

    // ── 레슨용 클래스 ──────────────────────────────────────────────

    record Recorder(String name, List<String> log) implements AutoCloseable {
        void use() { /* 아무것도 안 한다 */ }

        @Override
        public void close() {
            log.add(name);
        }
    }

    static final class FailingOnClose implements AutoCloseable {
        @Override
        public void close() {
            throw new IllegalStateException("close 에서 난 예외");
        }
    }

    /** writableStackTrace=false — 생성 시 스택을 걷지 않는다. */
    static final class StacklessException extends RuntimeException {
        StacklessException() {
            super("스택 없음", null, false, false);
        }
    }
}
