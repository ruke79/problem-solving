package io.webboy.springtutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 7 — 캐시·이벤트·비동기 (면접 Q23 · Q48 · Q49 · Q79 · Q104)
 *
 * <p>셋 다 프록시(레슨 3)와 트랜잭션 경계(레슨 4)가 실제 기능으로 나타나는 자리다.
 * 7-2(캐시 자기 호출)와 7-4(이벤트는 기본 동기)가 실무 사고의 단골이다.
 */
@DisplayName("레슨 7. 캐시·이벤트·비동기 — 프록시 위에 지은 기능들")
class Lesson07_CacheEventAsync {

    @Test
    @DisplayName("7-1. @Cacheable — 같은 인자는 한 번만 실행된다 (Q104)")
    void cacheableShortCircuitsRepeatCalls() {
        try (var context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            var service = context.getBean(PriceService.class);

            int first = service.price("사과");
            int second = service.price("사과");    // 실행되지 않고 캐시에서 온다
            int other = service.price("배");       // 다른 인자 → 다른 키 → 실행된다

            fact("같은 인자 두 번 + 다른 인자 한 번의 실제 실행 수", service.executionCount());
            fact("두 번째 호출 결과", second);

            assertThat(first).isEqualTo(second);
            assertThat(service.executionCount()).isEqualTo(2);   // '사과' 1 + '배' 1

            lesson("키는 기본으로 인자 전체다 — 인자가 같으면 메서드 본문은 다시 돌지 않는다");
            lesson("그래서 @Cacheable 메서드에 부수효과를 넣으면 안 된다. '가끔만 실행되는 코드'가 된다");
        }
    }

    @Test
    @DisplayName("7-2. 캐시도 자기 호출에는 무력하다 (Q104) ★레슨 3 의 세 번째 재현")
    void selfInvocationBypassesTheCacheToo() {
        try (var context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            var service = context.getBean(PriceService.class);

            // 같은 인자로 세 번 — 그러나 내부에서 this.price() 로 부른다
            service.totalViaThis("사과");
            service.totalViaThis("사과");
            service.totalViaThis("사과");

            fact("자기 호출 세 번의 실제 실행 수", service.executionCount());
            assertThat(service.executionCount()).isEqualTo(3);   // 캐시가 한 번도 안 걸렸다

            lesson("@Transactional(4-6)에 이어 @Cacheable 도 같은 이유로 무력화된다 — 프록시 미경유");
            lesson("'캐시 히트율이 0%인데 코드는 멀쩡해 보인다' — 이 증상이면 자기 호출부터 의심한다");
        }
    }

    @Test
    @DisplayName("7-3. @CacheEvict 가 갱신 시점의 정합성을 만든다 (Q104·Q105)")
    void evictionKeepsCacheConsistent() {
        try (var context = new AnnotationConfigApplicationContext(CacheConfig.class)) {
            var service = context.getBean(PriceService.class);

            int before = service.price("사과");
            service.changePrice("사과");            // @CacheEvict — 해당 키를 버린다
            int after = service.price("사과");      // 다시 실행되어 새 값을 가져온다

            fact("갱신 전", before);
            fact("갱신 후", after);
            fact("실제 실행 수", service.executionCount());

            assertThat(after).isNotEqualTo(before);
            assertThat(service.executionCount()).isEqualTo(2);

            lesson("갱신 경로에서 evict 를 빠뜨리면 '언젠가 TTL 이 지나야' 맞는 값이 보인다");
            lesson("TTL 은 정합성 장치가 아니라 안전망이다 — 정합성은 갱신 코드가 책임진다");
        }
    }

