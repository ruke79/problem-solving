package io.webboy.verify.core;

/**
 * 면접 질문 1개 = 검증 케이스 1개.
 *
 * <p>이 클래스를 상속한 Spring Bean 을 등록하면 자동으로 레지스트리에 잡힌다.
 * 다른 프로젝트에 이식할 때도 {@code verify-core} 의존성만 추가하면 그대로 동작한다.
 */
public abstract class VerificationCase {

    /** 케이스 식별자. 예: {@code SPRING-01} */
    public abstract String id();

    /** 분류. 예: {@code spring}, {@code jpa}, {@code concurrency} */
    public abstract String category();

    /** 면접에서 받은 질문 원문 */
    public abstract String question();

    /** 내 답변이 주장하는 명제 (이것이 검증 대상이다) */
    public abstract String claim();

    /** 타이밍/JIT/GC 의존이면 true. INCONCLUSIVE 가 나와도 실패로 보지 않는다. */
    public boolean nondeterministic() {
        return false;
    }

    protected abstract void verify(Evidence evidence) throws Exception;

    public final VerificationResult execute() {
        Evidence evidence = new Evidence();
        long start = System.nanoTime();
        Verdict verdict;
        String error = null;
        try {
            verify(evidence);
            verdict = evidence.verdict();
        } catch (Throwable t) {
            verdict = Verdict.ERROR;
            error = t.getClass().getName() + ": " + t.getMessage();
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        return new VerificationResult(id(), category(), question(), claim(), verdict,
                nondeterministic(), elapsed, evidence.facts(), evidence.expectations(),
                evidence.notes(), error);
    }
}
