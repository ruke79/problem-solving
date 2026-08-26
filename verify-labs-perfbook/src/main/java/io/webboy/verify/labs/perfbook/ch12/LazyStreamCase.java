package io.webboy.verify.labs.perfbook.ch12;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * 12장 — 스트림의 성능 이점은 병렬이 아니라 <b>지연 순회</b>에서 먼저 나온다.
 *
 * <p>책 12장의 마지막 팁: 200자 문자열의 스트림 필터 3개 체인이 이터레이터 3중 루프보다
 * 압도적으로 빨랐다(48.706초 → 0.359초). 스트림이 특별히 빨라서가 아니라, 각 원소가
 * 파이프라인을 <b>한 번에 통과하다 조건이 끝나는 즉시 멈추기</b> 때문이다 —
 * 이터레이터 판은 단계마다 전체를 순회했다.
 *
 * <p>측정: 시간이 아니라 <b>람다 호출 횟수를 센다.</b> "몇 개를 처리하고 멈췄는가"가
 * 명제의 실체이고, 횟수는 결정적이라 전부 {@code expect} 다.
 */
@Component
public class LazyStreamCase extends VerificationCase {

    private static final int SIZE = 1_000;
    private static final int FIRST_MATCH = 9;   // i % 100 == 9 의 첫 원소

    @Override
    public String id() {
        return "PERF-12C";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 12장 — 스트림은 원소를 몇 개나 처리하고 멈추나? (지연 순회)";
    }

    @Override
    public String claim() {
        return "스트림 파이프라인은 중간 연산을 쌓아만 두고, 종단 연산이 원소를 하나씩 끝까지 통과시킨다. "
                + "findFirst 같은 쇼트서킷 종단은 답이 나오는 즉시 멈추므로, 처리량이 '전체 크기'가 아니라 "
                + "'답까지의 거리'에 비례한다";
    }

    @Override
    protected void verify(Evidence evidence) {
        List<Integer> numbers = IntStream.range(0, SIZE).boxed().toList();

        // (a) 쇼트서킷 종단 — 첫 일치까지만 처리한다
        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger mapCalls = new AtomicInteger();
        Optional<Integer> first = numbers.stream()
                .filter(i -> {
                    filterCalls.incrementAndGet();
                    return i % 100 == FIRST_MATCH;
                })
                .map(i -> {
                    mapCalls.incrementAndGet();
                    return i * 10;
                })
                .findFirst();

        evidence.fact("전체 원소", SIZE + "개");
        evidence.fact("findFirst 까지 filter 호출", filterCalls.get() + "회");
        evidence.fact("findFirst 까지 map 호출", mapCalls.get() + "회");

        evidence.expectEquals("결과가 맞다", FIRST_MATCH * 10, first.orElse(-1));
        evidence.expectEquals("filter 는 첫 일치 원소까지만 불린다", FIRST_MATCH + 1, filterCalls.get());
        evidence.expectEquals("map 은 통과한 원소 1개에만 불린다", 1, mapCalls.get());

        // (b) 비교 — 종단이 전체를 요구하면 전체를 처리한다
        AtomicInteger fullFilterCalls = new AtomicInteger();
        long count = numbers.stream()
                .filter(i -> {
                    fullFilterCalls.incrementAndGet();
                    return i % 100 == FIRST_MATCH;
                })
                .count();
        evidence.fact("count() 종단의 filter 호출", fullFilterCalls.get() + "회");
        evidence.expectEquals("전체 순회 종단은 전 원소를 처리한다", SIZE, fullFilterCalls.get());
        evidence.expectEquals("일치 개수", SIZE / 100, (int) count);

        evidence.note("책의 48.706초 → 0.359초는 이 횟수 차이가 200자 × 3단계 필터로 증폭된 것이다. "
                + "여기서는 시간 대신 호출 횟수를 쟀다 — 명제의 실체가 횟수이고, 횟수는 결정적이라 "
                + "이 케이스에는 expectFlaky 가 하나도 없다.");
    }
}
