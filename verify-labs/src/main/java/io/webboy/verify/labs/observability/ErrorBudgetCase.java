package io.webboy.verify.labs.observability;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Q87 · Q142 — SLO 와 에러 버짓을 실제로 계산한다.
 *
 * <p>이 문항 쌍은 원고 안에서 <b>서로 어긋나 있었다.</b> Q87 은 에러 버짓의 기준을 SLA 로 잡고
 * Q142 는 SLO 로 잡는데, 계산해 보면 어느 쪽이 맞는지가 분명해진다 — 그것을 숫자로 남긴다
 * ({@code docs/04} 지적 ⑥).
 *
 * <p>가장 실무적인 부분은 마지막이다. <b>직렬로 의존하면 가용성은 곱해진다.</b>
 * 각 99.9% 짜리 서비스 다섯 개에 순서대로 의존하면 전체는 99.5% 로 떨어지고,
 * 허용 다운타임은 43분에서 3시간을 넘긴다. "우리 서비스는 99.9% 를 목표로 한다"는 말이
 * 의존을 세기 전에는 의미가 없다는 뜻이다.
 */
@Component
public class ErrorBudgetCase extends VerificationCase {

    private static final Duration WINDOW = Duration.ofDays(30);

    @Override
    public String id() {
        return "OBS-04";
    }

    @Override
    public String category() {
        return "observability";
    }

    @Override
    public String question() {
        return "SLI·SLO·SLA 를 구분해 설명하고, 에러 버짓을 어떻게 운영에 씁니까?";
    }

