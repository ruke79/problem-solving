package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Q123 · Q156 — 순서 보증을 위해 키를 잡으면 부하가 한쪽으로 몰린다.
 *
 * <p>{@code KAFKA-01} 이 "같은 키는 같은 파티션"을 확인했다면, 여기서는 그 대가를 잰다.
 * 대형 고객 하나가 트래픽의 대부분을 차지하면 그 고객의 파티션만 적체되고 나머지는 논다.
 * 해결책인 <b>솔트 부여</b>는 부하를 흩뜨리지만 <b>그 고객 안의 순서를 잃는다</b> — 공짜가 아니다.
 */
@Component
public class HotPartitionCase extends VerificationCase {

    private static final String SKEWED = "verify-hot-skewed";
    private static final String SALTED = "verify-hot-salted";
    private static final int PARTITIONS = 4;
    private static final int MESSAGES = 400;
    private static final int SALT_BUCKETS = 4;

    private final Brokers brokers;

    public HotPartitionCase(Brokers brokers) {
        this.brokers = brokers;
    }

    @Override
    public String id() {
        return "KAFKA-06";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "파티션 키를 고객 ID 로 잡았더니 특정 고객 때문에 한 파티션만 밀립니다. 어떻게 합니까?";
    }

    @Override
    public String claim() {
        return "키가 편중되면 한 파티션에만 메시지가 쌓여 컨슈머를 늘려도 그 파티션은 한 대가 처리한다. 키에 솔트를 붙이면 고르게 분산되지만 같은 고객의 메시지가 여러 파티션으로 흩어져 순서 보증을 잃는다 — 순서와 분산 중 하나를 고르는 문제다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!brokers.available()) {
            KafkaAvailability.markUnavailable(evidence, brokers);
            return;
        }
        brokers.recreateTopic(SKEWED, PARTITIONS);
        brokers.recreateTopic(SALTED, PARTITIONS);

        // 트래픽의 90% 가 대형 고객 한 명에게서 온다
        try (KafkaProducer<String, String> producer = brokers.producer(Map.of())) {
            for (int i = 0; i < MESSAGES; i++) {
                String customer = (i % 10 == 0) ? "small-" + i : "big-customer";
                producer.send(new ProducerRecord<>(SKEWED, customer, "event-" + i));
                producer.send(new ProducerRecord<>(SALTED, salted(customer), "event-" + i));
            }
            producer.flush();
        }

        Map<Integer, Long> skewed = endOffsets(SKEWED);
        Map<Integer, Long> salted = endOffsets(SALTED);

        long skewedMax = skewed.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long skewedMin = skewed.values().stream().mapToLong(Long::longValue).min().orElse(0);
        long saltedMax = salted.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long saltedMin = salted.values().stream().mapToLong(Long::longValue).min().orElse(0);
        int saltedPartitionsForBigCustomer = partitionsUsedByBigCustomer();

        evidence.fact("파티션 수 / 메시지 수", PARTITIONS + " / " + MESSAGES);
        evidence.fact("트래픽 구성", "90% 가 big-customer 한 명");
        evidence.fact("[키=고객ID] 파티션별 메시지 수", skewed.toString());
        evidence.fact("[키=고객ID] 최다 파티션 / 최소 파티션", skewedMax + " / " + skewedMin);
        evidence.fact("[키=고객ID+솔트] 파티션별 메시지 수", salted.toString());
        evidence.fact("[키=고객ID+솔트] 최다 파티션 / 최소 파티션", saltedMax + " / " + saltedMin);
        evidence.fact("솔트 적용 시 big-customer 가 퍼진 파티션 수", saltedPartitionsForBigCustomer);

        evidence.expect("키가 편중되면 한 파티션이 압도적으로 많이 받는다", skewedMax > MESSAGES * 0.8);
        evidence.expect("나머지 파티션은 놀거나 거의 비어 있다", skewedMin < MESSAGES * 0.1);
        evidence.expect("솔트를 붙이면 파티션 간 편차가 크게 줄어든다",
                (saltedMax - saltedMin) < (skewedMax - skewedMin));
        evidence.expect("대신 같은 고객의 메시지가 여러 파티션으로 흩어져 순서 보증을 잃는다",
                saltedPartitionsForBigCustomer > 1);

        brokers.deleteTopic(SKEWED);
        brokers.deleteTopic(SALTED);

        evidence.note("컨슈머를 늘려도 핫 파티션은 해결되지 않는다 — 한 파티션은 그룹 안에서 한 컨슈머만 읽기 때문이다(KAFKA-05). 파티션 편중은 스케일아웃으로 못 푸는 종류의 병목이다.");
        evidence.note("솔트를 붙이기 전에 '순서가 정말 고객 단위로 필요한가'를 먼저 묻는다. '고객+주문' 처럼 더 세밀한 단위로도 요건이 충족되면, 순서를 지키면서 자연히 분산된다.");
        evidence.note("그래도 대형 고객 하나가 파티션 처리 능력을 넘으면 그 고객만 전용 토픽으로 분리하는 선택지가 있다 — 벌크헤드(RES-09)의 메시징 판이다.");
        evidence.note("이 문제는 DB-05 의 UUID PK 이야기와 같은 뿌리다. '키의 분포가 물리적 배치를 정하고 그것이 성능을 좌우한다'는 원리가 인덱스에서는 페이지 분할로, Kafka 에서는 핫 파티션으로 나타난다.");
    }

    private String salted(String customer) {
        return customer + "#" + ThreadLocalRandom.current().nextInt(SALT_BUCKETS);
    }

    /** 솔트 버킷이 실제로 서로 다른 파티션에 배치되는지 확인한다. */
    private int partitionsUsedByBigCustomer() throws Exception {
        try (Admin admin = brokers.admin()) {
            var description = admin.describeTopics(List.of(SALTED)).allTopicNames().get(15, TimeUnit.SECONDS);
            int partitionCount = description.get(SALTED).partitions().size();
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (int bucket = 0; bucket < SALT_BUCKETS; bucket++) {
                String key = "big-customer#" + bucket;
                used.add(Math.abs(murmurLike(key)) % partitionCount);
            }
            return used.size();
        }
    }

    /** 파티셔너를 그대로 흉내내지 않고 '서로 다른 키가 흩어진다'만 본다. */
    private int murmurLike(String key) {
        return org.apache.kafka.common.utils.Utils.murmur2(key.getBytes());
    }

    private Map<Integer, Long> endOffsets(String topic) throws Exception {
        try (Admin admin = brokers.admin()) {
            var description = admin.describeTopics(List.of(topic)).allTopicNames().get(15, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            description.get(topic).partitions()
                    .forEach(p -> request.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest()));

            var offsets = admin.listOffsets(request).all().get(15, TimeUnit.SECONDS);
            Map<Integer, Long> result = new LinkedHashMap<>();
            offsets.forEach((partition, info) -> result.put(partition.partition(), info.offset()));
            return new java.util.TreeMap<>(result);
        }
    }
}
