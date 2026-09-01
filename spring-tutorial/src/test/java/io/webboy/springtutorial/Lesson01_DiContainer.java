package io.webboy.springtutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;

import java.util.List;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 1 — DI 컨테이너 (면접 Q3 · Q4 · Q5 · Q9 · Q10 · Q28)
 *
 * <p>1-2 의 <b>순환 참조</b>와 1-5 의 <b>{@code @Configuration} 프록시</b>가 이 레슨의 핵심이다.
 * 둘 다 "예전에는 이랬다"가 지금은 틀린 답이 되어 있는 항목이라, <b>실행해서 확인해야</b> 안다.
 */
@DisplayName("레슨 1. DI 컨테이너 — 주입 방식이 결정하는 것")
class Lesson01_DiContainer {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    @DisplayName("1-1. 생성자 주입이라야 필드를 final 로 잠글 수 있다 (Q3)")
    void constructorInjectionAllowsFinalFields() {
        runner.withUserConfiguration(BasicConfig.class).run(context -> {
            var service = context.getBean(ConstructorInjected.class);

            fact("주입된 의존", service.repository().name());
            assertThat(service.repository()).isNotNull();

            // 필드가 final 이므로 '주입 후 절대 바뀌지 않는다'가 컴파일러 보장이 된다.
            // 필드 주입(@Autowired private Repo repo)으로는 final 을 붙일 수 없다.
            assertThat(ConstructorInjected.class.getDeclaredField("repository").getModifiers() & 0x10)
                    .isNotZero();   // 0x10 = final
        });

        lesson("생성자 주입의 실익은 취향이 아니라 셋이다 — final 가능 · 순환 참조 즉시 발각 · 스프링 없이 new 가능");
    }

    @Test
    @DisplayName("1-2. 순환 참조는 주입 방식에 따라 드러나는 시점이 다르다 (Q4) ★핵심")
    void circularReferenceSurfacesDifferently() {
        // (1) 생성자 주입 — 만들 방법 자체가 없으므로 어디서든 기동이 실패한다
        try (var plain = new AnnotationConfigApplicationContext()) {
            plain.register(ConstructorCycleConfig.class);
            assertThatThrownBy(plain::refresh)
                    .hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
        }
        fact("생성자 주입 순환", "순수 컨텍스트에서도 기동 실패");

        // (2) 필드 주입 — 순수 스프링 컨텍스트에서는 '통과한다'.
        //     반쯤 만들어진 빈을 미리 노출해 서로 꽂아 주기 때문이다. 문제가 런타임까지 숨는다.
        try (var plain = new AnnotationConfigApplicationContext(FieldCycleConfig.class)) {
            assertThat(plain.getBean(FieldA.class).b).isNotNull();
            fact("필드 주입 순환 (순수 컨텍스트)", "기동 성공 — 문제가 숨는다");
        }

        // (3) 그런데 Spring Boot 기본값에서는 그 필드 주입도 막힌다(2.6+).
        //     "필드 주입이면 순환 참조가 통과합니다" 는 지금은 틀린 답이다.
        runner.withUserConfiguration(FieldCycleConfig.class).run(context -> {
            assertThat(context).hasFailed();
            fact("필드 주입 순환 (Boot 기본값)", "기동 실패");
        });

        lesson("생성자 주입은 순환을 '설계 시점에' 드러낸다 — 이게 가장 큰 실익이다");
        lesson("해법은 allow-circular-references 를 켜는 게 아니라 의존 방향을 끊는 것이다");
    }

    @Test
    @DisplayName("1-3. 같은 타입 빈이 둘이면 기동이 실패한다 (Q5)")
    void ambiguousBeansFailFast() {
        runner.withUserConfiguration(AmbiguousConfig.class).run(context ->
                assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class));

        // @Primary 로 기본값을 정하면 뜬다
        runner.withUserConfiguration(PrimaryConfig.class).run(context -> {
            var picked = context.getBean(Consumer.class).repository().name();
            fact("@Primary 가 선택한 빈", picked);
            assertThat(picked).isEqualTo("주-저장소");
        });

        // @Qualifier 는 호출하는 쪽에서 골라 쓴다 — @Primary 보다 우선한다
        runner.withUserConfiguration(QualifiedConfig.class).run(context -> {
            var picked = context.getBean(QualifiedConsumer.class).repository().name();
            fact("@Qualifier 가 선택한 빈", picked);
            assertThat(picked).isEqualTo("보조-저장소");
        });

