package com.project.sms.dto;

// import jakarta.validation.constraints.NotBlank;
// import jakarta.validation.constraints.Pattern;
// import lombok.Getter;
// import lombok.Setter;

// @Getter
// @Setter
// public class SmsMessageDto {

//     @NotBlank(message="전화번호는 필수입니다.")
//     @Pattern(regexp= "^01(?:0|1|[6-9])(?:\\d{3}|\\d{4}\\d{4}$", message="유효하지 않은 전화번호 형식입니다.")
//     private String phoneNumber;

//     @NotBlank(message="메시지 내용은 필수입니다.")
//     private String content;
// }


import java.io.Serializable;

/**
 * 메시지 큐(Queue)를 통해 전송될 SMS 요청 데이터를 담는 DTO.
 * 직렬화(Serializable)가 가능해야 합니다.
 */
public class SmsMessageDto implements Serializable {

    private static final long serialVersionUID = 1L; // 직렬화 ID

    private String recipient; // 수신자 전화번호
    private String content;   // 메시지 내용
    private String type;     // 추가: SMS, LMS, MMS 구분
    private String subject;  // 추가: LMS, MMS용 제목

    // 기본 생성자
    public SmsMessageDto() {
    }

    // 생성자
    public SmsMessageDto(String recipient, String content, String type, String subject) {
        this.recipient = recipient;
        this.content = content;
        this.type = type;
        this.subject = subject;
    }

    // Getters
    public String getRecipient() {
        return recipient;
    }

    public String getContent() {
        return content;
    }

    // Setters
    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }           
    public void setType(String type) {
        this.type = type;
    }
    public String getSubject() {
        return subject;
    }
    public void setSubject(String subject) {
        this.subject = subject;
    }
}