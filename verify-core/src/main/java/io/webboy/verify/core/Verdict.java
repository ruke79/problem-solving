package io.webboy.verify.core;

/**
 * 검증 판정.
 *
 * <ul>
 *   <li>{@link #CONFIRMED}   : 답변의 명제가 실행으로 재현됨</li>
 *   <li>{@link #REFUTED}     : 명제가 실행 결과와 어긋남 (답변을 고쳐야 한다)</li>
 *   <li>{@link #INCONCLUSIVE}: 환경/타이밍 의존이라 이번 실행에서는 결론을 낼 수 없음</li>
 *   <li>{@link #ERROR}       : 검증 코드 자체가 예외로 종료</li>
 * </ul>
 */
public enum Verdict {
    CONFIRMED,
    REFUTED,
    INCONCLUSIVE,
    ERROR
}
