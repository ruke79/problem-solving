package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Q93 — "불변으로 만든다"와 "방어적 복사를 한다"는 세트다. */
@Component
public class DefensiveCopyCase extends VerificationCase {

    /** record 지만 가변 컬렉션을 그대로 들고 있어 불변이 아니다. */
    public record LeakyOrder(String id, List<String> items) {
    }

    /** 컴팩트 생성자에서 불변 복사본을 만든다. */
    public record SafeOrder(String id, List<String> items) {
        public SafeOrder {
            items = List.copyOf(items);
        }
    }

    @Override
    public String id() {
        return "JVM-05";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "record 를 쓰면 자동으로 불변 객체가 됩니까?";
    }

    @Override
    public String claim() {
        return "record 는 필드 재할당만 막을 뿐이다. 가변 컬렉션을 그대로 담으면 외부 참조로 내용이 바뀌어 불변성이 깨진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        List<String> source = new ArrayList<>(List.of("book", "pen"));

        LeakyOrder leaky = new LeakyOrder("order-1", source);
        SafeOrder safe = new SafeOrder("order-1", source);

        int leakyBefore = leaky.items().size();
        int safeBefore = safe.items().size();

        source.add("injected-by-caller");   // 생성 이후 호출자가 원본을 수정

        String mutationOutcome;
        try {
            safe.items().add("hacked");
            mutationOutcome = "수정 성공(불변 아님)";
        } catch (UnsupportedOperationException e) {
            mutationOutcome = "UnsupportedOperationException";
        }

        evidence.fact("생성 시점 항목 수 (leaky / safe)", leakyBefore + " / " + safeBefore);
        evidence.fact("호출자가 원본 리스트 수정 후 leaky 항목 수", leaky.items().size());
        evidence.fact("호출자가 원본 리스트 수정 후 safe 항목 수", safe.items().size());
        evidence.fact("safe.items() 직접 수정 시도", mutationOutcome);

        evidence.expectEquals("방어적 복사 없는 record 는 외부에서 내용이 바뀐다", 3, leaky.items().size());
        evidence.expectEquals("List.copyOf 로 복사하면 영향받지 않는다", 2, safe.items().size());
        evidence.expectEquals("복사본은 수정 자체가 불가능하다", "UnsupportedOperationException", mutationOutcome);

        evidence.note("getter 로 내부 컬렉션을 그대로 반환하는 것도 같은 구멍이다 — 반환 시에도 불변 뷰를 준다.");
        evidence.note("'값'을 나타내는 객체는 불변으로, 상태를 가진 '엔티티'는 가변으로 다루는 구분이 실무 기준이다.");
    }
}
