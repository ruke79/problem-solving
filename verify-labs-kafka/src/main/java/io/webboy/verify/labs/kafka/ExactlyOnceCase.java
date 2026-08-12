package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Q100 — "Exactly-Once" 가 실제로 무엇을 보장하는가.
 *
 * <p>두 가지를 브로커에 직접 물어 확인한다.
 * <ul>
 *   <li>{@code enable.idempotence} 는 Kafka 3.0 부터 <b>기본 true</b> 다 — 프로듀서 재시도로 인한
 *       중복 저장은 기본 설정에서 이미 막혀 있다({@code docs/04} 의 보강 제안 항목)</li>
 *   <li>트랜잭션을 abort 하면 {@code read_committed} 컨슈머는 그 메시지를 <b>보지 못한다</b> —
 *       다만 {@code read_uncommitted}(기본값!) 컨슈머에게는 그대로 보인다</li>
 * </ul>
 */
@Component
public class ExactlyOnceCase extends VerificationCase {

    private static final String TOPIC = "verify-eos";

    private final Brokers brokers;

    public ExactlyOnceCase(Brokers brokers) {
        this.brokers = brokers;
    }

    @Override
    public String id() {
        return "KAFKA-04";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "Kafka 의 Exactly-Once 는 무엇을 보장합니까? 애플리케이션은 무엇을 더 해야 합니까?";
    }

    @Override
    public String claim() {
        return "프로듀서 멱등성은 Kafka 3.0 부터 기본 활성이고, 트랜잭션은 abort 된 메시지를 read_committed 컨슈머에게 감춘다. 다만 컨슈머의 기본 격리 수준은 read_uncommitted 라 설정하지 않으면 취소된 메시지까지 읽는다 — Exactly-Once 는 켜야 얻어지고, 외부 시스템 부수효과까지 덮어주지는 않는다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!brokers.available()) {
            KafkaAvailability.markUnavailable(evidence, brokers);
            return;
        }
        brokers.recreateTopic(TOPIC, 1);

        boolean idempotenceDefault = defaultIdempotence();
        evidence.fact("enable.idempotence 기본값", idempotenceDefault);
        evidence.fact("acks 기본값", defaultString(ProducerConfig.ACKS_CONFIG));
        evidence.fact("isolation.level 기본값(컨슈머)", defaultConsumerIsolation());

        // 커밋한 트랜잭션 1건 + 취소한 트랜잭션 1건
        Map<String, Object> tx = new HashMap<>();
        tx.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "verify-eos-tx");
        tx.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        try (KafkaProducer<String, String> producer = brokers.producer(tx)) {
            producer.initTransactions();

            producer.beginTransaction();
            producer.send(new ProducerRecord<>(TOPIC, "k", "committed-1"));
            producer.commitTransaction();

            producer.beginTransaction();
            producer.send(new ProducerRecord<>(TOPIC, "k", "aborted-1"));
            producer.send(new ProducerRecord<>(TOPIC, "k", "aborted-2"));
            // flush() 로 브로커까지 보낸 뒤에 abort 해야 '로그에는 남고 마커로 취소된' 상태가 된다.
            // flush 없이 abort 하면 아직 버퍼에 있던 배치가 그대로 버려져 로그에 아무것도 남지 않는다.
            producer.flush();
            producer.abortTransaction();
        }

        List<String> readCommitted = consume("read_committed");
        List<String> readUncommitted = consume("read_uncommitted");

        evidence.fact("read_committed 컨슈머가 본 메시지", readCommitted.toString());
        evidence.fact("read_uncommitted 컨슈머가 본 메시지", readUncommitted.toString());

        evidence.expect("Kafka 3.0+ 의 프로듀서 멱등성은 기본 활성이다", idempotenceDefault);
        evidence.expect("read_committed 컨슈머는 커밋된 메시지만 본다",
                readCommitted.contains("committed-1") && !readCommitted.contains("aborted-1"));
        evidence.expect("기본값인 read_uncommitted 로는 취소된 메시지까지 보인다",
                readUncommitted.contains("aborted-1"));

        brokers.deleteTopic(TOPIC);

        evidence.note("프로듀서 멱등성이 막는 것은 '브로커 재시도로 인한 중복 저장'뿐이다. 애플리케이션이 같은 메시지를 두 번 send 하면 그건 서로 다른 메시지이므로 그대로 두 건이 된다 — 업무 레벨 중복은 멱등 키로 막아야 한다(DB-12).");
        evidence.note("Exactly-Once 는 Kafka 안에서 닫힌 read-process-write 에만 성립한다. 처리 중 외부 API 를 호출하거나 다른 DB 에 쓰면 그 부수효과는 트랜잭션 밖이라 여전히 중복될 수 있다.");
        evidence.note("이 케이스를 만들면서 걸린 함정: flush() 없이 abortTransaction() 을 부르면 아직 전송되지 않은 배치가 그대로 폐기돼 로그에 아무것도 남지 않는다. '취소된 메시지가 로그에 남는다'를 관측하려면 브로커에 도달시킨 뒤 abort 해야 한다.");
        evidence.note("컨슈머의 isolation.level 기본값이 read_uncommitted 라는 점이 실무의 함정이다. 프로듀서만 트랜잭션으로 바꾸고 컨슈머 설정을 잊으면 '취소한 메시지가 처리되는' 상태가 된다.");
        evidence.note("트랜잭션은 공짜가 아니다 — 트랜잭션 코디네이터 왕복과 커밋 마커 때문에 처리량이 떨어진다. 정말 필요한 파이프라인에만 켠다.");
    }

    private boolean defaultIdempotence() {
        return Boolean.parseBoolean(defaultString(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
    }

    /** 프로듀서 기본 설정값을 브로커가 아니라 클라이언트 설정 정의에서 그대로 읽는다. */
    private String defaultString(String key) {
        Map<String, Object> minimal = new HashMap<>();
        minimal.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers.bootstrapServers());
        minimal.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        minimal.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        return String.valueOf(new ProducerConfig(minimal).values().get(key));
    }

    private String defaultConsumerIsolation() {
        Map<String, Object> minimal = new HashMap<>();
        minimal.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers.bootstrapServers());
        minimal.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        minimal.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        return String.valueOf(new ConsumerConfig(minimal).values().get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
    }

    private List<String> consume(String isolationLevel) {
        List<String> values = new java.util.ArrayList<>();
        Map<String, Object> overrides = Map.of(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolationLevel);
        try (KafkaConsumer<String, String> consumer = brokers.consumer("verify-eos-" + isolationLevel, overrides)) {
            consumer.subscribe(List.of(TOPIC));
            int emptyPolls = 0;
            while (emptyPolls < 3) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }
}
