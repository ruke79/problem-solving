package com.example.sms.service;

import com.example.sms.model.SmsMessage;
import com.example.sms.repository.SmsMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmsProducerService {

    private final KafkaTemplate<String, SmsMessage> kafkaTemplate;
    private final SmsMessageRepository smsMessageRepository;

    public SmsMessage sendSms(SmsMessage smsMessage) {
       smsMessage.setStatus(SmsMessage.Status.PENDING);
       SmsMessage savedMessage = smsMessageRepository.save(smsMessage);

       kafkaTemplate.send("sms-topic", savedMessage);
       return savedMessage;
    }

}
