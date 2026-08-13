package io.webboy.verify.labs.security;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q102 — CORS 는 무엇을 막고 무엇을 막지 않는가.
 *
 * <p>흉내내지 않고 <b>Spring 의 실제 {@link CorsFilter}</b> 에 요청을 통과시켜, 요청마다
 * <b>뒤쪽 핸들러까지 도달했는지</b>를 함께 센다. "무엇이 막히는가"를 헤더가 아니라 도달 여부로 본다.
 *
 * <p>여기서 교과서 설명과 실물이 갈린다. 명세상 CORS 는 브라우저가 응답을 버리는 규칙이지만,
 * Spring 의 필터는 허용되지 않은 출처를 <b>서버가 먼저 403 으로 끊는다.</b>
 * 그럼에도 "CORS 는 접근 제어가 아니다"는 성립하는데, 이유는 다른 데 있다 —
 * <b>Origin 헤더가 없으면 검사 자체를 건너뛰기 때문</b>이다.
 *
 * <p>{@code SEC-01}(CSRF)과 함께 보면 좋다. 둘 다 브라우저가 규칙을 지킨다는 전제 위의 방어라
 * 규칙을 따르지 않는 클라이언트 앞에서는 무력하다는 성질을 공유한다.
 */
@Component
public class CorsCase extends VerificationCase {

    private static final String ALLOWED = "https://app.example.com";
    private static final String ATTACKER = "https://evil.example.com";

    @Override
    public String id() {
        return "SEC-03";
    }

    @Override
    public String category() {
        return "security";
    }

    @Override
    public String question() {
        return "CORS 는 어떤 문제를 푸는 장치이고, 프리플라이트는 언제 발생합니까?";
    }

    @Override
    public String claim() {
        return "CORS 는 브라우저가 다른 출처의 응답을 스크립트에 넘겨줄지 결정하는 규칙이다. 서버가 허용 목록에 없는 출처에는 Access-Control-Allow-Origin 을 내리지 않아 브라우저가 응답을 버리며, 단순 요청이 아닌 경우 브라우저가 먼저 OPTIONS 프리플라이트를 보내 허용 여부를 묻는다. 다만 차단 주체가 브라우저이므로 CORS 는 접근 제어가 아니다 — 비브라우저 클라이언트에는 아무 효과가 없다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // 각 요청이 필터를 지나 '뒤쪽 핸들러'까지 갔는지를 따로 센다 — CORS 가 실제로 무엇을 막는지의 근거
        Exchange preflightAllowed = through(preflight(ALLOWED, "PUT", "X-Request-Id"));
        Exchange preflightDenied = through(preflight(ATTACKER, "PUT", "X-Request-Id"));
        Exchange simpleAllowed = through(simple(ALLOWED));
        Exchange simpleFromAttacker = through(simple(ATTACKER));
        Exchange noOrigin = through(new MockHttpServletRequest("GET", "/api/orders"));

        evidence.fact("허용 출처", ALLOWED);
        evidence.fact("[프리플라이트] 허용 출처 — 상태 / Allow-Origin / 로직 실행",
                describe(preflightAllowed));
        evidence.fact("[프리플라이트] 허용 메서드",
                preflightAllowed.response().getHeader("Access-Control-Allow-Methods"));
        evidence.fact("[프리플라이트] 차단 출처 — 상태 / Allow-Origin / 로직 실행",
                describe(preflightDenied));
        evidence.fact("[단순 요청] 허용 출처 — 상태 / Allow-Origin / 로직 실행", describe(simpleAllowed));
        evidence.fact("[단순 요청] 공격자 출처 — 상태 / Allow-Origin / 로직 실행", describe(simpleFromAttacker));
        evidence.fact("[Origin 헤더 없음] 상태 / Allow-Origin / 로직 실행", describe(noOrigin));

        evidence.expectEquals("프리플라이트는 본 요청 전에 OPTIONS 로 먼저 물어보고 200 으로 답한다",
                200, preflightAllowed.response().getStatus());
        evidence.expectEquals("허용 출처에는 Access-Control-Allow-Origin 이 내려간다",
                ALLOWED, header(preflightAllowed));
        evidence.expectEquals("허용되지 않은 출처의 프리플라이트는 403 으로 끊긴다",
                403, preflightDenied.response().getStatus());
        evidence.expect("차단된 프리플라이트에는 Allow-Origin 이 없다", header(preflightDenied) == null);
        evidence.expect("프리플라이트는 허용이든 차단이든 비즈니스 로직까지 가지 않는다 — 물어보는 단계일 뿐이다",
                !preflightAllowed.reachedHandler() && !preflightDenied.reachedHandler());

        evidence.expect("허용 출처의 단순 요청은 Allow-Origin 을 달고 통과한다",
                simpleAllowed.reachedHandler() && ALLOWED.equals(header(simpleAllowed)));
        evidence.expectEquals("공격자 출처의 단순 요청은 서버가 403 으로 끊는다 — 브라우저까지 가지도 않는다",
                403, simpleFromAttacker.response().getStatus());
        evidence.expect("그래서 그 요청은 비즈니스 로직에 도달하지 못한다",
                !simpleFromAttacker.reachedHandler());

        evidence.expectEquals("그런데 Origin 헤더가 없으면 CORS 검사 자체를 건너뛴다", 200,
                noOrigin.response().getStatus());
        evidence.expect("그 요청은 비즈니스 로직까지 그대로 도달한다 — CORS 는 접근 제어가 아니다",
                noOrigin.reachedHandler() && header(noOrigin) == null);

        evidence.note("이 랩이 케이스를 쓰면서 한 번 틀렸던 지점이다. 교과서는 'CORS 는 브라우저가 막는 것이라 서버 로직은 그대로 실행된다'고 설명하는데, **Spring 의 CorsFilter 는 그렇지 않다** — 위 관측값처럼 허용되지 않은 출처의 단순 요청을 서버가 직접 403 으로 끊고 핸들러까지 보내지 않는다. 명세(브라우저가 응답을 버린다)와 프레임워크 구현(서버가 먼저 끊는다)이 다르다는 것을 알고 말해야 한다.");
        evidence.note("그럼에도 'CORS 는 접근 제어가 아니다'는 여전히 맞다. 근거가 다를 뿐이다 — 위에서 보듯 **Origin 헤더가 없으면 검사 자체를 하지 않는다.** 공격자는 브라우저를 쓰지 않고 curl 로 그냥 붙으면 되고, 그때는 CORS 설정이 몇 줄이든 아무 의미가 없다. 인증·인가는 CORS 와 완전히 별개로 있어야 한다.");
        evidence.note("프리플라이트는 '단순 요청'이 아닐 때만 발생한다. GET/HEAD/POST 이면서 Content-Type 이 text/plain·form-urlencoded·multipart 이고 커스텀 헤더가 없으면 단순 요청이다. 실무에서 프리플라이트가 갑자기 늘었다면 대개 Authorization 이나 X-Request-Id 같은 헤더를 붙였기 때문이다.");
        evidence.note("프리플라이트는 왕복이 한 번 더 늘어난다. maxAge 를 주면(이 케이스는 1800초) 브라우저가 결과를 캐시해 반복 호출에서 사라지므로, 프리플라이트 때문에 지연이 늘었다면 여기부터 본다.");
        evidence.note("allowCredentials=true 와 allowedOrigins=\"*\" 는 함께 쓸 수 없다 — 자격 증명이 실린 요청에 와일드카드를 허용하면 아무 사이트나 사용자의 세션으로 API 를 읽을 수 있기 때문이다. Spring 도 이 조합을 예외로 막는다.");
        evidence.note("SEC-01(CSRF)과 같은 성질이다. 둘 다 브라우저의 규칙에 기대는 방어라, 규칙을 지키지 않는 클라이언트 앞에서는 무력하다. 서버 측 방어는 언제나 별도로 필요하다.");
    }

