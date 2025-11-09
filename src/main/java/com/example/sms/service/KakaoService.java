package com.example.sms.service;


import com.example.sms.dto.MessageRequest;
import com.example.sms.dto.MessageResponse;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class KakaoService  {

    private final ExternalMessageService kakaoMessageService;
    
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
    
    // KakaoMessageService의 고유 기능을 KakaoService에서 직접 활용 가능
    public boolean checkTemplate(String code) {
        return kakaoMessageService.validateTemplate(code);
    }


}
