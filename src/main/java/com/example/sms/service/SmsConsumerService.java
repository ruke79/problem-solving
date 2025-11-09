package com.example.sms.service;


import com.example.sms.dto.SmsMessageDto;
import com.example.sms.model.SmsMessage;
import com.example.sms.repository.SmsMessageRepository;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmsConsumerService {

    private final SmsGateway smsGateway;

    private final SmsMessageRepository smsMessageRepository;
    private final ExternalMessageService externalMessageService;

    @KafkaListener(topics="sms-topic", groupId = "sms-group")
    public void consumeSmsRequest(SmsMessageDto smsDto) {
     
        System.out.println("\n[CONSUMER] Kafka 메시지를 수신: " + smsDto.getRecipient());

        // 1. DB에 'PENDING' 상태로 이력 저장 (SmsMessage 엔티티 사용)
        SmsMessage smsEntity = new SmsMessage(smsDto);
                
        SmsMessage savedEntity = smsMessageRepository.save(smsEntity); 
        System.out.println("   [DB] 메시지 이력 ID " + savedEntity.getId() + " (PENDING) 저장 완료.");

        try {
            // 2. 외부 SMS API 호출 (SmsGatewayService 등의 클래스 사용 가능)
            boolean success = smsGateway.sendSms(smsDto);
            
            // 3. 전송 결과에 따라 DB 상태 업데이트
            if (success) {
                smsEntity.setStatus("SENT");
                smsEntity.setSentAt(LocalDateTime.now());
                System.out.println("[CONSUMER] SMS 전송 성공: " + smsDto.getRecipient());
            } else {
                smsEntity.setStatus("FAILED");
                System.err.println("[CONSUMER] SMS 전송 실패: " + smsDto.getRecipient());
            }
        } catch (Exception e) {
            smsEntity.setStatus("FAILED");
            System.err.println("[CONSUMER] SMS 처리 중 시스템 예외 발생: " + e.getMessage());
        } finally {
            // 4. 최종 상태 업데이트
            smsMessageRepository.save(savedEntity); 
            System.out.println("   [DB] 메시지 이력 ID " + savedEntity.getId() + " (" + savedEntity.getStatus() + ") 최종 업데이트 완료.");
        }
    }

}
