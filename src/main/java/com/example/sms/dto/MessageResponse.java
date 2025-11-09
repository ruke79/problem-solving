package com.example.sms.dto;

/**
 * 외부 메시지 전송 결과를 담는 클래스입니다.
 */
public class MessageResponse {

    private boolean success; // 전송 성공 여부
    private String message;  // 결과 메시지 (성공/실패 상세 내용)
    private String transactionId; // 전송 트랜잭션 ID (외부 시스템에서 부여한 ID)

    // 기본 생성자
    public MessageResponse() {
    }

    // 주요 필드를 포함하는 생성자
    public MessageResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    // 모든 필드를 포함하는 생성자
    public MessageResponse(boolean success, String message, String transactionId) {
        this.success = success;
        this.message = message;
        this.transactionId = transactionId;
    }

    // Getters and Setters

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
