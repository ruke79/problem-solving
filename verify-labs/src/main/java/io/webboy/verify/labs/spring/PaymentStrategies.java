package io.webboy.verify.labs.spring;

import org.springframework.stereotype.Component;

/** Q96 — 결제 수단을 추가할 때 기존 분기 로직을 건드리지 않는다는 것을 보이기 위한 구현체들. */
public final class PaymentStrategies {

    private PaymentStrategies() {
    }

    @Component("CREDIT_CARD")
    public static class CreditCard implements PaymentStrategy {
        @Override
        public String pay(long amount) {
            return "CREDIT_CARD:" + amount;
        }
    }

    @Component("PAYPAY")
    public static class PayPay implements PaymentStrategy {
        @Override
        public String pay(long amount) {
            return "PAYPAY:" + amount;
        }
    }

    /** "나중에 추가된 신규 수단" 역할. 라우터 코드는 이 클래스의 존재를 모른다. */
    @Component("BANK_TRANSFER")
    public static class BankTransfer implements PaymentStrategy {
        @Override
        public String pay(long amount) {
            return "BANK_TRANSFER:" + amount;
        }
    }
}
