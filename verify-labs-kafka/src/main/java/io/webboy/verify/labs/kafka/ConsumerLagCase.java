package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Q42 · Q45 — 컨슈머 랙은 "도착률 − 처리율"의 적분이다.
 *
 * <p>{@code MSA-03} 은 이 관계를 수식으로 모사한다. 여기서는 실제 브로커에 쌓아 두고
 * {@code Admin} 으로 랙을 조회한다 — 운영에서 스케일 트리거로 쓰는 바로 그 지표다.
 */
@Component
public class ConsumerLagCase extends VerificationCase {

    private static final String TOPIC = "verify-lag";
    private static final String GROUP = "verify-lag-group";
    private static final int TOTAL = 500;
    private static final int FIRST_BATCH = 100;

    private final Brokers brokers;

    public ConsumerLagCase(Brokers brokers) {
        this.brokers = brokers;
    }

    @Override
    public String id() {
        return "KAFKA-02";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "트래픽 급증을 Kafka 로 흡수한다고 했는데, 무엇을 보고 대응합니까?";
    }

    @Override
    public String claim() {
        return "큐는 순간 피크를 평준화할 뿐이고, 처리율이 도착률에 못 미치면 컨슈머 랙이 그만큼 쌓인다. 랙은 브로커에 물어보면 정확히 나오므로 오토스케일 트리거로 쓸 수 있고, 처리율이 따라잡으면 0으로 돌아온다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!brokers.available()) {
            KafkaAvailability.markUnavailable(evidence, brokers);
            return;
        }
        brokers.recreateTopic(TOPIC, 1);

        try (KafkaProducer<String, String> producer = brokers.producer(Map.of())) {
            for (int i = 0; i < TOTAL; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "k" + (i % 5), "event-" + i));
            }
            producer.flush();
        }

        long lagAfterProduce = lag();

        int consumedFirst = consumeAndCommit(FIRST_BATCH);
        long lagAfterPartialConsume = lag();

        int consumedRest = consumeAndCommit(TOTAL);
        long lagAfterFullConsume = lag();

        evidence.fact("발행 건수", TOTAL);
        evidence.fact("발행 직후 랙", lagAfterProduce);
        evidence.fact("일부(" + consumedFirst + "건) 처리 후 랙", lagAfterPartialConsume);
        evidence.fact("나머지(" + consumedRest + "건) 처리 후 랙", lagAfterFullConsume);

        evidence.expectEquals("아무도 소비하지 않으면 랙은 발행 건수 그대로다", TOTAL, lagAfterProduce);
        evidence.expectEquals("처리한 만큼만 랙이 줄어든다",
                (long) (TOTAL - consumedFirst), lagAfterPartialConsume);
        evidence.expectEquals("처리율이 따라잡으면 랙은 0이 된다", 0L, lagAfterFullConsume);

        brokers.deleteTopic(TOPIC);

        evidence.note("랙은 '지금 몇 건 밀렸는가'이지 '얼마나 늦었는가'가 아니다. 처리 시간이 긴 컨슈머라면 랙 100건이 10분 지연일 수도 있으므로, 스케일 임계치는 건수가 아니라 '랙 ÷ 처리율 = 예상 지연'으로 잡는 편이 정확하다.");
        evidence.note("컨슈머를 늘려도 파티션 수를 넘으면 노는 컨슈머가 생긴다 — 오토스케일 상한은 파티션 수다(KAFKA-05).");
        evidence.note("도착률이 처리율을 '지속적으로' 넘으면 랙은 무한히 증가한다. 큐는 시간을 벌어줄 뿐이라 앞단 레이트 리밋이 함께 필요하다는 것이 MSA-03 의 결론과 같다.");
    }

    /** 브로커가 알고 있는 랙 = (파티션 끝 오프셋) − (그룹이 커밋한 오프셋). */
    private long lag() throws Exception {
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        try (Admin admin = brokers.admin()) {
            ListOffsetsResult endOffsets = admin.listOffsets(Map.of(partition, OffsetSpec.latest()));
            long end = endOffsets.partitionResult(partition).get(15, TimeUnit.SECONDS).offset();

            var committed = admin.listConsumerGroupOffsets(brokers.groupId(GROUP))
                    .partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS);
            long current = committed.containsKey(partition) && committed.get(partition) != null
                    ? committed.get(partition).offset()
                    : 0L;
            return end - current;
        }
    }

    /**
     * 정확히 max 건까지만 처리하고 커밋한다.
     *
     * <p>커밋은 폴 단위로만 할 수 있으므로 {@code max.poll.records} 를 작게 고정해
     * "센 건수"와 "커밋한 오프셋"이 어긋나지 않게 한다 — 그래야 랙 숫자를 그대로 믿을 수 있다.
     */
    private int consumeAndCommit(int max) {
        int consumed = 0;
        Map<String, Object> overrides = Map.of(
                org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        try (KafkaConsumer<String, String> consumer = brokers.consumer(GROUP, overrides)) {
            consumer.subscribe(List.of(TOPIC));
            int emptyPolls = 0;
            while (consumed < max && emptyPolls < 3) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                if (consumed + records.count() > max) {
                    break;   // 예산을 넘는 배치는 손대지 않는다(커밋 단위가 폴 단위이므로)
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> ignored : records) {
                    consumed++;
                }
                consumer.commitSync();
            }
        }
        return consumed;
    }
}
