package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 7 — 모던 자바 (면접 Q118 ~ Q126)
 *
 * <p>7-2 의 <b>지연 순회</b>와 7-6 의 <b>{@code orElse} 는 항상 평가된다</b>가
 * 이 레슨의 두 핵심이다. 둘 다 <b>시간이 아니라 호출 횟수로</b> 확인한다.
 */
@DisplayName("레슨 7. 모던 자바 — 스트림과 Optional")
class Lesson07_ModernJava {

    @Test
    @DisplayName("7-1. 종단 연산이 없으면 아무것도 실행되지 않는다 (Q119)")
    void nothingRunsWithoutTerminalOperation() {
        var calls = new AtomicInteger();

        // 중간 연산만 늘어놓았다 — 파이프라인을 '만들기만' 한 상태
        var pipeline = List.of(1, 2, 3).stream()
                .filter(i -> {
                    calls.incrementAndGet();
                    return i > 1;
                });

        fact("종단 연산 전 filter 호출 횟수", calls.get());
        assertThat(calls.get()).isZero();          // 한 번도 안 불렸다

        pipeline.toList();                          // 종단 연산
        fact("종단 연산 후 filter 호출 횟수", calls.get());
        assertThat(calls.get()).isEqualTo(3);

        lesson("종단 연산을 빠뜨린 스트림은 '아무것도 하지 않는 코드'로 조용히 남는다");
    }

    @Test
    @DisplayName("7-2. 답이 나오면 즉시 멈춘다 — 지연 순회 (Q120) ★핵심")
    void lazyTraversalStopsEarly() {
        var filterCalls = new AtomicInteger();
        var mapCalls = new AtomicInteger();

        List<Integer> numbers = IntStream.range(0, 1_000).boxed().toList();

        Optional<Integer> first = numbers.stream()
                .filter(i -> {
                    filterCalls.incrementAndGet();
                    return i % 100 == 9;      // 첫 일치는 9
                })
                .map(i -> {
                    mapCalls.incrementAndGet();
                    return i * 10;
                })
                .findFirst();

        fact("전체 원소", numbers.size());
        fact("findFirst 까지 filter 호출", filterCalls.get());
        fact("findFirst 까지 map 호출", mapCalls.get());

        assertThat(first).contains(90);
        assertThat(filterCalls.get()).isEqualTo(10);   // 0~9 까지만 봤다
        assertThat(mapCalls.get()).isEqualTo(1);       // 통과한 1개만 변환했다

        // 반면 전체를 요구하는 종단 연산은 전 원소를 본다
        var countCalls = new AtomicInteger();
        long matched = numbers.stream()
                .filter(i -> {
                    countCalls.incrementAndGet();
                    return i % 100 == 9;
                })
                .count();
        fact("count() 종단의 filter 호출", countCalls.get());
        assertThat(countCalls.get()).isEqualTo(1_000);
        assertThat(matched).isEqualTo(10);

        lesson("처리량이 '전체 건수'가 아니라 '답까지의 거리'에 비례한다 — 이게 지연 순회의 본질");
        lesson("시간이 아니라 호출 횟수로 쟀다. 횟수는 장비와 무관하게 결정적이기 때문이다");
    }

    @Test
    @DisplayName("7-3. map 과 flatMap 의 차이 (Q122)")
    void mapVsFlatMap() {
        List<List<String>> nested = List.of(List.of("a", "b"), List.of("c"));

        List<List<String>> mapped = nested.stream().map(x -> x).toList();
        List<String> flattened = nested.stream().flatMap(List::stream).toList();

        fact("map 결과", mapped);
        fact("flatMap 결과", flattened);

        assertThat(mapped).hasSize(2);          // 중첩이 그대로다
        assertThat(flattened).containsExactly("a", "b", "c");   // 평탄해졌다

        lesson("map 은 1:1 변환, flatMap 은 '스트림으로 만든 뒤 이어붙이기'다");
    }

    @Test
    @DisplayName("7-4. groupingBy 로 분류·집계를 한 번에 (Q124)")
    void groupingByCountsInOnePass() {
        record Person(String dept, String name) {}
        List<Person> people = List.of(
                new Person("개발", "가"), new Person("개발", "나"), new Person("영업", "다"));

        Map<String, Long> countByDept = people.stream()
                .collect(Collectors.groupingBy(Person::dept, Collectors.counting()));

        fact("부서별 인원", countByDept);
        assertThat(countByDept).containsEntry("개발", 2L).containsEntry("영업", 1L);

        lesson("다운스트림 컬렉터를 붙일 수 있는 게 강점이다. 다만 3단 이상은 나눠 쓰는 게 낫다");
    }

