package io.webboy.verify.labs.security;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Q105 — 비밀번호를 어떻게 저장하는가.
 *
 * <p>흉내내지 않고 <b>Spring Security 의 실제 {@link BCryptPasswordEncoder}</b> 로 해싱해 관측한다.
 * 핵심은 "느린 것이 기능이다"라는 역설이다 — 일반 해시 함수의 미덕(빠름)이 여기서는 결함이 된다.
 *
 * <p>원고는 Argon2id 를 권장하면서 "못 쓰는 환경이면 bcrypt work factor 를 하드웨어에 맞춰 올린다"고
 * 말한다. 그 work factor 가 실제로 무엇을 바꾸는지, 그리고 <b>이미 저장된 해시를 어떻게 올리는지</b>를 확인한다.
 */
@Component
public class PasswordHashingCase extends VerificationCase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** bcrypt 의 cost. 1 올라갈 때마다 반복 횟수가 2배가 된다. */
    private static final int LOW_COST = 4;
    private static final int HIGH_COST = 10;

    @Override
    public String id() {
        return "SEC-04";
    }

    @Override
    public String category() {
        return "security";
    }

    @Override
    public String question() {
        return "비밀번호를 안전하게 저장하려면 어떤 해시를 어떻게 써야 합니까?";
    }

    @Override
    public String claim() {
        return "비밀번호에는 일반 해시가 아니라 의도적으로 느린 함수(Argon2id, bcrypt)를 써야 한다. 솔트가 자동으로 들어가 같은 비밀번호도 매번 다른 해시가 나오므로 레인보우 테이블이 무력해지고, work factor 로 계산 비용을 하드웨어에 맞춰 올릴 수 있다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 해싱 소요 시간은 장비와 JIT 에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        BCryptPasswordEncoder low = new BCryptPasswordEncoder(LOW_COST);
        BCryptPasswordEncoder high = new BCryptPasswordEncoder(HIGH_COST);

        // (1) 솔트 — 같은 비밀번호, 다른 해시
        String first = low.encode(PASSWORD);
        String second = low.encode(PASSWORD);

        // (2) work factor 를 올리면 비용이 얼마나 늘어나는가 (워밍업 후 측정)
        for (int i = 0; i < 3; i++) {
            low.encode(PASSWORD);
        }
        long lowMillis = timeEncode(low);
        long highMillis = timeEncode(high);

        // (3) 일반 해시는 얼마나 빠른가 — 그것이 곧 공격자의 속도다
        long sha256Micros = timeSha256();

        // (4) 이미 저장된 해시를 그대로 두고 상향할 수 있는가
        String oldHash = low.encode(PASSWORD);
        boolean oldStillVerifies = high.matches(PASSWORD, oldHash);
        boolean upgradeNeeded = extractCost(oldHash) < HIGH_COST;
        String upgraded = high.encode(PASSWORD);

        evidence.fact("비밀번호", PASSWORD);
        evidence.fact("같은 비밀번호 해시 #1", first);
        evidence.fact("같은 비밀번호 해시 #2", second);
        evidence.fact("두 해시가 같은가", first.equals(second));
        evidence.fact("cost " + LOW_COST + " 해싱 시간(ms)", lowMillis);
        evidence.fact("cost " + HIGH_COST + " 해싱 시간(ms)", highMillis);
        evidence.fact("cost 차이(" + (HIGH_COST - LOW_COST) + ")에 따른 배수",
                lowMillis <= 0 ? "측정 불가" : String.format("%.1f배", (double) highMillis / lowMillis));
        evidence.fact("SHA-256 1회 소요(us)", sha256Micros);
        evidence.fact("저장된 해시에서 읽어낸 cost", extractCost(oldHash));
        evidence.fact("cost 를 올린 인코더로도 옛 해시가 검증되는가", oldStillVerifies);
        evidence.fact("상향된 해시", upgraded);

        evidence.expect("같은 비밀번호라도 해시가 매번 다르다(솔트 자동 포함)", !first.equals(second));
        evidence.expect("그래도 둘 다 원래 비밀번호로 검증된다",
                low.matches(PASSWORD, first) && low.matches(PASSWORD, second));
        evidence.expect("틀린 비밀번호는 통과하지 못한다", !low.matches(PASSWORD + "!", first));
        evidence.expect("work factor 를 올리면 해싱이 눈에 띄게 느려진다", highMillis > lowMillis * 4);
        evidence.expect("일반 해시(SHA-256)는 비교가 무의미할 만큼 빠르다 — 공격자에게 그대로 유리하다",
                sha256Micros < 1_000 && highMillis > 1);
        evidence.expect("해시 문자열에 cost 가 들어 있어 옛 해시도 그대로 검증된다", oldStillVerifies);
        evidence.expect("그래서 '로그인 성공 시 재해싱'으로 무중단 상향이 가능하다", upgradeNeeded);
        evidence.expectEquals("상향된 해시의 cost 는 새 값이다", HIGH_COST, extractCost(upgraded));

        evidence.note("bcrypt 해시 문자열은 `$2a$10$...` 형태로 알고리즘·cost·솔트를 자기 안에 담고 있다. 그래서 검증에 별도 컬럼이 필요 없고, cost 를 올려도 옛 해시가 깨지지 않는다. 실무의 상향 절차가 '로그인 성공 시점에 평문을 알고 있으니 그때 새 cost 로 다시 해싱해 저장'인 이유가 이것이다 — 사용자는 아무것도 하지 않는다.");
        evidence.note("cost 는 1 올라갈 때마다 반복이 2배다. 위 관측값에서 " + (HIGH_COST - LOW_COST) + " 만큼 올렸으니 이론상 " + (1 << (HIGH_COST - LOW_COST)) + "배이고, 실측도 그 부근이다. 기준은 '우리 서버에서 로그인 1회가 견딜 만한 시간'(흔히 100~300ms)이며, 장비가 좋아지면 올려야 한다.");
        evidence.note("SHA-256 이 마이크로초 단위인 것이 문제의 본질이다. 유출된 해시를 GPU 로 초당 수십억 번 시도할 수 있으므로, 비밀번호에 일반 해시를 쓰면 솔트를 붙여도 시간만 조금 벌 뿐이다. '느린 것이 기능'이라는 말이 여기서 나온다.");
        evidence.note("원고 권장대로 Argon2id 가 1순위다 — 메모리까지 요구해 GPU 병렬화를 어렵게 만든다(bcrypt 는 메모리를 거의 안 쓴다). 다만 Spring 의 Argon2PasswordEncoder 는 BouncyCastle 의존성이 필요해 이 랩에서는 실행하지 않았다. 여기서 확인한 것은 bcrypt 쪽 성질뿐이라는 점을 분명히 해 둔다.");
        evidence.note("bcrypt 는 입력 72바이트까지만 본다. 그보다 긴 비밀번호는 뒤가 무시되므로, 아주 긴 패스프레이즈를 허용하는 서비스라면 이 한계를 알고 있어야 한다(사전 해싱으로 우회하면 또 다른 함정이 생긴다).");
    }

    private long timeEncode(BCryptPasswordEncoder encoder) {
        long began = System.nanoTime();
        encoder.encode(PASSWORD);
        return (System.nanoTime() - began) / 1_000_000L;
    }

    private long timeSha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 1_000; i++) {
            digest.digest(PASSWORD.getBytes(StandardCharsets.UTF_8));   // 워밍업
        }
        long began = System.nanoTime();
        byte[] hash = digest.digest(PASSWORD.getBytes(StandardCharsets.UTF_8));
        long micros = (System.nanoTime() - began) / 1_000L;
        HexFormat.of().formatHex(hash);   // 결과를 버리지 않게 해 JIT 이 통째로 지우지 못하게 한다
        return micros;
    }

    /** {@code $2a$10$....} 에서 cost 를 읽는다 — 해시가 자기 파라미터를 들고 다닌다는 증거다. */
    private int extractCost(String bcryptHash) {
        String[] parts = bcryptHash.split("\\$");
        return Integer.parseInt(parts[2]);
    }
}
