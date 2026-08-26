package io.webboy.verify.labs.perfbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * <i>Java Performance: The Definitive Guide</i> 검증 모듈.
 *
 * <p>{@code notes/java-performance/} 의 장별 요약에서 "검증 케이스 없음"으로 남았던 명제 중,
 * 이 환경에서 잴 수 있는 것들을 실제 실행으로 확인한다({@code docs/02} §9-4 목록).
 *
 * <p>케이스 id 는 {@code PERF-<장번호>} 를 따른다 — {@code -Dverify.only=PERF-12} 처럼
 * 장 단위로 골라 돌릴 수 있다. 측정 원칙은 랩 공통 규칙 그대로다:
 * <b>셀 수 있는 것은 결정적 {@code expect} 로, 시간 비교는 {@code expectFlaky} 로.</b>
 * (예: 지연 스트림은 시간이 아니라 람다 호출 횟수를 센다.)
 */
@SpringBootApplication
public class PerfBookLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(PerfBookLabApplication.class, args);
    }
}
