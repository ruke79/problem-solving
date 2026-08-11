package io.webboy.verify.labs.msa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

/** Q42 — "큐는 부하를 미룰 뿐 없애주지 않는다"를 숫자로 확인한다. */
@Component
public class QueueBackpressureCase extends VerificationCase {

    private static final int TICKS = 100;
    private static final int ARRIVALS_PER_TICK = 10;
    private static final int CAPACITY_PER_TICK = 4;

    @Override
    public String id() {
        return "MSA-03";
    }

    @Override
    public String category() {
        return "msa";
    }

    @Override
    public String question() {
        return "트래픽이 급증할 때 Kafka 로 시스템을 어떻게 지킵니까?";
    }

    @Override
    public String claim() {
        return "큐는 순간 피크를 평준화할 뿐이다. 도착률이 처리 능력을 지속적으로 넘으면 랙은 무한히 늘어나므로 앞단 레이트 리밋이 함께 필요하다";
    }

    @Override
    protected void verify(Evidence evidence) {
        Simulation burst = simulate(ARRIVALS_PER_TICK, 10, CAPACITY_PER_TICK, Integer.MAX_VALUE);
        Simulation sustained = simulate(ARRIVALS_PER_TICK, TICKS, CAPACITY_PER_TICK, Integer.MAX_VALUE);
        Simulation throttled = simulate(ARRIVALS_PER_TICK, TICKS, CAPACITY_PER_TICK, CAPACITY_PER_TICK);

        evidence.fact("틱당 도착 / 틱당 처리 능력", ARRIVALS_PER_TICK + " / " + CAPACITY_PER_TICK);
        evidence.fact("[순간 피크 10틱] 최종 랙", burst.finalLag());
        evidence.fact("[순간 피크 10틱] 모두 소진되는 데 걸린 틱", burst.ticksToDrain());
        evidence.fact("[지속 초과 100틱] 최종 랙", sustained.finalLag());
        evidence.fact("[지속 초과 100틱] 최대 랙", sustained.maxLag());
        evidence.fact("[레이트 리밋 적용] 최종 랙", throttled.finalLag());
        evidence.fact("[레이트 리밋 적용] 거절된 요청 수", throttled.rejected());

        evidence.expectEquals("순간 피크는 큐가 흡수해 결국 전부 처리된다", 0, burst.finalLag());
        evidence.expect("지속적으로 초과하면 랙이 선형으로 늘어난다", sustained.finalLag() > 500);
        evidence.expectEquals("레이트 리밋을 걸면 랙이 쌓이지 않는다", 0, throttled.finalLag());
        evidence.expect("대신 초과분은 거절된다", throttled.rejected() > 0);

        evidence.note("컨슈머는 파티션 수보다 늘릴 수 없다 — 파티션 수는 미래 병렬도를 내다보고 설계 시점에 정해야 하며 나중에 늘리기 어렵다.");
        evidence.note("API 는 최소 검증만 하고 202 Accepted + 접수 ID 를 즉시 반환해 동기 경로를 최단으로 만든다.");
        evidence.note("랙 자체가 오토스케일 트리거로 가장 적절한 지표다(Q45: CPU 가 아니라 컨슈머 랙).");
    }

    private Simulation simulate(int arrivalsPerTick, int arrivalTicks, int capacityPerTick, int admissionLimit) {
        int lag = 0;
        int maxLag = 0;
        int rejected = 0;
        int ticksToDrain = -1;

        for (int tick = 0; tick < TICKS + 50; tick++) {
            if (tick < arrivalTicks) {
                int arriving = arrivalsPerTick;
                int admitted = Math.min(arriving, admissionLimit);
                rejected += arriving - admitted;
                lag += admitted;
            }
            lag = Math.max(0, lag - capacityPerTick);
            maxLag = Math.max(maxLag, lag);
            if (lag == 0 && tick >= arrivalTicks && ticksToDrain < 0) {
                ticksToDrain = tick;
            }
            if (tick == TICKS - 1) {
                return new Simulation(lag, maxLag, rejected, ticksToDrain);
            }
        }
        return new Simulation(lag, maxLag, rejected, ticksToDrain);
    }

    private record Simulation(int finalLag, int maxLag, int rejected, int ticksToDrain) {
    }
}
