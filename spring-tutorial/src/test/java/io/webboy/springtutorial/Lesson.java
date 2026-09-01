package io.webboy.springtutorial;

/**
 * 레슨 공용 도구. {@code java-tutorial} 의 같은 이름 클래스와 내용이 같다 —
 * 모듈이 다르고 의존을 만들 이유가 없어 <b>일부러 복사</b>했다.
 *
 * <p>규칙은 저장소 전체와 같다({@code docs/00} §8) — <b>"확인한 척"을 만들지 않는다.</b>
 *
 * <ul>
 *   <li>주장을 <b>단정할 수 있으면</b> `assertThat` 으로 못 박고,</li>
 *   <li><b>환경·구현 세부에 좌우되면</b> 값을 출력만 하고 단정하지 않는다.</li>
 * </ul>
 *
 * <p>후자를 {@link #observe} 로 표시한다. 테스트가 초록이라고 해서
 * 그 줄까지 증명된 것은 아니라는 뜻이다.
 */
final class Lesson {

    private Lesson() {
    }

    /** 확인된 사실을 콘솔에 남긴다. 판정에는 영향을 주지 않는다. */
    static void fact(String label, Object value) {
        System.out.printf("   · %-46s = %s%n", label, value);
    }

    /**
     * <b>단정하지 않고 관측만 한 값.</b> 스프링 버전·구현 세부에 따라 달라질 수 있어
     * 이 줄은 "확인했다"고 말할 수 없다.
     */
    static void observe(String label, Object value) {
        System.out.printf("   ~ %-46s = %s   (구현 의존 — 단정하지 않음)%n", label, value);
    }

    /** 레슨의 결론 한 줄. */
    static void lesson(String text) {
        System.out.println("   → " + text);
    }
}
