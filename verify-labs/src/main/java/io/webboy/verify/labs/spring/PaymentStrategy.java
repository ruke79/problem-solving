package io.webboy.verify.labs.spring;

public interface PaymentStrategy {
    String pay(long amount);
}
