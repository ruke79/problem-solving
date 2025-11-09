package com.project.sms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.project.sms.dto.KakaoMessageRequest;
import com.project.sms.dto.MessageRequest;
import com.project.sms.dto.MessageResponse;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;


/*
    DESC : 카카오 알림톡 발생( 비지니스채널 신청 필요)
 */

@Service
@RequiredArgsConstructor
public class KakaoMessageService implements ExternalMessageService, KakaoTemplateValidator {

    private final WebClient webClient;

    @Value("${kakao.biz-message.access-token-url}")
    private String kaakoTokenUrl;

    @Value("${kakao.biz-message.app-key}")
    private String appKey;

    @Value("${kakao.biz-message.client-secret}")
    private String clientSecret;

    @Value("${kakao.biz-message.template-code}")
    private String templateCode;

    // Base URL을 상수 또는 설정에서 주입받아 사용하면 좋습니다.
    private static final String KAUTH_BASE_URL = "https://kauth.kakao.com";
    private static final String KAPI_BASE_URL = "https://kapi.kakao.com";

    // --- 1. Access Token 획득 메서드 추가 (누락된 부분) ---
    // 비즈메시지 API는 일반적으로 Client Credentials Grant Type을 사용하여 토큰을 받습니다.
    public Mono<String> getAccessToken() {
        // 실제 카카오 토큰 발급 URL로 가정 (Client Credentials Grant Type)
        String url = "https://kauth.kakao.com/oauth/token";

        return webClient.post()
                .uri(url)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", appKey) // app-key를 client_id로 사용
                        .with("client_secret", clientSecret))
                .retrieve()
                .bodyToMono(Map.class) // 응답을 Map으로 받아 Access Token 추출
                .map(response -> (String) response.get("access_token"));
    }

    //알림톡 발송
    public Mono<Void> sendAlimtalk(String receverUuid, String content) {
        return getAccessToken()
        .flatMap(accessToken-> {
            return executeAlimtalkRequest(accessToken, receverUuid, content);
        });
    }

    /**
 * Access Token을 사용하여 실제 카카오 알림톡 전송 API를 호출합니다.
 */
private Mono<Void> executeAlimtalkRequest(String accessToken, String receverUuid, String content) {
    String sendMessageUrl = KAPI_BASE_URL + "/v1/api/talk/bizmessage/template/send"; // KAPI_BASE_URL 사용
    
    // 알림톡 변수 구성
    Map<String, String> templateVariables = Collections.singletonMap("#{content}", content); // 변수명 확인 필요: #{content}
    
    return webClient.post()
        .uri(sendMessageUrl)
        .header("Authorization", "Bearer " + accessToken)
        .body(BodyInserters.fromValue(
            new KakaoMessageRequest(templateCode,
                Collections.singletonList(receverUuid), null, templateVariables)
        ))
        .retrieve()
        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), 
                  response -> response.bodyToMono(String.class)
                                      .flatMap(body -> Mono.error(new RuntimeException("Kakao API Error: " + body))))
        .bodyToMono(Void.class)
        .doOnError(e -> System.err.println("알림톡 전송 중 오류 발생: " + e.getMessage()));
}

    @Override
    public MessageResponse sendMessage(MessageRequest request) {
        
        // 실제 카카오 API 호출 로직이 여기에 들어갑니다.
        System.out.println("카카오 메시지를 외부 API를 통해 전송합니다: " + request.getContent());

        sendAlimtalk(request.getRecipient(), request.getContent())
            .subscribe(); // 비동기 호출
        
        // 전송 결과 반환
        return new MessageResponse(true, "Kakao 메시지 전송 완료 by MessageService");
    }

    @Override
    public String getServiceType() {
        return "KAKAO";
    }

    @Override
    public boolean validateTemplate(String templateCode) {
        // Kakao 알림톡의 템플릿 검증 로직 구현
        if (templateCode != null && templateCode.startsWith("T_VALID_")) {
            System.out.println("[KAKAO Validation] 템플릿 코드 '" + templateCode + "' 유효함.");
            return true;
        }
        System.err.println("[KAKAO Validation] 템플릿 코드 유효하지 않음.");
        return false;
    }
}
