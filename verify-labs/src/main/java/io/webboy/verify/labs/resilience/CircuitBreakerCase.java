package io.webboy.verify.labs.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Q34 — 서킷브레이커 3상태와, 흔히 빠뜨리는 "최소 시행 횟수". */
@Component
public class CircuitBreakerCase extends VerificationCase {

    private static final int MINIMUM_CALLS = 5;
    private static final long OPEN_WAIT_MILLIS = 300L;

    @Override
    public String id() {
        return "RES-04";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "서킷브레이커의 동작 원리를 3상태로 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "실패율 임계치와 '최소 시행 횟수'를 함께 걸어야 한다. 최소 시행 횟수가 없으면 첫 1회 실패만으로 차단되고, OPEN 중에는 호출 없이 즉시 실패한다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        CircuitBreaker guarded = CircuitBreaker.of("guarded", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(OPEN_WAIT_MILLIS))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());

        fail(guarded);
        CircuitBreaker.State afterOneFailure = guarded.getState();

        for (int i = 1; i < MINIMUM_CALLS; i++) {
            fail(guarded);
        }
        CircuitBreaker.State afterMinimumFailures = guarded.getState();

        long began = System.nanoTime();
        String openOutcome = call(guarded, true);
        long fastFailMillis = (System.nanoTime() - began) / 1_000_000L;

        Thread.sleep(OPEN_WAIT_MILLIS + 150);
        String firstProbe = call(guarded, false);
        CircuitBreaker.State afterFirstProbe = guarded.getState();
        call(guarded, false);
        CircuitBreaker.State afterSecondProbe = guarded.getState();

        CircuitBreaker naive = CircuitBreaker.of("naive", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(50)
                .build());
        fail(naive);
        CircuitBreaker.State naiveAfterOneFailure = naive.getState();

        evidence.fact("minimumNumberOfCalls", MINIMUM_CALLS);
        evidence.fact("1회 실패 후 상태", afterOneFailure);
        evidence.fact("최소 시행 횟수만큼 실패 후 상태", afterMinimumFailures);
        evidence.fact("OPEN 중 호출 결과", openOutcome);
        evidence.fact("OPEN 중 실패까지 걸린 시간(ms)", fastFailMillis);
        evidence.fact("대기 후 첫 시험 호출", firstProbe);
        evidence.fact("첫 시험 호출 직후 상태", afterFirstProbe);
        evidence.fact("두 번째 시험 호출 직후 상태", afterSecondProbe);
        evidence.fact("minimumNumberOfCalls=1 일 때 1회 실패 후 상태", naiveAfterOneFailure);

        evidence.expectEquals("최소 시행 횟수 전에는 CLOSED 를 유지한다",
                CircuitBreaker.State.CLOSED, afterOneFailure);
        evidence.expectEquals("임계치를 넘으면 OPEN 이 된다",
                CircuitBreaker.State.OPEN, afterMinimumFailures);
        evidence.expectEquals("OPEN 중에는 호출하지 않고 즉시 실패한다",
                "CallNotPermittedException", openOutcome);
        evidence.expect("즉시 실패이므로 타임아웃을 기다리지 않는다", fastFailMillis < 100);
        evidence.expectEquals("대기 시간이 지나면 HALF_OPEN 으로 시험 호출을 허용한다", "ok", firstProbe);
        evidence.expectEquals("허용된 시험 호출이 모두 성공하면 CLOSED 로 복귀한다",
                CircuitBreaker.State.CLOSED, afterSecondProbe);
        evidence.expectEquals("최소 시행 횟수가 없으면 단 1회 실패로 차단된다",
                CircuitBreaker.State.OPEN, naiveAfterOneFailure);

        evidence.note("서킷브레이커는 단독으로 기능하지 않는다 — 모든 외부 호출의 타임아웃 설정과 지수 백오프+지터 재시도가 전제다.");
        evidence.note("차단했을 때 '무엇을 반환할지'(캐시된 옛 값, 기능 축소 표시)를 업무 쪽과 미리 합의해 두는 것이 설계의 핵심이다.");
    }

    private void fail(CircuitBreaker breaker) {
        call(breaker, true);
    }

    private String call(CircuitBreaker breaker, boolean shouldFail) {
        try {
            return breaker.executeCallable(() -> {
                if (shouldFail) {
                    throw new IllegalStateException("downstream down");
                }
                return "ok";
            });
        } catch (CallNotPermittedException e) {
            return "CallNotPermittedException";
        } catch (Exception e) {
            return "failed";
        }
    }
}
