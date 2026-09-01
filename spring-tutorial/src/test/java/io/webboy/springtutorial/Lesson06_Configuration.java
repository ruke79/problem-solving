package io.webboy.springtutorial;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레슨 6 — 설정 바인딩 (면접 Q18 · Q19 · Q20)
 *
 * <p>6-2 의 <b>완화된 바인딩</b>과 6-4 의 <b>오류가 드러나는 시점 차이</b>가 핵심이다.
 * "환경변수로는 어떻게 넘기죠?" 에 정확히 답하려면 6-2 를 실행해 본 적이 있어야 한다.
 */
@DisplayName("레슨 6. 설정 — 값이 코드에 닿기까지")
class Lesson06_Configuration {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    @DisplayName("6-1. @ConfigurationProperties 는 접두사 아래를 통째로 객체에 묶는다 (Q19)")
    void propertiesBindAsAGroup() {
        runner.withUserConfiguration(PropsConfig.class)
                .withPropertyValues(
                        "app.client.base-url=https://api.example.test",
                        "app.client.timeout=5s",
                        "app.client.max-retries=3")
                .run(context -> {
                    var props = context.getBean(ClientProperties.class);

                    fact("baseUrl", props.baseUrl());
                    fact("timeout (Duration 으로 변환됨)", props.timeout());
                    fact("maxRetries", props.maxRetries());

                    assertThat(props.baseUrl()).isEqualTo("https://api.example.test");
                    assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(5));   // "5s" → Duration
                    assertThat(props.maxRetries()).isEqualTo(3);
                });

