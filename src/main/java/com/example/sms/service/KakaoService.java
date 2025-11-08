package com.example.sms.service;


import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class KakaoService {

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.client-secret}")
    private String clientSecret;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    private final WebClient webClient = WebClient.create();

    // 1. 인가 코드로 Access Token를 교환합니다.
    public Mono<String> getAccessToken(String code) {
        String tokenUrl = "https://kakao.com/oauth/access_token";

        return webClient.post()
                .uri(tokenUrl)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectUri)
                        .with("code", code))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.get("access_token").asText());
    }

    public Mono<String> sendMessageToMe(String accessToken, String message) {
        String messageUrl = "https://kapi.kakao.com/v2/api/talk/memo/default/semd";

        String templateObject = String.format(
                "{\"object_type\":\"text\", \"text\":\"%s\", \"link\":{\"web_url\":\"https://example.com\"}}",
                message
        );

        return webClient.post()
                .uri(messageUrl)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(BodyInserters.fromFormData("template_object", templateObject))
                .retrieve()
                .bodyToMono(String.class);
    }


}