    @Test
    @DisplayName("7-4. ApplicationEvent 는 기본이 '동기'다 (Q23) ★오해 1위")
    void eventsAreSynchronousByDefault() {
        try (var context = new AnnotationConfigApplicationContext(EventConfig.class)) {
            var publisher = context.getBean(OrderPlacer.class);
            var listener = context.getBean(MailListener.class);

            String publisherThread = Thread.currentThread().getName();
            publisher.place("주문-1");

            fact("발행한 스레드", publisherThread);
            fact("리스너가 돈 스레드", listener.observedThread);
            assertThat(listener.observedThread).isEqualTo(publisherThread);   // 같은 스레드!

            // 같은 스레드라는 것은 — 리스너의 예외가 발행자에게 그대로 돌아온다는 뜻이다
            assertThatThrownBy(() -> publisher.place("실패시킬-주문"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("리스너의 실패");

            lesson("'이벤트로 분리했으니 격리됐다'는 착각이다 — 기본은 같은 스레드의 순차 호출이다");
            lesson("리스너 실패가 본류를 깨뜨리면 안 된다면 @Async 를 붙이거나 트랜잭션 이벤트로 미룬다");
        }
    }

    @Test
    @DisplayName("7-5. @TransactionalEventListener 는 트랜잭션 밖에서는 아예 안 불린다 (Q49) ★함정")
    void transactionalListenersNeedATransaction() {
        try (var context = new AnnotationConfigApplicationContext(EventConfig.class)) {
            var publisher = context.getBean(OrderPlacer.class);
            var txListener = context.getBean(AfterCommitListener.class);

            // 트랜잭션 없이 발행 — AFTER_COMMIT 을 기다릴 트랜잭션 자체가 없다
            publisher.place("주문-2");

            fact("일반 @EventListener 가 받은 수", context.getBean(MailListener.class).received.size());
            fact("@TransactionalEventListener 가 받은 수", txListener.received.size());

            assertThat(txListener.received).isEmpty();   // ← 조용히 버려졌다

            lesson("'커밋 후 실행'을 붙였는데 커밋이 없으면, 실행이 미뤄지는 게 아니라 버려진다");
            lesson("테스트에서 트랜잭션 없이 돌리고 '리스너가 왜 안 불리지' 하는 사고의 정체가 이것이다");
            lesson("(fallbackExecution=true 로 바꿀 수는 있지만, 그 전에 설계를 의심하는 게 맞다)");
        }
    }

    @Test
    @DisplayName("7-6. @Async 는 다른 스레드에서 돈다 — 그래서 컨텍스트가 끊긴다 (Q48·Q79·Q80)")
    void asyncRunsOnAnotherThread() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            var service = context.getBean(ReportService.class);

            ThreadLocal<String> requestContext = ReportService.REQUEST_CONTEXT;
            requestContext.set("사용자-A");
            try {
                service.generate();
                assertThat(service.doneLatch().await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                requestContext.remove();
            }

            fact("호출한 스레드", Thread.currentThread().getName());
            fact("@Async 메서드가 돈 스레드", service.observedThread());
            fact("그 스레드에서 본 ThreadLocal", String.valueOf(service.observedContext()));

            assertThat(service.observedThread()).isNotEqualTo(Thread.currentThread().getName());
            // ThreadLocal 은 스레드에 붙는 것 — 스레드가 바뀌면 함께 가지 않는다
            assertThat(service.observedContext()).isNull();

            lesson("@Async 를 붙이는 순간 ThreadLocal 기반의 모든 것(인증 정보·MDC·트랜잭션)이 끊긴다(Q48·Q80)");
            lesson("필요한 값은 '인자로' 건네는 것이 정석이다 — 스레드에 실어 보내지 않는다");
        }
    }

    @Test
    @DisplayName("7-7. void @Async 의 예외는 호출자에게 절대 닿지 않는다 (Q79) ★위험")
    void voidAsyncSwallowsExceptions() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            var service = context.getBean(ReportService.class);

            // 예외를 던지는 async 메서드 — 호출은 아무 일 없이 반환된다
            service.failInBackground();
            assertThat(service.failedLatch().await(3, TimeUnit.SECONDS)).isTrue();

            fact("호출자에게 예외가 왔는가", "아니오 — 호출은 즉시 정상 반환됐다");

            // Future 로 받으면 예외가 결과에 실려 온다
            var future = service.failReturningFuture();
            assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);

