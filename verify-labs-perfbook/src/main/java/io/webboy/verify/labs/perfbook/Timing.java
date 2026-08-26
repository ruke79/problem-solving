package io.webboy.verify.labs.perfbook;

/**
 * 시간 비교 케이스의 공통 측정 도구.
 *
 * <p>이 랩이 {@code expectFlaky} 전수 점검에서 얻은 규칙을 코드로 박아 둔다
 * ({@code docs/02} §1-2): <b>성능 비교는 해상도(마이크로초)와 최소 여유를 함께 정한다.</b>
 * 밀리초로 잘라 {@code <=} 로 비교하면 0 == 0 헛통과가 생긴다.
 *
 * <p>측정값은 여러 회차의 <b>최솟값</b>을 쓴다 — 평균은 GC·스케줄링 잡음이 섞인 값이고,
 * 최솟값이 "이 코드가 실제로 낼 수 있는 시간"에 가장 가깝다. JMH 가 아니므로
 * 절대값이 아니라 <b>자릿수와 배율만</b> 신뢰해야 한다는 전제는 그대로다.
 */
public final class Timing {

    private Timing() {
    }

    public interface Work {
        void run() throws Exception;
    }

    /** {@code rounds} 회 반복 실행해 최솟값(마이크로초)을 돌려준다. */
    public static long minMicros(int rounds, Work work) throws Exception {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long began = System.nanoTime();
            work.run();
            long micros = (System.nanoTime() - began) / 1_000L;
            min = Math.min(min, micros);
        }
        return min;
    }
}
