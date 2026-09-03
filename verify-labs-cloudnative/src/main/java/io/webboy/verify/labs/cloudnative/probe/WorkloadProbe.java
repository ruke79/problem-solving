package io.webboy.verify.labs.cloudnative.probe;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * AOT 캐시(JEP 483/514) 실험용의 작은 워크로드 — 스트림·컬렉션·문자열 포맷을 써서 JDK 클래스를
 * 수백 개 로드하게 한다. 훈련 실행과 캐시 사용 실행이 같은 코드를 돌려야 캐시가 맞는다.
 */
public final class WorkloadProbe {

    public static void main(String[] args) {
        Map<Integer, List<Integer>> groups = IntStream.range(0, 1_000).boxed()
                .collect(Collectors.groupingBy(i -> i % 7));
        String summary = groups.entrySet().stream()
                .map(e -> String.format("%d:%d", e.getKey(), e.getValue().size()))
                .collect(Collectors.joining(","));
        System.out.println("WORKLOAD=" + summary);
    }
}