            lesson("void 반환 @Async 의 예외는 AsyncUncaughtExceptionHandler 로만 간다 — 기본은 로그뿐");
            lesson("결과나 실패를 알아야 하는 작업이라면 반환 타입을 CompletableFuture 로 한다");
        }
    }

    // ── 레슨용 빈 ──────────────────────────────────────────────────

    static class PriceService {
        private final AtomicInteger executions = new AtomicInteger();
        private int currentPrice = 100;

        /**
         * 카운터를 '메서드'로 노출하는 데는 이유가 있다 — 처음에 필드로 직접 읽었더니
         * CGLIB 프록시 자신의 (생성자를 안 거친) null 필드가 읽혀 NPE 가 났다.
         * 레슨 3-5 의 함정을 이 레슨을 만들면서 우리가 그대로 밟은 것이다.
         * 메서드는 진짜 객체로 위임되므로 올바른 값을 준다.
         */
        public int executionCount() {
            return executions.get();
        }

        @Cacheable("prices")
        public int price(String item) {
            executions.incrementAndGet();
            return currentPrice;
        }

        public int totalViaThis(String item) {
            return price(item) + 10;   // this.price() — 프록시 미경유
        }

        @CacheEvict(cacheNames = "prices", key = "#item")
        public void changePrice(String item) {
            currentPrice = 200;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("prices");
        }

        @Bean
        PriceService priceService() {
            return new PriceService();
        }
    }

    record OrderPlaced(String orderId) {}

    static class OrderPlacer {
        private final ApplicationEventPublisher publisher;

        OrderPlacer(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        public void place(String orderId) {
            publisher.publishEvent(new OrderPlaced(orderId));
        }
    }

    static class MailListener {
        String observedThread;
        final List<String> received = new ArrayList<>();

        @EventListener
        public void on(OrderPlaced event) {
            observedThread = Thread.currentThread().getName();
            received.add(event.orderId());
            if (event.orderId().startsWith("실패시킬")) {
                throw new IllegalStateException("리스너의 실패");
            }
        }
    }

    static class AfterCommitListener {
        final List<String> received = new ArrayList<>();

        @TransactionalEventListener   // 기본 phase = AFTER_COMMIT
        public void on(OrderPlaced event) {
            received.add(event.orderId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    // ↑ 이게 없으면 @TransactionalEventListener 가 '조용히 일반 리스너로 강등'된다.
    //   (@TransactionalEventListener 는 @EventListener 의 메타 어노테이션이라, 트랜잭션용
    //    리스너 팩토리가 등록돼 있지 않으면 기본 팩토리가 즉시 실행 리스너로 만들어 버린다.
    //    처음에 이것 없이 만들었더니 7-5 에서 '버려져야 할' 이벤트가 즉시 실행됐다.)
    static class EventConfig {
        @Bean
        OrderPlacer orderPlacer(ApplicationEventPublisher publisher) {
            return new OrderPlacer(publisher);
        }

        @Bean
        MailListener mailListener() {
            return new MailListener();
        }

        @Bean
        AfterCommitListener afterCommitListener() {
            return new AfterCommitListener();
        }
    }

    static class ReportService {
        static final ThreadLocal<String> REQUEST_CONTEXT = new ThreadLocal<>();

        private final CountDownLatch done = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        private final AtomicReference<String> observedThread = new AtomicReference<>();
        private final AtomicReference<String> observedContext = new AtomicReference<>();

        // PriceService 와 같은 이유로 전부 메서드 경유다 — 프록시의 필드는 비어 있다
        public CountDownLatch doneLatch() {
            return done;
        }

        public CountDownLatch failedLatch() {
            return failed;
        }

        public String observedThread() {
            return observedThread.get();
        }

        public String observedContext() {
            return observedContext.get();
        }

        @Async
        public void generate() {
            observedThread.set(Thread.currentThread().getName());
            observedContext.set(REQUEST_CONTEXT.get());
            done.countDown();
        }

        @Async
        public void failInBackground() {
            failed.countDown();
            throw new IllegalStateException("배경 작업의 실패 — 아무도 못 본다");
        }

        @Async
        public java.util.concurrent.CompletableFuture<String> failReturningFuture() {
            throw new IllegalStateException("Future 에 실려 갈 실패");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncConfig {
        @Bean
        ReportService reportService() {
            return new ReportService();
        }
    }
}
