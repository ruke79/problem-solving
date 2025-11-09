package com.project.sms.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.project.sms.service.KakaoService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class KakaoController {

    private final KakaoService kakaoService;

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.redirect-uri")
    private String redirectUri;
    

    /**
     * 카카오 로그인 시작 엔드포인트
     * 접근 주소: http://localhost:8080/kakao/login
     */
    @GetMapping("/kakao/login")
    public String kakaoLogin() {

        String authorizationUrl = String.format(
                "https://kauth.kakao.com/oauth/authorize?client_id=%s&redirect_uri=%s&response_type=code&scope=talk_message",
                clientId, redirectUri
        );
        return "redirect:" + authorizationUrl;
    }

    /**
     * 카카오 콜백 처리 엔드포인트
     * 개발자 센터에 등록된 URI: /kakao/callback
     */
    @GetMapping("/kakao/callback")
    @ResponseBody
    public Mono<String> kakaoCallback(@RequestParam("code") String code) {

        return kakaoService.getAccessToken(code)
                .flatMap(accessToken -> {
                    String message = "카카오 로그인 후 메시지 전송 테스트입니다. 성공! 🎉";
                    return kakaoService.sendMessageToMe(accessToken, message)
                    .map(response -> "메시지 전송 성공 (카카오톡 확인 필요). 응답: " + response)
                            .onErrorResume(e->Mono.just("메시지 전송 실패: " + e.getMessage()));
                })
                .onErrorResume(e -> Mono.just("Access Token 획득 실패: " + e.getMessage()));
    }
}
