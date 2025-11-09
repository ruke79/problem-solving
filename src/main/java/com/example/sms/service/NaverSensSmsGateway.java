package com.example.sms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.example.sms.dto.SmsMessageDto;

import java.util.HashMap;
import java.util.Map;

@Component // Spring Bean으로 등록
public class NaverSensSmsGateway implements SmsGateway {

    // [TODO] 실제 사용 시 Spring @Value 또는 Configuration Properties로 설정 주입 필요
    private final String sensUrl = "https://sens.apigw.ntruss.com/sms/v2/services/{serviceId}/messages";
    private final String serviceId = "YOUR_SERVICE_ID";
    private final String accessKey = "YOUR_ACCESS_KEY";
    private final String secretKey = "YOUR_SECRET_KEY";
    private final String fromNumber = "YOUR_SENDER_PHONE_NUMBER"; // 발신 번호

    private final RestTemplate restTemplate = new RestTemplate(); // 실제 통신 객체

    /**
     * NAVER Cloud SENS API를 호출하여 SMS 메시지를 전송합니다.
     */
    @Override
    public boolean sendSms(SmsMessageDto smsDto) {
        
        System.out.println("   [SENS API CALL] SENS 서버로 SMS 전송 요청 중...");
        
        // 1. SENS API에 맞는 요청 헤더 및 본문 구성
        // (SENS API는 인증을 위해 Signature 생성 및 헤더에 포함하는 복잡한 과정이 필요합니다.
        // 여기서는 REST 통신 구조만 시뮬레이션합니다.)
        
        Map<String, Object> requestPayload = createSensPayload(smsDto);
        
        try {
            // 2. SENS API 호출 (POST)
            // 실제 SENS API는 인증 헤더 설정이 매우 중요하나, 예제에서는 단순화
            ResponseEntity<Map> response = restTemplate.postForEntity(
                sensUrl.replace("{serviceId}", serviceId), 
                requestPayload, 
                Map.class // 응답 본문 타입
            );

            // 3. 응답 분석 (SENS는 보통 202 Accepted를 반환)
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("   [SENS SUCCESS] 전송 요청 성공. 수신자: " + smsDto.getRecipient());
                return true;
            } else {
                System.err.println("   [SENS FAIL] SENS API 호출 실패 (HTTP Status: " + response.getStatusCode() + ")");
                return false;
            }

        } catch (Exception e) {
            System.err.println("   [SENS ERROR] SENS 통신 중 예외 발생: " + e.getMessage());
            return false;
        }
    }
    
    // SENS API 요청 본문 생성 (예시)
    private Map<String, Object> createSensPayload(SmsMessageDto smsDto) {
        Map<String, Object> message = new HashMap<>();
        message.put("to", smsDto.getRecipient());
        message.put("content", smsDto.getContent());

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "SMS");
        payload.put("contentType", "COMM"); // 일반 통신
        payload.put("countryCode", "82");
        payload.put("from", fromNumber);
        payload.put("messages", new Object[]{message});
        
        return payload;
    }

    // [TODO] 실제 SENS 인증을 위한 Signature 생성 로직은 별도 유틸리티 클래스에 구현되어야 함
}