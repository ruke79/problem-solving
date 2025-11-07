package com.example.sms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class ExternalMessageServiceImpl  implements ExternalMessageService {

    @Override
    public void sendSms(String toPhoneNumber, String content) throws Exception {

        // 실제 API 호출 로직 (예: RestTemplate 또는 WebClient 사용)
        System.out.println("DEBUG: [External API] " + toPhoneNumber + "로 메시지 발송 시도: " + content);

        // 시뮬레이션을 위해 30% 확률로 실패하도록 가정
//        if (Math.random() < 0.3) {
//            throw new RuntimeException("외부 API 통신 오류 발생 (시뮬레이션)");
//        }

        System.out.println("DEBUG: [External API] 발송 성공!");
        // 실제로는 외부 API의 성공 응답을 파싱하는 로직이 필요합니다.
    }

    @Override
    public void sendViaNaverSens(String toPhoneNumber, String content) throws Exception {
        // 실제 네이버 SENS API 키 인증 및 호출 로직
        sendSms(toPhoneNumber, content);
    }

}
