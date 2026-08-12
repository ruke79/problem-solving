package io.webboy.verify.labs.security;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Q19 (CSRF 부분) — Synchronizer Token 이 실제로 무엇을 막는지.
 *
 * <p>흉내내지 않고 <b>Spring Security 의 실제 {@link CsrfFilter}</b> 에 요청을 통과시켜 관측한다.
 * 브라우저가 쿠키를 자동 전송한다는 전제 위에서, 토큰이 없는 POST 는 403 으로 끊기고
 * 토큰이 실린 POST 만 통과한다. GET 은 애초에 검사 대상이 아니다.
 */
@Component
public class CsrfCase extends VerificationCase {

    private final HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();

    @Override
    public String id() {
        return "SEC-01";
    }

    @Override
    public String category() {
        return "security";
    }

    @Override
    public String question() {
        return "CSRF 공격의 원인과 방어를 아키텍처 관점에서 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "CSRF 는 인증 쿠키가 공격자 사이트에서 온 요청에도 자동 전송되는 것이 원인이다. 서버가 발급한 토큰을 요청에 실어 검증하면(Synchronizer Token) 쿠키만으로는 위조할 수 없고, Authorization 헤더 방식으로 가면 애초에 자동 전송이 없어 구조적으로 사라진다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        MockHttpServletRequest tokenRequest = new MockHttpServletRequest("GET", "/form");
        MockHttpServletResponse tokenResponse = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(tokenRequest);
        repository.saveToken(token, tokenRequest, tokenResponse);

        int getWithoutToken = statusOf("GET", null, tokenRequest, false);
        int postWithoutToken = statusOf("POST", null, tokenRequest, false);
        int postWithWrongToken = statusOf("POST", "not-the-real-token", tokenRequest, false);
        int postWithToken = statusOf("POST", token.getToken(), tokenRequest, false);
        int postWithRawTokenUnderXor = statusOf("POST", token.getToken(), tokenRequest, true);

        String cookieHeader = org.springframework.http.ResponseCookie.from("SESSION", "abc")
                .httpOnly(true).secure(true).sameSite("Lax").build().toString();

        evidence.fact("GET (안전한 메서드)", getWithoutToken);
        evidence.fact("POST — 토큰 없음", postWithoutToken);
        evidence.fact("POST — 토큰이 틀림", postWithWrongToken);
        evidence.fact("POST — 올바른 토큰", postWithToken);
        evidence.fact("POST — 올바른 토큰이지만 XOR 마스킹 핸들러(기본값) 사용", postWithRawTokenUnderXor);
        evidence.fact("권장 세션 쿠키 속성", cookieHeader);

        evidence.expectEquals("GET 은 CSRF 검사 대상이 아니다", 200, getWithoutToken);
        evidence.expectEquals("토큰 없는 POST 는 403 으로 차단된다", HttpServletResponse.SC_FORBIDDEN, postWithoutToken);
        evidence.expectEquals("토큰이 틀린 POST 도 403 이다", HttpServletResponse.SC_FORBIDDEN, postWithWrongToken);
        evidence.expectEquals("올바른 토큰이 실린 POST 만 통과한다", 200, postWithToken);
        evidence.expectEquals("Spring Security 6 의 기본 핸들러는 XOR 마스킹된 값을 기대하므로 원본 토큰을 그대로 보내면 거절된다",
                HttpServletResponse.SC_FORBIDDEN, postWithRawTokenUnderXor);
        evidence.expect("쿠키에 SameSite=Lax 를 붙일 수 있다", cookieHeader.contains("SameSite=Lax"));

        evidence.note("Spring Security 6 는 BREACH 공격 대응으로 응답에 나가는 토큰을 매번 XOR 마스킹한다. 그래서 '세션에 저장된 값'과 '폼에 실린 값'이 문자열로는 다르며, 서버가 언마스킹해 비교한다 — 토큰을 직접 만들어 보내려다 403 을 만나면 대개 이 때문이다.");
        evidence.note("GET 이 검사에서 빠지는 것은 '안전한 메서드는 상태를 바꾸지 않는다'는 전제 때문이다. GET 으로 상태를 바꾸는 API 를 만들면 그 전제가 깨져 CSRF 방어에 구멍이 생긴다(API-03 의 멱등성 논의와 같은 뿌리다).");
        evidence.note("SameSite=Lax 는 크로스 사이트에서의 자동 전송 자체를 막아 주지만, 브라우저·버전에 의존하는 방어라 토큰과 함께 쓰는 다층 방어가 정석이다.");
        evidence.note("쿠키 대신 Authorization 헤더의 Bearer 토큰을 쓰면 자동 전송이 없어 CSRF 는 구조적으로 사라진다. 대신 토큰을 localStorage 에 두면 XSS 한 방에 탈취되므로(SEC-02) 리스크가 옮겨갈 뿐이다.");
        evidence.note("그래서 실무의 절충안이 '리프레시 토큰은 httpOnly 쿠키, 액세스 토큰은 메모리'다. 어느 위협을 더 무겁게 볼지 정하고 설계하는 것이 본질이다(RES-06 의 회전 전략과 함께 본다).");
    }

    /**
     * 실제 CsrfFilter 에 요청을 통과시켜 최종 상태 코드를 얻는다.
     *
     * @param xorHandler Spring Security 6 의 기본 핸들러(XOR 마스킹)를 쓸지 여부
     */
    private int statusOf(String method, String token, MockHttpServletRequest sessionSource, boolean xorHandler)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/orders");
        request.setSession(sessionSource.getSession());
        if (token != null) {
            request.setParameter("_csrf", token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfFilter filter = new CsrfFilter(repository);
        if (!xorHandler) {
            // 마스킹 없이 원본 토큰을 그대로 비교하는 핸들러 (예전 방식)
            filter.setRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler());
        }
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
