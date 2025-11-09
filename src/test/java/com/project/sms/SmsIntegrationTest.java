package com.project.sms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import com.project.sms.dto.MessageRequest;
import com.project.sms.repository.SmsMessageRepository;
import com.project.sms.service.ExternalMessageService;
import com.project.sms.service.MessageServiceFactory;
import com.project.sms.service.SmsGateway;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 테스트 시 임베디드 카프카 브로커 1개를 띄우고, "sms.queue.topic" 토픽을 생성합니다.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"sms.queue.topic"})
// 테스트 간 환경 설정을 초기화하여 브로커가 겹치지 않게 합니다.
@DirtiesContext 
class SmsIntegrationTest {

    @Autowired
    private MessageServiceFactory serviceFactory;

    // 실제 DB 접근을 막고 Mock 객체로 대체합니다.
    @MockBean 
    private SmsMessageRepository smsRepository;

    // 실제 SENS API 호출을 막고 Mock 객체로 대체합니다.
    @MockBean
    private SmsGateway smsGateway; 

    @Test
    void whenSmsRequested_thenMessageShouldFlowThroughKafkaToConsumer() throws InterruptedException {
        // Given
        ExternalMessageService smsService = serviceFactory.getService("SMS");
        MessageRequest request = new MessageRequest("01012345678", "테스트 메시지", "SMS", null);
        
        // Mocking: SmsGateway의 sendSms 호출 시 성공(true)을 반환하도록 설정
        when(smsGateway.sendSms(any())).thenReturn(true);
        
        // Mocking: SmsRepository의 save 호출 시 인자 그대로 반환하도록 설정 (DB 저장 시뮬레이션)
        when(smsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        // 1. Producer(SmsProducerService) 호출 -> 메시지가 Embedded Kafka로 발행됨
        smsService.sendMessage(request);

        // Then
        // 2. Consumer(SmsConsumerService)가 Kafka로부터 메시지를 받고 처리했는지 검증
        // 비동기 처리이므로 'timeout(5000)'을 사용하여 최대 5초까지 기다립니다.
        
        // SmsGateway의 sendSms 메서드가 정확히 1번 호출되었는지 검증
        verify(smsGateway, timeout(5000).times(1)).sendSms(any());
        
        // SmsRepository의 save 메서드가 최소 2번(PENDING 저장, 최종 상태 업데이트) 호출되었는지 검증
        verify(smsRepository, timeout(5000).atLeast(2)).save(any());
    }
}
