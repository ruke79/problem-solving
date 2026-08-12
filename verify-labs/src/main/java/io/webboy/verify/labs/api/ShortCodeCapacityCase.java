package io.webboy.verify.labs.api;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * Q64 — URL 단축 서비스의 Base62 채번 용량.
 *
 * <p><b>답변 수정 필요</b>: 원문 답변은 "7자리 Base62 로 약 350억 가지"라고 말하지만,
 * 실제 62<sup>7</sup> 은 약 3.5조다. 350억에 가까운 것은 6자리(약 568억)다.
 */
@Component
public class ShortCodeCapacityCase extends VerificationCase {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @Override
    public String id() {
        return "API-02";
    }

    @Override
    public String category() {
        return "api";
    }

    @Override
    public String question() {
        return "URL 단축 서비스를 설계해 주세요. 단축 코드는 몇 자리로 잡습니까?";
    }

    @Override
    public String claim() {
        return "Base62 7자리는 약 3.5조(62^7) 가지를 커버한다 — 답변 원고의 '350억'은 오기이며, 350억대에 해당하는 것은 6자리(약 568억)다";
    }

    @Override
    protected void verify(Evidence evidence) {
        BigInteger base = BigInteger.valueOf(62);
        BigInteger sixDigits = base.pow(6);
        BigInteger sevenDigits = base.pow(7);
        BigInteger scriptClaim = BigInteger.valueOf(35_000_000_000L);   // 답변 원고의 "350억"

        String encoded = encode(123_456_789L, 7);
        long decoded = decode(encoded);
        String next = encode(123_456_790L, 7);

        evidence.fact("알파벳 크기", ALPHABET.length());
        evidence.fact("62^6", sixDigits);
        evidence.fact("62^7", sevenDigits);
        evidence.fact("답변 원고가 말한 값(350억)", scriptClaim);
        evidence.fact("62^7 / 350억", sevenDigits.divide(scriptClaim));
        evidence.fact("123456789 인코딩", encoded);
        evidence.fact("바로 다음 ID 인코딩", next);
        evidence.fact("디코딩 왕복", decoded);

        evidence.expectEquals("62^7 = 3,521,614,606,208", new BigInteger("3521614606208"), sevenDigits);
        evidence.expect("62^7 은 350억의 100배 이상이다",
                sevenDigits.compareTo(scriptClaim.multiply(BigInteger.valueOf(100))) > 0);
        evidence.expect("350억에 가까운 것은 6자리(약 568억)다",
                sixDigits.compareTo(scriptClaim) > 0
                        && sixDigits.compareTo(scriptClaim.multiply(BigInteger.TWO)) < 0);
        evidence.expectEquals("Base62 인코딩/디코딩이 왕복한다", 123_456_789L, decoded);
        evidence.expect("연번 채번이면 다음 코드를 그대로 추측할 수 있다", !encoded.equals(next));

        evidence.note("원고 원문 확인 완료(Part 5): 「7桁のBase62でおよそ350億通り」 / \"7자리 Base62로 약 350억 가지\". "
                + "확정 수정문은 docs/06-원고-수정본-Part5.md §1 에 있다.");
        evidence.note("면접에서 자릿수를 말할 때는 62^n 을 즉석에서 계산할 수 있어야 한다. 62^6≈568억, 62^7≈3.5조.");
        evidence.note("연번 기반 코드는 다음 값이 추측 가능하므로, 비공개 캠페인 URL 같은 용도라면 랜덤성이나 해시 기반이 필요하다.");
        evidence.note("리다이렉트는 301 로 하면 브라우저가 캐시해 이후 서버로 오지 않아 클릭 수를 셀 수 없다 — 분석이 필요하면 302 를 쓴다.");
    }

    private String encode(long value, int width) {
        StringBuilder sb = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            sb.append(ALPHABET.charAt((int) (remaining % 62)));
            remaining /= 62;
        }
        while (sb.length() < width) {
            sb.append(ALPHABET.charAt(0));
        }
        return sb.reverse().toString();
    }

    private long decode(String code) {
        long value = 0;
        for (char c : code.toCharArray()) {
            value = value * 62 + ALPHABET.indexOf(c);
        }
        return value;
    }
}
