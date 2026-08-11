package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Q101 — 리프레시 토큰 로테이션과 재사용 탐지. */
@Component
public class RefreshTokenRotationCase extends VerificationCase {

    /** 토큰 저장소 최소 구현. family 는 "한 로그인 세션 계보"를 뜻한다. */
    static final class TokenStore {
        private record Token(String family, boolean used) {
        }

        private final Map<String, Token> tokens = new HashMap<>();
        private final Map<String, Boolean> revokedFamilies = new HashMap<>();

        String issue(String family) {
            String token = UUID.randomUUID().toString();
            tokens.put(token, new Token(family, false));
            return token;
        }

        String rotate(String presented) {
            Token token = tokens.get(presented);
            if (token == null) {
                throw new IllegalStateException("알 수 없는 토큰");
            }
            if (Boolean.TRUE.equals(revokedFamilies.get(token.family()))) {
                throw new IllegalStateException("무효화된 세션");
            }
            if (token.used()) {
                // 이미 쓴 토큰이 다시 왔다 = 탈취 후 재사용 신호
                revokedFamilies.put(token.family(), true);
                throw new IllegalStateException("재사용 탐지 — 해당 계보 전체 무효화");
            }
            tokens.put(presented, new Token(token.family(), true));
            return issue(token.family());
        }
    }

    @Override
    public String id() {
        return "RES-06";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "JWT 가 탈취됐을 때의 피해를 어떻게 줄입니까?";
    }

    @Override
    public String claim() {
        return "리프레시 토큰을 회전시키고 한 번 쓴 토큰은 무효화한다. 무효화된 토큰이 다시 오면 탈취 신호로 보고 그 세션 계보 전체를 강제 만료시킨다";
    }

    @Override
    protected void verify(Evidence evidence) {
        TokenStore store = new TokenStore();
        String rt1 = store.issue("session-A");

        String rt2 = store.rotate(rt1);
        String rt3 = store.rotate(rt2);

        String reuseOutcome = attemptRotate(store, rt1);      // 공격자가 탈취한 옛 토큰 재사용
        String victimOutcome = attemptRotate(store, rt3);     // 정상 사용자의 최신 토큰

        evidence.fact("정상 회전 1회차 성공", !rt2.equals(rt1));
        evidence.fact("정상 회전 2회차 성공", !rt3.equals(rt2));
        evidence.fact("이미 사용한 토큰 재사용 결과", reuseOutcome);
        evidence.fact("재사용 탐지 후 정상 사용자의 최신 토큰 결과", victimOutcome);

        evidence.expect("회전할 때마다 새 토큰이 발급된다", !rt1.equals(rt2) && !rt2.equals(rt3));
        evidence.expectEquals("이미 쓴 토큰을 다시 제시하면 재사용으로 탐지된다",
                "재사용 탐지 — 해당 계보 전체 무효화", reuseOutcome);
        evidence.expectEquals("탐지 시 같은 계보의 최신 토큰도 함께 무효화된다",
                "무효화된 세션", victimOutcome);

        evidence.note("정상 사용자까지 로그아웃되는 것은 부작용이 아니라 의도된 동작이다 — 누가 진짜인지 서버는 구분할 수 없기 때문이다.");
        evidence.note("액세스 토큰 만료 단축만으로는 부족하다. 단축·회전·재사용 탐지·쿠키 속성(httpOnly/SameSite)의 조합이라야 실용적 안전성이 된다.");
        evidence.note("httpOnly 쿠키는 XSS 를 막는 대신 CSRF 리스크와 트레이드오프가 되므로 SameSite 나 CSRF 토큰이 전제다.");
    }

    private String attemptRotate(TokenStore store, String token) {
        try {
            store.rotate(token);
            return "회전 성공";
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }
}
