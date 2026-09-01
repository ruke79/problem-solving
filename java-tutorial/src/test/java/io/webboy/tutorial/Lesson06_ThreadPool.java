package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 6 — 스레드풀과 비동기 (면접 Q57 · Q67 · Q70 ~ Q74)
 *
 * <p>6-2 의 <b>무제한 큐</b>와 6-5 의 <b>{@code ThreadLocal} 누출</b>이
 * 실무에서 실제로 사고를 내는 두 가지다.
 */
@DisplayName("레슨 6. 스레드풀 — 상한을 정하는 일")
class Lesson06_ThreadPool {

    @Test
    @DisplayName("6-1. Callable 은 예외를 삼키지 않는다 (Q57)")
    void callablePropagatesException() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            // Runnable 로 던지면 예외가 어디로도 안 간다 (기본 핸들러로만 간다)
            Future<?> silent = pool.submit(() -> {
                throw new IllegalStateException("조용히 사라질 뻔한 예외");
            });

            // Future.get 을 부르면 그때 ExecutionException 으로 감싸여 나온다
            assertThatThrownBy(silent::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);

            lesson("submit 한 작업의 예외는 Future.get 을 불러야 보인다 — 안 부르면 조용히 묻힌다");
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("6-2. Executors 의 기본 풀은 큐가 무제한이다 (Q72) ★가장 위험")
    void defaultPoolQueueIsUnbounded() throws Exception {
        ThreadPoolExecutor fixed = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        try {
            // 스레드 하나를 붙잡아 둔다
            var block = new java.util.concurrent.CountDownLatch(1);
            fixed.submit(() -> {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // 그 상태로 1,000건을 더 넣는다 — 전부 받아들여진다
            for (int i = 0; i < 1_000; i++) {
                fixed.submit(() -> { });
            }

            int queued = fixed.getQueue().size();
            int remaining = fixed.getQueue().remainingCapacity();
            fact("대기 큐에 쌓인 작업 수", queued);
            fact("큐의 남은 용량", remaining);
            fact("쌓인 것 + 남은 것", (long) queued + remaining);

            assertThat(queued).isEqualTo(1_000);
            // 무제한이라는 증거 — 큐의 총 용량이 Integer.MAX_VALUE 다.
            // remainingCapacity() 는 '용량 - 현재 크기' 이므로 MAX_VALUE 그 자체는 아니다
            // (처음에 그렇게 단정했다가 이 테스트가 실패했다).
            assertThat((long) queued + remaining).isEqualTo(Integer.MAX_VALUE);

            block.countDown();
            lesson("거절되지 않는다는 건 좋은 게 아니다 — 메모리가 다할 때까지 쌓인다");
        } finally {
            shutdown(fixed);
        }
    }

    @Test
    @DisplayName("6-3. 직접 조립하면 상한과 거절 정책을 정할 수 있다 (Q72·Q73)")
    void buildYourOwnWithBoundsAndPolicy() throws Exception {
        var pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),                       // ← 용량을 정한다
                new ThreadPoolExecutor.AbortPolicy());             // ← 넘치면 거절한다
        try {
            var block = new java.util.concurrent.CountDownLatch(1);
            pool.submit(() -> {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> { });   // 큐 1/2
            pool.submit(() -> { });   // 큐 2/2

            fact("큐 용량", 2);
            fact("현재 큐 크기", pool.getQueue().size());

            // 세 번째는 거절된다 — 조용히 쌓이는 대신 즉시 알려 준다
            assertThatThrownBy(() -> pool.submit(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);

            block.countDown();
            lesson("상한이 있으면 '지금 감당이 안 된다'를 즉시 알 수 있다 — 이게 배압의 출발점이다");
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("6-4. CallerRunsPolicy 는 호출자에게 일을 떠넘겨 속도를 늦춘다 (Q73)")
    void callerRunsIsBackpressure() throws Exception {
        var pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            var ranOnCaller = new AtomicInteger();
            String mainThread = Thread.currentThread().getName();

            var block = new java.util.concurrent.CountDownLatch(1);
            pool.submit(() -> {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> { });   // 큐가 찬다

            // 넘친 작업은 호출한 스레드가 직접 실행한다
            pool.submit(() -> {
                if (Thread.currentThread().getName().equals(mainThread)) {
                    ranOnCaller.incrementAndGet();
                }
            });

            fact("호출자 스레드에서 실행된 작업 수", ranOnCaller.get());
            assertThat(ranOnCaller.get()).isEqualTo(1);

            block.countDown();
            lesson("호출자가 일을 하는 동안 새 요청을 못 받으니, 자연스럽게 유입이 느려진다");
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("6-5. 스레드풀 + ThreadLocal 은 값이 새어 나간다 (Q67) ★실무 사고")
    void threadLocalLeaksAcrossTasks() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        ExecutorService single = Executors.newSingleThreadExecutor();   // 스레드를 재사용한다
        try {
            // 첫 번째 작업이 값을 넣고 지우지 않는다
            single.submit(() -> context.set("사용자-A 의 비밀")).get();

            // 두 번째 작업이 같은 스레드에 배정되면, 앞의 값이 그대로 보인다
            String leaked = single.submit(context::get).get();

            fact("다음 작업에서 읽힌 값", leaked);
            assertThat(leaked).isEqualTo("사용자-A 의 비밀");   // ← 새어 나왔다

            // remove 하면 사라진다
            single.submit(context::remove).get();
            assertThat(single.submit(context::get).get()).isNull();

            lesson("스레드가 재사용되므로 finally 에서 remove() 하지 않으면 다음 요청이 본다");
            lesson("정보 유출이면서 동시에 메모리 누수다");
        } finally {
            shutdown(single);
        }
    }

    @Test
    @DisplayName("6-6. 종료는 shutdown → await → shutdownNow 3단계 (Q70)")
    void shutdownInThreeSteps() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> { });

        pool.shutdown();                                    // 1. 신규 접수 중단
        boolean finished = pool.awaitTermination(2, TimeUnit.SECONDS);   // 2. 대기
        if (!finished) {
            pool.shutdownNow();                             // 3. 인터럽트
        }

        fact("정상 종료됐는가", finished);
        assertThat(pool.isShutdown()).isTrue();
        assertThat(finished).isTrue();

        // shutdown 후에는 새 작업을 받지 않는다
        assertThatThrownBy(() -> pool.submit(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);

        lesson("shutdown 을 안 부르면 비데몬 스레드가 남아 JVM 이 안 끝난다");
    }

    @Test
    @DisplayName("6-7. CompletableFuture 는 블록하지 않고 합성한다 (Q74)")
    void composeWithoutBlocking() throws Exception {
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> 20);
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> 22);

        // 두 결과를 합친다 — 각각에 get 을 부르지 않는다
        Integer sum = a.thenCombine(b, Integer::sum).get(2, TimeUnit.SECONDS);
        assertThat(sum).isEqualTo(42);

        // 예외도 체인으로 다룬다
        String recovered = CompletableFuture.<String>supplyAsync(() -> {
                    throw new IllegalStateException("실패");
                })
                .exceptionally(e -> "폴백")
                .get(2, TimeUnit.SECONDS);
        assertThat(recovered).isEqualTo("폴백");

        // 타임아웃은 반드시 붙인다 — get() 만 부르면 영원히 기다릴 수 있다
        assertThatThrownBy(() ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "늦은 응답";
                }).get(100, TimeUnit.MILLISECONDS)
        ).isInstanceOf(java.util.concurrent.TimeoutException.class);

        lesson("합성은 thenCombine·thenCompose 로, 실패는 exceptionally 로, 그리고 타임아웃은 필수");
    }

    @Test
    @DisplayName("6-8. 코어 수를 확인해 두면 풀 크기 판단의 출발점이 된다 (Q71)")
    void knowYourCoreCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        fact("availableProcessors()", cores);

        // CPU 바운드면 코어 수 정도, I/O 바운드면 대기 비율만큼 더
        int cpuBound = cores;
        int ioBound = cores * (1 + 4);   // 대기:처리 = 4:1 인 경우의 예시 계산
        fact("CPU 바운드 권장 출발점", cpuBound);
        fact("I/O 바운드 예시 (대기:처리 = 4:1)", ioBound);

        assertThat(cores).isGreaterThan(0);

        lesson("공식은 출발점일 뿐이다 — DB 를 쓴다면 커넥션 풀 크기가 진짜 상한이다");
    }

    private static void shutdown(ExecutorService pool) throws Exception {
        pool.shutdownNow();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }
}
