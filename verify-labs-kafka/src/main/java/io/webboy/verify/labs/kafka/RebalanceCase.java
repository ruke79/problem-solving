package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Q45 · Q110 — 컨슈머를 늘리면 정말 처리량이 늘어나는가.
 *
 * <p>파티션 2개짜리 토픽에 컨슈머를 하나씩 붙여 <b>실제 파티션 재할당</b>을 관측한다.
 * 컨슈머가 파티션 수를 넘으면 어떻게 되는지도 함께 본다 — 오토스케일 상한이 여기서 정해진다.
 */
@Component
public class RebalanceCase extends VerificationCase {

    private static final String TOPIC = "verify-rebalance";
    private static final int PARTITIONS = 2;
    private static final String GROUP = "verify-rebalance-group";

    private final Brokers brokers;

    public RebalanceCase(Brokers brokers) {
        this.brokers = brokers;
    }

    @Override
    public String id() {
        return "KAFKA-05";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "컨슈머를 늘리면 처리량이 그만큼 늘어납니까? 오토스케일은 어디까지 유효합니까?";
    }

    @Override
    public String claim() {
        return "한 파티션은 그룹 안에서 컨슈머 하나에만 할당된다. 컨슈머를 늘리면 파티션이 재분배되지만 파티션 수를 넘는 컨슈머는 아무 파티션도 못 받고 논다 — 오토스케일 상한은 파티션 수이며, 합류·이탈 때마다 리밸런스로 처리가 잠시 멈춘다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 리밸런스 완료 시점은 브로커 타이밍에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!brokers.available()) {
            KafkaAvailability.markUnavailable(evidence, brokers);
            return;
        }
        brokers.recreateTopic(TOPIC, PARTITIONS);

        List<String> revoked = new CopyOnWriteArrayList<>();

        try (KafkaConsumer<String, String> first = brokers.consumer(GROUP, Map.of());
             KafkaConsumer<String, String> second = brokers.consumer(GROUP, Map.of());
             KafkaConsumer<String, String> third = brokers.consumer(GROUP, Map.of())) {

            first.subscribe(List.of(TOPIC), listener(revoked, "consumer-1"));
            settle(List.of(first));
            Set<TopicPartition> aloneAssignment = Set.copyOf(first.assignment());

            // 두 번째 컨슈머 합류 → 리밸런스.
            // 리밸런스는 그룹 멤버가 '모두' poll 로 재합류해야 끝나므로 번갈아 poll 해야 한다 —
            // 한쪽만 돌리면 다른 쪽의 할당이 낡은 채로 보인다.
            second.subscribe(List.of(TOPIC));
            settle(List.of(first, second));
            Set<TopicPartition> firstAfterJoin = Set.copyOf(first.assignment());
            Set<TopicPartition> secondAssignment = Set.copyOf(second.assignment());

            // 세 번째 컨슈머 합류 → 파티션이 모자란다
            third.subscribe(List.of(TOPIC));
            settle(List.of(first, second, third));
            Set<TopicPartition> thirdAssignment = Set.copyOf(third.assignment());

            evidence.fact("파티션 수", PARTITIONS);
            evidence.fact("컨슈머 1대일 때 할당", describe(aloneAssignment));
            evidence.fact("2대가 된 뒤 consumer-1 할당", describe(firstAfterJoin));
            evidence.fact("2대가 된 뒤 consumer-2 할당", describe(secondAssignment));
            evidence.fact("3대째 consumer-3 할당", describe(thirdAssignment));
            evidence.fact("리밸런스로 회수된 파티션 기록", revoked.isEmpty() ? "(없음)" : String.join(", ", revoked));

            evidence.expectEquals("컨슈머가 1대면 모든 파티션을 혼자 받는다", PARTITIONS, aloneAssignment.size());
            evidence.expect("컨슈머가 늘면 파티션이 나눠진다",
                    firstAfterJoin.size() + secondAssignment.size() == PARTITIONS
                            && !secondAssignment.isEmpty());
            evidence.expectEquals("파티션 수를 넘는 컨슈머는 아무것도 받지 못한다", 0, thirdAssignment.size());
            evidence.expectFlaky("리밸런스 과정에서 기존 컨슈머의 파티션이 한 번 회수된다", !revoked.isEmpty());
        }

        brokers.deleteTopic(TOPIC);

        evidence.note("파티션 수가 처리 병렬성의 상한이다. 컨슈머만 늘리는 오토스케일은 파티션 수에서 멈추므로, 스케일 계획은 파티션 설계와 함께 세워야 한다(KAFKA-02 의 랙 지표와 짝).");
        evidence.note("리밸런스 동안에는 해당 파티션의 처리가 멈춘다(stop-the-world). 배포로 컨슈머가 순차 재기동되면 그때마다 리밸런스가 일어나므로, 잦은 배포와 짧은 session.timeout.ms 조합은 처리 정지를 자주 만든다.");
        evidence.note("Kafka 2.4+ 의 협조적 리밸런스(CooperativeStickyAssignor)를 쓰면 전 파티션을 회수했다 재분배하지 않고 필요한 것만 옮긴다 — 기본 전략(RangeAssignor)보다 정지 시간이 짧다.");
        evidence.note("파티션을 넉넉히 잡으면 스케일 여지가 생기지만, 파티션마다 파일 핸들·메모리·리더 선출 비용이 들고 나중에 줄일 수 없다.");
    }

    private ConsumerRebalanceListener listener(List<String> revoked, String name) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    revoked.add(name + ": " + describe(Set.copyOf(partitions)));
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // 관측만 한다
            }
        };
    }

    /** 그룹 전원을 번갈아 poll 해서 리밸런스가 끝나고 할당이 안정될 때까지 기다린다. */
    private void settle(List<KafkaConsumer<String, String>> consumers) {
        int stableRounds = 0;
        String previous = "";
        for (int round = 0; round < 30 && stableRounds < 3; round++) {
            StringBuilder snapshot = new StringBuilder();
            for (KafkaConsumer<String, String> consumer : consumers) {
                consumer.poll(Duration.ofMillis(300));
                snapshot.append(describe(Set.copyOf(consumer.assignment()))).append(" / ");
            }
            String current = snapshot.toString();
            stableRounds = current.equals(previous) ? stableRounds + 1 : 0;
            previous = current;
        }
    }

    private static String describe(Set<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return "(없음 — 놀고 있음)";
        }
        return partitions.stream()
                .map(p -> "p" + p.partition())
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
