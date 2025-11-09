package com.project.sms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 요청된 서비스 타입(예: "SMS", "KAKAO")에 따라 적절한 ExternalMessageService 구현체를 제공하는 팩토리 클래스입니다.
 */
@Component
public class MessageServiceFactory {

    private final Map<String, ExternalMessageService> serviceMap;

    /**
     * Spring 컨테이너에서 ExternalMessageService를 구현한 모든 빈을 주입받아 Map으로 초기화합니다.
     * @param services ExternalMessageService 구현체 리스트
     */
    @Autowired
    public MessageServiceFactory(List<ExternalMessageService> services) {
        // ExternalMessageService의 getServiceType() 메서드 결과를 Map의 키로 사용합니다.
        // 예: "SMS" -> SmsProducerService 인스턴스
        this.serviceMap = services.stream()
                .collect(Collectors.toMap(
                    service -> service.getServiceType().toUpperCase(), 
                    service -> service
                ));
        
        System.out.println("MessageServiceFactory 초기화 완료. 등록된 서비스: " + serviceMap.keySet());
    }

    /**
     * 주어진 서비스 타입에 해당하는 ExternalMessageService 구현체를 반환합니다.
     * @param serviceType 원하는 메시지 서비스 타입 (예: "sms", "KAKAO")
     * @return 해당 타입의 ExternalMessageService 구현체
     * @throws IllegalArgumentException 지원하지 않는 서비스 타입일 경우
     */
    public ExternalMessageService getService(String serviceType) {
        if (serviceType == null) {
            throw new IllegalArgumentException("서비스 타입은 null일 수 없습니다.");
        }
        
        ExternalMessageService service = serviceMap.get(serviceType.toUpperCase());
        
        if (service == null) {
            throw new IllegalArgumentException("지원하지 않는 메시지 서비스 타입입니다: " + serviceType);
        }
        
        return service;
    }

}
