package com.project.sms.dto;

/**
 * 외부 메시지 전송을 요청하는 데이터를 담는 클래스입니다.
 */
public class MessageRequest {

    private String recipient; // 수신자 (전화번호, 이메일 주소 등)
    private String content;   // 메시지 내용
    private String serviceType; // 사용될 서비스 타입 (예: "SMS", "KAKAO")
    private String templateCode; // (카카오톡 알림톡 등) 템플릿 코드

    // 기본 생성자
    public MessageRequest() {
    }

    // 모든 필드를 포함하는 생성자
    public MessageRequest(String recipient, String content, String serviceType, String templateCode) {
        this.recipient = recipient;
        this.content = content;
        this.serviceType = serviceType;
        this.templateCode = templateCode;
    }

    // Getters and Setters

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    @Override
    public String toString() {
        return "MessageRequest{" +
                "recipient='" + recipient + '\'' +
                ", serviceType='" + serviceType + '\'' +
                ", content='" + (content != null && content.length() > 20 ? content.substring(0, 20) + "..." : content) + '\'' +
                '}';
    }
}