    @Override
    public String claim() {
        return "SLI 는 측정값, SLO 는 우리가 지키기로 한 내부 목표, SLA 는 고객과의 계약이며 보통 SLO 보다 느슨하다. 에러 버짓은 SLO 를 기준으로 계산한 '허용된 실패량'이고, 남아 있으면 배포 속도를 높이고 소진하면 안정화에 집중하는 식으로 의사결정에 쓴다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Duration budget999 = errorBudget(0.999);
        Duration budget99 = errorBudget(0.99);
        Duration budget9999 = errorBudget(0.9999);

        // 실제 관측: 30일 중 25분 장애가 났다면 버짓을 얼마나 썼나
        Duration observedDowntime = Duration.ofMinutes(25);
        double burnedAgainstSlo = (double) observedDowntime.toSeconds() / budget999.toSeconds();

        // 같은 장애를 SLA(99.5%) 기준으로 재면 — 원고 지적 ⑥ 의 쟁점
        Duration slaBudget = errorBudget(0.995);
        double burnedAgainstSla = (double) observedDowntime.toSeconds() / slaBudget.toSeconds();

        // 직렬 의존이 늘면 가용성은 곱해진다
        double chained3 = Math.pow(0.999, 3);
        double chained5 = Math.pow(0.999, 5);
        Duration chained5Budget = errorBudget(chained5);

        evidence.fact("측정 창", WINDOW.toDays() + "일");
        evidence.fact("SLO 99%   의 에러 버짓", human(budget99));
        evidence.fact("SLO 99.9% 의 에러 버짓", human(budget999));
        evidence.fact("SLO 99.99% 의 에러 버짓", human(budget9999));
        evidence.fact("관측된 장애 시간", human(observedDowntime));
        evidence.fact("SLO(99.9%) 기준 소진율", String.format("%.0f%%", burnedAgainstSlo * 100));
        evidence.fact("SLA(99.5%) 기준으로 재면", String.format("%.0f%%", burnedAgainstSla * 100));
        evidence.fact("99.9% 서비스 3개에 직렬 의존", String.format("%.4f%%", chained3 * 100));
        evidence.fact("99.9% 서비스 5개에 직렬 의존", String.format("%.4f%%", chained5 * 100));
        evidence.fact("그때 허용 다운타임", human(chained5Budget));

        evidence.expectEquals("SLO 99.9% 의 30일 에러 버짓은 43분 12초다", 43 * 60 + 12, budget999.toSeconds());
        evidence.expect("한 자리 올리면(99.99%) 버짓은 10분의 1로 줄어든다",
                Math.abs(budget999.toSeconds() / 10.0 - budget9999.toSeconds()) < 1);
        evidence.expect("25분 장애면 SLO 기준으로 절반 이상을 쓴 것이다", burnedAgainstSlo > 0.5);
        evidence.expect("같은 장애를 SLA 기준으로 재면 훨씬 여유롭게 보인다 — 기준을 바꾸면 판단이 바뀐다",
                burnedAgainstSla < burnedAgainstSlo);
        evidence.expect("SLA 기준으로는 아직 버짓이 남은 것으로 보인다", burnedAgainstSla < 1.0);

        evidence.expect("직렬 의존이 늘수록 전체 가용성은 떨어진다", chained5 < chained3 && chained3 < 0.999);
        evidence.expect("99.9% 다섯 개에 의존하면 전체 목표는 99.5% 수준으로 내려간다",
                chained5 < 0.9951 && chained5 > 0.9949);
        evidence.expect("허용 다운타임은 43분에서 3시간 이상으로 늘어난다",
                chained5Budget.toMinutes() > 180);

        evidence.note("에러 버짓의 기준은 **SLO** 다. SLA 는 위약금이 걸린 계약선이라 보통 SLO 보다 느슨하게 잡는데(예: SLO 99.9% / SLA 99.5%), 그것을 기준으로 삼으면 위 관측값처럼 같은 장애가 '" + String.format("%.0f%%", burnedAgainstSlo * 100) + " 소진'에서 '" + String.format("%.0f%%", burnedAgainstSla * 100) + " 소진'으로 보인다. 즉 **계약을 어기기 직전까지 아무 조치도 하지 않게 된다.** SLO 를 더 빡빡하게 두는 이유가 이 완충 구간을 만들기 위해서다.");
        evidence.note("원고에서 이 지점이 어긋나 있었다 — Q87 은 에러 버짓을 SLA 기준으로 설명하고 Q142 는 SLO 기준으로 설명한다. 같은 사람이 두 번 답한 것이라 면접에서 교차 질문을 받으면 드러난다. Q87 을 SLO 기준으로 맞추는 것이 맞다(`docs/04` 지적 ⑥).");
        evidence.note("버짓은 '쓰면 안 되는 것'이 아니라 **쓰라고 있는 것**이다. 남아 있으면 배포 속도를 올리고 위험한 실험을 해도 되며, 소진하면 기능 개발을 멈추고 안정화에 집중한다. 이 규칙을 미리 합의해 두면 '지금 배포해도 되나'가 논쟁이 아니라 계산이 된다.");
        evidence.note("소진 속도(burn rate)를 보면 더 빨리 움직일 수 있다. 1시간 만에 한 달 버짓의 2% 를 썼다면 그 속도로는 이틀 만에 다 쓴다 — 그래서 경보를 '오류율 임계치'가 아니라 '버짓 소진 속도'로 거는 방식(multi-window burn rate alert)이 표준이 됐다.");
        evidence.note("마지막 관측값이 설계에 직접 영향을 준다. 직렬 의존은 가용성을 곱하므로, 목표를 지키려면 의존을 줄이거나(비동기·캐시) 실패를 흡수해야 한다 — 서킷 브레이커(RES-04)·벌크헤드(RES-09)·폴백이 '가용성을 곱셈에서 빼내는' 장치다.");
    }

    /** 목표 가용성에서 허용 실패 시간을 구한다 — 에러 버짓의 정의 그대로다. */
    private Duration errorBudget(double slo) {
        return Duration.ofSeconds(Math.round(WINDOW.toSeconds() * (1 - slo)));
    }

    private String human(Duration duration) {
        long minutes = duration.toMinutes();
        long seconds = duration.toSeconds() % 60;
        if (minutes >= 60) {
            return duration.toHours() + "시간 " + (minutes % 60) + "분";
        }
        return minutes + "분 " + seconds + "초";
    }
}
