package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class CacheStampedeCase extends VerificationCase {

    private static final int CONCURRENT_READERS = 16;

    private final StampedeService service;
    private final CacheManager cacheManager;

    public CacheStampedeCase(StampedeService service, CacheManager cacheManager) {
        this.service = service;
        this.cacheManager = cacheManager;
    }

    @Override
    public String id() {
        return "RES-02";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "캐시 스탬피드(thundering herd)가 무엇이고 어떻게 막습니까?";
    }

    @Override
    public String claim() {
        return "캐시 미스 순간 동시 요청이 전부 원본을 때린다. @Cacheable(sync=true) 는 키 단위로 로딩을 1회로 묶는다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String key = "k-" + UUID.randomUUID();

        int unsyncBefore = service.unsyncLoads();
        stormWith(k -> service.loadWithoutSync(k), key);
        int unsyncLoads = service.unsyncLoads() - unsyncBefore;

        int syncBefore = service.syncLoads();
        stormWith(k -> service.loadWithSync(k), key);
        int syncLoads = service.syncLoads() - syncBefore;

        evidence.fact("동시 요청 수", CONCURRENT_READERS);
        evidence.fact("CacheManager 구현", cacheManager.getClass().getSimpleName());
        evidence.fact("sync=false 일 때 원본 호출 횟수", unsyncLoads);
        evidence.fact("sync=true 일 때 원본 호출 횟수", syncLoads);

        evidence.expectEquals("sync=true 는 로딩을 1회로 묶는다", 1, syncLoads);
        evidence.expectFlaky("sync=false 는 동시 미스만큼 원본을 호출한다", unsyncLoads > 1);

        evidence.note("sync=true 는 단일 JVM 안에서만 유효하다 — 인스턴스가 N대면 원본 호출은 최대 N회다.");
        evidence.note("분산 환경 대책: 분산 락(Redlock 계열), 확률적 조기 만료(probabilistic early expiration), 논리적 TTL + 백그라운드 갱신.");
        evidence.note("만료 시각을 균등 분산(TTL 지터)하지 않으면 대량 키가 동시에 만료되어 같은 현상이 재발한다.");

        if (cacheManager.getCache(StampedeService.UNSYNC_CACHE) != null) {
            cacheManager.getCache(StampedeService.UNSYNC_CACHE).clear();
        }
        if (cacheManager.getCache(StampedeService.SYNC_CACHE) != null) {
            cacheManager.getCache(StampedeService.SYNC_CACHE).clear();
        }
    }

    private void stormWith(Consumer<String> loader, String key) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_READERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_READERS);
        try {
            for (int i = 0; i < CONCURRENT_READERS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        loader.accept(key);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
