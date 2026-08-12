package io.webboy.verify.labs.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q34 심화 — 재시도와 서킷브레이커를 <b>겹칠 때의 순서</b>가 결과를 바꾼다.
 *
 * <p>{@code RES-04} 는 서킷브레이커 단독의 상태 전이(CLOSED→OPEN→HALF_OPEN)를 본다.
 * 실무에서 사고가 나는 지점은 그다음이다 — 두 패턴을 겹치는 순서에 따라
 * <b>서킷이 열리는 속도</b>와 <b>원격지에 실제로 가는 호출 수</b>가 달라진다.
 *
 * <ul>
 *   <li><b>Retry(CircuitBreaker(call))</b> — 재시도가 바깥. 시도 1회마다 서킷에 실패 1건이
 *       기록되므로 서킷이 훨씬 빨리 열리고, 열린 뒤의 재시도는 즉시 실패로 되돌아온다</li>
 *   <li><b>CircuitBreaker(Retry(call))</b> — 서킷이 바깥. 재시도를 다 소진한 결과 1건만
 *       실패로 기록되므로 서킷은 늦게 열리고, 그동안 원격지는 재시도 배수만큼 두들겨 맞는다</li>
 * </ul>
 */
@Component
public class RetryWithCircuitBreakerCase extends VerificationCase {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MINIMUM_CALLS = 6;
    private static final int OPERATIONS = 4;

    @Override
    public String id() {
        return "RES-08";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "재시도와 서킷브레이커를 함께 쓸 때 주의할 점은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "두 패턴을 겹치는 순서가 동작을 바꾼다. 재시도를 바깥에 두면 시도마다 서킷에 실패가 기록돼 서킷이 빨리 열리고 원격지 부하가 일찍 끊긴다. 서킷을 바깥에 두면 실패가 요청 단위로 1건만 기록돼 서킷이 늦게 열리고 그동안 원격지는 재시도 배수만큼 더 맞는다";
    }

    @Override
    protected void verify(Evidence evidence) {
        Outcome retryOutside = run(true);
        Outcome breakerOutside = run(false);

        evidence.fact("요청 수 / 요청당 최대 시도", OPERATIONS + " / " + MAX_ATTEMPTS);
        evidence.fact("서킷 최소 시행 횟수 / 실패율 임계치", MINIMUM_CALLS + " / 50%");
        evidence.fact("[재시도가 바깥] 원격지에 실제로 도달한 호출 수", retryOutside.remoteCalls);
        evidence.fact("[재시도가 바깥] 서킷이 열린 시점(요청 번호)", describeOpening(retryOutside));
        evidence.fact("[재시도가 바깥] 차단(CallNotPermitted)된 시도 수", retryOutside.blockedAttempts);
        evidence.fact("[서킷이 바깥] 원격지에 실제로 도달한 호출 수", breakerOutside.remoteCalls);
        evidence.fact("[서킷이 바깥] 서킷이 열린 시점(요청 번호)", describeOpening(breakerOutside));
        evidence.fact("[서킷이 바깥] 차단(CallNotPermitted)된 시도 수", breakerOutside.blockedAttempts);

        // "끝까지 안 열림"은 -1 로 들어오므로 그대로 비교하면 안 된다. 늦게 열린 것으로 취급한다.
        evidence.expect("재시도를 바깥에 두면 서킷이 더 일찍 열린다",
                openingOrder(retryOutside) < openingOrder(breakerOutside));
        evidence.expect("서킷을 바깥에 두면 같은 요청 수 안에서는 최소 시행 횟수를 못 채워 아예 열리지 않는다",
                breakerOutside.openedAtOperation < 0);
        evidence.expect("재시도를 바깥에 두면 원격지에 도달하는 호출이 더 적다",
                retryOutside.remoteCalls < breakerOutside.remoteCalls);
        evidence.expect("서킷이 열린 뒤의 시도는 원격지에 가지 않고 즉시 차단된다",
                retryOutside.blockedAttempts > 0);

        evidence.note("정답은 '재시도를 서킷 안쪽에 두지 않는 것'이 아니라 '무엇을 지키려는가'에 달렸다. 원격지를 빨리 쉬게 하려면 재시도를 바깥(=서킷이 시도마다 카운트)에, 일시적 순간 장애를 사용자에게 안 보이게 하려면 서킷을 바깥에 둔다.");
        evidence.note("resilience4j 의 데코레이터 조합 순서(Retry.decorateSupplier(retry, cb.decorateSupplier(...)))가 곧 이 의미다 — 라이브러리 설정 한 줄이 아니라 설계 결정이다.");
        evidence.note("서킷이 OPEN 인 동안의 재시도는 원격지에 가지 않으므로 백오프를 길게 잡을 이유가 없다 — 대신 폴백(캐시된 값·축소 응답)으로 즉시 응답하는 편이 낫다.");
        evidence.note("타임아웃이 없으면 둘 다 무의미하다. 응답이 오지 않는 호출은 실패로 기록되지 않아 서킷이 영원히 닫힌 채로 스레드만 잡아먹는다.");
    }

