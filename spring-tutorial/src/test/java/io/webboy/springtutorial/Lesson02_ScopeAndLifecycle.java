package io.webboy.springtutorial;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레슨 2 — 스코프와 생명주기 (면접 Q6 · Q7 · Q8 · Q13)
 *
 * <p>2-3 의 <b>싱글턴이 prototype 을 주입받으면 갱신되지 않는다</b>가 이 레슨의 핵심이다.
 * "prototype 으로 바꿨는데 왜 하나만 만들어지죠" 는 실무에서 실제로 나오는 질문이다.
 */
@DisplayName("레슨 2. 스코프와 생명주기 — 언제 만들어지고 언제 사라지는가")
class Lesson02_ScopeAndLifecycle {

    @Test
    @DisplayName("2-1. 기본 스코프는 싱글턴이다 — 몇 번 꺼내도 하나다 (Q6)")
    void singletonIsTheDefault() {
        Counter.reset();
        try (var context = new AnnotationConfigApplicationContext(SingletonOnlyConfig.class)) {
            var a = context.getBean(SingletonBean.class);
            var b = context.getBean(SingletonBean.class);

            fact("생성된 인스턴스 수", Counter.singletons.get());
            fact("두 번 꺼낸 것이 같은 객체인가", a == b);

            assertThat(a).isSameAs(b);
            assertThat(Counter.singletons.get()).isEqualTo(1);
        }

        lesson("싱글턴이므로 상태를 필드에 두면 모든 요청이 공유한다 — 무상태로 짜는 이유다");
    }

    @Test
    @DisplayName("2-2. prototype 은 꺼낼 때마다 새로 만들어진다 (Q6)")
    void prototypeIsCreatedPerLookup() {
        Counter.reset();
        try (var context = new AnnotationConfigApplicationContext(PrototypeOnlyConfig.class)) {
            var a = context.getBean(PrototypeBean.class);
            var b = context.getBean(PrototypeBean.class);

            fact("생성된 인스턴스 수", Counter.prototypes.get());
            assertThat(a).isNotSameAs(b);
            assertThat(Counter.prototypes.get()).isEqualTo(2);
        }

        lesson("컨테이너가 만들어 주기만 하고, 그 뒤의 생애는 받아 간 쪽이 책임진다");
    }

    @Test
    @DisplayName("2-3. 싱글턴이 prototype 을 주입받으면 갱신되지 않는다 (Q7) ★가장 위험")
    void singletonHoldsOnePrototypeForever() {
        Counter.reset();
        try (var context = new AnnotationConfigApplicationContext(NaiveHolderConfig.class)) {
            var holder = context.getBean(NaiveHolder.class);

            var first = holder.get();
            var second = holder.get();
            var third = holder.get();

            fact("prototype 이 만들어진 횟수", Counter.prototypes.get());   // 기동 때 주입되며 1개
            fact("세 번 호출이 모두 같은 객체인가", first == second && second == third);

            // 주입은 싱글턴이 만들어질 때 딱 한 번 일어난다. 그 뒤로는 같은 것을 계속 쥐고 있다
            assertThat(first).isSameAs(second).isSameAs(third);
            assertThat(Counter.prototypes.get()).isEqualTo(1);
        }

        lesson("스코프를 prototype 으로 바꿔도, 그것을 쥔 쪽이 싱글턴이면 아무 일도 일어나지 않는다");
        lesson("주입은 '한 번 꽂는 것'이지 '매번 물어보는 것'이 아니다");
    }

    @Test
    @DisplayName("2-4. 매번 새로 받으려면 ObjectProvider 로 물어봐야 한다 (Q7)")
    void objectProviderAsksEveryTime() {
        Counter.reset();
        try (var context = new AnnotationConfigApplicationContext(ProviderHolderConfig.class)) {
            var holder = context.getBean(ProviderHolder.class);

            var first = holder.get();
            var second = holder.get();

            fact("prototype 이 만들어진 횟수", Counter.prototypes.get());
            assertThat(first).isNotSameAs(second);
            assertThat(Counter.prototypes.get()).isEqualTo(2);
        }

        lesson("ObjectProvider·@Lookup·프록시 스코프 셋 중 하나가 필요하다. 그냥 주입으로는 안 된다");
    }

