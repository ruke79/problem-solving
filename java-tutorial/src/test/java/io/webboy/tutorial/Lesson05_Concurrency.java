package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static io.webboy.tutorial.Lesson.observe;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레슨 5 — 동시성 (면접 Q39 · Q40 · Q60 · Q61 · Q65 · Q75)
 *
 * <p><b>이 레슨은 다른 레슨과 판정 방식이 다르다.</b> 스레드 타이밍은 우리가 정하지 못하므로,
 * <b>반드시 성립하는 것만 단정</b>하고 나머지는 {@code observe} 로 출력만 한다.
 * 저장소의 {@code expect} / {@code expectFlaky} 구분과 같은 원칙이다({@code docs/00} §3).
 */
@DisplayName("레슨 5. 동시성 — 무엇이 보장되고 무엇이 아닌가")
class Lesson05_Concurrency {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 50_000;
    private static final int EXPECTED = THREADS * PER_THREAD;

    @Test
    @DisplayName("5-1. ++ 는 원자적이지 않다 (Q60) ★핵심")
    void plusPlusIsNotAtomic() throws Exception {
        var plain = new PlainCounter();
        var atomic = new AtomicInteger();

        runConcurrently(() -> {
            plain.increment();
            atomic.incrementAndGet();
        });

        fact("기대값", EXPECTED);
        fact("AtomicInteger 결과", atomic.get());
        observe("일반 int++ 결과", plain.value() + "  (갱신 손실 " + (EXPECTED - plain.value()) + "건)");

        // 단정할 수 있는 것 — Atomic 은 항상 정확하다
        assertThat(atomic.get()).isEqualTo(EXPECTED);
        // 단정할 수 있는 것 — 일반 int 는 절대 기대값을 '넘지' 못한다 (손실만 생긴다)
        assertThat(plain.value()).isLessThanOrEqualTo(EXPECTED);
        // 단정하지 않는 것 — "반드시 손실이 난다"는 보장이 없다. 운 좋으면 맞을 수도 있다

        lesson("++ 는 읽고·더하고·쓰는 3단계다. volatile 을 붙여도 이건 못 막는다");
        lesson("손실이 '항상' 난다고 단정하지 않았다 — 스케줄링은 우리 손 밖이기 때문이다");
    }

    @Test
    @DisplayName("5-2. volatile 은 가시성만 준다 (Q60)")
    void volatileGivesVisibilityNotAtomicity() throws Exception {
        var counter = new VolatileCounter();
        runConcurrently(counter::increment);

        observe("volatile int++ 결과", counter.value() + " / 기대 " + EXPECTED);
        assertThat(counter.value()).isLessThanOrEqualTo(EXPECTED);

        lesson("volatile 은 '최신 값을 본다'를 보장할 뿐, '끼어들지 못한다'를 보장하지 않는다");
    }

    @Test
    @DisplayName("5-3. volatile 이 맞는 자리는 플래그다 (Q61)")
    void volatileIsForFlags() throws Exception {
        var worker = new StoppableWorker();
        Thread t = new Thread(worker);
        t.start();

        Thread.sleep(30);
        worker.stop();                     // 다른 스레드가 플래그를 내린다
        t.join(2_000);

        fact("스레드가 멈췄는가", !t.isAlive());
        fact("반복 횟수", worker.loops());

        assertThat(t.isAlive()).isFalse();  // volatile 이라 갱신이 보인다
        assertThat(worker.loops()).isGreaterThan(0);

        lesson("단순한 대입과 읽기만 하는 플래그 — 여기가 volatile 의 정확한 용도다");
    }

    @Test
    @DisplayName("5-4. HashMap 을 여러 스레드가 쓰면 깨진다 (Q39)")
    void plainHashMapIsNotThreadSafe() throws Exception {
        Map<Integer, Integer> plain = new HashMap<>();
        Map<Integer, Integer> concurrent = new ConcurrentHashMap<>();

        var index = new AtomicInteger();
        runConcurrently(() -> {
            int key = index.getAndIncrement();
            try {
                plain.put(key, key);
            } catch (RuntimeException ignored) {
                // 동시 수정으로 내부 상태가 깨지면 예외가 날 수도 있다
            }
            concurrent.put(key, key);
        });

        fact("ConcurrentHashMap 크기", concurrent.size());
        observe("일반 HashMap 크기", plain.size() + " / 기대 " + EXPECTED);

        assertThat(concurrent).hasSize(EXPECTED);          // 항상 정확하다
        assertThat(plain.size()).isLessThanOrEqualTo(EXPECTED);

        lesson("일반 HashMap 은 값을 잃을 뿐 아니라 내부 구조 자체가 깨질 수 있다");
    }