    private String describeOpening(Outcome outcome) {
        return outcome.openedAtOperation < 0
                ? "열리지 않음(최소 시행 횟수 미달)"
                : String.valueOf(outcome.openedAtOperation);
    }

    private int openingOrder(Outcome outcome) {
        return outcome.openedAtOperation < 0 ? Integer.MAX_VALUE : outcome.openedAtOperation;
    }

    private Outcome run(boolean retryOutside) {
        AtomicInteger remoteCalls = new AtomicInteger();
        AtomicInteger blockedAttempts = new AtomicInteger();
        int openedAtOperation = -1;

        CircuitBreaker breaker = CircuitBreaker.of("res-08-" + retryOutside, CircuitBreakerConfig.custom()
                .slidingWindowSize(MINIMUM_CALLS * 2)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());

        for (int operation = 1; operation <= OPERATIONS; operation++) {
            if (retryOutside) {
                // Retry(CircuitBreaker(call)) — 시도 하나하나가 서킷을 통과한다
                for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                    if (!callThroughBreaker(breaker, remoteCalls, blockedAttempts)) {
                        continue;   // 실패 → 다음 시도
                    }
                    break;
                }
            } else {
                // CircuitBreaker(Retry(call)) — 재시도를 다 소진한 '요청 1건'이 서킷을 통과한다
                callRetryThroughBreaker(breaker, remoteCalls, blockedAttempts);
            }

            if (openedAtOperation < 0 && breaker.getState() == CircuitBreaker.State.OPEN) {
                openedAtOperation = operation;
            }
        }
        return new Outcome(remoteCalls.get(), openedAtOperation, blockedAttempts.get());
    }

    /** 항상 실패하는 원격 호출을 서킷을 통해 1회 시도한다. */
    private boolean callThroughBreaker(CircuitBreaker breaker, AtomicInteger remoteCalls, AtomicInteger blocked) {
        try {
            breaker.executeSupplier(() -> {
                remoteCalls.incrementAndGet();
                throw new IllegalStateException("remote down");
            });
            return true;
        } catch (CallNotPermittedException e) {
            blocked.incrementAndGet();
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 재시도를 서킷 '안쪽'에 두고 요청 1건으로 실행한다. */
    private void callRetryThroughBreaker(CircuitBreaker breaker, AtomicInteger remoteCalls, AtomicInteger blocked) {
        try {
            breaker.executeSupplier(() -> {
                RuntimeException last = null;
                for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                    try {
                        remoteCalls.incrementAndGet();
                        throw new IllegalStateException("remote down");
                    } catch (RuntimeException e) {
                        last = e;
                    }
                }
                throw last;
            });
        } catch (CallNotPermittedException e) {
            blocked.incrementAndGet();
        } catch (RuntimeException ignored) {
            // 요청 1건의 실패로 기록된다
        }
    }

    private record Outcome(int remoteCalls, int openedAtOperation, int blockedAttempts) {}
}