        lesson("@Primary 는 '기본은 이것', @Qualifier 는 '여기서는 이것'. 둘이 겹치면 @Qualifier 가 이긴다");
    }

    @Test
    @DisplayName("1-4. List 로 받으면 같은 타입 빈이 전부 들어온다 (Q5)")
    void collectionInjectionGathersAll() {
        runner.withUserConfiguration(HandlerConfig.class).run(context -> {
            List<String> names = context.getBean(HandlerRegistry.class).names();

            fact("주입된 핸들러", names);
            // @Order 가 정렬을 결정한다 — 선언 순서가 아니다
            assertThat(names).containsExactly("먼저", "나중");
        });

        lesson("전략 패턴을 컨테이너가 조립해 준다. 새 구현을 빈으로 올리기만 하면 자동으로 낀다");
        lesson("순서에 의존한다면 @Order 를 반드시 명시한다 — 스캔 순서는 보장이 아니다");
    }

    @Test
    @DisplayName("1-5. @Bean 메서드끼리 호출해도 싱글턴이 유지된다 (Q10) ★변별력")
    void configurationClassIsProxied() {
        // proxyBeanMethods = true (기본값) — @Bean 메서드 호출이 컨테이너 조회로 바뀐다
        runner.withUserConfiguration(ProxiedConfig.class).run(context -> {
            var shared = context.getBean(Repository.class);
            var holder = context.getBean(Holder.class);

            fact("설정 클래스가 프록시인가", context.getBean(ProxiedConfig.class).getClass().getName()
                    .contains("$$"));
            fact("holder 가 쥔 것이 컨테이너의 그 빈인가", holder.repository() == shared);

            assertThat(holder.repository()).isSameAs(shared);
        });

        // proxyBeanMethods = false — 그냥 자바 메서드 호출이라 새 인스턴스가 만들어진다
        runner.withUserConfiguration(LiteConfig.class).run(context -> {
            var shared = context.getBean(Repository.class);
            var holder = context.getBean(Holder.class);

            fact("lite 모드에서 같은 인스턴스인가", holder.repository() == shared);
            assertThat(holder.repository()).isNotSameAs(shared);   // ← 두 개가 됐다
        });

        lesson("기본값에서는 CGLIB 프록시가 @Bean 호출을 가로채 싱글턴을 지킨다");
        lesson("proxyBeanMethods=false 는 기동이 빨라지는 대신 이 보호가 사라진다 — 서로 호출하지 않을 때만 쓴다");
    }

    @Test
    @DisplayName("1-6. 빈 이름이 겹치면 Spring Boot 는 기동을 멈춘다 (Q28)")
    void duplicateBeanNamesAreRejectedInBoot() {
        // 순수 컨텍스트는 조용히 덮어쓴다 — 나중에 등록된 것이 이긴다
        try (var plain = new AnnotationConfigApplicationContext(
                FirstDefinition.class, SecondDefinition.class)) {
            String survivor = plain.getBean("저장소", Repository.class).name();
            fact("순수 컨텍스트에서 살아남은 빈", survivor);
            assertThat(survivor).isEqualTo("두번째");   // 경고 한 줄 없이 덮인다
        }

        // Spring Boot 기본값은 막는다 — 조용히 덮이는 게 더 위험하기 때문이다
        runner.withUserConfiguration(FirstDefinition.class, SecondDefinition.class).run(context -> {
            assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .isInstanceOf(BeanDefinitionOverrideException.class);
            fact("Boot 기본값에서", "기동 실패 (BeanDefinitionOverrideException)");
        });

        lesson("덮어쓰기가 조용히 되면 '왜 내 설정이 안 먹지'로 며칠을 쓴다. 그래서 기본이 실패다");
    }

    @Test
    @DisplayName("1-8. ApplicationContextRunner 는 순수 컨텍스트가 아니다 (Q125) ★이 레슨을 만들다 발견")
    void theRunnerAppliesBootDefaults() {
        // 1-2 와 1-6 을 만들 때, 같은 설정이 도구에 따라 다른 결과를 냈다.
        // ApplicationContextRunner 는 이름이 주는 인상과 달리 **Spring Boot 의 기본값**을 적용한다.
        runner.withUserConfiguration(FieldCycleConfig.class)
                .run(context -> assertThat(context).hasFailed());

        try (var plain = new AnnotationConfigApplicationContext(FieldCycleConfig.class)) {
            assertThat(plain.getBean(FieldA.class).b).isNotNull();   // 같은 설정인데 뜬다
        }

        fact("같은 설정, ApplicationContextRunner", "기동 실패");
        fact("같은 설정, AnnotationConfigApplicationContext", "기동 성공");

        lesson("테스트 도구가 프로덕션과 같은 기본값을 쓰는지 확인하지 않으면, 테스트가 거짓말을 한다");
        lesson("반대도 성립한다 — 순수 컨텍스트로 짠 테스트는 Boot 에서 막힐 것을 통과시킨다");
    }

    @Test
    @DisplayName("1-7. ObjectProvider 는 '없을 수도 있음'을 다룬다 (Q5)")
    void objectProviderHandlesAbsence() {
        // 빈이 없어도 기동에 실패하지 않는다
        runner.withUserConfiguration(OptionalConsumerConfig.class).run(context -> {
            var consumer = context.getBean(OptionalConsumer.class);
            fact("빈이 없을 때 폴백", consumer.describe());
            assertThat(consumer.describe()).isEqualTo("없음");
        });

        // 있으면 그것을 쓴다
        runner.withUserConfiguration(OptionalConsumerConfig.class, BasicConfig.class).run(context -> {
            var consumer = context.getBean(OptionalConsumer.class);
            fact("빈이 있을 때", consumer.describe());
            assertThat(consumer.describe()).isEqualTo("저장소");
        });

        lesson("선택적 의존에 @Autowired(required=false) 대신 쓴다 — null 이 아니라 '비어 있음'을 다룬다");
    }

    // ── 레슨용 빈 ──────────────────────────────────────────────────

    record Repository(String name) {}

    record ConstructorInjected(Repository repository) {}

    @Configuration(proxyBeanMethods = false)
    static class BasicConfig {
        @Bean
        Repository repository() {
            return new Repository("저장소");
        }

        @Bean
        ConstructorInjected constructorInjected(Repository repository) {
            return new ConstructorInjected(repository);
        }
    }

    // (1) 생성자 순환 — 만들 방법이 없다
    static class CycleA {
        CycleA(CycleB b) { }
    }

    static class CycleB {
        CycleB(CycleA a) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConstructorCycleConfig {
        @Bean
        CycleA cycleA(CycleB b) {
            return new CycleA(b);
        }

        @Bean
        CycleB cycleB(CycleA a) {
            return new CycleB(a);
        }
    }

    // (2) 필드 순환 — 반쯤 만들어진 것을 서로 꽂는다
    static class FieldA {
        @Autowired
        FieldB b;
    }

    static class FieldB {
        @Autowired
        FieldA a;
    }

    @Configuration(proxyBeanMethods = false)
    static class FieldCycleConfig {
        @Bean
        FieldA fieldA() {
            return new FieldA();
        }

        @Bean
        FieldB fieldB() {
            return new FieldB();
        }
    }

    record Consumer(Repository repository) {}

    @Configuration(proxyBeanMethods = false)
    static class AmbiguousConfig {
        @Bean
        Repository primary() {
            return new Repository("주-저장소");
        }

        @Bean
        Repository secondary() {
            return new Repository("보조-저장소");
        }

        @Bean
        Consumer consumer(Repository repository) {
            return new Consumer(repository);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryConfig {
        @Bean
        @Primary
        Repository primary() {
            return new Repository("주-저장소");
        }

        @Bean
        Repository secondary() {
            return new Repository("보조-저장소");
        }

        @Bean
        Consumer consumer(Repository repository) {
            return new Consumer(repository);
        }
    }

    record QualifiedConsumer(Repository repository) {}

    @Configuration(proxyBeanMethods = false)
    static class QualifiedConfig {
        @Bean
        @Primary
        Repository primary() {
            return new Repository("주-저장소");
        }

        @Bean
        Repository secondary() {
            return new Repository("보조-저장소");
        }

        // @Primary 가 있어도 @Qualifier 가 이긴다
        @Bean
        QualifiedConsumer qualifiedConsumer(@Qualifier("secondary") Repository repository) {
            return new QualifiedConsumer(repository);
        }
    }

    interface Handler {
        String name();
    }

    record HandlerRegistry(List<Handler> handlers) {
        List<String> names() {
            return handlers.stream().map(Handler::name).toList();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfig {
        // 선언 순서를 일부러 거꾸로 둔다 — 정렬은 @Order 가 한다는 것을 보이기 위해
        @Bean
        @Order(2)
        Handler later() {
            return () -> "나중";
        }

        @Bean
        @Order(1)
        Handler earlier() {
            return () -> "먼저";
        }

        @Bean
        HandlerRegistry registry(List<Handler> handlers) {
            return new HandlerRegistry(handlers);
        }
    }

    record Holder(Repository repository) {}

    /** proxyBeanMethods = true (기본값). */
    @Configuration
    static class ProxiedConfig {
        @Bean
        Repository repository() {
            return new Repository("저장소");
        }

        @Bean
        Holder holder() {
            return new Holder(repository());   // ← 직접 호출인데도 컨테이너 조회로 바뀐다
        }
    }

    /** proxyBeanMethods = false — 그냥 자바 호출이다. */
    @Configuration(proxyBeanMethods = false)
    static class LiteConfig {
        @Bean
        Repository repository() {
            return new Repository("저장소");
        }

        @Bean
        Holder holder() {
            return new Holder(repository());   // ← 새 인스턴스가 만들어진다
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FirstDefinition {
        @Bean(name = "저장소")
        Repository first() {
            return new Repository("첫번째");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondDefinition {
        @Bean(name = "저장소")
        Repository second() {
            return new Repository("두번째");
        }
    }

    record OptionalConsumer(ObjectProvider<Repository> provider) {
        String describe() {
            return provider.getIfAvailable(() -> new Repository("없음")).name();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OptionalConsumerConfig {
        @Bean
        OptionalConsumer optionalConsumer(ObjectProvider<Repository> provider) {
            return new OptionalConsumer(provider);
        }
    }
}
