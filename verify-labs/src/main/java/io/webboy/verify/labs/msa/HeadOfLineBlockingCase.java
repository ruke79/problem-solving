package io.webboy.verify.labs.msa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Q43 — 파티션 내 재시도의 헤드 오브 라인 블로킹과, 논블로킹 재시도의 대가(순서 붕괴). */
@Component
public class HeadOfLineBlockingCase extends VerificationCase {

    private static final List<String> PARTITION =
            List.of("m1", "m2", "poison", "m4", "m5", "m6");
    private static final int MAX_ATTEMPTS = 3;

    @Override
    public String id() {
        return "MSA-04";
    }

    @Override
    public String category() {
        return "msa";
    }

    @Override
    public String question() {
        return "Kafka 컨슈머에서 처리에 실패한 메시지는 어떻게 재처리합니까?";
    }

    @Override
    public String claim() {
        return "같은 파티션에서 재시도를 반복하면 뒤 메시지가 전부 막힌다. 재시도 전용 토픽으로 옮기면 막히지 않지만 그 메시지의 순서는 깨진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        Blocking blocking = consumeWithInPlaceRetry();
        NonBlocking nonBlocking = consumeWithRetryTopic();

        evidence.fact("파티션 메시지", PARTITION);
        evidence.fact("[파티션 내 재시도] 처리 완료 메시지", blocking.processed());
        evidence.fact("[파티션 내 재시도] 소비된 재시도 횟수", blocking.attempts());
        evidence.fact("[파티션 내 재시도] 뒤에서 막힌 메시지 수", blocking.blocked());
        evidence.fact("[재시도 토픽] 처리 순서", nonBlocking.processedOrder());
        evidence.fact("[재시도 토픽] DLQ 로 간 메시지", nonBlocking.deadLetters());

        evidence.expectEquals("불량 메시지 하나가 뒤의 모든 메시지를 막는다", 3, blocking.blocked());
        evidence.expectEquals("막히기 전까지 처리된 것은 앞의 2건뿐이다", 2, blocking.processed().size());
        evidence.expectEquals("재시도 토픽 방식은 정상 메시지를 모두 처리한다", 5, nonBlocking.processedOrder().size());
        evidence.expect("대신 원래 파티션 순서는 유지되지 않는다",
                !nonBlocking.processedOrder().equals(List.of("m1", "m2", "poison", "m4", "m5", "m6")));
        evidence.expectEquals("계속 실패하는 메시지는 DLQ 로 격리된다", List.of("poison"), nonBlocking.deadLetters());

        evidence.note("순서가 업무 요건인 경우(같은 계좌의 입출금 등)에는 이 방식을 쓸 수 없다 — 해당 키의 처리를 일시 정지시키거나 순서 비의존 설계로 바꾼다.");
        evidence.note("에러 분류가 중요하다. 상대 타임아웃 같은 일시적 에러는 재시도로 회복되지만, 필수 항목이 빠진 데이터는 몇 번을 해도 실패하므로 즉시 DLQ 가 낭비가 없다.");
        evidence.note("DLQ 는 넣고 끝이면 의미가 없다 — 건수 메트릭·알림·재투입 수단까지 있어야 완성이다.");
    }

    private Blocking consumeWithInPlaceRetry() {
        List<String> processed = new ArrayList<>();
        int attempts = 0;
        for (int i = 0; i < PARTITION.size(); i++) {
            String message = PARTITION.get(i);
            boolean ok = false;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                attempts++;
                if (handle(message)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return new Blocking(processed, attempts, PARTITION.size() - i - 1);
            }
            processed.add(message);
        }
        return new Blocking(processed, attempts, 0);
    }

    private NonBlocking consumeWithRetryTopic() {
        List<String> processed = new ArrayList<>();
        List<String> retryTopic = new ArrayList<>();
        List<String> deadLetters = new ArrayList<>();

        for (String message : PARTITION) {
            if (handle(message)) {
                processed.add(message);
            } else {
                retryTopic.add(message);   // 원 토픽에서는 처리 완료로 커밋하고 옆으로 뺀다
            }
        }
        for (String message : retryTopic) {
            boolean ok = false;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                if (handle(message)) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                processed.add(message);
            } else {
                deadLetters.add(message);
            }
        }
        return new NonBlocking(processed, deadLetters);
    }

    private boolean handle(String message) {
        return !"poison".equals(message);
    }

    private record Blocking(List<String> processed, int attempts, int blocked) {
    }

    private record NonBlocking(List<String> processedOrder, List<String> deadLetters) {
    }
}
