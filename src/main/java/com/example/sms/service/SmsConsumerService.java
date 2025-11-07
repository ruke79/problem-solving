package com.example.sms.service;


import com.example.sms.model.SmsMessage;
import com.example.sms.repository.SmsMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmsConsumerService {

    private final SmsMessageRepository smsMessageRepository;
    private final ExternalMessageService externalMessageService;

    @KafkaListener(topics="sms-topic", groupId = "sms-group")
    public void consume(SmsMessage smsMessage) {
        try {
            externalMessageService.sendViaNaverSens(smsMessage.getPhoneNumber(), smsMessage.getContent());
            smsMessage.setStatus(SmsMessage.Status.SENT);
            smsMessage.setResultMessage("Success");
            smsMessageRepository.save(smsMessage);
        } catch(Exception e) {

            smsMessage.setRetryCount(smsMessage.getRetryCount() + 1);
            smsMessage.setResultMessage("Fail" + e.getMessage());

            if(smsMessage.getRetryCount() < 3) {
                smsMessage.setStatus(SmsMessage.Status.RETRY);
            } else {
                smsMessage.setStatus(SmsMessage.Status.FAILED);
            }
            smsMessageRepository.save(smsMessage);
        }

    }

}
