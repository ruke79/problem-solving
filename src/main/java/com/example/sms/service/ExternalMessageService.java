package com.example.sms.service;

public interface ExternalMessageService {

    /**
     * 외부 메시징 API를 통해 SMS를 발송합니다.
     * @param toPhoneNumber 수신자 전화번호
     * @param content 메시지 내용
     * @throws Exception API 통신 실패 또는 응답 오류 시 발생
     */
    void sendSms(String toPhoneNumber, String content) throws Exception;

    // 네이버 SENS 전용 구현 메서드 (필요에 따라 인터페이스에 포함)
    void sendViaNaverSens(String toPhoneNumber, String content) throws Exception;
}