package io.webboy.verify.labs.spring;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 분기문이 한 줄도 없는 라우터.
 * Spring 이 {@code Map<빈이름, PaymentStrategy>} 로 모든 구현체를 주입해 준다.
 */
@Service
public class PaymentRouter {

    private final Map<String, PaymentStrategy> strategies;

    public PaymentRouter(Map<String, PaymentStrategy> strategies) {
        this.strategies = strategies;
    }

    public String pay(String type, long amount) {
        PaymentStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("지원하지 않는 결제 수단: " + type);
        }
        return strategy.pay(amount);
    }

    public Set<String> supportedTypes() {
        return strategies.keySet();
    }
}