    @Test
    @DisplayName("7-5. forEach 안에서 외부 상태를 바꾸지 않는다 (Q121)")
    void doNotMutateFromForEach() {
        List<Integer> source = IntStream.range(0, 1_000).boxed().toList();

        // 나쁜 예 — 병렬로 바꾸는 순간 깨진다
        List<Integer> viaForEach = new ArrayList<>();
        source.stream().filter(i -> i % 2 == 0).forEach(viaForEach::add);

        // 좋은 예 — 수집은 collect 에 맡긴다
        List<Integer> viaCollect = source.stream().filter(i -> i % 2 == 0).toList();

        assertThat(viaForEach).isEqualTo(viaCollect);   // 순차라면 결과는 같다

        // 같은 코드를 병렬로 돌리면 위쪽은 깨질 수 있다 (ArrayList 는 스레드 세이프가 아니다)
        List<Integer> parallelSafe = source.parallelStream().filter(i -> i % 2 == 0).toList();
        assertThat(parallelSafe).isEqualTo(viaCollect);   // collect 는 병렬에서도 안전하다

        lesson("순차에서 되니까 괜찮아 보이지만, 병렬로 바꾸는 순간 무너지는 코드가 된다");
    }

    @Test
    @DisplayName("7-6. orElse 는 값이 있어도 인자를 평가한다 (Q126) ★변별력")
    void orElseAlwaysEvaluatesItsArgument() {
        var orElseCalls = new AtomicInteger();
        var orElseGetCalls = new AtomicInteger();

        Optional<String> present = Optional.of("있는 값");

        // orElse 의 인자는 '미리 계산된 값'이므로 반드시 평가된다
        String a = present.orElse(expensive(orElseCalls));
        // orElseGet 은 람다라 필요할 때만 실행된다
        String b = present.orElseGet(() -> expensive(orElseGetCalls));

        fact("값이 있는데 orElse 인자가 실행된 횟수", orElseCalls.get());
        fact("값이 있는데 orElseGet 람다가 실행된 횟수", orElseGetCalls.get());

        assertThat(a).isEqualTo("있는 값");
        assertThat(b).isEqualTo("있는 값");
        assertThat(orElseCalls.get()).isEqualTo(1);      // ← 쓰이지도 않는데 실행됐다
        assertThat(orElseGetCalls.get()).isZero();        // 실행되지 않았다

        lesson("기본값 생성에 비용이 들면(DB 조회 등) orElse 는 매번 그 비용을 낸다");
        lesson("상수면 orElse, 계산이 필요하면 orElseGet");
    }

    private String expensive(AtomicInteger counter) {
        counter.incrementAndGet();
        return "비싼 기본값";
    }

    @Test
    @DisplayName("7-7. Optional 은 반환값에만 쓴다 (Q125)")
    void optionalIsForReturnValues() {
        Optional<String> found = find("있음");
        Optional<String> missing = find("없음");

        // 좋은 사용 — 체인으로 다룬다
        assertThat(found.map(String::toUpperCase).orElse("기본")).isEqualTo("찾음");
        assertThat(missing.map(String::toUpperCase).orElse("기본")).isEqualTo("기본");

        // 나쁜 사용 — isPresent + get 은 null 체크를 이름만 바꾼 것이다
        // if (found.isPresent()) { found.get() ... }

        // get() 을 빈 Optional 에 부르면 예외다 — 그래서 orElseThrow 를 쓴다
        assertThatThrownBy(missing::orElseThrow)
                .isInstanceOf(java.util.NoSuchElementException.class);

        lesson("필드나 인자에는 쓰지 않는다. 반환 타입으로 '없을 수 있음'을 알리는 게 목적이다");
    }

    private Optional<String> find(String key) {
        return "있음".equals(key) ? Optional.of("찾음") : Optional.empty();
    }

    @Test
    @DisplayName("7-8. 람다는 실질적 final 만 캡처한다 (Q118)")
    void lambdaCapturesEffectivelyFinalOnly() {
        int captured = 10;              // 재대입하지 않으므로 실질적 final
        var adder = (java.util.function.IntUnaryOperator) x -> x + captured;
        assertThat(adder.applyAsInt(5)).isEqualTo(15);

        // captured = 20;   ← 이 줄을 넣으면 위 람다가 컴파일 에러가 된다

        // 우회하려면 담을 그릇을 쓴다 — 다만 이런 설계 자체를 피하는 게 낫다
        var counter = new AtomicInteger();
        List.of(1, 2, 3).forEach(i -> counter.addAndGet(i));
        assertThat(counter.get()).isEqualTo(6);

        lesson("람다는 값을 복사해 가지므로, 원본이 바뀌면 어긋난다 — 그래서 막혀 있다");
    }
}
