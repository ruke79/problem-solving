package com.example.sms;

import com.example.sms.dto.SmsMessageDto;
import com.example.sms.model.SmsMessage;
import com.example.sms.service.SmsProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SmsController {

    private final SmsProducerService smsProducerService;

    @PostMapping("/api/v1/sms/send")
    public ResponseEntity<String> sendSms(@RequestBody SmsMessageDto smsMessageDto) {
        SmsMessage smsMessage = smsProducerService.sendSms(
                new SmsMessage(null, null, smsMessageDto.getPhoneNumber(), smsMessageDto.getContent(), "NAVER_SENS", null, 0, null, null)
        );
        return ResponseEntity.ok("SMS 발송 요청이 접수되었습니다. ID: " + smsMessage.getId());
    }
}
