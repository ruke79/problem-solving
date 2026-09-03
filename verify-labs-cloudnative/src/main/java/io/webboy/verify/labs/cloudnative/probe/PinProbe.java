package io.webboy.verify.labs.cloudnative.probe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 2판 13장의 한계 목록 — "synchronized 는 가상 스레드를 캐리어에 피닝한다" — 를 캐리어 1개로 실험한다.
 *
 * <p>부모가 {@code -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1}
 * 을 준다. 가상 스레드 A 가 {@code synchronized} 블록 안에서 {@code sleep} 하는 동안 가상 스레드 B 가
 * 언제 시작하는지를 잰다. A 가 캐리어에 피닝돼 있으면 B 는 A 가 끝날 때까지(≈ HOLD_MS) 기다린다.
 * 피닝이 없으면 A 가 sleep 에서 언마운트되고 B 는 곧바로 돈다.
 */
public final class PinProbe {

    private static final Object LOCK = new Object();
    private static final long HOLD_MS = 600;

    public static void main(String[] args) throws Exception {
        CountDownLatch inLock = new CountDownLatch(1);
        long t0 = System.nanoTime();
        Thread a = Thread.ofVirtual().name("A").start(() -> {
            synchronized (LOCK) {
                inLock.countDown();
                try {
                    Thread.sleep(HOLD_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        inLock.await();
        CompletableFuture<Long> bStarted = new CompletableFuture<>();
        Thread b = Thread.ofVirtual().name("B").start(
                () -> bStarted.complete((System.nanoTime() - t0) / 1_000_000L));
        long bMs = bStarted.get(10, TimeUnit.SECONDS);
        a.join();
        b.join();
        System.out.println("HOLD_MS=" + HOLD_MS);
        System.out.println("B_START_MS=" + bMs);
        System.out.println("A_DAEMON=" + a.isDaemon() + " A_VIRTUAL=" + a.isVirtual());
    }
}
