package com.project.sms.service;

import com.project.sms.dto.SmsMessageDto;

/**
 * 외부 SMS 게이트웨이와의 통신을 담당하는 인터페이스입니다.
 * 이 인터페이스를 분리하여 테스트 환경에서 실제 API 호출 없이 mock 구현체를 사용할 수 있게 합니다.
 */
public interface SmsGateway {

    /**
     * 외부 게이트웨이를 통해 SMS 메시지를 전송합니다.
     * @param smsDto 전송할 메시지 DTO
     * @return 전송 성공 여부
     */
    boolean sendSms(SmsMessageDto smsDto);
}