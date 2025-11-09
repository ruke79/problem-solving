package com.project.sms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.project.sms.dto.SmsMessageDto;
import com.project.sms.util.NcpSignatureUtil;

import java.util.HashMap;
import java.util.Map;

@Component // Spring Bean으로 등록
public class NaverSensSmsGateway implements SmsGateway {

    // [TODO] 실제 사용 시 Spring @Value 또는 Configuration Properties로 설정 주입 필요
    private final String baseUrl = "https://sens.apigw.ntruss.com"; // 기본 URL 분리
    private final String urlPath = "/sms/v2/services/{serviceId}/messages"; // API Path
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

        
        // SENS API에 맞는 요청 헤더 및 본문 구성
        // (SENS API는 인증을 위해 Signature 생성 및 헤더에 포함하는 복잡한 과정이 필요합니다.
        // 여기서는 REST 통신 구조만 시뮬레이션합니다.)

        // 최종 요청 URL 생성
        String apiUrl = baseUrl + urlPath.replace("{serviceId}", serviceId);
        String urlForSignature = urlPath.replace("{serviceId}", serviceId); // Signature 생성용 URL Path

        // 1. Signature 생성에 필요한 값 준비
        String timestamp = String.valueOf(System.currentTimeMillis());
        String method = "POST";

        // 2. NcpSignatureUtil을 사용하여 Signature 생성 (가장 중요한 단계)
        String signature = NcpSignatureUtil.makeSignature(
            secretKey,
            method,
            urlForSignature,
            timestamp,
            accessKey
        );

        // 3. HttpHeaders 설정 (인증 헤더 포함)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-ncp-apigw-timestamp", timestamp);
        headers.set("x-ncp-iam-access-key", accessKey);
        headers.set("x-ncp-apigw-signature-v2", signature); // 생성된 Signature 주입!
        
        // 4. 요청 본문(Payload) 및 HttpEntity 결합
        Map<String, Object> requestPayload = createSensPayload(smsDto);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestPayload, headers);

        try {            
            // 5. RestTemplate.exchange()를 사용하여 헤더와 본문이 담긴 요청 전송
            ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity, // HttpEntity에 헤더와 본문이 포함됨                    
                Map.class // 응답 본문 타입
            );

            // 6. 응답 분석 (SENS는 보통 202 Accepted를 반환)
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
}