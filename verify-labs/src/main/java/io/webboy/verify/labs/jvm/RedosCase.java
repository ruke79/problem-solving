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

    /**
     * 폭발하는 패턴. 반복되는 그룹의 <b>몸통이 비결정적</b>({@code .*a} — 어디까지 먹을지 정해지지 않음)이라
     * 바깥 {@code {12}} 가 그 분할을 전부 시도한다.
     */
    private static final Pattern VULNERABLE = Pattern.compile("(.*a){12}b");

    /** 같은 패턴을 원자적 그룹으로 감싼 것. 그룹 안으로 되돌아가지 않으므로 폭발이 사라진다. */
    private static final Pattern ATOMIC_FIX = Pattern.compile("(?>.*a){12}b");

    /**
     * 교과서에 늘 나오는 예제. 이 랩이 원래 쓰던 패턴인데 <b>Java 17 에서는 폭발하지 않는다</b>
     * — 반복 몸통이 단일 문자 클래스라 결정적이어서 엔진이 되돌아갈 필요가 없다.
     * 관측값으로만 남기고 판정에는 쓰지 않는다(§ 아래 메모).
     */
    private static final Pattern TEXTBOOK = Pattern.compile("^(a+)+$");

    // (.*a){12}b 기준 실측: n=16 이 약 1.4ms, n=22 가 약 63ms — 40배 이상 벌어진다.
    private static final int SHORT_LENGTH = 16;
    private static final int LONG_LENGTH = 22;

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
        // JIT 워밍업. 이걸 빼면 첫 측정이 인터프리터로 돌아 배수가 뭉개진다.
        for (int i = 0; i < 5; i++) {
            measure(VULNERABLE, input(SHORT_LENGTH));
        }

        long shortMicros = measure(VULNERABLE, input(SHORT_LENGTH));
        long longMicros = measure(VULNERABLE, input(LONG_LENGTH));
        long atomicMicros = measure(ATOMIC_FIX, input(LONG_LENGTH));
        long textbookMicros = measure(TEXTBOOK, input(LONG_LENGTH) + "!");

        int grew = LONG_LENGTH - SHORT_LENGTH;
        double ratio = shortMicros <= 0 ? Double.NaN : (double) longMicros / shortMicros;

        evidence.fact("취약 패턴", VULNERABLE.pattern());
        evidence.fact("같은 패턴 + 원자적 그룹", ATOMIC_FIX.pattern());
        evidence.fact("입력 길이 " + SHORT_LENGTH + " 매칭 시간(us)", shortMicros);
        evidence.fact("입력 길이 " + LONG_LENGTH + " 매칭 시간(us)", longMicros);
        evidence.fact("길이 " + grew + " 증가에 따른 배수", String.format("%.1f", ratio));
        evidence.fact("원자적 그룹으로 고친 뒤 같은 입력(us)", atomicMicros);
        evidence.fact("교과서 예제 " + TEXTBOOK.pattern() + " 의 같은 길이 매칭 시간(us)", textbookMicros);

        evidence.expect("취약 패턴은 입력 " + grew + "자 증가에 시간이 배 이상 늘어난다",
                longMicros > shortMicros * 3);
        evidence.expect("원자적 그룹으로 백트래킹을 막으면 같은 입력이 즉시 끝난다",
                atomicMicros * 10 < longMicros);

        evidence.note("폭발 조건은 '중첩 수량자'라는 모양이 아니라 '반복되는 그룹의 몸통이 비결정적인가'다. (.*a){12} 는 .* 가 어디까지 먹을지 정해지지 않아 바깥 반복이 그 분할을 전부 시도한다 — 그래서 입력이 조금만 길어져도 시간이 배로 뛴다.");
        evidence.note("반대로 교과서 예제 ^(a+)+$ 는 Java 17 에서 폭발하지 않는다(위 관측값). 반복 몸통이 단일 문자 클래스라 결정적이어서 엔진이 되돌아갈 필요가 없기 때문이다. 이 랩도 처음에는 이 패턴을 썼다가 시간이 전혀 늘지 않아 원인을 찾았다 — '중첩 수량자를 쓰면 무조건 터진다'고 외운 답변은 실물 앞에서 재현되지 않을 수 있다.");
        evidence.note("실무 방어책: 입력 길이 상한, 원자적 그룹(?>...)·소유 수량자(*+), 사용자 입력으로 정규식을 만들지 않기, 별도 스레드 + 타임아웃, 또는 RE2 계열(선형 시간) 엔진 사용.");
        evidence.note("조사 순서는 top -H 로 스레드 특정 → 스레드 ID 를 16진수로 변환 → jstack 덤프에서 같은 위치가 반복되는지 확인이다.");
    }

    /** 'b' 가 없으므로 절대 매칭되지 않는다 — 엔진이 모든 분할을 다 시도하고서야 실패한다. */
    private String input(int aCount) {
        return "a".repeat(aCount);
    }

    /**
     * 매칭을 데몬 스레드에서 돌려, 만에 하나 폭주해도 검증 자체는 끝나게 한다.
     *
     * @return 경과 시간(마이크로초). 밀리초로 재면 짧은 쪽이 0~1 로 뭉개져 배수를 못 잡는다.
     */
    private long measure(Pattern pattern, String input) throws Exception {
        AtomicLong elapsed = new AtomicLong(-1);
        Thread worker = new Thread(() -> {
            long began = System.nanoTime();
            pattern.matcher(input).matches();
            elapsed.set((System.nanoTime() - began) / 1_000L);
        }, "redos-probe");
        worker.setDaemon(true);
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(20));
        return elapsed.get() < 0 ? TimeUnit.SECONDS.toMicros(20) : elapsed.get();
    }
}
