package io.webboy.verify.labs.cloudnative;

import io.webboy.verify.core.CaseFilter;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <i>Optimizing Cloud Native Java</i> 2판(2024)의 명제를 JDK 25 에서 케이스마다 독립된 테스트로 검증한다.
 *
 * <p>Spring 없이 verify-core 의 하네스만 쓴다 — 레지스트리는 {@link CloudNativeCases#all()} 로 손수 만든다.
 * JDK 25 미만이면 스위트 전체를 건너뛴다(이 모듈의 툴체인이 25 이므로 보통은 일어나지 않는다).
 *
 * <p>골라 돌리기: {@code -Dverify.only=CN-13} (13장 셋), {@code -Dverify.only=CN-05A} (하나).
 * 결과물: {@code verify-labs-cloudnative/build/reports/verification-cloudnative.md}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudNativeVerificationSuiteTest {

    private static final Path FULL_REPORT = Path.of("build/reports/verification-cloudnative.md");
    private static final Path SELECTED_REPORT = Path.of("build/reports/verification-cloudnative-selected.md");

    private final List<VerificationResult> results = new ArrayList<>();
    private VerificationRegistry registry;

    @BeforeAll
    void requireJdk25() {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
                "이 모듈은 JDK 25 이상에서만 판정한다 (지금: " + Runtime.version() + ")");
        registry = new VerificationRegistry(CloudNativeCases.all());
    }

    @TestFactory
    @DisplayName("Optimizing Cloud Native Java 2판의 명제가 JDK 25 에서 재현되는가")
    Stream<DynamicTest> 케이스별_검증() {
        List<VerificationCase> selected;
        try {
            selected = CaseFilter.select(registry);
        } catch (IllegalArgumentException e) {
            System.out.println("\n[verify.only] " + e.getMessage() + "\n");
            throw e;
        }
        return selected.stream().map(c -> DynamicTest.dynamicTest(
                "[" + c.id() + "] " + c.category() + " — " + c.question(), () -> verify(c)));
    }

    private void verify(VerificationCase testCase) {
        VerificationResult result = testCase.execute();
        results.add(result);
        System.out.println(VerificationReport.describe(result));
        assertThat(result.acceptable()).as(() -> describeFailure(result)).isTrue();
    }

    @AfterAll
    void 리포트를_남긴다() throws Exception {
        if (results.isEmpty()) {
            return;
        }
        results.sort(Comparator.comparing(VerificationResult::id));
        boolean filtered = System.getProperty(CaseFilter.PROPERTY) != null;
        Path report = VerificationReport.write(results, filtered ? SELECTED_REPORT : FULL_REPORT);
        System.out.println(VerificationReport.toConsole(results));
        System.out.println("리포트 저장 위치: " + report.toAbsolutePath());
    }

    private static String describeFailure(VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.id()).append(" 판정이 ").append(result.verdict()).append(" 다.\n  주장: ").append(result.claim());
        if (result.error() != null) {
            sb.append("\n  오류: ").append(result.error());
        }
        for (Evidence.Expectation expectation : result.expectations()) {
            if (!expectation.satisfied()) {
                sb.append("\n  실패한 항목: ").append(expectation.description());
            }
        }
        return sb.toString();
    }
}
