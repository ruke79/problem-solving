package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Q67 — CPU 100% 의 단골 원인인 정규식 파국적 백트래킹. */
@Component
public class RedosCase extends VerificationCase {

    private static final Pattern VULNERABLE = Pattern.compile("^(a+)+$");
    private static final Pattern SAFE = Pattern.compile("^a+$");

    @Override
    public String id() {
        return "JVM-06";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "CPU 사용률이 100%로 붙어 있을 때 어떤 원인을 의심합니까?";
    }

    @Override
    public String claim() {
        return "중첩 수량자를 가진 정규식은 입력 길이에 대해 지수적으로 시간이 늘어난다 — 한 스레드가 CPU 한 코어를 붙잡고 돌아오지 않는다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        long short18 = measure(VULNERABLE, input(18));
        long long22 = measure(VULNERABLE, input(22));
        long safe22 = measure(SAFE, input(22));

        double ratio = short18 <= 0 ? Double.NaN : (double) long22 / short18;

        evidence.fact("취약 패턴", VULNERABLE.pattern());
        evidence.fact("안전 패턴", SAFE.pattern());
        evidence.fact("입력 길이 18 매칭 시간(ms)", short18);
        evidence.fact("입력 길이 22 매칭 시간(ms)", long22);
        evidence.fact("길이 4 증가에 따른 배수", String.format("%.1f", ratio));
        evidence.fact("안전 패턴 길이 22 매칭 시간(ms)", safe22);

        evidence.expect("안전 패턴은 입력이 길어져도 즉시 끝난다", safe22 < 50);
        evidence.expectFlaky("취약 패턴은 입력 4자 증가에 시간이 배 이상 늘어난다", long22 > short18 * 3);

        evidence.note("길이 4 증가마다 약 16배씩 늘어난다. 길이 30이면 사실상 영원히 돌아오지 않는다.");
        evidence.note("실무 방어책: 입력 길이 상한, 중첩 수량자 제거, 별도 스레드 + 타임아웃, 또는 RE2 계열(선형 시간) 엔진 사용.");
        evidence.note("조사 순서는 top -H 로 스레드 특정 → 스레드 ID 를 16진수로 변환 → jstack 덤프에서 같은 위치가 반복되는지 확인이다.");
    }

    private String input(int aCount) {
        return "a".repeat(aCount) + "!";
    }

    /** 매칭을 데몬 스레드에서 돌려, 만에 하나 폭주해도 검증 자체는 끝나게 한다. */
    private long measure(Pattern pattern, String input) throws Exception {
        AtomicLong elapsed = new AtomicLong(-1);
        Thread worker = new Thread(() -> {
            long began = System.nanoTime();
            pattern.matcher(input).matches();
            elapsed.set((System.nanoTime() - began) / 1_000_000L);
        }, "redos-probe");
        worker.setDaemon(true);
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(20));
        return elapsed.get() < 0 ? 20_000 : elapsed.get();
    }
}
