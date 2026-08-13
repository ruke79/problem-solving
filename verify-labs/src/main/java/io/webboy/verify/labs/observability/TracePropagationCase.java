package io.webboy.verify.labs.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Q33 — 서비스가 여러 개일 때 하나의 요청을 어떻게 따라가는가.
 *
 * <p>흉내내지 않고 <b>실제 OpenTelemetry SDK</b> 로 스팬을 만들고 W3C {@code traceparent} 헤더로
 * 전파한다. 콜렉터는 띄우지 않고 인메모리 익스포터로 받으므로 인프라가 필요 없지만,
 * 계측·전파·샘플링 로직은 전부 실물이다.
 *
 * <p>핵심은 "로그에 요청 ID 를 넣는다"와 무엇이 다른가이다. 트레이스는 <b>부모-자식 관계</b>를
 * 들고 다니므로 어느 구간에서 시간을 썼는지가 복원된다. 그리고 그 관계는 <b>헤더를 넘겨야만</b>
 * 이어진다 — 한 군데라도 빠뜨리면 그 지점에서 트레이스가 끊긴다.
 */
@Component
public class TracePropagationCase extends VerificationCase {

    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> {
        if (carrier != null) {
            carrier.put(key, value);
        }
    };

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    @Override
    public String id() {
        return "OBS-01";
    }

    @Override
    public String category() {
        return "observability";
    }

    @Override
    public String question() {
        return "마이크로서비스에서 하나의 요청이 여러 서비스를 거칠 때 어떻게 추적합니까?";
    }

    @Override
    public String claim() {
        return "요청마다 trace ID 를 부여하고 서비스 경계에서 표준 헤더(W3C traceparent)로 전파하면, 각 서비스가 만든 스팬이 같은 trace 로 묶이고 부모-자식 관계로 구간별 소요 시간까지 복원된다. 헤더를 전파하지 않으면 그 지점에서 트레이스가 끊겨 별개의 요청으로 보인다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        try (Telemetry telemetry = Telemetry.create()) {
            Tracer tracer = telemetry.tracer();

            // --- 전파하는 경우: 게이트웨이 → (헤더) → 주문 서비스 → (헤더) → 결제 서비스
            Map<String, String> headers = new HashMap<>();
            Span gateway = tracer.spanBuilder("GET /orders").startSpan();
            try (Scope ignored = gateway.makeCurrent()) {
                telemetry.propagator().inject(Context.current(), headers, SETTER);
                callDownstream(telemetry, headers, "order-service", "payment-service");
            } finally {
                gateway.end();
            }

            // --- 전파하지 않는 경우: 헤더를 안 넘기고 그냥 호출
            Span lonelyGateway = tracer.spanBuilder("GET /orders (헤더 미전파)").startSpan();
            try (Scope ignored = lonelyGateway.makeCurrent()) {
                callDownstream(telemetry, Map.of(), "order-service-detached", null);
            } finally {
                lonelyGateway.end();
            }

            List<SpanData> spans = telemetry.finishedSpans();
            Map<String, SpanData> byName = spans.stream()
                    .collect(Collectors.toMap(SpanData::getName, span -> span, (a, b) -> a));

            SpanData root = byName.get("GET /orders");
            SpanData order = byName.get("order-service");
            SpanData payment = byName.get("payment-service");
            SpanData detachedRoot = byName.get("GET /orders (헤더 미전파)");
            SpanData detached = byName.get("order-service-detached");

            // Set.of 는 중복 원소를 예외로 막는다. 여기서는 셋이 '같은 것'이 정답이므로 copyOf 를 쓴다.
            Set<String> connectedTraceIds = Set.copyOf(
                    List.of(root.getTraceId(), order.getTraceId(), payment.getTraceId()));
            Set<String> distinctSpanIds = Set.copyOf(
                    List.of(root.getSpanId(), order.getSpanId(), payment.getSpanId()));

            evidence.fact("생성된 스팬 수", spans.size());
            evidence.fact("전파에 쓰인 헤더", headers.toString());
            evidence.fact("[전파] 게이트웨이 traceId / spanId",
                    root.getTraceId() + " / " + root.getSpanId());
            evidence.fact("[전파] 주문 서비스 traceId / spanId / parentSpanId",
                    order.getTraceId() + " / " + order.getSpanId() + " / " + order.getParentSpanId());
            evidence.fact("[전파] 결제 서비스 traceId / parentSpanId",
                    payment.getTraceId() + " / " + payment.getParentSpanId());
            evidence.fact("[전파] 서로 다른 traceId 개수", connectedTraceIds.size());
            evidence.fact("[미전파] 게이트웨이 traceId", detachedRoot.getTraceId());
            evidence.fact("[미전파] 하위 서비스 traceId", detached.getTraceId());
            evidence.fact("[미전파] 하위 서비스에 부모가 있는가",
                    detached.getParentSpanContext().isValid());

            evidence.expect("전파 헤더는 W3C 표준 이름(traceparent)으로 실린다", headers.containsKey("traceparent"));
            evidence.expect("traceparent 값에 게이트웨이의 traceId 가 들어 있다",
                    headers.get("traceparent").contains(root.getTraceId()));
            evidence.expectEquals("전파한 세 서비스의 스팬이 하나의 trace 로 묶인다", 1, connectedTraceIds.size());
            evidence.expectEquals("주문 서비스의 부모는 게이트웨이다", root.getSpanId(), order.getParentSpanId());
            evidence.expectEquals("결제 서비스의 부모는 주문 서비스다", order.getSpanId(), payment.getParentSpanId());
            evidence.expectEquals("그래도 각 스팬의 spanId 는 서로 다르다 — 구간을 구분해야 하기 때문이다",
                    3, distinctSpanIds.size());

            evidence.expect("헤더를 전파하지 않으면 하위 서비스가 새 trace 를 시작한다",
                    !detached.getTraceId().equals(detachedRoot.getTraceId()));
            evidence.expect("그 스팬에는 부모가 없어 어느 요청에서 왔는지 복원할 수 없다",
                    !detached.getParentSpanContext().isValid());
        }

