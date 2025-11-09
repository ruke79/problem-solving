package com.example.sms.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.example.sms.dto.SmsMessageDto;

import java.time.LocalDateTime;

// @Entity
// @Getter
// @Setter
// @NoArgsConstructor
// @AllArgsConstructor
// public class SmsMessage {
//     @Id
//     @GeneratedValue(strategy = GenerationType.IDENTITY)
//     private Long id;

//     @Enumerated(EnumType.STRING)
//     private Status status;

//     private String phoneNumber;
//     private String content;
//     private String messageApi;
//     private String resultMessage;
//     private int retryCount = 0;

//     @CreationTimestamp
//     private LocalDateTime createdAt;

//     @UpdateTimestamp
//     private LocalDateTime updatedAt;

//     public enum Status {
//         PENDING, SENT, FAILED, RETRY
//     }

// }

/**
 * 전송 이력 관리를 위해 DB에 저장될 SMS 메시지 엔티티/모델.
 */
public class SmsMessage {

    private Long id;
    private String recipient;
    private String content;
    private String status; // 예: PENDING, SENT, FAILED
    private LocalDateTime requestedAt;
    private LocalDateTime sentAt;

    // 기본 생성자
    public SmsMessage() {
        this.requestedAt = LocalDateTime.now();
        this.status = "PENDING";
    }

    // DTO를 기반으로 엔티티 생성
    public SmsMessage(SmsMessageDto dto) {
        this.recipient = dto.getRecipient();
        this.content = dto.getContent();
        this.requestedAt = LocalDateTime.now();
        this.status = "PENDING";
    }

    // Getters and Setters (생략 가능하나 예시를 위해 일부만 표기)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipient() { return recipient; }
    public String getContent() { return content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }    
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
}