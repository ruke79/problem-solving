package com.project.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.sms.service.KakaoMessageService;
import com.project.sms.service.KakaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoServiceUnitTest {

    @Mock
    private WebClient webClient;

    // WebClient 호출 체인 Mocking을 위한 중첩 Mock 객체들
    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;
    
    @Mock
    private KakaoMessageService kakaoMessageService; // 사용되지 않지만 의존성 주입을 위해 Mock

    @InjectMocks
    private KakaoService kakaoService;

    private static final String TEST_ACCESS_TOKEN = "TEST_TOKEN_XYZ";
    private static final String TEST_CODE = "TEST_AUTH_CODE";

    @BeforeEach
    void setUp() {
        // ReflectionTestUtils를 사용하여 @Value 필드에 테스트 값 주입 (Unit Test 환경)
        ReflectionTestUtils.setField(kakaoService, "clientId", "TEST_CLIENT_ID");
        ReflectionTestUtils.setField(kakaoService, "redirectUri", "http://localhost/callback");
        ReflectionTestUtils.setField(kakaoService, "clientSecret", "TEST_SECRET");

        // WebClient 호출 체인의 기본 설정
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.body(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    /**
     * getAccessToken(code) 메서드 검증: 성공적으로 Access Token을 받아오는지 확인
     */
    @Test
    void whenGetAccessToken_thenShouldReturnAccessToken() {
        // Given
        // 1. Mocking: 토큰 API 응답 JSON 생성
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tokenResponse = mapper.createObjectNode()
                .put("access_token", TEST_ACCESS_TOKEN)
                .put("token_type", "bearer")
                .put("expires_in", 21599);
        
        // 2. Mocking: bodyToMono(JsonNode.class) 호출 시 위 토큰 응답 반환
        when(responseSpec.bodyToMono(any(Class.class)))
                .thenReturn(Mono.just(tokenResponse));

        // When
        Mono<String> resultMono = kakaoService.getAccessToken(TEST_CODE);

        // Then
        String resultToken = resultMono.block(); // Mono의 결과를 동기적으로 추출
        
        assertNotNull(resultToken);
        assertEquals(TEST_ACCESS_TOKEN, resultToken, "획득한 Access Token이 예상 값과 일치해야 합니다.");
    }

    /**
     * sendMessageToMe() 메서드 검증: 메시지 전송 API 호출이 성공하고 응답을 반환하는지 확인
     */
    @Test
    void whenSendMessageToMe_thenShouldReturnApiSuccessResponse() {
        // Given
        String successResponseJson = "{\"result_code\":0, \"msg\":\"success\"}";
        
        // Mocking: bodyToMono(String.class) 호출 시 성공 응답 JSON 문자열 반환
        when(responseSpec.bodyToMono(any(Class.class)))
                .thenReturn(Mono.just(successResponseJson));

        // When
        Mono<String> resultMono = kakaoService.sendMessageToMe(TEST_ACCESS_TOKEN, "테스트 메시지");

        // Then
        String resultResponse = resultMono.block();
        
        assertNotNull(resultResponse);
        // 메시지 전송 API의 실제 응답 JSON이 포함되어 있는지 확인
        assert(resultResponse.contains("result_code")); 
        assert(resultResponse.contains("success"));
    }
}