package com.example.sms;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.sms.dto.SmsMessageDto;
import com.example.sms.model.SmsMessage;
import com.example.sms.repository.SmsMessageRepository;
import com.example.sms.service.SmsConsumerService;
import com.example.sms.service.SmsGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Mockito를 JUnit 5에서 사용하기 위한 설정
@ExtendWith(MockitoExtension.class) 
class SmsConsumerServiceUnitTest {

    @Mock // Mock 객체 생성: 실제 SENS 통신 대신 사용할 가짜 객체
    private SmsGateway smsGateway;

    @Mock // Mock 객체 생성: 실제 DB 접근 대신 사용할 가짜 객체
    private SmsMessageRepository smsRepository;

    @InjectMocks // 테스트 대상 객체: Mock 객체들을 자동으로 주입받습니다.
    private SmsConsumerService smsConsumerService;

    private SmsMessageDto successfulDto;
    
    @BeforeEach
    void setUp() {
        successfulDto = new SmsMessageDto("01011112222", "성공 테스트 메시지");
    }

    @Test
    void whenSmsGatewaySucceeds_thenStatusShouldBeSent() {
        // Given
        // 1. Mocking: smsGateway의 sendSms 호출 시 무조건 true (성공) 반환하도록 설정
        when(smsGateway.sendSms(any(SmsMessageDto.class))).thenReturn(true); 
        
        // 2. Mocking: smsRepository.save 호출 시 인자(SmsMessage)를 그대로 반환하도록 설정
        when(smsRepository.save(any(SmsMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // ArgumentCaptor: save 메서드에 전달된 최종 SmsMessage 객체를 캡처
        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);

        // When
        smsConsumerService.consumeSmsRequest(successfulDto);

        // Then
        // 1. SUT(Service)가 smsGateway를 1번 호출했는지 검증
        verify(smsGateway, times(1)).sendSms(successfulDto);

        // 2. SUT가 smsRepository.save를 최소 2번 (초기 PENDING, 최종 SENT) 호출했는지 검증
        verify(smsRepository, times(2)).save(captor.capture());
        
        // 3. 최종 저장된 SmsMessage의 상태가 "SENT"인지 검증
        SmsMessage finalStatus = captor.getValue();
        assertEquals("SENT", finalStatus.getStatus(), "성공 시 상태는 SENT여야 합니다.");
    }
    
    @Test
    void whenSmsGatewayFails_thenStatusShouldBeFailed() {
        // Given
        // 1. Mocking: smsGateway의 sendSms 호출 시 false (실패) 반환하도록 설정
        when(smsGateway.sendSms(any(SmsMessageDto.class))).thenReturn(false);
        when(smsRepository.save(any(SmsMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);

        // When
        smsConsumerService.consumeSmsRequest(successfulDto);

        // Then
        verify(smsGateway, times(1)).sendSms(successfulDto);
        verify(smsRepository, times(2)).save(captor.capture());
        
        // 최종 저장된 SmsMessage의 상태가 "FAILED"인지 검증
        SmsMessage finalStatus = captor.getValue();
        assertEquals("FAILED", finalStatus.getStatus(), "실패 시 상태는 FAILED여야 합니다.");
    }
}
