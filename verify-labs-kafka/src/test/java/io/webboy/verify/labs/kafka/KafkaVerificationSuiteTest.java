package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import io.webboy.verify.core.VerificationRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실물 Kafka 로 MSA 답변을 검증한다.
 *
 * <p>브로커가 없으면 각 케이스가 스스로 INCONCLUSIVE 를 남기므로 이 테스트는 실패하지 않는다 —
 * "브로커가 없어서 검증 못 했다"와 "답변이 틀렸다"를 구분하는 것이 이 랩의 규칙이다.
 *
 * <p>결과물: {@code verify-labs-kafka/build/reports/verification-kafka.md}
 */
@SpringBootTest
class KafkaVerificationSuiteTest {

    @Autowired
    private VerificationRunner runner;

    @Autowired
    private VerificationRegistry registry;

    @Autowired
    private Brokers brokers;

    @Test
    @DisplayName("Kafka 관련 답변이 실제 브로커에서 재현되는가")
    void kafkaAnswersHoldUp() throws Exception {
        List<VerificationResult> results = runner.runAll();

        // 브로커가 없으면 전건이 INCONCLUSIVE 로 남는다. 그것은 '답변이 틀렸다'가 아니라
        // '검증하지 못했다'이므로 실패가 아니라 건너뛴 것으로 다룬다 — 리포트에는 그대로 남는다.
        boolean brokerUp = brokers.available();
        if (!brokerUp) {
            VerificationReport.write(results, Path.of("build/reports/verification-kafka.md"));
            assertThat(results).allMatch(r -> r.verdict() == io.webboy.verify.core.Verdict.INCONCLUSIVE);
            Assumptions.abort("브로커에 접속할 수 없어 검증을 건너뛴다 — `docker compose up -d kafka` 후 다시 실행한다. "
                    + "(리포트에는 5건 전부 INCONCLUSIVE 로 기록됨)");
        }

        Path report = VerificationReport.write(results, Path.of("build/reports/verification-kafka.md"));
        System.out.println(VerificationReport.toConsole(results));
        System.out.println("리포트 저장 위치: " + report.toAbsolutePath());

        String failures = results.stream()
                .filter(r -> !r.acceptable())
                .map(KafkaVerificationSuiteTest::describeFailure)
                .collect(Collectors.joining("\n"));

        assertThat(registry.size()).isGreaterThanOrEqualTo(5);
        assertThat(registry.categories()).containsExactly("kafka");
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
