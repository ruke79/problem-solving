package io.webboy.verify.labs.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.Map;

/**
 * Q19 (XSS 부분) — 백엔드가 책임지는 경계까지.
 *
 * <p>방어의 핵심은 입력 검증이 아니라 <b>출력 시 컨텍스트에 맞는 이스케이프</b>다.
 * 템플릿 엔진의 자동 이스케이프가 실제로 무엇을 바꾸는지, 그것을 끄는 순간({@code th:utext})
 * 무엇이 되는지, JSON 응답은 어떻게 나가는지, 그리고 CSP 헤더까지 관측한다.
 *
 * <p><b>이 랩이 증명하지 못하는 것</b>: 브라우저가 실제로 스크립트를 실행하는지 여부.
 * 그건 브라우저가 있어야 하고, 여기서 보는 것은 "서버가 무엇을 내보냈는가"까지다.
 */
@Component
public class XssEscapingCase extends VerificationCase {

    private static final String PAYLOAD = "<script>alert('xss')</script>";

    private final SpringTemplateEngine templateEngine = createEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String id() {
        return "SEC-02";
    }

    @Override
    public String category() {
        return "security";
    }

    @Override
    public String question() {
        return "XSS 공격을 아키텍처 관점에서 어떻게 막습니까?";
    }

    @Override
    public String claim() {
        return "XSS 방어의 기본은 입력 검증이 아니라 출력 시 컨텍스트에 맞는 이스케이프이고, 그것은 직접 구현하지 않고 템플릿 엔진에 맡긴다. 위험한 지점은 자동 이스케이프를 끄는 곳(th:utext)이며, CSP 헤더로 인라인 스크립트를 금지하는 다층 방어를 함께 둔다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String escaped = render("<p th:text=\"${comment}\"></p>");
        String unescaped = render("<p th:utext=\"${comment}\"></p>");
        // 링크 표현식(@{...})은 웹 컨텍스트가 있어야 하므로, 속성값 컨텍스트로 확인한다
        String attributeContext = render("<a th:title=\"${comment}\">link</a>");
        String json = toJson(PAYLOAD);
        String csp = cspHeader();

        boolean escapedIsSafe = escaped.contains("&lt;script&gt;") && !escaped.contains("<script>");
        boolean unescapedIsRaw = unescaped.contains("<script>");
        boolean jsonKeepsPayloadAsData = json.contains("\\u003cscript") || json.contains("<script");

        evidence.fact("입력 값", PAYLOAD);
        evidence.fact("th:text (자동 이스케이프)", escaped);
        evidence.fact("th:utext (이스케이프 끔)", unescaped);
        evidence.fact("속성값 컨텍스트 (th:title)", attributeContext);
        evidence.fact("JSON 직렬화 결과", json);
        evidence.fact("CSP 헤더", csp);

        evidence.expect("자동 이스케이프는 태그를 문자로 바꿔 실행되지 않게 한다", escapedIsSafe);
        evidence.expect("th:utext 는 입력을 그대로 내보내 XSS 통로가 된다", unescapedIsRaw);
        evidence.expect("속성값 컨텍스트에서도 이스케이프가 적용된다",
                !attributeContext.contains("<script>") && attributeContext.contains("&lt;script&gt;"));
        evidence.expect("JSON 응답에서 페이로드는 데이터로만 남는다", jsonKeepsPayloadAsData);
        evidence.expect("CSP 로 인라인 스크립트를 금지할 수 있다",
                csp.contains("script-src") && !csp.contains("unsafe-inline"));

        evidence.note("이스케이프 규칙은 컨텍스트마다 다르다 — HTML 본문, 속성값, JavaScript 안, URL 안이 각각 다르다. 그래서 자체 구현하지 않고 템플릿 엔진에 맡기는 것이 원칙이다.");
        evidence.note("리치 텍스트처럼 태그를 살려야 하는 경우에만 th:utext 를 쓰고, 그때는 반드시 서버 쪽에서 OWASP Java HTML Sanitizer 같은 화이트리스트 정화를 통과시킨다.");
        evidence.note("**이 케이스가 증명하지 못하는 것**: 브라우저가 실제로 스크립트를 실행하는지. 여기서 확인한 것은 '서버가 무엇을 내보냈는가'까지이고, 그 앞은 브라우저 테스트의 영역이다.");
        evidence.note("CSP 는 이스케이프가 뚫렸을 때의 2차 방어다. 인라인 스크립트를 금지하면 주입된 <script> 가 살아 있어도 실행되지 않는다 — 다만 기존 페이지가 인라인 스크립트를 쓰고 있으면 적용 자체가 큰 작업이 된다.");
    }

    private String render(String template) {
        Context context = new Context();
        context.setVariables(Map.of("comment", PAYLOAD));
        return templateEngine.process(template, context).replaceAll("\\s+", " ").trim();
    }

    private String toJson(String value) {
        try {
            return objectMapper.writeValueAsString(Map.of("comment", value));
        } catch (Exception e) {
            return "(직렬화 실패: " + e.getMessage() + ")";
        }
    }

    private String cspHeader() {
        ContentSecurityPolicyHeaderWriter writer =
                new ContentSecurityPolicyHeaderWriter("default-src 'self'; script-src 'self'");
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.writeHeaders(new MockHttpServletRequest(), response);
        return response.getHeader("Content-Security-Policy");
    }

    /** SpringTemplateEngine 은 SpEL 을 쓴다 — 순수 TemplateEngine 은 OGNL 이 필요해 의존성이 하나 더 붙는다. */
    private static SpringTemplateEngine createEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
