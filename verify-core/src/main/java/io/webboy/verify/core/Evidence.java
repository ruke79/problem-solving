package io.webboy.verify.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 검증 케이스가 실행 중에 수집한 "증거" 묶음.
 *
 * <p>사용 규칙:
 * <ul>
 *   <li>{@code fact(...)}        — 판정에 영향을 주지 않는 관측값. 문서에 그대로 실린다.</li>
 *   <li>{@code expect(...)}      — 반드시 성립해야 하는 명제. 하나라도 깨지면 REFUTED.</li>
 *   <li>{@code expectFlaky(...)} — 타이밍/JIT/GC 의존 명제. 깨져도 REFUTED 가 아니라 INCONCLUSIVE.</li>
 * </ul>
 */
public final class Evidence {

    public record Expectation(String description, boolean satisfied, boolean flaky) {}

    private final Map<String, String> facts = new LinkedHashMap<>();
    private final List<Expectation> expectations = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public Evidence fact(String key, Object value) {
        facts.put(key, String.valueOf(value));
        return this;
    }

    public Evidence note(String note) {
        notes.add(note);
        return this;
    }

    public Evidence expect(String description, boolean satisfied) {
        expectations.add(new Expectation(description, satisfied, false));
        return this;
    }

    public Evidence expectFlaky(String description, boolean satisfied) {
        expectations.add(new Expectation(description, satisfied, true));
        return this;
    }

    public Evidence expectEquals(String description, Object expected, Object actual) {
        return expect(description + " [기대=" + expected + " / 실제=" + actual + "]",
                Objects.equals(String.valueOf(expected), String.valueOf(actual)));
    }

    public Evidence expectEqualsFlaky(String description, Object expected, Object actual) {
        return expectFlaky(description + " [기대=" + expected + " / 실제=" + actual + "]",
                Objects.equals(String.valueOf(expected), String.valueOf(actual)));
    }

    Map<String, String> facts() {
        return Collections.unmodifiableMap(facts);
    }

    List<Expectation> expectations() {
        return Collections.unmodifiableList(expectations);
    }

    List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    Verdict verdict() {
        if (expectations.isEmpty()) {
            return Verdict.INCONCLUSIVE;
        }
        boolean flakyMiss = false;
        for (Expectation e : expectations) {
            if (!e.satisfied()) {
                if (e.flaky()) {
                    flakyMiss = true;
                } else {
                    return Verdict.REFUTED;
                }
            }
        }
        return flakyMiss ? Verdict.INCONCLUSIVE : Verdict.CONFIRMED;
    }
}
