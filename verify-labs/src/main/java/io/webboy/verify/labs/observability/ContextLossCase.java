package io.webboy.verify.labs.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Q33 (실무 부작용) — "트레이스가 비동기 구간에서만 끊긴다".
 *
 * <p>{@code OBS-01} 은 서비스 <b>사이</b>의 전파를 본다. 여기서는 한 프로세스 <b>안</b>에서
 * 스레드가 바뀌는 순간 컨텍스트가 사라지는 것을 관측한다. 원인은 하나다 —
 * OpenTelemetry 의 {@code Context} 도 SLF4J 의 {@code MDC} 도 <b>ThreadLocal</b> 이기 때문이다.
 *
 * <p>이것이 실무에서 특히 성가신 이유는 <b>조용히</b> 깨지기 때문이다. 예외도 로그도 없고,
 * 대시보드에서 트레이스가 짧게 잘려 보일 뿐이라 계측이 잘못된 줄 모르고 넘어가기 쉽다.
 */
@Component
public class ContextLossCase extends VerificationCase {

    private static final String MDC_KEY = "traceId";

    @Override
    public String id() {
        return "OBS-02";
    }

    @Override
    public String category() {
        return "observability";
    }

    @Override
    public String question() {
        return "비동기 처리나 스레드 풀을 쓰면 트레이스와 로그 상관관계가 왜 끊깁니까?";
    }

    @Override
    public String claim() {
        return "트레이스 컨텍스트와 MDC 는 ThreadLocal 에 담기므로 다른 스레드로 작업을 넘기면 그대로 사라진다. 예외가 나지 않고 조용히 끊기는 것이 문제이며, 실행자를 감싸(context propagation) 넘겨줄 때 함께 복사해야 이어진다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        ExecutorService plainPool = Executors.newFixedThreadPool(2);
        try (Telemetry telemetry = Telemetry.create()) {
            Span request = telemetry.tracer().spanBuilder("POST /orders").startSpan();
            String expectedTraceId = request.getSpanContext().getTraceId();

            String sameThreadTraceId;
            String pooledTraceId;
            String wrappedPoolTraceId;
            String sameThreadMdc;
            String pooledMdc;
            String wrappedMdc;

            try (Scope ignored = request.makeCurrent()) {
                MDC.put(MDC_KEY, expectedTraceId);

                // (1) 같은 스레드 — 당연히 보인다
                sameThreadTraceId = currentTraceId();
                sameThreadMdc = MDC.get(MDC_KEY);

                // (2) 그냥 스레드 풀로 넘기면 — 조용히 사라진다
                pooledTraceId = plainPool.submit(this::currentTraceId).get(5, TimeUnit.SECONDS);
                pooledMdc = plainPool.submit(() -> MDC.get(MDC_KEY)).get(5, TimeUnit.SECONDS);

                // (3) 컨텍스트를 함께 넘기도록 감싸면 — 이어진다
                ExecutorService wrapped = Context.taskWrapping(plainPool);
                wrappedPoolTraceId = wrapped.submit(this::currentTraceId).get(5, TimeUnit.SECONDS);
                wrappedMdc = plainPool.submit(copyingMdc(() -> MDC.get(MDC_KEY))).get(5, TimeUnit.SECONDS);
            } finally {
                MDC.remove(MDC_KEY);
                request.end();
            }

            evidence.fact("요청 스팬의 traceId", expectedTraceId);
            evidence.fact("[같은 스레드] Context 에서 읽은 traceId", sameThreadTraceId);
            evidence.fact("[같은 스레드] MDC 값", sameThreadMdc);
            evidence.fact("[스레드 풀 - 그냥 제출] Context 에서 읽은 traceId", pooledTraceId);
            evidence.fact("[스레드 풀 - 그냥 제출] MDC 값", String.valueOf(pooledMdc));
            evidence.fact("[스레드 풀 - Context.taskWrapping] traceId", wrappedPoolTraceId);
            evidence.fact("[스레드 풀 - MDC 수동 복사] MDC 값", String.valueOf(wrappedMdc));
            evidence.fact("컨텍스트가 사라질 때 예외가 났는가", "아니오 — 빈 값으로 조용히 진행된다");

            evidence.expectEquals("같은 스레드에서는 traceId 가 그대로 보인다", expectedTraceId, sameThreadTraceId);
            evidence.expectEquals("같은 스레드에서는 MDC 도 그대로다", expectedTraceId, sameThreadMdc);
            evidence.expect("스레드 풀로 넘기면 trace 컨텍스트가 사라진다(유효하지 않은 traceId)",
                    !expectedTraceId.equals(pooledTraceId));
            evidence.expect("MDC 도 함께 사라져 로그에서 요청을 이어 붙일 수 없다", pooledMdc == null);
            evidence.expectEquals("실행자를 감싸면 trace 컨텍스트가 그대로 따라간다",
                    expectedTraceId, wrappedPoolTraceId);
            evidence.expectEquals("MDC 도 명시적으로 복사하면 따라간다", expectedTraceId, wrappedMdc);
        } finally {
            plainPool.shutdownNow();
        }

        evidence.note("가장 중요한 관측값은 '예외가 나지 않는다'는 것이다. 컨텍스트가 사라져도 traceId 가 전부 0 인 값으로 바뀔 뿐이라 코드는 정상 동작한다. 그래서 계측이 깨진 것을 **대시보드에서 트레이스가 짧게 잘려 보일 때에야** 알게 된다. 조용한 실패라는 점이 이 문제의 본질이다.");
        evidence.note("해결은 '전파를 자동화'하는 쪽으로 간다. Spring 이라면 `TaskDecorator` 를 등록해 `@Async` 와 `ThreadPoolTaskExecutor` 전부에 걸고, 직접 만든 풀은 `Context.taskWrapping(...)` 으로 감싼다. 개별 호출부에서 매번 복사하는 방식은 반드시 어딘가를 빠뜨린다.");
        evidence.note("MDC 는 OpenTelemetry 와 별개의 ThreadLocal 이라 따로 챙겨야 한다. 관측값에서 둘이 같이 사라진 이유이고, 그래서 실무에서는 로그 패턴에 traceId 를 넣을 때 MDC 복사까지 세트로 설정한다(Micrometer Tracing 의 `ContextSnapshot` 이 이 둘을 함께 옮긴다).");
        evidence.note("같은 병이 다른 이름으로도 나타난다 — `SecurityContextHolder`(인증 주체), 트랜잭션 동기화, 언어별 요청 스코프 값이 전부 ThreadLocal 기반이다. '스레드를 바꾸면 무엇이 사라지는가'를 목록으로 갖고 있어야 한다. 가상 스레드로 바꿔도 이 성질은 그대로다.");
    }

    private String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    /** MDC 를 손으로 복사해 넘기는 전형적 패턴 — 감싸지 않으면 이 일을 호출부마다 해야 한다. */
    private <T> Callable<T> copyingMdc(Callable<T> task) {
        var snapshot = MDC.getCopyOfContextMap();
        return () -> {
            if (snapshot != null) {
                MDC.setContextMap(snapshot);
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }
}
