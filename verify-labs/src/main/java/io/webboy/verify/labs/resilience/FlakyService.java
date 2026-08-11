package io.webboy.verify.labs.resilience;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/** 처음 N 번은 실패하고 그 뒤 성공하는, 일시적 장애를 흉내낸 서비스. */
@Service
public class FlakyService {

    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger sideEffects = new AtomicInteger();

    public String call(int failFirst) {
        int attempt = attempts.incrementAndGet();
        if (attempt <= failFirst) {
            throw new IllegalStateException("일시적 실패 #" + attempt);
        }
        sideEffects.incrementAndGet();
        return "ok@attempt-" + attempt;
    }

    public int attempts() {
        return attempts.get();
    }

    public int sideEffects() {
        return sideEffects.get();
    }

    public void reset() {
        attempts.set(0);
        sideEffects.set(0);
    }
}
