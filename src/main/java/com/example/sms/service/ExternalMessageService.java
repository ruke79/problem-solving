package com.example.sms.service;

import com.example.sms.dto.MessageRequest;
import com.example.sms.dto.MessageResponse;

public interface ExternalMessageService {

   /**
     * 외부 메시지 전송을 처리합니다.
     * (기존 KakaoMessageService의 핵심 메서드를 반영)
     * @param request 전송할 메시지 내용, 수신자 등의 정보를 담은 객체
     * @return 전송 결과 상태를 담은 응답 객체
     */
    MessageResponse sendMessage(MessageRequest request);

    /**
     * 서비스 타입 (예: "SMS", "EMAIL", "KAKAO")을 반환합니다.
     */
    String getServiceType();    
    
}