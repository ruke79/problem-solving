package com.example.sms;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.sms.model.SmsMessage;
import com.example.sms.repository.SmsMessageRepository;
import com.example.sms.service.CallbackService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallbackServiceUnitTest {
    
    @Mock
    private SmsMessageRepository smsRepository;

    @InjectMocks
    private CallbackService callbackService;

    private final String TEST_TX_ID = "TX12345";

    @Test
    void whenFinalStatusIsReceivedFirstTime_thenShouldUpdateDb() {
        // Given
        SmsMessage message = new SmsMessage();
        message.setStatus("SENT"); // Kafka Consumer가 이미 SENT로 업데이트했다고 가정
        
        // Mocking: findByTransactionId 호출 시 메시지 반환
        when(smsRepository.findByTransactionId(TEST_TX_ID)).thenReturn(Optional.of(message));
        
        // When
        callbackService.processFinalStatus(TEST_TX_ID, "DELIVERED");

        // Then
        // 1. DB에서 메시지를 조회했는지 검증
        verify(smsRepository, times(1)).findByTransactionId(TEST_TX_ID);
        
        // 2. 최종 상태로 업데이트 후 저장했는지 검증
        verify(smsRepository, times(1)).save(any(SmsMessage.class));
        
        // 3. 실제 상태가 업데이트 되었는지 검증 (객체 상태 직접 확인)
        assertEquals("DELIVERED", message.getStatus(), "최종 상태가 DELIVERED로 업데이트되어야 합니다.");
    }

    @Test
    void whenFinalStatusIsReceivedTwice_thenShouldIgnoreSecondUpdate() {
        // Given
        SmsMessage message = new SmsMessage();
        message.setStatus("DELIVERED"); // 이미 최종 상태로 처리됨
        
        when(smsRepository.findByTransactionId(TEST_TX_ID)).thenReturn(Optional.of(message));
        
        // When
        callbackService.processFinalStatus(TEST_TX_ID, "READ"); // READ는 DELIVERED 이후 상태지만, 중복 처리 방지 로직을 검증

        // Then
        // DB를 조회는 하지만, save는 호출되지 않아야 함 (중복 처리 방지)
        verify(smsRepository, times(1)).findByTransactionId(TEST_TX_ID);
        verify(smsRepository, never()).save(any()); // save가 호출되지 않았는지 검증
    }
}
