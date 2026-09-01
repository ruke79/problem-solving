package io.webboy.springtutorial;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 3 — 프록시와 AOP (면접 Q33 · Q34 · Q35 · Q36 · Q47 · Q52 · Q53)
 *
 * <p>3-2 의 <b>자기 호출(self-invocation)</b>이 이 레슨의 전부라 해도 된다.
 * {@code @Transactional} · {@code @Cacheable} · {@code @Async} 가 "안 먹는" 사고의
 * 대부분이 여기서 나온다. 레슨 4 와 7 에서 같은 함정이 실제 기능으로 재등장한다.
 */
@DisplayName("레슨 3. 프록시 — 스프링 AOP 가 동작하는 방식과 안 되는 자리")
class Lesson03_Proxy {

    @Test
    @DisplayName("3-1. 어드바이스가 붙은 빈은 프록시로 바뀌어 있다 (Q33)")
    void advisedBeansAreProxies() {
        try (var context = new AnnotationConfigApplicationContext(AopConfig.class)) {
            var service = context.getBean(OrderService.class);

            fact("빈의 실제 클래스", service.getClass().getSimpleName());
            fact("AopUtils.isAopProxy", AopUtils.isAopProxy(service));
            fact("직접 new 한 객체는", AopUtils.isAopProxy(new OrderService()));

            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThat(service.getClass()).isNotEqualTo(OrderService.class);   // 서브클래스다
            // 당연하지만 중요한 대조 — 컨테이너 밖에서 new 한 객체에는 아무것도 안 붙는다
            assertThat(AopUtils.isAopProxy(new OrderService())).isFalse();

            lesson("@Transactional 이든 @Cacheable 이든, 실체는 '빈을 프록시로 바꿔치기'다");
            lesson("그래서 new 로 만든 객체에는 스프링의 어떤 어노테이션도 동작하지 않는다");
        }
    }

    @Test
    @DisplayName("3-2. 자기 호출은 프록시를 지나가지 않는다 (Q35) ★가장 중요")
    void selfInvocationBypassesTheProxy() {
        try (var context = new AnnotationConfigApplicationContext(AopConfig.class)) {
            var log = context.getBean(CallLog.class);
            var service = context.getBean(OrderService.class);

            // 밖에서 부르면 둘 다 어드바이스에 잡힌다
            service.inner();
            fact("밖에서 inner() 호출", log.calls);
            assertThat(log.calls).containsExactly("inner");

            // 안에서 this.inner() 로 부르면 — outer 만 잡히고 inner 는 잡히지 않는다
            log.calls.clear();
            service.outer();
            fact("outer() 가 this.inner() 를 부른 경우", log.calls);
            assertThat(log.calls).containsExactly("outer");   // inner 가 없다!

            lesson("프록시는 '밖에서 들어오는 호출'만 가로챈다. this.메서드() 는 프록시가 모른다");
            lesson("@Transactional 자기 호출이 안 먹는 이유가 정확히 이것이다 — 레슨 4-6 에서 실물로 본다");
        }
    }

    @Test
    @DisplayName("3-3. 프록시를 통해 다시 들어가면 잡힌다 (Q35·Q47)")
    void goingThroughTheProxyWorks() {
        try (var context = new AnnotationConfigApplicationContext(AopConfig.class)) {
            var log = context.getBean(CallLog.class);
            var service = context.getBean(OrderService.class);

            // 자기 자신의 '프록시'를 컨테이너에서 받아 그쪽으로 부른다
            service.outerViaProxy();

            fact("프록시를 경유한 내부 호출", log.calls);
            assertThat(log.calls).containsExactly("outerViaProxy", "inner");   // 이번엔 둘 다 잡혔다

            lesson("해법은 셋 — 메서드를 다른 빈으로 분리(정석) / 자기 프록시 주입 / 구조 재설계");
            lesson("자기 프록시 주입은 동작하지만, 설계가 이상하다는 신호로 읽는 것이 맞다");
        }
    }

