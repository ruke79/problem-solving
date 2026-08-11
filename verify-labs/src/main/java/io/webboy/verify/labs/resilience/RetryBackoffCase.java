package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryBackoffCase extends VerificationCase {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_DELAY_MILLIS = 50L;

    private final FlakyService flaky;

    public RetryBackoffCase(FlakyService flaky) {
        this.flaky = flaky;
    }

    @Override
    public String id() {
        return "RES-03";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "재시도에 지수 백오프와 지터를 왜 넣습니까?";
    }

    @Override
    public String claim() {
        return "고정 간격 재시도는 부하를 동기화시켜 재장애를 부른다. 지수 증가로 부하를 줄이고 지터로 재시도 시점을 흩뜨린다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        flaky.reset();
        List<Long> delays = new ArrayList<>();

        long began = System.nanoTime();
        String result = null;
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                result = flaky.call(2);
                break;
            } catch (RuntimeException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                long backoff = BASE_DELAY_MILLIS * (1L << (attempt - 1));
                long jittered = ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1);
                delays.add(jittered);
                Thread.sleep(jittered);
            }
        }
        long elapsed = (System.nanoTime() - began) / 1_000_000L;

        long minimumWait = delays.stream().mapToLong(Long::longValue).sum();
        boolean delaysIncrease = delays.size() < 2 || delays.get(1) > delays.get(0) / 2;

        evidence.fact("최종 결과", String.valueOf(result));
        evidence.fact("마지막 예외", last == null ? "-" : last.getMessage());
        evidence.fact("총 호출 횟수", flaky.attempts());
        evidence.fact("성공한 부수효과 횟수", flaky.sideEffects());
        evidence.fact("적용된 백오프(ms)", delays);
        evidence.fact("총 소요(ms)", elapsed);

        evidence.expect("2회 실패 후 3번째 시도에서 성공한다", "ok@attempt-3".equals(result));
        evidence.expectEquals("총 호출은 3회다", 3, flaky.attempts());
        evidence.expectEquals("성공한 부수효과는 1회뿐이다", 1, flaky.sideEffects());
        evidence.expect("대기 시간만큼은 최소한 걸린다", elapsed >= minimumWait);
        evidence.expectFlaky("백오프가 지수적으로 증가한다", delaysIncrease);

        evidence.note("여기서 성공한 것은 부수효과가 없던 호출이 실패했기 때문이다 — "
                + "'요청은 도달했는데 응답만 유실된' 경우라면 재시도가 중복 처리를 만든다(RES-01 이 짝이다).");
        evidence.note("재시도는 타임아웃·서킷브레이커와 함께 설계해야 한다. 재시도만 있으면 장애 시 부하가 배로 늘어난다.");
        evidence.note("전체 지연 상한(총 재시도 예산)을 두지 않으면 상위 호출자의 타임아웃을 넘겨버린다.");

        flaky.reset();
    }
}
