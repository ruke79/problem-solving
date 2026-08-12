package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Q154 — WebSocket 서버를 여러 대로 늘리면 생기는 문제.
 *
 * <p>세션은 <b>연결된 그 인스턴스에만</b> 있으므로, 인스턴스 A 에 붙은 사용자에게
 * 인스턴스 B 가 메시지를 보낼 수 없다. 로드밸런서 스티키 세션은 "붙은 곳에 계속 붙게" 할 뿐
 * 이 문제를 풀지 못한다 — 인스턴스 간 pub/sub 백플레인이 필요하다.
 *
 * <p>여기서는 인스턴스 두 대의 세션 레지스트리를 만들고, Redis pub/sub 유무로 전달 여부를 비교한다.
 */
@Component
public class PubSubBackplaneCase extends VerificationCase {

    private static final String TOPIC = "verify-ws-broadcast";

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;

    public PubSubBackplaneCase(StringRedisTemplate redis, RedisConnectionFactory connectionFactory) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String id() {
        return "RES-11";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "WebSocket 서버를 스케일 아웃할 때의 과제와 해결책은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "WebSocket 세션은 연결된 인스턴스의 메모리에만 있으므로, 다른 인스턴스가 그 사용자에게 메시지를 보낼 수 없다. 스티키 세션은 이 문제를 풀지 못하고, Redis pub/sub 같은 백플레인을 두어 인스턴스 간에 메시지를 중계해야 전 사용자에게 전달된다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!redisAvailable()) {
            evidence.expectFlaky("검증에는 Redis 가 필요하다 — 접속되지 않는다", false);
            evidence.note("`docker compose up -d redis` 로 띄우면 검증된다. 다른 인스턴스는 REDIS_HOST/REDIS_PORT 로 지정한다.");
            return;
        }

        // 인스턴스 2대: A 에는 사용자 2명, B 에는 사용자 1명이 붙어 있다
        Instance instanceA = new Instance("A", List.of("user-1", "user-2"));
        Instance instanceB = new Instance("B", List.of("user-3"));

        // 1) 백플레인 없이 A 가 브로드캐스트하면 A 의 사용자에게만 간다
        instanceA.broadcastLocally("hello");
        int localOnlyDelivered = instanceA.delivered.size() + instanceB.delivered.size();
        int localOnlyOnB = instanceB.delivered.size();

        instanceA.delivered.clear();
        instanceB.delivered.clear();

        // 2) Redis pub/sub 을 백플레인으로 붙이면 B 의 사용자에게도 간다
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        CountDownLatch received = new CountDownLatch(2);
        MessageListener listenerA = (message, pattern) -> {
            instanceA.deliverToLocalSessions(new String(message.getBody(), StandardCharsets.UTF_8));
            received.countDown();
        };
        MessageListener listenerB = (message, pattern) -> {
            instanceB.deliverToLocalSessions(new String(message.getBody(), StandardCharsets.UTF_8));
            received.countDown();
        };
        container.addMessageListener(listenerA, new ChannelTopic(TOPIC));
        container.addMessageListener(listenerB, new ChannelTopic(TOPIC));
        container.afterPropertiesSet();
        container.start();
        try {
            Thread.sleep(300);                       // 구독이 성립할 시간
            redis.convertAndSend(TOPIC, "hello");    // A 가 발행했다고 가정
            received.await(5, TimeUnit.SECONDS);
        } finally {
            container.stop();
            container.destroy();
        }

        int backplaneDelivered = instanceA.delivered.size() + instanceB.delivered.size();
        int backplaneOnB = instanceB.delivered.size();

        evidence.fact("인스턴스 구성", "A: user-1, user-2 / B: user-3");
        evidence.fact("[백플레인 없음] 전달된 사용자 수", localOnlyDelivered);
        evidence.fact("[백플레인 없음] 다른 인스턴스(B)의 사용자에게 전달", localOnlyOnB);
        evidence.fact("[Redis pub/sub] 전달된 사용자 수", backplaneDelivered);
        evidence.fact("[Redis pub/sub] 다른 인스턴스(B)의 사용자에게 전달", backplaneOnB);

        evidence.expectEquals("백플레인이 없으면 발행한 인스턴스의 사용자에게만 간다", 2, localOnlyDelivered);
        evidence.expectEquals("다른 인스턴스에 붙은 사용자는 아예 못 받는다", 0, localOnlyOnB);
        evidence.expectEquals("백플레인을 붙이면 전 사용자에게 전달된다", 3, backplaneDelivered);
        evidence.expectEquals("다른 인스턴스의 사용자도 받는다", 1, backplaneOnB);

        evidence.note("스티키 세션은 '같은 사용자가 같은 인스턴스로 계속 가게' 할 뿐, 다른 인스턴스가 그 사용자에게 보내는 문제를 풀지 못한다 — 자주 혼동되는 지점이다.");
        evidence.note("Redis pub/sub 은 메시지를 저장하지 않는다(fire-and-forget). 구독자가 잠깐 끊긴 사이의 메시지는 사라지므로, 유실이 곤란하면 Kafka 같은 로그형 브로커나 재전송 설계가 필요하다(KAFKA-01 의 '되감기' 참고).");
        evidence.note("Spring 은 이 구조를 STOMP 릴레이(RabbitMQ·ActiveMQ)로도 제공한다. 직접 pub/sub 을 붙이든 릴레이를 쓰든, 핵심은 '세션은 로컬, 메시지는 전역'이라는 분리다.");
        evidence.note("연결 수가 늘면 인스턴스마다 세션 메모리와 하트비트 비용이 든다. 스케일 아웃의 상한은 CPU 가 아니라 커넥션 수와 메모리인 경우가 많다.");
    }

    private boolean redisAvailable() {
        try {
            redis.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** WebSocket 인스턴스 한 대 — 세션 레지스트리는 이 프로세스(인스턴스) 안에만 있다. */
    private static final class Instance {
        private final String name;
        private final Map<String, String> localSessions = new ConcurrentHashMap<>();
        private final List<String> delivered = new CopyOnWriteArrayList<>();

        private Instance(String name, List<String> users) {
            this.name = name;
            users.forEach(user -> localSessions.put(user, "session-of-" + user));
        }

        private void broadcastLocally(String message) {
            deliverToLocalSessions(message);
        }

        private void deliverToLocalSessions(String message) {
            localSessions.keySet().forEach(user -> delivered.add(name + ":" + user + ":" + message));
        }
    }
}