        lesson("'5s' 가 Duration 이 되는 타입 변환까지가 바인딩이다 — @Value 로는 이만큼 안 된다");
        lesson("관련 설정 열 개가 한 타입으로 묶이면, 그 타입이 곧 설정의 문서가 된다");
    }

    @Test
    @DisplayName("6-2. 완화된 바인딩 — kebab·camel·환경변수 표기가 전부 같은 값이다 (Q18·Q19) ★핵심")
    void relaxedBindingAcceptsManySpellings() {
        // kebab-case (권장 표기)
        runner.withUserConfiguration(PropsConfig.class)
                .withPropertyValues("app.client.max-retries=1")
                .run(context -> assertThat(
                        context.getBean(ClientProperties.class).maxRetries()).isEqualTo(1));

        // camelCase 로 써도 같은 자리에 꽂힌다
        runner.withUserConfiguration(PropsConfig.class)
                .withPropertyValues("app.client.maxRetries=2")
                .run(context -> assertThat(
                        context.getBean(ClientProperties.class).maxRetries()).isEqualTo(2));

        // 환경변수 표기(APP_CLIENT_MAXRETRIES) — 실제 환경변수 소스 형태로 넣어 본다.
        // 소스 이름이 반드시 "systemEnvironment" 여야 한다 — Boot 의 바인더는 '이름'을 보고
        // 환경변수용 매핑 규칙을 켠다. 처음에 이름을 마음대로 지었더니 0 이 나왔다.
        runner.withUserConfiguration(PropsConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                org.springframework.core.env.StandardEnvironment
                                        .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("APP_CLIENT_MAXRETRIES", "9"))))
                .run(context -> {
                    var props = context.getBean(ClientProperties.class);
                    fact("APP_CLIENT_MAXRETRIES=9 로 넘긴 결과", props.maxRetries());
                    assertThat(props.maxRetries()).isEqualTo(9);
                });

        lesson("컨테이너·쉘에서는 점과 대시를 못 쓴다 — 대문자+언더스코어가 규칙대로 매핑된다");
        lesson("이게 @ConfigurationProperties 를 쓰는 실질적 이유 중 하나다. @Value 는 이 매핑이 훨씬 약하다");
    }

    @Test
    @DisplayName("6-3. 먼저 등록된 프로퍼티 소스가 이긴다 (Q18)")
    void earlierPropertySourcesWin() {
        runner.withUserConfiguration(PropsConfig.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    // 나중 순위 — 파일(application.yml)에 해당
                    sources.addLast(new MapPropertySource("파일-역할",
                            Map.of("app.client.max-retries", "1")));
                    // 앞 순위 — 커맨드라인/환경변수에 해당
                    sources.addFirst(new MapPropertySource("커맨드라인-역할",
                            Map.of("app.client.max-retries", "7")));
                })
                .run(context -> {
                    var props = context.getBean(ClientProperties.class);
                    fact("두 소스가 같은 키를 가질 때", props.maxRetries());
                    assertThat(props.maxRetries()).isEqualTo(7);   // 앞선 소스가 이겼다
                });

        lesson("우선순위의 실체는 '소스 목록의 순서'다 — 커맨드라인 > 환경변수 > 파일이 그 순서로 꽂혀 있을 뿐");
        lesson("같은 키가 어디서 왔는지 헷갈리면 actuator 의 env 엔드포인트가 소스별로 보여 준다");
    }

    @Test
    @DisplayName("6-4. @Value 의 오타는 기동을 깨고, 바인딩 누락은 조용히 기본값이 된다 (Q19)")
    void failureModesDiffer() {
        // @Value 로 참조한 키가 없으면 — 플레이스홀더를 못 풀어 기동 자체가 실패한다.
        // (엄밀히는 PropertySourcesPlaceholderConfigurer 가 있을 때의 이야기다.
        //  Boot 는 이 빈을 자동 등록한다 — 없는 순수 컨텍스트는 문자열이 그대로 주입되고 조용히 뜬다.)
        runner.withUserConfiguration(ValueConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    fact("@Value(\"${없는.키}\")", "기동 실패");
                });

        // @ConfigurationProperties 는 키가 없으면 — 그냥 자바 기본값(0/null)으로 남는다
        runner.withUserConfiguration(PropsConfig.class)
                .run(context -> {
                    var props = context.getBean(ClientProperties.class);
                    fact("아무 설정 없이 바인딩", "baseUrl=" + props.baseUrl()
                            + ", maxRetries=" + props.maxRetries());
                    assertThat(props.baseUrl()).isNull();
                    assertThat(props.maxRetries()).isZero();
                });

        lesson("시끄러운 실패(@Value)와 조용한 누락(@ConfigurationProperties) — 후자가 더 위험할 수 있다");
        lesson("그래서 다음 레슨의 검증(@Validated)을 붙여 조용한 누락도 시끄럽게 만든다");
    }

    @Test
    @DisplayName("6-5. @Validated 를 붙이면 잘못된 설정이 기동을 막는다 (Q19·Q144)")
    void validatedPropertiesFailFast() {
        // 범위를 벗어난 값 — 기동 실패
        runner.withUserConfiguration(ValidatedPropsConfig.class)
                .withPropertyValues("app.pool.size=0")     // @Min(1) 위반
                .run(context -> {
                    assertThat(context).hasFailed();
                    fact("app.pool.size=0 (@Min(1))", "기동 실패");
                });

        // 올바른 값 — 정상 기동
        runner.withUserConfiguration(ValidatedPropsConfig.class)
                .withPropertyValues("app.pool.size=8")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PoolProperties.class).size()).isEqualTo(8);
                });

        lesson("설정 실수는 '첫 요청에서 이상 동작'이 아니라 '기동 실패'로 드러나야 한다(Q144)");
        lesson("배포 파이프라인이 기동 실패를 잡아 주는 순간, 설정 오류는 프로덕션에 못 들어간다");
    }

    @Test
    @DisplayName("6-6. 프로파일이 빈 구성을 바꾼다 (Q20)")
    void profilesSelectBeans() {
        // 프로파일 미지정 — 기본 빈이 뜬다
        runner.withUserConfiguration(ProfiledConfig.class)
                .run(context -> {
                    fact("프로파일 없음", context.getBean(Notifier.class).channel());
                    assertThat(context.getBean(Notifier.class).channel()).isEqualTo("콘솔(기본)");
                });

        // prod 활성화 — 같은 타입의 다른 빈으로 갈아끼워진다
        runner.withUserConfiguration(ProfiledConfig.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    fact("spring.profiles.active=prod", context.getBean(Notifier.class).channel());
                    assertThat(context.getBean(Notifier.class).channel()).isEqualTo("메일(운영)");
                });

        lesson("코드는 하나, 조립이 프로파일로 갈린다 — if(env==prod) 를 코드에 심지 않기 위한 장치다");
        lesson("주의는 하나 — 프로파일이 늘수록 '운영에서만 나는 문제'도 는다. 개수는 최소로");
    }

    @Test
    @DisplayName("6-7. 리스트·맵도 구조 그대로 바인딩된다 (Q19)")
    void collectionsBindStructurally() {
        runner.withUserConfiguration(PropsConfig.class)
                .withPropertyValues(
                        "app.client.endpoints[0]=https://a.test",
                        "app.client.endpoints[1]=https://b.test",
                        "app.client.headers.x-team=core",
                        "app.client.headers.x-region=kr")
                .run(context -> {
                    var props = context.getBean(ClientProperties.class);

                    fact("endpoints", props.endpoints());
                    fact("headers", props.headers());

                    assertThat(props.endpoints())
                            .containsExactly("https://a.test", "https://b.test");
                    assertThat(props.headers())
                            .containsEntry("x-team", "core")
                            .containsEntry("x-region", "kr");
                });

        lesson("설정이 구조를 가지기 시작하면 @Value 로는 못 따라간다 — 처음부터 프로퍼티 클래스로 시작한다");
    }

    // ── 레슨용 설정 클래스 ───────────────────────────────────────────

    @ConfigurationProperties("app.client")
    record ClientProperties(String baseUrl, Duration timeout, int maxRetries,
                            List<String> endpoints, Map<String, String> headers) {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClientProperties.class)
    static class PropsConfig {
    }

    static class ValueHolder {
        ValueHolder(@Value("${없는.키}") String value) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ValueConfig {
        /**
         * Boot 라면 자동 등록되는 빈. 이게 있어야 미해결 플레이스홀더가 '기동 실패'가 된다 —
         * 없으면 순수 컨텍스트는 {@code ${없는.키}} 라는 문자열을 그대로 주입하고 조용히 뜬다
         * (이 레슨을 만들다 발견했다).
         */
        @Bean
        static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders() {
            return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ValueHolder valueHolder(@Value("${없는.키}") String value) {
            return new ValueHolder(value);
        }
    }

    @Validated
    @ConfigurationProperties("app.pool")
    record PoolProperties(@Min(1) @Max(64) int size) {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PoolProperties.class)
    static class ValidatedPropsConfig {
    }

    interface Notifier {
        String channel();
    }

    @Configuration(proxyBeanMethods = false)
    static class ProfiledConfig {
        @Bean
        @Profile("!prod")
        Notifier consoleNotifier() {
            return () -> "콘솔(기본)";
        }

        @Bean
        @Profile("prod")
        Notifier mailNotifier() {
            return () -> "메일(운영)";
        }
    }
}
