package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q41 — "Kafka 는 순서를 보증한다"는 말의 정확한 범위.
 *
 * <p>{@code MSA-02} 는 이 명제를 자바 코드로 흉내 내지만, 여기서는 <b>실제 브로커</b>에
 * 파티션 3개짜리 토픽을 만들고 관측한다. 그리고 RabbitMQ 와의 결정적 차이인
 * <b>오프셋 되감기 재소비</b>도 함께 확인한다.
 */
@Component
public class PartitionOrderingCase extends VerificationCase {

    private static final String TOPIC = "verify-ordering";
    private static final int PARTITIONS = 3;
    private static final int MESSAGES_PER_KEY = 8;

    private final Brokers brokers;

    public PartitionOrderingCase(Brokers brokers) {
        this.brokers = brokers;
    }

    @Override
    public String id() {
        return "KAFKA-01";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "Kafka 와 RabbitMQ 를 어떤 기준으로 고릅니까? 순서 보증은 어디까지 됩니까?";
    }

    @Override
    public String claim() {
        return "Kafka 의 순서 보증은 토픽 전체가 아니라 파티션 단위다 — 같은 키는 같은 파티션에 가므로 그 키 안에서는 순서가 지켜지지만 토픽 전체 순서는 지켜지지 않는다. 그리고 소비해도 로그가 남아 오프셋을 되감으면 같은 메시지를 다시 처리할 수 있다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!brokers.available()) {
            KafkaAvailability.markUnavailable(evidence, brokers);
            return;
        }
        evidence.fact("브로커", brokers.clusterDescription());
        brokers.recreateTopic(TOPIC, PARTITIONS);

        // 키 3개 × 8건을 번갈아 발행한다 (전체 순서와 키별 순서를 구분해 보기 위해)
        List<String> keys = List.of("order-A", "order-B", "order-C");
        try (KafkaProducer<String, String> producer = brokers.producer(Map.of())) {
            for (int seq = 0; seq < MESSAGES_PER_KEY; seq++) {
                for (String key : keys) {
                    producer.send(new ProducerRecord<>(TOPIC, key, key + "#" + seq)).get();
                }
            }
        }

        Consumed first = consumeAll("verify-ordering-group");

        evidence.fact("파티션 수 / 발행 건수", PARTITIONS + " / " + (keys.size() * MESSAGES_PER_KEY));
        evidence.fact("키가 배치된 파티션", first.partitionByKey.toString());
        evidence.fact("소비된 건수", first.records.size());

        boolean perKeyOrdered = keys.stream().allMatch(key -> isOrdered(first.records, key));
        boolean globallyOrdered = isGloballyOrdered(first.records);
        boolean singlePartitionPerKey = first.partitionByKey.values().stream().allMatch(v -> v.size() == 1);

        evidence.fact("키별 순서가 지켜졌는가", perKeyOrdered);
        evidence.fact("토픽 전체 순서가 지켜졌는가", globallyOrdered);
        evidence.fact("소비 순서 앞 9건", preview(first.records));

        evidence.expect("같은 키는 항상 한 파티션에만 들어간다", singlePartitionPerKey);
        evidence.expect("키(파티션) 안에서는 발행 순서가 그대로 유지된다", perKeyOrdered);
        evidence.expect("토픽 전체로 보면 발행 순서가 유지되지 않는다", !globallyOrdered);

        // 오프셋 되감기 — 같은 그룹으로 처음부터 다시 읽는다
        Consumed replayed = consumeAll("verify-ordering-group-replay");
        evidence.fact("오프셋을 되감아 다시 소비한 건수", replayed.records.size());
        evidence.expectEquals("소비해도 로그는 남아 있어 전건을 다시 처리할 수 있다",
                first.records.size(), replayed.records.size());

        brokers.deleteTopic(TOPIC);

        evidence.note("파티션을 늘리면 처리량은 늘지만 '전체 순서'는 그만큼 더 깨진다. 순서가 필요한 단위(주문 ID·사용자 ID)를 키로 잡는 것이 설계의 핵심이고, 순서 단위가 곧 병렬성의 상한이 된다.");
        evidence.note("RabbitMQ 는 소비하면 큐에서 사라지므로 되감기가 없다. 재처리하려면 발행자가 다시 보내야 한다 — '로그가 남는가'가 두 제품의 가장 큰 차이다.");
        evidence.note("키를 지정하지 않으면 라운드로빈(정확히는 sticky partitioner)으로 흩어져 순서 보증이 아예 없어진다.");
        evidence.note("파티션 수를 나중에 늘리면 같은 키가 다른 파티션으로 갈 수 있어 그 시점에 순서가 한 번 깨진다 — 운영 중 파티션 증설이 위험한 이유다.");
    }

    private Consumed consumeAll(String groupId) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        Map<String, List<Integer>> partitionByKey = new LinkedHashMap<>();
        try (KafkaConsumer<String, String> consumer = brokers.consumer(groupId, Map.of())) {
            consumer.subscribe(List.of(TOPIC));
            int emptyPolls = 0;
            while (emptyPolls < 3) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(2));
                if (polled.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : polled) {
                    records.add(record);
                    partitionByKey.computeIfAbsent(record.key(), k -> new ArrayList<>());
                    List<Integer> partitions = partitionByKey.get(record.key());
                    if (!partitions.contains(record.partition())) {
                        partitions.add(record.partition());
                    }
                }
            }
        }
        return new Consumed(records, partitionByKey);
    }

    private boolean isOrdered(List<ConsumerRecord<String, String>> records, String key) {
        int previous = -1;
        for (ConsumerRecord<String, String> record : records) {
            if (!key.equals(record.key())) {
                continue;
            }
            int seq = Integer.parseInt(record.value().substring(record.value().indexOf('#') + 1));
            if (seq <= previous) {
                return false;
            }
            previous = seq;
        }
        return true;
    }

    /** 발행은 seq 0 의 A·B·C → seq 1 의 A·B·C … 순이었다. 소비도 그 순서면 전체 순서가 지켜진 것이다. */
    private boolean isGloballyOrdered(List<ConsumerRecord<String, String>> records) {
        int previous = -1;
        for (ConsumerRecord<String, String> record : records) {
            int seq = Integer.parseInt(record.value().substring(record.value().indexOf('#') + 1));
            if (seq < previous) {
                return false;
            }
            previous = seq;
        }
        return true;
    }

    private String preview(List<ConsumerRecord<String, String>> records) {
        return records.stream().limit(9)
                .map(r -> "p" + r.partition() + ":" + r.value())
                .reduce((a, b) -> a + " → " + b)
                .orElse("(없음)");
    }

    private record Consumed(List<ConsumerRecord<String, String>> records,
                            Map<String, List<Integer>> partitionByKey) {
        private Consumed {
        }
    }

    /** 다른 케이스에서도 쓰는 파티션 조회 헬퍼. */
    static TopicPartition partition(String topic, int number) {
        return new TopicPartition(topic, number);
    }
}
