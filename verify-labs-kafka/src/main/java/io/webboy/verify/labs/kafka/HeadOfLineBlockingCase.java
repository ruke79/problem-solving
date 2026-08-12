package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Q43 — 실패한 메시지를 같은 파티션에서 재시도하면 뒤가 전부 막힌다.
 *
 * <p>{@code MSA-04} 는 이 현상을 인메모리 큐로 모사한다. 여기서는 실제 파티션 하나에
 * 독이 든 메시지를 넣고, 두 전략의 결과를 비교한다.
 */
@Component
public class HeadOfLineBlockingCase extends VerificationCase {

    private static final String MAIN = "verify-hol-main";
    private static final String RETRY = "verify-hol-retry";
    private static final int MESSAGES = 5;
    private static final int POISON_INDEX = 1;
    private static final int RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 300L;

    private final Brokers brokers;

    public HeadOfLineBlockingCase(Brokers brokers) {
        this.brokers = brokers;
    }

    @Override
    public String id() {
        return "KAFKA-03";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "Kafka 컨슈머에서 처리에 실패한 메시지는 어떻게 재처리합니까?";
    }

    @Override
    public String claim() {
        return "같은 파티션에서 재시도를 반복하면 그 뒤의 메시지가 전부 대기한다(head-of-line blocking). 재시도 전용 토픽으로 옮기면 뒤 메시지는 막히지 않지만, 그 메시지만은 원래 순서를 잃는다 — 순서와 가용성 중 하나를 고르는 문제다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 브로커 왕복 시간이 섞이므로 시간 비교는 환경 의존
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!brokers.available()) {
            KafkaAvailability.markUnavailable(evidence, brokers);
            return;
        }

        Outcome inPartition = run(false);
        Outcome retryTopic = run(true);

        evidence.fact("메시지 수 / 독이 든 메시지 위치", MESSAGES + " / index " + POISON_INDEX);
        evidence.fact("재시도 횟수 × 백오프", RETRY_ATTEMPTS + " × " + RETRY_BACKOFF_MILLIS + "ms");
        evidence.fact("[파티션 내 재시도] 처리 순서", String.join(" → ", inPartition.processedOrder));
        evidence.fact("[파티션 내 재시도] 마지막 메시지까지 걸린 시간(ms)", inPartition.lastMessageMillis);
        evidence.fact("[재시도 토픽] 처리 순서", String.join(" → ", retryTopic.processedOrder));
        evidence.fact("[재시도 토픽] 마지막 메시지까지 걸린 시간(ms)", retryTopic.lastMessageMillis);

        evidence.expect("두 전략 모두 결국 전건을 처리한다",
                inPartition.processedOrder.size() == MESSAGES && retryTopic.processedOrder.size() == MESSAGES);
        evidence.expect("파티션 내 재시도에서는 뒤 메시지가 독이 든 메시지의 재시도가 끝날 때까지 밀린다",
                inPartition.lastMessageMillis >= RETRY_ATTEMPTS * RETRY_BACKOFF_MILLIS);
        evidence.expect("재시도 토픽 전략에서는 독이 든 메시지가 맨 뒤로 밀려 순서가 깨진다",
                retryTopic.processedOrder.indexOf("msg-" + POISON_INDEX) > POISON_INDEX);
        evidence.expectFlaky("재시도 토픽 쪽이 뒤 메시지를 더 빨리 처리한다",
                retryTopic.lastMessageMillis < inPartition.lastMessageMillis);

        brokers.deleteTopic(MAIN);
        brokers.deleteTopic(RETRY);

        evidence.note("파티션 내 재시도는 순서를 지키는 대신 가용성을 버리고, 재시도 토픽은 가용성을 지키는 대신 그 메시지의 순서를 버린다. '어느 쪽이 맞나'가 아니라 '이 도메인에서 순서가 정말 필요한가'를 먼저 물어야 하는 이유다.");
        evidence.note("재시도가 무한이면 파티션이 영원히 멈춘다. 시도 횟수 상한과 DLQ 는 선택이 아니라 필수다.");
        evidence.note("컨슈머가 max.poll.interval.ms 안에 poll() 로 돌아오지 못하면 브로커가 죽은 것으로 보고 리밸런스를 시작한다 — 재시도 대기를 poll 루프 안에서 sleep 으로 때우면 이 한도에 걸린다.");
        evidence.note("재시도 토픽은 보통 지연 단계별로 여러 개(5s·1m·10m)를 두고, 마지막에 DLQ 로 보낸다. 순서가 필요한 메시지는 애초에 이 경로에 넣으면 안 된다.");
    }

    private Outcome run(boolean useRetryTopic) throws Exception {
        brokers.recreateTopic(MAIN, 1);
        if (useRetryTopic) {
            brokers.recreateTopic(RETRY, 1);
        }

        try (KafkaProducer<String, String> producer = brokers.producer(Map.of())) {
            for (int i = 0; i < MESSAGES; i++) {
                producer.send(new ProducerRecord<>(MAIN, "key", "msg-" + i)).get();
            }
        }

        List<String> processed = new ArrayList<>();
        long began = System.nanoTime();
        long lastMessageMillis = -1;

        String group = "verify-hol-" + (useRetryTopic ? "retry" : "inline");
        try (KafkaConsumer<String, String> consumer = brokers.consumer(group, Map.of());
             KafkaProducer<String, String> forwarder = brokers.producer(Map.of())) {
            consumer.subscribe(List.of(MAIN));

            int emptyPolls = 0;
            while (processed.size() < (useRetryTopic ? MESSAGES - 1 : MESSAGES) && emptyPolls < 5) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    boolean poison = record.value().equals("msg-" + POISON_INDEX);
                    if (poison && useRetryTopic) {
                        // 전략 B: 즉시 재시도 토픽으로 넘기고 파티션은 계속 흐르게 둔다
                        forwarder.send(new ProducerRecord<>(RETRY, record.key(), record.value())).get();
                        continue;
                    }
                    if (poison) {
                        // 전략 A: 같은 파티션에서 붙잡고 재시도한다 — 뒤 메시지는 그동안 대기
                        for (int attempt = 0; attempt < RETRY_ATTEMPTS; attempt++) {
                            Thread.sleep(RETRY_BACKOFF_MILLIS);
                        }
                    }
                    processed.add(record.value());
                    if (record.value().equals("msg-" + (MESSAGES - 1))) {
                        lastMessageMillis = (System.nanoTime() - began) / 1_000_000L;
                    }
                }
                consumer.commitSync();
            }
        }

        if (useRetryTopic) {
            // 재시도 토픽의 소비자는 백오프 뒤에 따로 처리한다 — 그래서 순서가 맨 뒤로 밀린다
            Thread.sleep(RETRY_ATTEMPTS * RETRY_BACKOFF_MILLIS);
            try (KafkaConsumer<String, String> retryConsumer = brokers.consumer("verify-hol-retry-consumer", Map.of())) {
                retryConsumer.subscribe(List.of(RETRY));
                int emptyPolls = 0;
                while (processed.size() < MESSAGES && emptyPolls < 5) {
                    ConsumerRecords<String, String> records = retryConsumer.poll(Duration.ofSeconds(2));
                    if (records.isEmpty()) {
                        emptyPolls++;
                        continue;
                    }
                    emptyPolls = 0;
                    for (ConsumerRecord<String, String> record : records) {
                        processed.add(record.value());
                    }
                    retryConsumer.commitSync();
                }
            }
        }

        return new Outcome(processed, lastMessageMillis);
    }

    private record Outcome(List<String> processedOrder, long lastMessageMillis) {}
}
