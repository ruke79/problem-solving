package io.webboy.verify.labs.resilience;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 최소 구현 멱등성 저장소. 운영에서는 Redis SETNX + TTL 또는 DB 유니크 제약이 같은 역할을 한다.
 */
@Component
public class IdempotencyStore {

    private final Map<String, String> processed = new ConcurrentHashMap<>();

    /** 이 키를 처음 잡은 호출자만 true 를 받는다. */
    public boolean tryAcquire(String key, String owner) {
        return processed.putIfAbsent(key, owner) == null;
    }

    public void clear() {
        processed.clear();
    }

    public int size() {
        return processed.size();
    }
}
