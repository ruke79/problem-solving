package com.example.sms.service;

import com.example.sms.dto.MessageRequest;
import com.example.sms.dto.MessageResponse;
import com.example.sms.dto.SmsMessageDto;
import com.example.sms.model.SmsMessage;
import com.example.sms.repository.SmsMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmsProducerService implements ExternalMessageService {

    private static final String SMS_TOPIC = "sms-topic";

    private final KafkaTemplate<String, SmsMessageDto> kafkaTemplate;

    @Override
    public MessageResponse sendMessage(MessageRequest request) {
        
        // 1. DTO로 변환
        SmsMessageDto smsDto = new SmsMessageDto(
                request.getRecipient(),
                request.getContent()
        );

        // 2. 메시지 큐에 발행 (비동기 처리)
        System.out.println("[PRODUCER] SMS 요청을 큐에 발행: " + request.getRecipient());
        kafkaTemplate.send(SMS_TOPIC, smsDto); // 실제 코드

        // 3. 응답 반환 (성공적으로 큐에 넣었음을 알림)
        return new MessageResponse(true, "SMS 메시지가 큐에 성공적으로 등록되었습니다.");
    }

    @Override
    public String getServiceType() {
        return "SMS";
    }

    @Override
    public boolean validateTemplate(String templateCode) {
        return true;
    }
    
    

}
