package com.example.sms.repository;

import com.example.sms.model.SmsMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

    // Spring Data JPA는 이 외에도 메서드 이름 규칙(Naming convention)을 통해
    // findByRecipientAndStatus(String recipient, String status) 등 다양한 쿼리 메서드를 자동 생성할 수 있습니다.
    
    // 예시: 특정 상태의 메시지 목록을 찾는 쿼리 메서드
    // List<SmsMessage> findByStatus(String status);
}
