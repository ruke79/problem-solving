package com.project.sms.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.io.UnsupportedEncodingException;
import java.util.Base64;

/**
 * NAVER Cloud Platform SENS API 호출을 위한 Signature 생성 유틸리티
 * HMAC-SHA256 알고리즘 사용
 */
public class NcpSignatureUtil {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String ENCODING = "UTF-8";

    /**
     * SENS API 호출에 필요한 디지털 서명(Signature)을 생성합니다.
     * * @param secretKey     NAVER Cloud Platform에서 발급받은 Secret Key
     * @param method        HTTP 메소드 (예: "POST")
     * @param url           API 요청 URL (Query String 제외, 예: "/sms/v2/services/{serviceId}/messages")
     * @param timestamp     현재 시간 (millisecond 단위의 문자열)
     * @param accessKey     NAVER Cloud Platform에서 발급받은 Access Key
     * @return              Base64 인코딩된 Signature 문자열
     */
    public static String makeSignature(String secretKey, String method, String url, String timestamp, String accessKey) {
        String signature;

        // 1. 시그니처 메시지 조합
        // 조합 순서: {method} {space} {url} {new line} {timestamp} {new line} {accessKey}
        String message = new StringBuilder()
                .append(method)
                .append(" ")
                .append(url)
                .append("\n")
                .append(timestamp)
                .append("\n")
                .append(accessKey)
                .toString();

        try {
            // 2. Secret Key를 기반으로 HMAC-SHA256 암호화 준비
            SecretKeySpec signingKey = new SecretKeySpec(secretKey.getBytes(ENCODING), ALGORITHM);
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(signingKey);

            // 3. 메시지 암호화 및 Base64 인코딩
            byte[] rawHmac = mac.doFinal(message.getBytes(ENCODING));
            signature = Base64.getEncoder().encodeToString(rawHmac);

        } catch (NoSuchAlgorithmException | InvalidKeyException | UnsupportedEncodingException e) {
            // 실제 애플리케이션에서는 로깅 후 적절한 예외 처리 (e.g., RuntimeException 발생) 필요
            throw new RuntimeException("NAVER Cloud Signature 생성 중 오류 발생: " + e.getMessage());
        }

        return signature;
    }
}
