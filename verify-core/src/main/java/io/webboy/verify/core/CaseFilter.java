package io.webboy.verify.core;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 실행할 케이스를 골라 준다 — "전부 돌리지 않고 하나만 확인하고 싶다"를 위한 장치.
 *
 * <p>표현식은 쉼표로 구분한 목록이며 각 항목은 셋 중 하나로 해석된다.
 *
 * <ul>
 *   <li>정확한 케이스 id — {@code DB-14}</li>
 *   <li>id 접두사 — {@code SEC} 이면 {@code SEC-01}~{@code SEC-05} 전부</li>
 *   <li>분류 이름 — {@code observability} 면 그 분류 전체</li>
 * </ul>
 *
 * <p>대소문자는 구분하지 않는다. 아무것도 지정하지 않으면 전부 실행한다.
 */
public final class CaseFilter {

    /** 테스트 JVM 에 넘기는 시스템 프로퍼티 이름. */
    public static final String PROPERTY = "verify.only";

    private CaseFilter() {
    }

    /** {@link #PROPERTY} 값을 읽어 케이스를 고른다. */
    public static List<VerificationCase> select(VerificationRegistry registry) {
        return select(registry, System.getProperty(PROPERTY));
    }

    public static List<VerificationCase> select(VerificationRegistry registry, String expression) {
        if (expression == null || expression.isBlank()) {
            return registry.all();
        }
        List<String> tokens = Arrays.stream(expression.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> token.toUpperCase(Locale.ROOT))
                .toList();
        if (tokens.isEmpty()) {
            return registry.all();
        }

        List<VerificationCase> matched = registry.all().stream()
                .filter(candidate -> matches(candidate, tokens))
                .toList();
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("-D" + PROPERTY + "=" + expression
                    + " 에 해당하는 케이스가 없다. 쓸 수 있는 id: "
                    + registry.all().stream().map(VerificationCase::id).toList()
                    + " / 분류: " + registry.categories());
        }
        return matched;
    }

    private static boolean matches(VerificationCase candidate, List<String> tokens) {
        String id = candidate.id().toUpperCase(Locale.ROOT);
        String category = candidate.category().toUpperCase(Locale.ROOT);
        return tokens.stream().anyMatch(token ->
                id.equals(token) || id.startsWith(token) || category.equals(token));
    }
}
