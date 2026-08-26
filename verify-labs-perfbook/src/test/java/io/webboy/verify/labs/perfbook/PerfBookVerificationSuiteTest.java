package io.webboy.verify.labs.perfbook;

import io.webboy.verify.core.CaseFilter;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <i>Java Performance: The Definitive Guide</i> 의 명제를 케이스마다 독립된 테스트로 검증한다.
 *
 * <p>PERF-11* 만 PostgreSQL 이 필요하고, DB 가 없으면 그 케이스들이 스스로 INCONCLUSIVE 를
 * 남긴다 — 나머지 장 케이스는 인프라 없이 돈다. 그래서 kafka 모듈과 달리
 * 스위트 수준의 가용성 게이트는 두지 않는다.
 *
 * <p>장 단위로 골라 돌리기: {@code -Dverify.only=PERF-12} (12장 셋),
 * {@code -Dverify.only=PERF-09A} (하나), {@code -Dverify.only=perfbook} (전부).
 *
 * <p>결과물: {@code verify-labs-perfbook/build/reports/verification-perfbook.md}
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerfBookVerificationSuiteTest {

    private static final Path FULL_REPORT = Path.of("build/reports/verification-perfbook.md");
    /** 일부만 골라 돌렸을 때는 전체 리포트를 덮어쓰지 않고 이쪽에 남긴다. */
    private static final Path SELECTED_REPORT = Path.of("build/reports/verification-perfbook-selected.md");

    @Autowired
    private VerificationRegistry registry;

    private final List<VerificationResult> results = new ArrayList<>();

    @TestFactory
    @DisplayName("Java Performance 책의 명제가 이 환경에서 재현되는가")
    Stream<DynamicTest> 케이스별_검증() {
        List<VerificationCase> selected = select();
        return selected.stream().map(c -> DynamicTest.dynamicTest(displayName(c), () -> verify(c)));
    }

    /** 필터 오타 시 쓸 수 있는 id 목록을 콘솔에 바로 보여 준다 (kafka 스위트와 같은 이유). */
    private List<VerificationCase> select() {
        try {
            return CaseFilter.select(registry);
        } catch (IllegalArgumentException e) {
            System.out.println("\n[verify.only] " + e.getMessage() + "\n");
            throw e;
        }
    }

    private void verify(VerificationCase testCase) {
        VerificationResult result = testCase.execute();
        results.add(result);
        System.out.println(VerificationReport.describe(result));

        assertThat(result.acceptable())
                .as(() -> describeFailure(result))
                .isTrue();
    }

    @AfterAll
    void 리포트를_남긴다() throws Exception {
        if (results.isEmpty()) {
            return;
        }
        results.sort(java.util.Comparator.comparing(VerificationResult::id));
        boolean filtered = System.getProperty(CaseFilter.PROPERTY) != null;
        Path report = VerificationReport.write(results, filtered ? SELECTED_REPORT : FULL_REPORT);
        System.out.println(VerificationReport.toConsole(results));
        System.out.println("리포트 저장 위치: " + report.toAbsolutePath());
    }

    private static String displayName(VerificationCase testCase) {
        return "[" + testCase.id() + "] " + testCase.category() + " — " + testCase.question();
    }

    private static String describeFailure(VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.id()).append(" 판정이 ").append(result.verdict()).append(" 다.\n")
          .append("  주장: ").append(result.claim());
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
