package io.webboy.verify.labs.cloudnative;

/**
 * 시간 비교 케이스의 공통 측정 도구 — {@code verify-labs-perfbook} 의 것과 같은 규칙이다
 * ({@code docs/02} §1-2): 마이크로초 해상도, 여러 회차의 <b>최솟값</b>, 자릿수와 배율만 신뢰.
 *
 * <p>perfbook 모듈은 Spring Boot 앱이라 의존할 수 없어 여기 다시 둔다.
 */
public final class Timing {

    private Timing() {
    }

    public interface Work {
        void run() throws Exception;
    }

    public static long minMicros(int rounds, Work work) throws Exception {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long began = System.nanoTime();
            work.run();
            min = Math.min(min, (System.nanoTime() - began) / 1_000L);
        }
        return min;
    }
}
