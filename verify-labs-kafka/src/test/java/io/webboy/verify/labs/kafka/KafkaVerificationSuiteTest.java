package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.CaseFilter;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
 * 실물 Kafka 로 MSA 답변을 <b>케이스마다 독립된 테스트</b>로 검증한다.
 *
 * <p>브로커가 없으면 각 케이스가 스스로 INCONCLUSIVE 를 남기고, 테스트는 실패가 아니라
 * <b>건너뜀</b>으로 표시된다 — "브로커가 없어서 검증 못 했다"와 "답변이 틀렸다"를 구분하는 것이
 * 이 랩의 규칙이다. 케이스 단위로 나뉘어 있어 어느 것이 건너뛰어졌는지도 개별로 보인다.
 *
 * <p>하나만 돌리려면 {@code -Dverify.only=KAFKA-05} 처럼 준다.
 *
 * <p>결과물: {@code verify-labs-kafka/build/reports/verification-kafka.md}
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaVerificationSuiteTest {

    private static final Path FULL_REPORT = Path.of("build/reports/verification-kafka.md");
    /** 일부만 골라 돌렸을 때는 전체 리포트를 덮어쓰지 않고 이쪽에 남긴다. */
    private static final Path SELECTED_REPORT = Path.of("build/reports/verification-kafka-selected.md");

    @Autowired
    private VerificationRegistry registry;

    @Autowired
    private Brokers brokers;

    private final List<VerificationResult> results = new ArrayList<>();

    /** 브로커 확인은 접속 시도라 느리다 — 케이스마다 하지 않고 한 번만 한다. */
    private Boolean brokerUp;

    @TestFactory
    @DisplayName("Kafka 관련 답변이 실제 브로커에서 재현되는가")
    Stream<DynamicTest> 케이스별_검증() {
        List<VerificationCase> selected = select();
        return selected.stream().map(c -> DynamicTest.dynamicTest(displayName(c), () -> verify(c)));
    }

    /**
     * 필터가 아무것도 고르지 못하면 예외가 나는데, Gradle 은 예외 <b>메시지</b>를 콘솔에 안 찍는다
     * (클래스 이름과 줄 번호만 나온다). 오타를 냈을 때 쓸 수 있는 id 목록을 바로 보여 주려고 직접 출력한다.
     */
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

        if (!brokerAvailable()) {
            // 리포트에는 INCONCLUSIVE 로 남기고, 테스트는 '건너뜀'으로 둔다.
            Assumptions.abort(testCase.id() + ": 브로커에 접속할 수 없어 검증을 건너뛴다 — "
                    + "`docker compose up -d kafka` 후 다시 실행한다. (리포트에는 INCONCLUSIVE 로 기록됨)");
        }

        assertThat(result.acceptable())
                .as(() -> describeFailure(result))
                .isTrue();
    }

    private boolean brokerAvailable() {
        if (brokerUp == null) {
            brokerUp = brokers.available();
        }
        return brokerUp;
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
