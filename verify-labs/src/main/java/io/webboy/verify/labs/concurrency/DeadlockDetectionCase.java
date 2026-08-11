package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class DeadlockDetectionCase extends VerificationCase {

    @Override
    public String id() {
        return "CON-05";
    }

    @Override
    public String category() {
        return "concurrency";
    }

    @Override
    public String question() {
        return "데드락은 어떻게 발생하고 운영 중에 어떻게 탐지·예방합니까?";
    }

    @Override
    public String claim() {
        return "락 획득 순서가 엇갈리면 데드락이 난다. ThreadMXBean 으로 탐지 가능하며, 락 순서를 통일하면 예방된다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);
        AtomicBoolean interrupted1 = new AtomicBoolean();
        AtomicBoolean interrupted2 = new AtomicBoolean();

        Thread t1 = crossLockThread("deadlock-1", lockA, lockB, bothHoldFirstLock, interrupted1);
        Thread t2 = crossLockThread("deadlock-2", lockB, lockA, bothHoldFirstLock, interrupted2);
        t1.start();
        t2.start();

        Thread.sleep(500);

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mx.findDeadlockedThreads();
        int deadlockedCount = deadlocked == null ? 0 : deadlocked.length;

        t1.interrupt();
        t2.interrupt();
        t1.join(3_000);
        t2.join(3_000);

        boolean recovered = !t1.isAlive() && !t2.isAlive();
        long orderedElapsed = runWithConsistentLockOrder();

        evidence.fact("탐지된 데드락 스레드 수", deadlockedCount);
        evidence.fact("인터럽트로 t1 회복", interrupted1.get());
        evidence.fact("인터럽트로 t2 회복", interrupted2.get());
        evidence.fact("락 순서 통일 버전 소요(ms)", orderedElapsed);

        evidence.expectFlaky("ThreadMXBean 이 데드락 스레드 2개를 탐지한다", deadlockedCount >= 2);
        evidence.expect("lockInterruptibly 를 쓰면 인터럽트로 회복할 수 있다", recovered);
        evidence.expect("락 순서를 통일하면 데드락 없이 완료된다", orderedElapsed < 3_000);

        evidence.note("synchronized 로 걸린 데드락은 인터럽트로 풀 수 없다 — 그래서 회복 가능성이 필요하면 ReentrantLock 을 쓴다.");
        evidence.note("운영 탐지 수단: jstack 의 'Found one Java-level deadlock', JMX ThreadMXBean, Micrometer JvmThreadDeadlockMetrics.");
        evidence.note("예방의 정석은 탐지가 아니라 '모든 코드가 같은 순서로 락을 잡게 하는 것' 또는 tryLock 타임아웃이다.");
    }

    private Thread crossLockThread(String name, ReentrantLock first, ReentrantLock second,
                                   CountDownLatch gate, AtomicBoolean interruptedFlag) {
        Thread thread = new Thread(() -> {
            try {
                first.lockInterruptibly();
                try {
                    gate.countDown();
                    gate.await();
                    second.lockInterruptibly();
                    second.unlock();
                } finally {
                    first.unlock();
                }
            } catch (InterruptedException e) {
                interruptedFlag.set(true);
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private long runWithConsistentLockOrder() throws Exception {
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        CountDownLatch done = new CountDownLatch(2);
        long began = System.nanoTime();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                for (int n = 0; n < 1_000; n++) {
                    lockA.lock();
                    try {
                        lockB.lock();
                        lockB.unlock();
                    } finally {
                        lockA.unlock();
                    }
                }
                done.countDown();
            }, "ordered-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        done.await(5, TimeUnit.SECONDS);
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
