package io.webboy.verify.labs.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

/** Q108 — 버전을 올리기 전에 "안 올려도 되는 변경"인지 먼저 판별한다. */
@Component
public class AdditiveChangeCase extends VerificationCase {

    /** v1 클라이언트가 아는 형태. */
    public static class UserV1 {
        public String id;
        public String name;
    }

    /** v2 에서 선택 필드가 추가된 형태. */
    public static class UserV2 {
        public String id;
        public String name;
        public String nickname;
    }

    @Override
    public String id() {
        return "API-01";
    }

    @Override
    public String category() {
        return "api";
    }

    @Override
    public String question() {
        return "API 버저닝 전략은 어떻게 가져갑니까?";
    }

    @Override
    public String claim() {
        return "필드 추가 같은 가산적 변경은 클라이언트를 깨지 않으므로 버전을 올릴 필요가 없다. 버저닝은 필드 삭제·타입 변경 같은 파괴적 변경의 최후 수단이다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        ObjectMapper lenient = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ObjectMapper strict = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        String v1Payload = "{\"id\":\"u1\",\"name\":\"alice\"}";
        String v2Payload = "{\"id\":\"u1\",\"name\":\"alice\",\"nickname\":\"al\"}";
        String removedFieldPayload = "{\"id\":\"u1\"}";
        String typeChangedPayload = "{\"id\":123,\"name\":\"alice\"}";

        String additiveOnOldClient = attempt(() -> lenient.readValue(v2Payload, UserV1.class).name);
        String strictOldClient = attempt(() -> strict.readValue(v2Payload, UserV1.class).name);
        String oldPayloadOnNewServer = attempt(() -> {
            UserV2 parsed = lenient.readValue(v1Payload, UserV2.class);
            return parsed.name + "/nickname=" + parsed.nickname;
        });
        String removedField = attempt(() -> String.valueOf(lenient.readValue(removedFieldPayload, UserV1.class).name));
        String typeChanged = attempt(() -> lenient.readValue(typeChangedPayload, UserV1.class).id);

        evidence.fact("v2 페이로드를 v1 클라이언트가 파싱(관대 모드)", additiveOnOldClient);
        evidence.fact("v2 페이로드를 v1 클라이언트가 파싱(엄격 모드)", strictOldClient);
        evidence.fact("v1 페이로드를 v2 서버가 파싱", oldPayloadOnNewServer);
        evidence.fact("필드 삭제 시 v1 클라이언트가 읽은 값", removedField);
        evidence.fact("타입 변경(string→number) 파싱", typeChanged);

        evidence.expectEquals("필드 추가는 구 클라이언트를 깨지 않는다", "alice", additiveOnOldClient);
        evidence.expectEquals("구 페이로드는 신 서버에서 선택 필드가 null 로 들어온다",
                "alice/nickname=null", oldPayloadOnNewServer);
        evidence.expectEquals("필드를 삭제하면 구 클라이언트는 null 을 받는다(계약 위반)", "null", removedField);
        evidence.expect("알 수 없는 필드에 엄격한 클라이언트는 가산적 변경에도 깨진다",
                strictOldClient.startsWith("FAILED"));

        evidence.note("서버가 가산적 변경을 해도 클라이언트가 엄격 모드면 깨진다 — '가산적 변경은 안전하다'는 클라이언트 측 관용(FAIL_ON_UNKNOWN_PROPERTIES=false)이 전제다.");
        evidence.note("Jackson 은 string→number 를 관대하게 강제 변환하는 경우가 있으므로, 타입 변경의 위험은 파싱 성공 여부가 아니라 의미가 조용히 바뀐다는 데 있다.");
        evidence.note("버전을 올린다면 Sunset 헤더로 폐기 예정일을 알리고, 구 버전 호출 추이를 모니터링해 이용자가 줄어든 뒤 폐기하는 운영까지가 버저닝 전략이다.");
    }

    private interface Parser {
        String parse() throws Exception;
    }

    private String attempt(Parser parser) {
        try {
            return parser.parse();
        } catch (Exception e) {
            return "FAILED (" + e.getClass().getSimpleName() + ")";
        }
    }
}
