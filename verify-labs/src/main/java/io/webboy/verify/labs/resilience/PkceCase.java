package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Q103 — 시크릿을 숨길 수 없는 퍼블릭 클라이언트에서 PKCE 가 하는 일. */
@Component
public class PkceCase extends VerificationCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 인가 서버 최소 구현. */
    static final class AuthorizationServer {
        private String issuedCode;
        private String storedChallenge;
        private String storedMethod;

        String authorize(String challenge, String method) {
            this.storedChallenge = challenge;
            this.storedMethod = method;
            this.issuedCode = "code-" + RANDOM.nextInt(1_000_000);
            return issuedCode;
        }

        String token(String code, String verifier) {
            if (!code.equals(issuedCode)) {
                throw new IllegalStateException("잘못된 인가 코드");
            }
            String expected = "S256".equals(storedMethod) ? challenge(verifier) : verifier;
            if (!expected.equals(storedChallenge)) {
                throw new IllegalStateException("code_verifier 불일치");
            }
            return "access-token";
        }
    }

    @Override
    public String id() {
        return "RES-07";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "OAuth 2.0 인가 코드 플로우의 장점은 무엇입니까? 모바일/SPA 에서도 그대로 안전합니까?";
    }

    @Override
    public String claim() {
        return "클라이언트 시크릿을 숨길 수 없는 퍼블릭 클라이언트에서는 인가 코드를 가로채면 토큰을 받을 수 있다. PKCE(S256)를 붙여야 비로소 안전해진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String verifier = verifier();
        String challenge = challenge(verifier);

        AuthorizationServer server = new AuthorizationServer();
        String code = server.authorize(challenge, "S256");

        String legitimate = attempt(server, code, verifier);
        String attacker = attempt(server, code, verifier());   // 코드만 가로챈 공격자

        // plain 방식: challenge == verifier 라서, 인가 요청을 관측한 공격자가 그대로 재사용할 수 있다
        AuthorizationServer plainServer = new AuthorizationServer();
        String plainVerifier = verifier();
        String plainCode = plainServer.authorize(plainVerifier, "plain");
        String plainAttacker = attempt(plainServer, plainCode, plainVerifier);

        evidence.fact("code_verifier 길이", verifier.length());
        evidence.fact("code_challenge (S256, base64url)", challenge);
        evidence.fact("정상 클라이언트의 토큰 교환", legitimate);
        evidence.fact("코드만 가로챈 공격자의 토큰 교환", attacker);
        evidence.fact("plain 방식에서 인가 요청을 관측한 공격자", plainAttacker);
        evidence.fact("challenge 는 verifier 로부터 역산 불가한가(단방향 해시)", !challenge.equals(verifier));

        evidence.expectEquals("정상 클라이언트는 토큰을 받는다", "access-token", legitimate);
        evidence.expectEquals("verifier 를 모르면 코드가 있어도 교환할 수 없다", "code_verifier 불일치", attacker);
        evidence.expectEquals("plain 방식은 challenge 를 본 공격자가 그대로 통과한다", "access-token", plainAttacker);
        evidence.expect("code_verifier 는 RFC 7636 권장 길이(43~128)를 만족한다",
                verifier.length() >= 43 && verifier.length() <= 128);

        evidence.note("OAuth 2.1 은 모바일·SPA 를 불문하고 PKCE 병용을 표준 권장으로 삼는다.");
        evidence.note("plain 방식은 하위 호환용일 뿐이며 실질적 보호가 없다 — 반드시 S256 을 쓴다.");
    }

    private String attempt(AuthorizationServer server, String code, String verifier) {
        try {
            return server.token(code, verifier);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    private static String verifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
