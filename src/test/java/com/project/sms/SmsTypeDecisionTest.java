package com.project.sms;



import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.project.sms.dto.MessageRequest;
import com.project.sms.dto.SmsMessageDto;
import com.project.sms.service.SmsProducerService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

//SmsProducerService 내부의 SMS/LMS 타입 결정 로직이 문자 길이 경계값(90자)에서 정확하게 동작하는지 검증하는 테스트입니다.

class SmsTypeDecisionTest {

    private SmsProducerService smsService;
    private KafkaTemplate<String, SmsMessageDto> kafkaTemplateMock;
    private ArgumentCaptor<SmsMessageDto> dtoCaptor;

    @BeforeEach
    void setUp() {
        // Mockito의 mock을 사용하여 KafkaTemplate을 Mocking
        kafkaTemplateMock = mock(KafkaTemplate.class);
        smsService = new SmsProducerService(kafkaTemplateMock);
        dtoCaptor = ArgumentCaptor.forClass(SmsMessageDto.class);
    }

    @Test
    void whenContentLengthIsExactly90_thenShouldBeSMS() {
        // Given
        String content = "a".repeat(90); // 90자
        MessageRequest request = new MessageRequest("01011112222", content, "SMS", "제목");

        // When
        smsService.sendMessage(request);

        // Then
        verify(kafkaTemplateMock).send(eq("sms.queue.topic"), dtoCaptor.capture());
        assertEquals("SMS", dtoCaptor.getValue().getType(), "90자는 SMS여야 합니다.");
    }

    @Test
    void whenContentLengthIs91_thenShouldBeLMS() {
        // Given
        String content = "a".repeat(91); // 91자
        MessageRequest request = new MessageRequest("01011112222", content, "SMS", "제목");

        // When
        smsService.sendMessage(request);

        // Then
        verify(kafkaTemplateMock).send(eq("sms.queue.topic"), dtoCaptor.capture());
        assertEquals("LMS", dtoCaptor.getValue().getType(), "91자는 LMS여야 합니다.");
    }
}
