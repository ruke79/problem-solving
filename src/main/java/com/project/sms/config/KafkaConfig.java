package com.project.sms.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.util.backoff.FixedBackOff;

import com.project.sms.dto.SmsMessageDto;

@Configuration
@EnableKafka
@EnableRetry // 전송 실패 시 Spring Retry 기능을 활성화
public class KafkaConfig {
    
    private static final String SMS_TOPIC = "sms-topic";
    private static final String SMS_DLT_TOPIC = "sms-topic.dlt"; // Dead Letter Topic

    private final KafkaProperties kafkaProperties;

    // KafkaProperties를 통해 application.yml/properties 설정을 가져옵니다.
    public KafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * 1. Consumer Factory 정의
     */
    @Bean
    public ConsumerFactory<String, SmsMessageDto> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                kafkaProperties.buildConsumerProperties(),
                // Key Deserializer: String
                // Value Deserializer: SmsMessageDto (JSON)
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.springframework.kafka.support.serializer.JsonDeserializer<>(SmsMessageDto.class)
        );
    }

    /**
     * 2. 에러 핸들러 (DefaultErrorHandler) 정의
     * - 일시적 오류 시 재시도 (Retry)
     * - 영구적 오류 시 DLQ로 전송 (Recovery)
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template) {
        
        // Dead Letter Queue 설정: Recovery 실패 시 DLT 토픽으로 메시지를 보냅니다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            template,
            // TopicPartionResolver: DLT 토픽을 결정 (여기는 고정된 DLT 토픽 사용)
            (r, e) -> new TopicPartition(SMS_DLT_TOPIC, r.partition()) 
        );

        // 3번 재시도 (FixedBackOff: 지연 시간 2초, 최대 시도 횟수 3회)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer, 
            new FixedBackOff(2000L, 3L) 
        );

        // 역직렬화(Deserialization) 예외는 즉시 처리하지 않고 건너뛰거나 DLQ로 보냅니다.
        errorHandler.addNotRetryableExceptions(
            // 메시지 포맷이 아예 잘못된 경우 (역직렬화 오류)는 재시도 없이 DLQ로
            org.springframework.kafka.support.serializer.DeserializationException.class 
        );

        return errorHandler;
    }

    /**
     * 3. Kafka Listener Container Factory 정의
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SmsMessageDto> kafkaListenerContainerFactory(
            DefaultErrorHandler errorHandler) {
        
        ConcurrentKafkaListenerContainerFactory<String, SmsMessageDto> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3); // 컨슈머 스레드 수 (병렬 처리)

        // 설정된 에러 핸들러를 Container에 적용
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}