package io.webboy.verify.labs;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import io.webboy.verify.core.VerificationRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등록된 모든 검증 케이스를 한 번에 실행하고 마크다운 리포트를 남긴다.
 *
 * <p>결과물: {@code verify-labs/build/reports/verification.md}
 */
@SpringBootTest
class VerificationSuiteTest {

    @Autowired
    private VerificationRunner runner;

    @Autowired
    private VerificationRegistry registry;

    @Test
    @DisplayName("면접 답변이 실제 실행 결과와 일치하는가")
    void allInterviewAnswersHoldUp() throws Exception {
        List<VerificationResult> results = runner.runAll();

        Path report = VerificationReport.write(results, Path.of("build/reports/verification.md"));
        System.out.println(VerificationReport.toConsole(results));
        System.out.println("리포트 저장 위치: " + report.toAbsolutePath());

        String failures = results.stream()
                .filter(r -> !r.acceptable())
                .map(VerificationSuiteTest::describeFailure)
                .collect(Collectors.joining("\n"));

        assertThat(registry.size()).isGreaterThanOrEqualTo(45);
        assertThat(failures).as("판정이 CONFIRMED 가 아닌 케이스").isEmpty();
    }

    private static String describeFailure(VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(result.id()).append(" [").append(result.verdict()).append("] ")
          .append(result.claim());
        if (result.error() != null) {
            sb.append("\n    오류: ").append(result.error());
        }
        for (Evidence.Expectation expectation : result.expectations()) {
            if (!expectation.satisfied()) {
                sb.append("\n    실패: ").append(expectation.description());
            }
        }
        return sb.toString();
    }
}
