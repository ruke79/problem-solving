package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;

/**
 * 브로커가 없을 때의 공통 처리.
 *
 * <p>"브로커가 없다"는 것과 "명제가 틀렸다"는 것은 전혀 다르다. 브로커가 없으면
 * REFUTED 가 아니라 INCONCLUSIVE 로 남겨, 검증하지 못했다는 사실 자체를 리포트에 남긴다.
 */
final class KafkaAvailability {

    private KafkaAvailability() {
    }

    static void markUnavailable(Evidence evidence, Brokers brokers) {
        evidence.fact("bootstrap.servers", brokers.bootstrapServers());
        evidence.expectFlaky("브로커에 접속할 수 있어야 검증할 수 있다 — 지금은 접속되지 않는다", false);
        evidence.note("`docker compose up -d` 로 kafka 서비스를 띄우면 검증된다. "
                + "다른 브로커를 쓰려면 verify.kafka.bootstrap-servers 또는 KAFKA_BOOTSTRAP_SERVERS 로 지정한다.");
    }
}