    /** 커스텀 헤더가 붙어 프리플라이트가 필요한 요청. */
    private MockHttpServletRequest preflight(String origin, String method, String requestHeaders) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/orders");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        request.addHeader("Access-Control-Request-Headers", requestHeaders);
        return request;
    }

    /** 프리플라이트가 필요 없는 '단순 요청'. */
    private MockHttpServletRequest simple(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader("Origin", origin);
        return request;
    }

    /**
     * 한 번의 요청 결과.
     *
     * @param reachedHandler 필터를 통과해 뒤쪽 핸들러까지 갔는가 — "CORS 가 무엇을 막는가"의 직접 증거
     */
    private record Exchange(MockHttpServletResponse response, boolean reachedHandler) {}

    /** 실제 {@link CorsFilter} 에 요청을 통과시킨다. */
    private Exchange through(MockHttpServletRequest request) throws Exception {
        AtomicInteger businessLogicRuns = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(ALLOWED));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT"));
        configuration.setAllowedHeaders(List.of("X-Request-Id", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        FilterChain countingChain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                businessLogicRuns.incrementAndGet();   // 여기까지 왔다 = 서버 로직이 돌았다
            }
        };
        new CorsFilter(source).doFilter(request, response, countingChain);
        return new Exchange(response, businessLogicRuns.get() > 0);
    }

    private String header(Exchange exchange) {
        return exchange.response().getHeader("Access-Control-Allow-Origin");
    }

    private String describe(Exchange exchange) {
        return exchange.response().getStatus()
                + " / " + (header(exchange) == null ? "(없음)" : header(exchange))
                + " / " + (exchange.reachedHandler() ? "실행됨" : "도달 못 함");
    }
}
