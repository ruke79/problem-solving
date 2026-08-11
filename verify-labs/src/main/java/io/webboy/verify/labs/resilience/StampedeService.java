package io.webboy.verify.labs.resilience;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StampedeService {

    public static final String UNSYNC_CACHE = "stampede-unsync";
    public static final String SYNC_CACHE = "stampede-sync";
    private static final long LOAD_MILLIS = 300L;

    private final AtomicInteger unsyncLoads = new AtomicInteger();
    private final AtomicInteger syncLoads = new AtomicInteger();

    @Cacheable(cacheNames = UNSYNC_CACHE, key = "#key")
    public String loadWithoutSync(String key) {
        unsyncLoads.incrementAndGet();
        sleep();
        return "value:" + key;
    }

    @Cacheable(cacheNames = SYNC_CACHE, key = "#key", sync = true)
    public String loadWithSync(String key) {
        syncLoads.incrementAndGet();
        sleep();
        return "value:" + key;
    }

    public int unsyncLoads() {
        return unsyncLoads.get();
    }

    public int syncLoads() {
        return syncLoads.get();
    }

    private void sleep() {
        try {
            Thread.sleep(LOAD_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
