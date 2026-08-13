package io.webboy.verify.labs;

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
 * 등록된 케이스를 <b>하나씩 독립된 테스트</b>로 실행한다.
 *
 * <p>예전에는 전건을 한 테스트 안에서 돌렸다. 그러면 어느 케이스가 깨졌는지 알려면 실패 메시지를
 * 읽어야 했고, 하나가 깨지면 나머지도 같이 빨갛게 보였다. 지금은 케이스마다 테스트 노드가 생기므로
 * IDE·Gradle 리포트에서 <b>케이스 단위로 통과/실패가 보이고</b> 실패한 것만 다시 돌릴 수 있다.
 *
 * <p>하나만 돌리려면 {@code verify.only} 를 준다.
 *
 * <pre>
 * ./gradlew :verify-labs:test -Dverify.only=DB-14          # 케이스 하나
 * ./gradlew :verify-labs:test -Dverify.only=SEC            # id 접두사 → SEC-01~05
 * ./gradlew :verify-labs:test -Dverify.only=observability  # 분류 전체
 * ./gradlew :verify-labs:test -Dverify.only=DB-14,SEC-03   # 여러 개
 * </pre>
 *
 * <p>결과물: 전건을 돌리면 {@code build/reports/verification.md},
 * {@code verify.only} 로 골라 돌리면 {@code build/reports/verification-selected.md} 에 남는다 —
 * 하나만 확인하려다 전체 리포트를 덮어쓰지 않게 분리했다.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerificationSuiteTest {

    private static final Path FULL_REPORT = Path.of("build/reports/verification.md");
    /** 일부만 골라 돌렸을 때는 전체 리포트를 덮어쓰지 않고 이쪽에 남긴다. */
    private static final Path SELECTED_REPORT = Path.of("build/reports/verification-selected.md");

    @Autowired
    private VerificationRegistry registry;

    /** 케이스별 테스트가 채우고, 전부 끝난 뒤 리포트로 내보낸다. */
    private final List<VerificationResult> results = new ArrayList<>();

    @TestFactory
    @DisplayName("면접 답변이 실제 실행 결과와 일치하는가")
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

        // 판정 근거를 콘솔에 그대로 펼친다 — 리포트 파일을 열지 않고도 확인할 수 있게.
        // (기본 실행에서는 Gradle 이 표준 출력을 감추므로, 보려면 -Dverify.only 를 주거나
        //  build.gradle 의 showStandardStreams 를 켠다.)
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
        results.sort(java.util.Comparator.comparing(VerificationResult::category)
                .thenComparing(VerificationResult::id));
        boolean filtered = System.getProperty(CaseFilter.PROPERTY) != null;
        Path report = VerificationReport.write(results, filtered ? SELECTED_REPORT : FULL_REPORT);
        System.out.println(VerificationReport.toConsole(results));
        System.out.println("리포트 저장 위치: " + report.toAbsolutePath());
    }

    /** {@code [DB-14] db — 복합 인덱스 (A, B) 를 만들었는데 …} 형태로 목록에서 바로 읽히게 한다. */
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