    @Test
    @DisplayName("2-5. 생명주기 콜백은 정해진 순서로 불린다 (Q8)")
    void lifecycleCallbacksRunInOrder() {
        List<String> log = new ArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LifecycleBean.class, () -> new LifecycleBean(log));
            context.refresh();
            context.getBean(LifecycleBean.class);
            fact("닫기 전까지의 순서", List.copyOf(log));
        }   // ← 여기서 컨텍스트가 닫힌다

        fact("닫은 뒤의 순서", log);
        assertThat(log).containsExactly(
                "생성자", "@PostConstruct", "afterPropertiesSet", "@PreDestroy");

        lesson("@PostConstruct 는 주입이 끝난 뒤에 불린다 — 생성자에서는 아직 의존이 비어 있을 수 있다");
        lesson("소멸 콜백은 컨텍스트를 닫아야 불린다. 안 닫으면 영원히 안 불린다");
    }

    @Test
    @DisplayName("2-6. prototype 빈은 소멸 콜백이 불리지 않는다 (Q6·Q8) ★놓치기 쉬움")
    void prototypeDestructionIsNotManaged() {
        List<String> singletonLog = new ArrayList<>();
        List<String> prototypeLog = new ArrayList<>();

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("싱글턴", LifecycleBean.class, () -> new LifecycleBean(singletonLog));
            context.registerBean("프로토타입", LifecycleBean.class,
                    () -> new LifecycleBean(prototypeLog),
                    definition -> definition.setScope("prototype"));
            context.refresh();
            context.getBean("프로토타입");
        }

        fact("싱글턴 로그", singletonLog);
        fact("prototype 로그", prototypeLog);

        // 초기화까지는 컨테이너가 해 준다
        assertThat(prototypeLog).contains("@PostConstruct");
        // 그러나 소멸은 해 주지 않는다 — 컨테이너가 참조를 들고 있지 않기 때문이다
        assertThat(prototypeLog).doesNotContain("@PreDestroy");
        assertThat(singletonLog).contains("@PreDestroy");

        lesson("prototype 을 '자원을 쥔 객체'로 쓰면 닫히지 않는다 — 받아 간 쪽이 직접 닫아야 한다");
    }

    @Test
    @DisplayName("2-7. @Lazy 는 첫 사용까지 생성을 미룬다 (Q13)")
    void lazyDefersCreation() {
        Counter.reset();
        try (var context = new AnnotationConfigApplicationContext(LazyConfig.class)) {
            // 기동이 끝난 시점 — 아직 안 만들어졌다
            fact("기동 직후 생성 횟수", Counter.singletons.get());
            assertThat(Counter.singletons.get()).isZero();

            context.getBean(SingletonBean.class);   // 처음 꺼낼 때 만들어진다
            fact("한 번 꺼낸 뒤 생성 횟수", Counter.singletons.get());
            assertThat(Counter.singletons.get()).isEqualTo(1);
        }

        lesson("기동은 빨라지지만, 설정 오류가 기동이 아니라 '첫 요청'에서 터진다 — 그래서 전역 적용은 신중히");
    }

    // ── 레슨용 빈 ──────────────────────────────────────────────────

    static final class Counter {
        static final AtomicInteger singletons = new AtomicInteger();
        static final AtomicInteger prototypes = new AtomicInteger();

        static void reset() {
            singletons.set(0);
            prototypes.set(0);
        }
    }

    static final class SingletonBean {
        SingletonBean() {
            Counter.singletons.incrementAndGet();
        }
    }

    static final class PrototypeBean {
        PrototypeBean() {
            Counter.prototypes.incrementAndGet();
        }
    }

    /** 나쁜 예 — 주입받은 prototype 을 계속 쥐고 있다. */
    record NaiveHolder(PrototypeBean bean) {
        PrototypeBean get() {
            return bean;
        }
    }

    /** 좋은 예 — 필요할 때마다 컨테이너에 물어본다. */
    record ProviderHolder(ObjectProvider<PrototypeBean> provider) {
        PrototypeBean get() {
            return provider.getObject();
        }
    }

    // 설정을 레슨마다 나눠 둔다. 하나로 합치면 다른 빈의 주입이 카운터를 올려
    // '몇 번 만들어졌나'를 셀 수 없게 된다 (이 레슨을 만들다 실제로 겪었다 — README §4).

    @Configuration(proxyBeanMethods = false)
    static class SingletonOnlyConfig {
        @Bean
        SingletonBean singletonBean() {
            return new SingletonBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrototypeOnlyConfig {
        @Bean
        @Scope("prototype")
        PrototypeBean prototypeBean() {
            return new PrototypeBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NaiveHolderConfig {
        @Bean
        @Scope("prototype")
        PrototypeBean prototypeBean() {
            return new PrototypeBean();
        }

        @Bean
        NaiveHolder naiveHolder(PrototypeBean bean) {
            return new NaiveHolder(bean);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProviderHolderConfig {
        @Bean
        @Scope("prototype")
        PrototypeBean prototypeBean() {
            return new PrototypeBean();
        }

        @Bean
        ProviderHolder providerHolder(ObjectProvider<PrototypeBean> provider) {
            return new ProviderHolder(provider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyConfig {
        @Bean
        @Lazy
        SingletonBean singletonBean() {
            return new SingletonBean();
        }
    }

    static final class LifecycleBean implements InitializingBean {
        private final List<String> log;

        LifecycleBean(List<String> log) {
            this.log = log;
            log.add("생성자");
        }

        @PostConstruct
        void postConstruct() {
            log.add("@PostConstruct");
        }

        @Override
        public void afterPropertiesSet() {
            log.add("afterPropertiesSet");
        }

        @PreDestroy
        void preDestroy() {
            log.add("@PreDestroy");
        }
    }
}
