package com.example.sms;

// src/test/java/com/example/sms/SmsDlqIntegrationTest.java


import com.example.sms.dto.MessageRequest;
import com.example.sms.dto.SmsMessageDto;
import com.example.sms.repository.SmsMessageRepository;
import com.example.sms.repository.SmsMessageRepository;
import com.example.sms.service.ExternalMessageService;
import com.example.sms.service.MessageServiceFactory;
import com.example.sms.service.SmsGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// EmbeddedKafka 설정: DLT 토픽 포함
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"sms-topic", "sms-topic-dlt"})
@DirtiesContext
@TestPropertySource(properties = {
    // 테스트 환경에서만 사용될 컨슈머 그룹 ID 및 오프셋 설정
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.group-id=test-group"
})
class SmsDlqIntegrationTest {

    @Autowired
    private MessageServiceFactory serviceFactory;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockBean 
    private SmsMessageRepository smsRepository;
    @MockBean 
    private SmsGateway smsGateway; 

    private static final String SMS_DLT_TOPIC = "sms.queue.topic.dlt";
    private final BlockingQueue<String> dltRecords = new LinkedBlockingQueue<>();


    @Test
    void whenSmsGatewayFailsRepeatedly_thenMessageShouldBeSentToDLQ() throws Exception {
        // Given
        ExternalMessageService smsService = serviceFactory.getService("SMS");
        MessageRequest request = new MessageRequest("01099999999", "DLQ 테스트 메시지", "SMS", null);
        
        // 1. DLQ 검증을 위한 임시 컨슈머 설정 (가장 중요)
        
        // 🚨 수정된 부분: KafkaTestUtils.consumerProps 사용
        // KafkaTestUtils.consumerProps(임베디드 브로커, 컨슈머 그룹 ID, 문자열 디코더 사용 여부)
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
            "dlt-test-group", // 별도 그룹 ID
            "false",          // 컨슈머 팩토리에서 Json Deserializer를 사용하므로 false
            embeddedKafkaBroker
        );
        
        ContainerProperties containerProps = new ContainerProperties(SMS_DLT_TOPIC);
        
        // String Deserializer를 사용하는 컨슈머 팩토리 생성 (DLQ 메시지 본문은 단순 String으로 처리)
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        KafkaMessageListenerContainer<String, String> container = 
            new KafkaMessageListenerContainer<>(cf, containerProps);
        
        // 수신된 메시지의 Value(본문)를 dltRecords 큐에 넣는 리스너 설정
        container.setupMessageListener((MessageListener<String, String>) record -> dltRecords.add(record.value()));
        container.start();
        ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());
        
        
        // **오류 시나리오 설정:** 3회 재시도 실패 후 DLQ로 전송되도록 설정
        doThrow(new RuntimeException("Simulated API Error for DLQ")).when(smsGateway).sendSms(any());
        //when(smsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));  Error!

        // 2. Repository Mocking 설정
        // save(엔티티)가 호출되면 -> 호출된 인자(엔티티)를 그대로 반환하도록 설정
        when(smsRepository.save(any())).thenAnswer(invocation -> {
            // save 메서드에 전달된 첫 번째 인자(SmsMessage)를 그대로 반환합니다.
            // 이는 JpaRepository의 save() 동작을 시뮬레이션합니다.
            return invocation.getArgument(0);
        });

        // When
        smsService.sendMessage(request);

        // Then
        // 4. DLQ 토픽에서 메시지가 수신될 때까지 대기 (최대 10초)
        String receivedDltRecord = dltRecords.poll(10, TimeUnit.SECONDS);

        // 5. 검증
        assertThat(receivedDltRecord).isNotNull();
        
        // SmsGateway 호출 횟수 검증 (초기 1회 + 3회 재시도 = 4회)
        verify(smsGateway, times(4)).sendSms(any()); 
        
        // SmsRepository save 횟수 검증 (최소 5회: PENDING 저장 1회 + 각 실패 시마다 상태 업데이트 4회)
        verify(smsRepository, times(5)).save(any()); 

        container.stop();
    }
}