        evidence.note("전파의 실체는 헤더 한 줄이다. `traceparent: 00-<traceId>-<spanId>-<flags>` 형태이고, 여기서 traceId 는 요청 전체, spanId 는 '지금 이 구간'이다. 다음 서비스는 이 spanId 를 자기 부모로 삼는다 — 관측값의 parentSpanId 가 그것이다.");
        evidence.note("'로그에 요청 ID 를 넣는다'와의 차이가 여기서 갈린다. 요청 ID 만 있으면 '같은 요청'인 것까지는 아는데 **어느 구간이 느렸는지**는 모른다. 부모-자식 관계가 있어야 구간별 소요가 복원되고, 그래서 병목을 서비스 단위로 지목할 수 있다.");
        evidence.note("실무에서 트레이스가 끊기는 지점은 대개 정해져 있다 — 메시지 큐(헤더를 메시지 속성으로 옮겨 실어야 한다), 배치·스케줄러(들어오는 요청이 없어 새 trace 로 시작한다), 그리고 직접 만든 HTTP 클라이언트. 위 '미전파' 관측값이 그때 보이는 모습 그대로다.");
        evidence.note("표준 헤더를 쓰는 것이 중요하다. 자체 헤더(X-Request-Id 등)로 전파하면 우리 서비스끼리는 이어져도 서드파티 SDK·프록시·서비스 메시가 이어 주지 못한다. W3C traceparent 는 그 지점을 표준화한 것이다.");
        evidence.note("케이스를 쓰다 걸린 함정 하나 — 하위 서비스가 헤더를 꺼낼 때 기준 컨텍스트를 `Context.current()` 로 두면, 같은 JVM 안에서는 ThreadLocal 이 남아 있어 **헤더를 안 넘겨도 trace 가 이어진 것처럼 보인다.** 다른 프로세스에는 물려받을 컨텍스트가 없으므로 `Context.root()` 에서 추출해야 실제 원격 경계가 재현된다. 인프로세스 테스트가 전파를 통과시켜 놓고 배포하면 끊기는 이유가 대개 이것이다.");
        evidence.note("이 케이스는 콜렉터 없이 인메모리 익스포터로 받았다. 실제 운영에서는 여기에 수집·저장·질의 계층이 더 붙고 비용도 거기서 발생한다 — 그래서 샘플링이 필요해진다(OBS-03).");
    }

    /**
     * 헤더에서 컨텍스트를 꺼내 자식 스팬을 만든다 — 서비스 경계에서 실제로 일어나는 일.
     *
     * <p>추출의 기준을 {@link Context#root()} 로 두는 것이 중요하다. 다른 프로세스에는 물려받은
     * 컨텍스트가 없기 때문이다. {@code Context.current()} 를 기준으로 삼으면 같은 JVM 안에서는
     * ThreadLocal 이 남아 있어 <b>헤더를 안 넘겨도 이어진 것처럼 보인다</b> — 이 랩도 처음에
     * 그렇게 짜서 '미전파인데 trace 가 이어지는' 결과를 봤다.
     */
    private void callDownstream(Telemetry telemetry, Map<String, String> incomingHeaders,
                                String spanName, String nextSpanName) {
        Context extracted = telemetry.propagator().extract(Context.root(), incomingHeaders, GETTER);
        Span span = telemetry.tracer().spanBuilder(spanName).setParent(extracted).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (nextSpanName != null) {
                Map<String, String> outgoing = new HashMap<>();
                telemetry.propagator().inject(Context.current(), outgoing, SETTER);
                Span next = telemetry.tracer().spanBuilder(nextSpanName)
                        .setParent(telemetry.propagator().extract(Context.root(), outgoing, GETTER))
                        .startSpan();
                next.end();
            }
        } finally {
            span.end();
        }
    }
}
