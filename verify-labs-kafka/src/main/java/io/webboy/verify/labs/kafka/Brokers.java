package io.webboy.verify.labs.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 케이스들이 공유하는 브로커 접속 도구.
 *
 * <p>브로커 가용성 확인, 토픽 생성·삭제, 기본 설정의 프로듀서·컨슈머 생성만 담당한다.
 * <b>검증 대상이 되는 설정</b>(멱등성, 트랜잭션, 격리 수준 등)은 여기서 정하지 않고
 * 각 케이스가 직접 지정한다 — 그래야 무엇을 검증하는지가 케이스 안에서 다 보인다.
 */
@Component
public class Brokers {

    private final String bootstrapServers;

    public Brokers(@Value("${verify.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String bootstrapServers() {
        return bootstrapServers;
    }

    /** 브로커가 살아 있는지 짧게 확인한다. 없으면 케이스가 INCONCLUSIVE 를 남기고 끝낸다. */
    public boolean available() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
        try (Admin admin = Admin.create(config)) {
            admin.describeCluster().nodes().get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String clusterDescription() {
        try (Admin admin = admin()) {
            var cluster = admin.describeCluster();
            return "nodeCount=" + cluster.nodes().get(5, TimeUnit.SECONDS).size()
                    + ", clusterId=" + cluster.clusterId().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "(확인 실패: " + e.getClass().getSimpleName() + ")";
        }
    }

    public Admin admin() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // default.api.timeout.ms 는 request.timeout.ms 보다 작을 수 없다 — 둘을 함께 지정한다
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 20_000);
        return Admin.create(config);
    }

    /** 이전 실행이 남긴 토픽을 지우고 새로 만든다 — 케이스는 항상 같은 상태에서 출발해야 한다. */
    public void recreateTopic(String topic, int partitions) throws Exception {
        try (Admin admin = admin()) {
            if (admin.listTopics().names().get(15, TimeUnit.SECONDS).contains(topic)) {
                admin.deleteTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
                waitUntilGone(admin, topic);
            }
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    public void deleteTopic(String topic) {
        try (Admin admin = admin()) {
            admin.deleteTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 뒷정리 실패는 검증 결과에 영향을 주지 않는다
        }
    }

    private void waitUntilGone(Admin admin, String topic) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(topic)) {
                return;
            }
            Thread.sleep(250);
        }
    }

    public KafkaProducer<String, String> producer(Map<String, Object> overrides) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.putAll(overrides);
        return new KafkaProducer<>(props);
    }

    public KafkaConsumer<String, String> consumer(String groupId, Map<String, Object> overrides) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.putAll(overrides);
        return new KafkaConsumer<>(props);
    }
}
