package io.webboy.verify.labs.observability;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.List;

/**
 * 관측성 케이스가 공유하는 OpenTelemetry 실행 환경.
 *
 * <p>콜렉터를 띄우지 않고 {@link InMemorySpanExporter} 로 스팬을 받는다 — 인프라 없이 돌리되
 * 계측·전파·샘플링은 전부 실제 SDK 를 쓴다. 케이스마다 새로 만들어 쓰고 닫는다(스팬이 섞이지 않게).
 *
 * <p>전역 {@code GlobalOpenTelemetry} 는 쓰지 않는다. 한 JVM 에서 여러 케이스가 도는 이 랩에서는
 * 전역 상태를 공유하는 순간 서로의 스팬을 보게 되고, 그러면 <b>다른 케이스 때문에 판정이 흔들린다</b>
 * — {@code KAFKA-05} 가 컨슈머 그룹을 공유해 거짓 REFUTED 를 냈던 것과 같은 종류의 오염이다.
 */
final class Telemetry implements AutoCloseable {

    private final OpenTelemetrySdk sdk;
    private final InMemorySpanExporter exporter;

    private Telemetry(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        this.sdk = sdk;
        this.exporter = exporter;
    }

    /** 모든 스팬을 기록하는 기본 환경. */
    static Telemetry create() {
        return create(Sampler.alwaysOn());
    }

    /** 샘플러를 지정한 환경 — {@code OBS-03} 이 비율 샘플링을 실측할 때 쓴다. */
    static Telemetry create(Sampler sampler) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .setSampler(sampler)
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        return new Telemetry(sdk, exporter);
    }

    Tracer tracer() {
        return sdk.getTracer("verify-lab");
    }

    TextMapPropagator propagator() {
        return sdk.getPropagators().getTextMapPropagator();
    }

    /** 익스포터까지 도달한 스팬 — 샘플링에서 탈락한 스팬은 여기 없다. */
    List<SpanData> finishedSpans() {
        return exporter.getFinishedSpanItems();
    }

    @Override
    public void close() {
        sdk.close();
    }
}
