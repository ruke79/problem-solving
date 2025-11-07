package com.example.sms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.sms.dto.KakaoMessageRequest;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class KakaoMessageService {

    private final WebClient Webclient;


    @Value("${kakao.biz-message,access-token-url}")
    private String kaakoTokenUrl;

    @Value("${kakao.biz-message.app-key}")
    private String appKey;

    @Value("${kakao.biz-nessage.client-secret}")
    private String clientSecret;

    @Value("${kakao.biz-message.template-code}")
    private String templateCode;

    //알림톡 발송
    public Mono<Void> sendAlimtalk(String receverUuid, String content) {
        return getAccessToken()
        .flatMap(accessToken->
        String sendMessageUrl = 
        "https://kapi.kakao.com/v1/api/talk/bitmessage/template/send";
        Map<String, String> templateVariables = Collections.singletonMap("#(content)"),
        content);
        return WebClient.post()
        .uri(sendMessageUrl)    
        .header("Authorization", "Bearer " + accessToken)
        .body(BodyInserters.fromValue(
            new KakaoMessageRequest(tenoplateCode, 
            Collections.singletonList(receverUuid), null,templateVariables)
        ))
        .retrieve()
        .bodyToMono(Void.class);
    }

}
