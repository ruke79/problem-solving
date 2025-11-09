package com.example.sms;

// src/test/java/com/example/sms/KakaoMessageServiceUnitTest.java


// ... (필요한 import 생략) ...

import com.example.sms.dto.MessageRequest;
import com.example.sms.dto.MessageResponse;
import com.example.sms.service.KakaoMessageService;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// KakaoTemplateValidator의 핵심 로직인 템플릿 코드 검증과 sendMessage의 성공/실패 시뮬레이션을 검증합니다.

class KakaoMessageServiceUnitTest {

    // 🚨 WebClient Mocking: KakaoService가 주입받을 객체
    @Mock 
    private WebClient webClient;

    // WebClient.RequestHeadersUriSpec Mocking을 위한 중첩 Mock 객체
    @Mock 
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;

    @InjectMocks
    private KakaoMessageService kakaoService;
    private MessageRequest validRequest;

    @BeforeEach
    void setUp() {
        
        validRequest = new MessageRequest("01011112222", "카카오 메시지 내용", "KAKAO", "T_VALID_001");

        // Mocking: WebClient 호출 체인 설정
        // 1. webClient.post() -> requestBodyUriSpec 반환
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        // 2. requestBodyUriSpec.uri(anyString()) -> requestBodyUriSpec 반환
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        // 3. requestBodyUriSpec.bodyValue(any()) -> requestHeadersSpec 반환
        when(requestBodyUriSpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        // 4. requestHeadersSpec.retrieve() -> responseSpec 반환
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        
        // 5. responseSpec.bodyToMono(MessageResponse.class) 호출 시 성공 응답 반환
        when(responseSpec.bodyToMono(any(Class.class)))
            .thenReturn(Mono.just(new MessageResponse(true, "Mocked Kakao ccess")));
    }

    @Test
    void whenTemplateCodeIsValid_thenValidationShouldReturnTrue() {
        // Given
        String validTemplateCode = "T_VALID_XYZ";
        
        // When
        boolean result = kakaoService.validateTemplate(validTemplateCode);
        
        // Then
        assertTrue(result, "유효한 템플릿 코드는 true를 반환해야 합니다.");
    }

    @Test
    void whenTemplateCodeIsInvalid_thenValidationShouldReturnFalse() {
        // Given
        String invalidTemplateCode = "T_PENDING_XYZ"; // T_VALID_가 아닌 코드
        
        // When
        boolean result = kakaoService.validateTemplate(invalidTemplateCode);
        
        // Then
        assertFalse(result, "유효하지 않은 템플릿 코드는 false를 반환해야 합니다.");
    }

    @Test
    void whenKakaoMessageIsSent_thenShouldReturnSuccessResponse() {
        // Given: sendMessage 로직은 현재 true만 반환하도록 구현되어 있음
        
        // When
        MessageResponse response = kakaoService.sendMessage(validRequest);
        
        // Then
        assertTrue(response.isSuccess(), "카카오 전송은 성공 응답을 반환해야 합니다.");
    }
}
