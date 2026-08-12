package io.webboy.verify.labs.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Q129 — "Mockito 로 목 처리했습니다"의 위험한 전제.
 *
 * <p>목은 <b>내가 믿는 계약</b>을 재현할 뿐이라, 실제 API 가 바뀌어도 목은 그대로 통과한다.
 * 여기서는 목 기반 검증이 통과하는 동안 실제 서버(WireMock)의 응답이 바뀌면 어떻게 깨지는지를
 * HTTP 레벨에서 재현한다.
 */
@Component
public class MockDriftCase extends VerificationCase {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String id() {
        return "API-04";
    }

    @Override
    public String category() {
        return "api";
    }

    @Override
    public String question() {
        return "외부 API 연동 부분은 테스트를 어떻게 하셨습니까?";
    }

    @Override
    public String claim() {
        return "단위 테스트의 목은 '내가 믿는 계약'을 재현할 뿐이라, 실제 API 가 필드를 바꿔도 목은 계속 통과한다. HTTP 레벨의 스텁이나 계약 테스트를 함께 둬야 목과 현실의 괴리를 잡을 수 있다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        try {
            String base = "http://localhost:" + server.port();
            RestClient client = RestClient.create();

            // 1) 계약대로 응답하는 상태 — 목과 실제가 일치한다
            server.stubFor(get(urlEqualTo("/api/user/1")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":1,\"userName\":\"alice\"}")));
            String beforeChange = parseUserName(client, base);

            // 2) 제공 측이 필드 이름을 바꿨다 (userName → name) — 파괴적 변경
            server.resetAll();
            server.stubFor(get(urlEqualTo("/api/user/1")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":1,\"name\":\"alice\"}")));
            String afterChange = parseUserName(client, base);

            // 3) 목만 쓰는 단위 테스트는 실제 서버를 보지 않으므로 여전히 통과한다
            String mockedOnly = parseUserNameFromFixedJson();

            evidence.fact("실제 서버 응답(변경 전)", "{\"id\":1,\"userName\":\"alice\"}");
            evidence.fact("HTTP 스텁 기반 검증 결과(변경 전)", beforeChange);
            evidence.fact("실제 서버 응답(변경 후)", "{\"id\":1,\"name\":\"alice\"}");
            evidence.fact("HTTP 스텁 기반 검증 결과(변경 후)", afterChange);
            evidence.fact("목만 쓰는 단위 테스트 결과", mockedOnly);

            evidence.expectEquals("계약이 맞을 때는 정상 파싱된다", "alice", beforeChange);
            evidence.expect("제공 측이 필드를 바꾸면 HTTP 레벨 검증은 즉시 깨진다",
                    !afterChange.equals("alice"));
            evidence.expectEquals("반면 목만 쓰는 단위 테스트는 아무 일 없다는 듯 통과한다", "alice", mockedOnly);
        } finally {
            server.stop();
        }

        evidence.note("목이 나쁜 것이 아니라 목의 '목적'이 다르다. 목은 속도(네트워크 없이 로직만 빠르게)를 위한 것이고, 목이 현실과 어긋나지 않았음을 보증하는 것은 통합·계약 테스트의 몫이다.");
        evidence.note("Pact 같은 계약 테스트는 제공 측과 소비 측이 같은 계약 파일에 대해 각자 테스트하게 만든다 — 제공 측이 계약을 깨면 제공 측 CI 가 먼저 빨개진다.");
        evidence.note("필드 삭제·이름 변경이 파괴적 변경이라는 점은 API-01(가산적 변경)과 KAFKA-07(스키마 호환성)에서 본 원칙과 같다. 계약을 늘리기만 하면 이 사고 자체가 나지 않는다.");
        evidence.note("클라이언트를 관대하게(모르는 필드는 무시) 만들면 '필드 추가'에는 안 깨지지만, 이 케이스처럼 이름이 바뀌면 값이 null 이 되어 조용히 잘못 동작한다 — 그래서 필수 필드는 명시적으로 검증해야 한다.");
    }

    private String parseUserName(RestClient client, String base) {
        String body = client.get().uri(base + "/api/user/1").retrieve().body(String.class);
        return extractUserName(body);
    }

    /** 목만 쓰는 단위 테스트는 이 고정 JSON 을 계속 믿는다. */
    private String parseUserNameFromFixedJson() {
        return extractUserName("{\"id\":1,\"userName\":\"alice\"}");
    }

    private String extractUserName(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode userName = node.get("userName");
            return userName == null ? "(userName 없음 — 파싱 실패)" : userName.asText();
        } catch (Exception e) {
            return "(파싱 예외: " + e.getClass().getSimpleName() + ")";
        }
    }
}
