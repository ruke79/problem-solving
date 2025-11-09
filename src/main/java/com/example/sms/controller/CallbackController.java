package com.example.sms.controller;


import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.sms.service.CallbackService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/callback")
@RequiredArgsConstructor
public class CallbackController {
    
    private final CallbackService callbackService;

    

    /**
     * NAVER SENS 또는 Kakao의 최종 전송 결과(Delivery Report)를 수신하는 End-Point
     */
    @PostMapping("/delivery-report")
    public ResponseEntity<Void> handleDeliveryReport(@RequestBody Map<String, Object> reportData) {
        
        // [TODO] reportData에서 거래 ID(transactionId)와 최종 상태(DELIVERED/FAILED)를 추출
        String transactionId = (String) reportData.get("transactionId");
        String finalStatus = (String) reportData.get("status");

        callbackService.processFinalStatus(transactionId, finalStatus);
        
        return ResponseEntity.ok().build();
    }
}