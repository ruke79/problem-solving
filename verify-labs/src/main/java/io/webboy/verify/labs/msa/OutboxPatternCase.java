package io.webboy.verify.labs.msa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.jpa.Note;
import io.webboy.verify.labs.jpa.NoteRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Q31 — DB 갱신과 이벤트 발행의 원자성. */
@Component
public class OutboxPatternCase extends VerificationCase {

    private final NoteRepository orders;
    private final OutboxEventRepository outbox;
    private final FlakyBroker broker;
    private final TransactionTemplate tx;

    public OutboxPatternCase(NoteRepository orders, OutboxEventRepository outbox,
                             FlakyBroker broker, TransactionTemplate tx) {
        this.orders = orders;
        this.outbox = outbox;
        this.broker = broker;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "MSA-01";
    }

    @Override
    public String category() {
        return "msa";
    }

    @Override
    public String question() {
        return "모놀리스에서 MSA 로 갈 때 가장 큰 과제는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "DB 커밋과 이벤트 발행은 원자적이지 않다. 커밋 직후 발행에 실패하면 이벤트가 영구 유실되며, Outbox 패턴은 이벤트를 같은 트랜잭션에 써서 이를 막는다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        broker.reset();

        // (A) Outbox 없음: 커밋 → 발행 순서. 발행 시점에 브로커가 죽으면 이벤트는 그대로 사라진다.
        String naiveOrder = "order-naive-" + tag;
        tx.executeWithoutResult(status -> orders.save(new Note(naiveOrder)));
        broker.down();
        String naivePublish;
        try {
            broker.publish(naiveOrder);
            naivePublish = "발행 성공";
        } catch (IllegalStateException e) {
            naivePublish = "발행 실패(이벤트 유실)";
        }
        long naiveOrderRows = orders.countByText(naiveOrder);
        long naiveRecoverable = outbox.findByPublishedFalse().stream()
                .filter(e -> e.getAggregateId().equals(naiveOrder)).count();

        // (B) Outbox: 주문과 이벤트를 같은 트랜잭션에 쓴다. 발행 실패해도 재시도할 근거가 DB 에 남는다.
        String outboxOrder = "order-outbox-" + tag;
        tx.executeWithoutResult(status -> {
            orders.save(new Note(outboxOrder));
            outbox.save(new OutboxEvent(outboxOrder, outboxOrder));
        });
        long pendingBeforeRelay = outbox.findByPublishedFalse().stream()
                .filter(e -> e.getAggregateId().equals(outboxOrder)).count();

        relay();                       // 브로커가 죽어 있으므로 아무것도 못 보낸다
        long pendingAfterFailedRelay = outbox.findByPublishedFalse().stream()
                .filter(e -> e.getAggregateId().equals(outboxOrder)).count();

        broker.up();
        relay();                       // 복구 후 재시도
        long pendingAfterRecovery = outbox.findByPublishedFalse().stream()
                .filter(e -> e.getAggregateId().equals(outboxOrder)).count();

        List<String> delivered = broker.delivered();

        evidence.fact("[Outbox 없음] 주문 저장 행 수", naiveOrderRows);
        evidence.fact("[Outbox 없음] 발행 결과", naivePublish);
        evidence.fact("[Outbox 없음] 재시도할 근거가 DB 에 남았는가", naiveRecoverable > 0);
        evidence.fact("[Outbox] 커밋 직후 미발행 이벤트 수", pendingBeforeRelay);
        evidence.fact("[Outbox] 브로커 다운 중 릴레이 후 미발행 수", pendingAfterFailedRelay);
        evidence.fact("[Outbox] 브로커 복구 후 릴레이 후 미발행 수", pendingAfterRecovery);
        evidence.fact("최종 전달된 이벤트", delivered);

        evidence.expectEquals("Outbox 없이도 주문 자체는 커밋된다", 1L, naiveOrderRows);
        evidence.expectEquals("Outbox 없으면 발행 실패 시 복구 근거가 남지 않는다", 0L, naiveRecoverable);
        evidence.expect("Outbox 없는 주문의 이벤트는 끝내 전달되지 않는다", !delivered.contains(naiveOrder));
        evidence.expectEquals("Outbox 는 같은 트랜잭션에 이벤트를 남긴다", 1L, pendingBeforeRelay);
        evidence.expectEquals("브로커가 죽어 있으면 미발행 상태로 보존된다", 1L, pendingAfterFailedRelay);
        evidence.expectEquals("복구 후 릴레이가 밀린 이벤트를 전달한다", 0L, pendingAfterRecovery);
        evidence.expect("Outbox 이벤트는 최종적으로 전달된다", delivered.contains(outboxOrder));

        evidence.note("Outbox 는 '최소 1회' 전달이다 — 릴레이가 보낸 뒤 표시 전에 죽으면 중복 발행되므로 컨슈머 쪽 멱등성(MSA-02, RES-01)이 짝이다.");
        evidence.note("실무 릴레이 구현은 폴링 대신 CDC(Debezium)로 트랜잭션 로그를 읽는 방식이 소스 DB 부하가 없다.");

        broker.reset();
    }

    private void relay() {
        tx.executeWithoutResult(status -> {
            for (OutboxEvent event : outbox.findByPublishedFalse()) {
                try {
                    broker.publish(event.getPayload());
                    event.markPublished();
                } catch (IllegalStateException e) {
                    // 다음 릴레이에서 다시 시도한다
                }
            }
        });
    }
}
