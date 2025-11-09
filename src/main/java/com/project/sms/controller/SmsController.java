package com.project.sms.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.project.sms.dto.MessageRequest;
import com.project.sms.dto.MessageResponse;
import com.project.sms.dto.SmsMessageDto;
import com.project.sms.model.SmsMessage;
import com.project.sms.service.SmsProducerService;

@RestController
@RequiredArgsConstructor
public class SmsController {

    private final SmsProducerService smsProducerService;

    @PostMapping("/api/v1/sms/send")
    public ResponseEntity<MessageResponse> sendSms(@RequestBody MessageRequest messageRequest) {
        
        MessageResponse response = smsProducerService.sendMessage(messageRequest);
        
        // Kafka에 성공적으로 등록되면 200 OK와 응답 객체를 반환
        if (response.isSuccess()) {
             return ResponseEntity.ok(response);
        } else {
             // 실제로는 500 Internal Server Error 등을 반환할 수 있습니다.
             return ResponseEntity.badRequest().body(response); 
        }
    }
}
