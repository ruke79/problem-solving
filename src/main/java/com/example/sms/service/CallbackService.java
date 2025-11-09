package com.example.sms.service;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.sms.repository.SmsMessageRepository;

import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CallbackService {
    
    private final SmsMessageRepository smsRepository;

    
    public void processFinalStatus(String transactionId, String finalStatus) {
        // 1. 거래 ID를 통해 DB에서 해당 메시지 이력을 조회
        // [TODO] SmsRepository에 findByTransactionId(String id) 메서드가 필요함
        // SmsMessage message = smsRepository.findByTransactionId(transactionId)
        
        // 2. 임시 처리 (실제 DB 조회 및 업데이트 필요)
        System.out.println("CALLBACK RECEIVED: TxID=" + transactionId + ", Final Status=" + finalStatus);
        
        // 3. 최종 상태 업데이트 로직
        // message.setStatus(finalStatus);
        // message.setSentAt(LocalDateTime.now()); // 최종 상태 수신 시간으로 업데이트
        // smsRepository.save(message);
    }
}