package com.project.sms.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.project.sms.dto.MessageRequest;
import com.project.sms.dto.MessageResponse;

import lombok.RequiredArgsConstructor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class KakaoService  {

    private final WebClient webClient;

    // [추가] 카카오 토큰 발급에 필요한 설정 값
    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;
    
    @Value("${kakao.client-secret:}") // client-secret이 없는 경우를 대비해 기본값 설정
    private String clientSecret;

    private final KakaoMessageService kakaoMessageService;
    
    // 카카오 인증 서버 URL
    private static final String KAUTH_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    // [추가] 카카오 메시지 API URL (나에게 보내기)
    private static final String KAPI_MESSAGE_URL = "https://kapi.kakao.com/v2/api/talk/memo/default/send";
    
    // [기존 KakaoService의 비즈니스 로직 메서드]
    public MessageResponse sendKakaoMessageWithLogging(MessageRequest request) {
        
        // 1. 비즈니스 로직 처리 (예: 전송 전 로그 기록)
        System.out.println("전송 전 로그를 기록합니다.");
        
        // 2. 실제 전송 로직은 KakaoMessageService에 위임
        MessageResponse response = kakaoMessageService.sendMessage(request);
        
        // 3. 비즈니스 로직 처리 (예: 전송 후 상태 업데이트)
        System.out.println("전송 후 상태를 업데이트합니다.");
        
        return response;
    }

    /**
     * 카카오 인증 코드(code)를 사용하여 Access Token을 획득합니다.
     * 이는 Controller의 kakaoCallback에서 호출됩니다.
     */
    public Mono<String> getAccessToken(String code) {
        
        // 1. WebClient를 사용하여 POST 요청 생성
        return webClient.post()
                .uri(KAUTH_TOKEN_URL)
                // 2. Body에 Authorization Code Grant Type에 필요한 파라미터 구성
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", clientId)
                        .with("redirect_uri", redirectUri)
                        .with("code", code) // Controller에서 받은 인증 코드
                        .with("client_secret", clientSecret) // 필요 시 client_secret 포함
                )
                .retrieve()
                // 3. API 통신 중 발생하는 HTTP 오류 처리 (4xx, 5xx)
                .onStatus(status -> status.isError(), 
                          response -> response.bodyToMono(String.class)
                                              .flatMap(body -> Mono.error(new RuntimeException("Kakao Token API Error: " + body))))
                // 4. 응답 본문을 JsonNode로 받아 Access Token 추출
                .bodyToMono(JsonNode.class) 
                .map(jsonNode -> jsonNode.get("access_token").asText())
                .doOnError(e -> System.err.println("Kakao Access Token 발급 중 예외 발생: " + e.getMessage()));
    }

    /**
     * Access Token을 사용하여 로그인한 사용자 본인에게 메시지를 전송합니다.
     * @param accessToken 인증 토큰
     * @param message 전송할 텍스트 메시지
     * @return API 응답 (JSON 문자열)
     */
    public Mono<String> sendMessageToMe(String accessToken, String message) {
        
        // 1. 기본 텍스트 템플릿 JSON 구조 정의
        String templateJson = String.format(
            "{\"object_type\":\"text\",\"text\":\"%s\",\"link\":{\"web_url\":\"%s\",\"mobile_web_url\":\"%s\"}}",
            message, "https://developers.kakao.com", "https://developers.kakao.com"
        );

        // 2. 메시지 본문(template_object)은 URL 인코딩 필요
        String templateObject;
        try {
            templateObject = URLEncoder.encode(templateJson, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return Mono.error(new RuntimeException("메시지 인코딩 오류: " + e.getMessage()));
        }

        // 3. WebClient 호출
        return webClient.post()
                .uri(KAPI_MESSAGE_URL)
                .header("Authorization", "Bearer " + accessToken) // 인증 토큰 사용
                .header("Content-Type", "application/x-www-form-urlencoded") // 필수 헤더
                .body(BodyInserters.fromFormData("template_object", templateObject))
                .retrieve()
                // API 호출 오류 처리
                .onStatus(status -> status.isError(), 
                          response -> response.bodyToMono(String.class)
                                              .flatMap(body -> Mono.error(new RuntimeException("Kakao Message API Error: " + body))))
                .bodyToMono(String.class) // 메시지 전송 API 응답 (JSON string)
                .doOnError(e -> System.err.println("나에게 메시지 전송 중 예외 발생: " + e.getMessage()));
    }
    
    // KakaoMessageService의 고유 기능을 KakaoService에서 직접 활용 가능
    public boolean checkTemplate(String code) {
        return kakaoMessageService.validateTemplate(code);
    }


}