    @Test
    @DisplayName("3-4. 인터페이스가 있으면 JDK 프록시 — 구현 타입으로는 못 받는다 (Q34) ★함정")
    void jdkProxyIsNotTheImplementationType() {
        // 기본 설정(proxyTargetClass 지정 안 함) — 인터페이스가 있으면 JDK 동적 프록시
        try (var context = new AnnotationConfigApplicationContext(JdkProxyConfig.class)) {
            var greeter = context.getBean(Greeter.class);

            fact("프록시 클래스", greeter.getClass().getSimpleName());
            fact("Greeter 인가", greeter instanceof Greeter);
            fact("GreeterImpl 인가", greeter instanceof GreeterImpl);

            assertThat(greeter).isInstanceOf(Greeter.class);
            assertThat(greeter).isNotInstanceOf(GreeterImpl.class);   // ← 구현 클래스가 아니다

            // 그래서 구현 타입으로 조회하면 — 빈이 분명히 등록돼 있는데 "없다"고 한다.
            // 컨테이너에 있는 것은 GreeterImpl 이 아니라 $Proxy 이기 때문이다
            assertThatThrownBy(() -> context.getBean(GreeterImpl.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            // 이름으로 집어 타입을 요구하면 그제서야 진짜 이유를 말해 준다
            assertThatThrownBy(() -> context.getBean("greeter", GreeterImpl.class))
                    .isInstanceOf(BeanNotOfRequiredTypeException.class)
                    .hasMessageContaining("$Proxy");
        }

        // proxyTargetClass = true — CGLIB 서브클래싱이라 구현 타입이 유지된다
        try (var context = new AnnotationConfigApplicationContext(CglibProxyConfig.class)) {
            var greeter = context.getBean(GreeterImpl.class);   // 이번엔 된다
            fact("CGLIB 프록시 클래스", greeter.getClass().getSimpleName());
            assertThat(AopUtils.isCglibProxy(greeter)).isTrue();
        }

        lesson("JDK 프록시는 '인터페이스를 새로 구현'하고, CGLIB 는 '클래스를 상속'한다");
        lesson("구현 타입 주입이 깨지면 이걸 의심한다. 애초에 인터페이스 타입으로 주입하는 게 정석이다");
    }

    @Test
    @DisplayName("3-5. CGLIB 프록시에서 final 메서드는 조용히 잘못된 값을 준다 (Q34·Q47) ★위험")
    void finalMethodsOnCglibProxyAreSilentlyBroken() {
        try (var context = new AnnotationConfigApplicationContext(CglibProxyConfig.class)) {
            var product = context.getBean(Product.class);

            int viaNormalMethod = product.stock();   // 재정의됨 → 진짜 객체로 위임된다
            int viaFinalMethod = product.price();    // final → 재정의 불가 → 프록시 자신에서 실행

            fact("일반 메서드가 돌려준 값", viaNormalMethod);
            fact("final 메서드가 돌려준 값", viaFinalMethod);

            assertThat(viaNormalMethod).isEqualTo(7);   // 정상
            // 프록시 인스턴스는 생성자를 거치지 않아 필드가 전부 0/null 이다.
            // final 메서드는 위임되지 못하고 그 빈 필드를 읽는다.
            assertThat(viaFinalMethod).isZero();        // 100 이 아니라 0 이 나온다!

            lesson("예외라도 나면 다행인데, 0 이 '조용히' 나온다 — 가장 찾기 어려운 종류의 버그다");
            lesson("프록시 대상 클래스에서 final 메서드·final 클래스를 피하는 이유가 이것이다");
        }
    }

    @Test
    @DisplayName("3-6. 어드바이스가 여럿이면 @Order 가 겹겹의 순서를 정한다 (Q52)")
    void adviceOrderingIsExplicit() {
        try (var context = new AnnotationConfigApplicationContext(OrderedAspectsConfig.class)) {
            var trace = context.getBean(Trace.class);
            context.getBean(OrderService.class).inner();

            fact("실행 순서", trace.events);
            // 낮은 @Order 가 바깥 껍질이 된다 — 들어갈 때 먼저, 나올 때 나중
            assertThat(trace.events).containsExactly(
                    "바깥-진입", "안쪽-진입", "안쪽-이탈", "바깥-이탈");

            lesson("트랜잭션과 커스텀 어드바이스가 섞일 때 순서가 문제가 된다 — @Order 로 명시한다");
            lesson("'로그는 트랜잭션 밖에서' 같은 요구가 정확히 이 순서 문제다");
        }
    }

    @Test
    @DisplayName("3-7. @Around 는 인자와 반환값을 바꿀 수 있다 (Q53)")
    void aroundCanRewriteArgumentsAndResult() {
        try (var context = new AnnotationConfigApplicationContext(RewritingConfig.class)) {
            var greeter = context.getBean(Greeter.class);

            String result = greeter.greet("손님");
            fact("호출은 greet(\"손님\") 이었지만", result);

            // 어드바이스가 인자를 바꾸고("손님"→"VIP") 반환값에 장식을 붙였다
            assertThat(result).isEqualTo("[안내] 안녕하세요, VIP");

            lesson("@Before/@After 는 껴들 수만 있고, 실행 자체를 쥐는 것은 @Around 뿐이다");
            lesson("강력한 만큼 proceed() 를 빠뜨리면 대상이 아예 실행되지 않는다 — 조용히");
        }
    }

    // ── 레슨용 빈 ──────────────────────────────────────────────────

    /** 어드바이스에 잡힌 호출을 기록한다. */
    static final class CallLog {
        final List<String> calls = new ArrayList<>();
    }

    @Aspect
    static class RecordingAspect {
        private final CallLog log;

        RecordingAspect(CallLog log) {
            this.log = log;
        }

        @Before("execution(* io.webboy.springtutorial.Lesson03_Proxy.OrderService.*(..))")
        void record(org.aspectj.lang.JoinPoint joinPoint) {
            log.calls.add(joinPoint.getSignature().getName());
        }
    }

    static class OrderService {
        private ObjectProvider<OrderService> self;

        void outer() {
            inner();               // this.inner() — 프록시를 지나가지 않는다
        }

        void outerViaProxy() {
            self.getObject().inner();   // 프록시로 다시 들어간다
        }

        void inner() {
        }

        void setSelf(ObjectProvider<OrderService> self) {
            this.self = self;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class AopConfig {
        @Bean
        CallLog callLog() {
            return new CallLog();
        }

        @Bean
        RecordingAspect recordingAspect(CallLog log) {
            return new RecordingAspect(log);
        }

        @Bean
        OrderService orderService(ObjectProvider<OrderService> self) {
            var service = new OrderService();
            service.setSelf(self);
            return service;
        }
    }

    interface Greeter {
        String greet(String name);

        default String greet() {
            return greet("이름없음");
        }
    }

    static class GreeterImpl implements Greeter {
        @Override
        public String greet(String name) {
            return "안녕하세요, " + name;
        }
    }

    @Aspect
    static class NoopAspect {
        @Before("execution(* io.webboy.springtutorial.Lesson03_Proxy.Greeter+.*(..))")
        void noop() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy   // proxyTargetClass 기본값(false) — 인터페이스가 있으면 JDK 프록시
    static class JdkProxyConfig {
        @Bean
        NoopAspect noopAspect() {
            return new NoopAspect();
        }

        @Bean
        GreeterImpl greeter() {
            return new GreeterImpl();
        }
    }

    /** 필드를 생성자에서 채우는 클래스 — CGLIB 프록시의 함정을 보이기 위한 것. */
    static class Product {
        private final int price;
        private final int stockCount;

        Product() {
            this.price = 100;
            this.stockCount = 7;
        }

        /** final — CGLIB 이 재정의하지 못한다. */
        final int price() {
            return price;
        }

        int stock() {
            return stockCount;
        }
    }

    @Aspect
    static class ProductAspect {
        @Before("execution(* io.webboy.springtutorial.Lesson03_Proxy.Product.*(..))")
        void noop() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)   // CGLIB 강제
    static class CglibProxyConfig {
        @Bean
        NoopAspect noopAspect() {
            return new NoopAspect();
        }

        @Bean
        GreeterImpl greeter() {
            return new GreeterImpl();
        }

        @Bean
        ProductAspect productAspect() {
            return new ProductAspect();
        }

        @Bean
        Product product() {
            return new Product();
        }
    }

    static final class Trace {
        final List<String> events = new ArrayList<>();
    }

    @Aspect
    @Order(1)   // 낮을수록 바깥 껍질
    static class OuterAspect {
        private final Trace trace;

        OuterAspect(Trace trace) {
            this.trace = trace;
        }

        @Around("execution(* io.webboy.springtutorial.Lesson03_Proxy.OrderService.inner(..))")
        Object around(ProceedingJoinPoint pjp) throws Throwable {
            trace.events.add("바깥-진입");
            try {
                return pjp.proceed();
            } finally {
                trace.events.add("바깥-이탈");
            }
        }
    }

    @Aspect
    @Order(2)
    static class InnerAspect {
        private final Trace trace;

        InnerAspect(Trace trace) {
            this.trace = trace;
        }

        @Around("execution(* io.webboy.springtutorial.Lesson03_Proxy.OrderService.inner(..))")
        Object around(ProceedingJoinPoint pjp) throws Throwable {
            trace.events.add("안쪽-진입");
            try {
                return pjp.proceed();
            } finally {
                trace.events.add("안쪽-이탈");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class OrderedAspectsConfig {
        @Bean
        Trace trace() {
            return new Trace();
        }

        @Bean
        OuterAspect outerAspect(Trace trace) {
            return new OuterAspect(trace);
        }

        @Bean
        InnerAspect innerAspect(Trace trace) {
            return new InnerAspect(trace);
        }

        @Bean
        OrderService orderService(ObjectProvider<OrderService> self) {
            var service = new OrderService();
            service.setSelf(self);
            return service;
        }
    }

    @Aspect
    static class RewritingAspect {
        @Around("execution(* io.webboy.springtutorial.Lesson03_Proxy.Greeter+.greet(String))")
        Object rewrite(ProceedingJoinPoint pjp) throws Throwable {
            Object result = pjp.proceed(new Object[]{"VIP"});   // 인자를 바꾼다
            return "[안내] " + result;                            // 반환값을 바꾼다
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class RewritingConfig {
        @Bean
        RewritingAspect rewritingAspect() {
            return new RewritingAspect();
        }

        @Bean
        GreeterImpl greeter() {
            return new GreeterImpl();
        }
    }
}
