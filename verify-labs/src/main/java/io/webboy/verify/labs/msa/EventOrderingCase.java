package io.webboy.verify.labs.msa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.List;

/** Q37 · Q41 — 결과적 일관성 구현에 반드시 들어가야 하는 순서·중복 대처. */
@Component
public class EventOrderingCase extends VerificationCase {

    private record StockEvent(String itemId, long version, int quantity) {
    }

    @Override
    public String id() {
        return "MSA-02";
    }

    @Override
    public String category() {
        return "msa";
    }

    @Override
    public String question() {
        return "결과적 일관성을 구현할 때 반드시 넣어야 하는 것은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "이벤트는 재전송으로 중복되고 파티션을 넘으면 순서가 역전된다. 버전(또는 타임스탬프)으로 오래된 갱신을 걸러야 최종 상태가 정확해진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        // 실제 발생 순서는 v1 → v2 → v3 이지만, 도착은 뒤섞이고 v2 가 중복된다
        List<StockEvent> arrived = List.of(
                new StockEvent("item-1", 2, 80),
                new StockEvent("item-1", 1, 100),
                new StockEvent("item-1", 3, 50),
                new StockEvent("item-1", 2, 80),
                new StockEvent("item-1", 1, 100));

        int lastWriteWins = applyLastWriteWins(arrived);
        Applied versionGuarded = applyWithVersionGuard(arrived);

        evidence.fact("도착 순서(version)", arrived.stream().map(StockEvent::version).toList());
        evidence.fact("진짜 최신 버전", 3);
        evidence.fact("무조건 덮어쓰기 결과 수량", lastWriteWins);
        evidence.fact("버전 가드 결과 수량", versionGuarded.quantity());
        evidence.fact("버전 가드가 무시한 이벤트 수", versionGuarded.ignored());

        evidence.expectEquals("무조건 덮어쓰면 마지막에 도착한 오래된 이벤트가 이긴다", 100, lastWriteWins);
        evidence.expectEquals("버전 가드는 최신 상태를 유지한다", 50, versionGuarded.quantity());
        evidence.expectEquals("중복·역전 이벤트는 모두 무시된다", 3, versionGuarded.ignored());

        evidence.note("Kafka 의 순서 보증은 파티션 단위다. 주문 ID 를 키로 하면 그 주문 내 순서는 보증되지만 전체 순서는 보증되지 않는다.");
        evidence.note("버전 가드는 멱등성도 함께 제공한다 — 같은 이벤트가 두 번 와도 두 번째는 조건에서 걸러진다.");
        evidence.note("어느 정도의 지연까지 업무가 성립하는지는 도메인별로 정해야 한다. 재고 표시는 수 초 허용, 결제 잔고는 불가.");
    }

    private int applyLastWriteWins(List<StockEvent> events) {
        int quantity = 0;
        for (StockEvent event : events) {
            quantity = event.quantity();
        }
        return quantity;
    }

    private Applied applyWithVersionGuard(List<StockEvent> events) {
        long currentVersion = 0;
        int quantity = 0;
        int ignored = 0;
        for (StockEvent event : events) {
            if (event.version() > currentVersion) {
                currentVersion = event.version();
                quantity = event.quantity();
            } else {
                ignored++;
            }
        }
        return new Applied(quantity, ignored);
    }

    private record Applied(int quantity, int ignored) {
    }
}
