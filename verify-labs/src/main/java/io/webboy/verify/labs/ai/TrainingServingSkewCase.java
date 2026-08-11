package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Q76 — Feature Store 가 막으려는 Training-Serving Skew 를 재현한다. */
@Component
public class TrainingServingSkewCase extends VerificationCase {

    private record Purchase(LocalDate date, Integer amount) {
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final List<Purchase> HISTORY = List.of(
            new Purchase(TODAY.minusDays(0), 1000),
            new Purchase(TODAY.minusDays(3), 2000),
            new Purchase(TODAY.minusDays(6), null),      // 결측치
            new Purchase(TODAY.minusDays(7), 4000),      // 경계일
            new Purchase(TODAY.minusDays(9), 9000));

    @Override
    public String id() {
        return "AI-01";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "Feature Store 의 역할은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "같은 '최근 7일 평균 구매액' 정의라도 날짜 경계와 결측치 처리가 미묘하게 어긋나면 학습과 추론의 값이 달라진다 — 정의를 한 곳에 두는 것이 해법이다";
    }

    @Override
    protected void verify(Evidence evidence) {
        double offline = offlineBatchFeature();
        double online = onlineServingFeature();
        double sharedForTraining = sharedDefinition(true);
        double sharedForServing = sharedDefinition(false);

        double skew = Math.abs(offline - online);

        evidence.fact("오프라인(배치) 구현: 경계일 포함 + 결측치 0 취급", String.format("%.1f", offline));
        evidence.fact("온라인(추론) 구현: 경계일 제외 + 결측치 스킵", String.format("%.1f", online));
        evidence.fact("두 구현의 차이(skew)", String.format("%.1f", skew));
        evidence.fact("공통 정의 - 학습 경로", String.format("%.1f", sharedForTraining));
        evidence.fact("공통 정의 - 추론 경로", String.format("%.1f", sharedForServing));

        evidence.expect("따로 구현하면 같은 정의인데도 값이 달라진다", skew > 0.5);
        evidence.expect("공통 정의를 쓰면 두 경로의 값이 정확히 일치한다",
                Double.compare(sharedForTraining, sharedForServing) == 0);

        evidence.note("이 어긋남은 예외를 던지지 않아 알아채기 어렵고, '프로덕션 정확도가 학습 시 평가보다 나쁘다'는 증상으로만 드러난다.");
        evidence.note("도입 판단은 모델 수와 팀 규모로 한다 — 모델 하나를 한 팀이 돌리는 단계에서는 Feature Store 운영 비용이 더 크다.");
        evidence.note("실시간 추론에서는 온라인 스토어 조회 자체가 레이턴시의 큰 부분을 차지한다는 점도 함께 본다(Q80).");
    }

    /** 데이터 사이언티스트가 Pandas 로 만든 버전. */
    private double offlineBatchFeature() {
        return HISTORY.stream()
                .filter(p -> !p.date().isBefore(TODAY.minusDays(7)))    // 7일 전 포함
                .mapToInt(p -> Optional.ofNullable(p.amount()).orElse(0))  // 결측치 = 0
                .average().orElse(0);
    }

    /** 백엔드 엔지니어가 Java 로 만든 버전. */
    private double onlineServingFeature() {
        return HISTORY.stream()
                .filter(p -> p.date().isAfter(TODAY.minusDays(7)))      // 7일 전 제외
                .filter(p -> p.amount() != null)                        // 결측치 스킵
                .mapToInt(Purchase::amount)
                .average().orElse(0);
    }

    /** Feature Store 에 한 번만 정의된 계산식. 학습·추론 어느 쪽에서 불러도 같다. */
    private double sharedDefinition(boolean forTraining) {
        return HISTORY.stream()
                .filter(p -> !p.date().isBefore(TODAY.minusDays(7)))
                .mapToInt(p -> Optional.ofNullable(p.amount()).orElse(0))
                .average().orElse(0);
    }
}
