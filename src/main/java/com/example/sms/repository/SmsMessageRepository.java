package com.example.sms.repository;

import com.example.sms.model.SmsMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

    List<SmsMessage> findByStatus(SmsMessage.Status status);
}
