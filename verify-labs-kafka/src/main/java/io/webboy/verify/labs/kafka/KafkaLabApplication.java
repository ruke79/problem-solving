package io.webboy.verify.labs.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Kafka 실물 검증 모듈.
 *
 * <p>{@code verify-labs} 의 MSA 케이스는 브로커를 자바 코드로 흉내 낸다. 이 모듈은 같은 명제를
 * <b>실제 브로커</b>에 걸어 확인한다. 브로커가 없으면 각 케이스가 스스로 INCONCLUSIVE 를 남긴다.
 */
@SpringBootApplication
public class KafkaLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaLabApplication.class, args);
    }
}
