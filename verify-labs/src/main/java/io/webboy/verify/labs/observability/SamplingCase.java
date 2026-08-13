package io.webboy.verify.labs.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Q33 (비용 측면) — 트레이스를 전부 저장할 수 없을 때 무엇을 잃는가.
 *
 * <p>흉내내지 않고 <b>실제 {@link Sampler#traceIdRatioBased} 샘플러</b>로 1000건을 흘려
 * 몇 건이 남는지, 그리고 <b>희귀한 오류가 살아남는지</b>를 관측한다.
 *
 * <p>핵심은 헤드 기반 샘플링의 구조적 한계다 — 샘플링 여부를 <b>트레이스 시작 시점</b>에 정하므로,
 * 그 요청이 나중에 실패할지 느릴지 알 수 없는 상태에서 버릴지 말지를 결정한다.
 * 그래서 "평소에는 잘 보이는데 정작 장애 때 그 요청이 없다"는 일이 생긴다.
 */
@Component
public class SamplingCase extends VerificationCase {

    private static final int TOTAL_REQUESTS = 1_000;
    private static final double SAMPLE_RATIO = 0.05;

    /** 1000건 중 5건만 실패하는 희귀 오류 — 실무에서 정작 보고 싶은 것. */
    private static final int FAILING_EVERY = 200;

    @Override
    public String id() {
        return "OBS-03";
    }

    @Override
    public String category() {
        return "observability";
    }

    @Override
    public String question() {
        return "트래픽이 많을 때 트레이스를 전부 수집합니까? 샘플링은 어떻게 정합니까?";
    }

    @Override
    public String claim() {
        return "전량 수집은 저장 비용과 오버헤드 때문에 현실적이지 않아 비율 샘플링을 쓴다. 다만 헤드 기반 샘플링은 트레이스 시작 시점에 버릴지를 정하므로, 드물게 발생하는 오류나 느린 요청이 표본에서 통째로 빠질 수 있다 — 그래서 오류·지연을 본 뒤에 결정하는 테일 기반 샘플링이나 오류 강제 수집이 필요하다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 샘플링은 traceId 난수에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Result full = run(Sampler.alwaysOn());
        Result sampled = run(Sampler.traceIdRatioBased(SAMPLE_RATIO));

        int expectedErrors = TOTAL_REQUESTS / FAILING_EVERY;
        double keptRatio = (double) sampled.total() / TOTAL_REQUESTS;

        evidence.fact("흘린 요청 수", TOTAL_REQUESTS);
        evidence.fact("그중 실패한 요청 수", expectedErrors);
        evidence.fact("샘플링 비율 설정", SAMPLE_RATIO);
        evidence.fact("[전량 수집] 저장된 스팬 수 / 그중 오류", full.total() + " / " + full.errors());
        evidence.fact("[비율 샘플링] 저장된 스팬 수 / 그중 오류", sampled.total() + " / " + sampled.errors());
        evidence.fact("[비율 샘플링] 실제 저장 비율", String.format("%.1f%%", keptRatio * 100));
        evidence.fact("[비율 샘플링] 오류 중 살아남은 비율",
                expectedErrors == 0 ? "-" : sampled.errors() + "/" + expectedErrors);

        evidence.expectEquals("전량 수집이면 모든 요청이 저장된다", TOTAL_REQUESTS, full.total());
        evidence.expectEquals("전량 수집이면 희귀 오류도 전부 남는다", expectedErrors, full.errors());
        evidence.expect("비율 샘플링은 저장량을 설정값 부근으로 줄인다",
                keptRatio > SAMPLE_RATIO * 0.4 && keptRatio < SAMPLE_RATIO * 2.0);
        evidence.expectFlaky("그 대가로 희귀 오류 대부분이 표본에서 사라진다",
                sampled.errors() < expectedErrors);

        evidence.note("숫자를 그대로 읽으면 된다. 오류는 원래 " + expectedErrors + "건인데 5% 샘플링을 걸면 기대값이 " + String.format("%.2f", expectedErrors * SAMPLE_RATIO) + "건이다 — 즉 **대부분의 장애 조사에서 정작 그 트레이스가 없다.** 헤드 기반 샘플링은 시작 시점에 버릴지를 정하므로, 그 요청이 실패할 것을 알 방법이 없다.");
        evidence.note("그래서 실무 조합은 보통 셋이다. (1) 정상 요청은 낮은 비율로 샘플링해 비용을 잡고, (2) 오류·느린 요청은 **강제로 수집**하며, (3) 여력이 되면 테일 기반 샘플링(요청이 끝난 뒤 결과를 보고 결정)을 콜렉터에 둔다. OpenTelemetry Collector 의 tail_sampling 프로세서가 (3)이다.");
        evidence.note("샘플링 결정은 traceId 로 계산되므로 **전 서비스가 같은 결정을 내린다.** 그래서 '게이트웨이는 저장했는데 결제 서비스는 버렸다' 같은 반쪽 트레이스가 생기지 않는다 — traceparent 의 flags 비트가 그 결정을 실어 나른다(OBS-01).");
        evidence.note("비율만으로 정하면 트래픽이 늘 때 비용도 그대로 늘어난다. 실무에서는 '초당 N건' 상한(rate limiting sampler)을 함께 걸어 최대 비용을 고정하는 쪽이 예측 가능하다.");
        evidence.note("메트릭과 역할을 나누는 것이 중요하다. '오류율이 올랐는가'는 전량 집계되는 메트릭이 답하고, 트레이스는 '그 요청 하나가 왜 그랬는가'를 답한다. 트레이스를 메트릭처럼 쓰려 하면 샘플링 때문에 반드시 틀린다.");
    }

    private Result run(Sampler sampler) {
        try (Telemetry telemetry = Telemetry.create(sampler)) {
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                Span span = telemetry.tracer().spanBuilder("GET /orders").startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    if (i % FAILING_EVERY == 0) {
                        span.setStatus(StatusCode.ERROR, "결제 게이트웨이 타임아웃");
                    }
                } finally {
                    span.end();
                }
            }
            List<SpanData> spans = telemetry.finishedSpans();
            long errors = spans.stream().filter(s -> s.getStatus().getStatusCode() == StatusCode.ERROR).count();
            return new Result(spans.size(), (int) errors);
        }
    }

    /**
     * @param total  익스포터까지 도달한 스팬 수
     * @param errors 그중 오류 상태인 스팬 수
     */
    private record Result(int total, int errors) {}
}