    @Test
    @DisplayName("5-5. ConcurrentHashMap 이라도 조합은 원자적이 아니다 (Q40) ★함정")
    void compoundActionsAreStillNotAtomic() throws Exception {
        Map<String, Integer> unsafe = new ConcurrentHashMap<>();
        Map<String, Integer> safe = new ConcurrentHashMap<>();

        runConcurrently(() -> {
            // 나쁜 예 — get 하고 판단하고 put. 그 사이에 끼어들 수 있다
            Integer current = unsafe.get("k");
            unsafe.put("k", current == null ? 1 : current + 1);

            // 좋은 예 — 한 번의 원자적 연산
            safe.merge("k", 1, Integer::sum);
        });

        fact("merge 로 센 값", safe.get("k"));
        observe("get→put 으로 센 값", unsafe.get("k") + " / 기대 " + EXPECTED);

        assertThat(safe.get("k")).isEqualTo(EXPECTED);      // 항상 정확하다
        assertThat(unsafe.get("k")).isLessThanOrEqualTo(EXPECTED);

        lesson("메서드 하나하나가 안전한 것과, 그것들의 조합이 안전한 것은 다른 이야기다");
        lesson("compute · merge · putIfAbsent — 복합 연산은 전용 메서드로");
    }

    @Test
    @DisplayName("5-6. 락 순서를 통일하면 데드락이 사라진다 (Q75) ★핵심")
    void consistentLockOrderPreventsDeadlock() throws Exception {
        var first = new ReentrantLock();
        var second = new ReentrantLock();

        // 두 스레드가 '같은 순서'로 잡으면 서로 기다릴 일이 없다
        var barrier = new CyclicBarrier(2);
        var done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    barrier.await();                 // 동시에 출발시킨다
                    first.lock();                    // ← 둘 다 first 먼저
                    try {
                        second.lock();
                        try {
                            Thread.sleep(5);
                        } finally {
                            second.unlock();
                        }
                    } finally {
                        first.unlock();
                    }
                    done.countDown();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).start();
        }

        boolean finished = done.await(5, TimeUnit.SECONDS);
        fact("두 스레드가 모두 끝났는가", finished);
        assertThat(finished).isTrue();

        lesson("데드락 대책 1순위는 타임아웃이 아니라 '취득 순서 통일'이다");
        lesson("반대 순서로 짜면 여기서 멈춘다 — 위험해서 테스트로는 재현하지 않았다");
    }

    @Test
    @DisplayName("5-7. tryLock 은 포기할 수 있다 (Q65)")
    void tryLockCanGiveUp() throws Exception {
        var lock = new ReentrantLock();
        lock.lock();   // 메인 스레드가 먼저 잡는다

        try {
            var acquired = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread other = new Thread(() -> {
                try {
                    acquired.set(lock.tryLock(100, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            other.start();
            other.join(2_000);

            fact("다른 스레드가 락을 얻었는가", acquired.get());
            assertThat(acquired.get()).isFalse();   // 못 얻고 포기했다 — 매달리지 않는다
        } finally {
            lock.unlock();
        }

        lesson("synchronized 로는 못 하는 것 — 일정 시간 뒤 포기하는 설계가 가능해진다");
    }

    /** 스레드 {@value #THREADS}개가 각각 {@value #PER_THREAD} 회 작업을 수행한다. */
    private static void runConcurrently(Runnable work) throws Exception {
        var barrier = new CyclicBarrier(THREADS);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();        // 전원이 준비된 뒤 동시에 출발 → 경합을 최대화한다
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < PER_THREAD; i++) {
                    work.run();
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    // ── 레슨용 클래스 ──────────────────────────────────────────────

    static final class PlainCounter {
        private int value;

        void increment() {
            value++;
        }

        int value() {
            return value;
        }
    }

    static final class VolatileCounter {
        private volatile int value;

        void increment() {
            value++;   // volatile 이어도 원자적이지 않다
        }

        int value() {
            return value;
        }
    }

    static final class StoppableWorker implements Runnable {
        private volatile boolean running = true;   // ← 이 volatile 이 핵심이다
        private volatile long loops;

        @Override
        public void run() {
            while (running) {
                loops++;
            }
        }

        void stop() {
            running = false;
        }

        long loops() {
            return loops;
        }
    }
}
