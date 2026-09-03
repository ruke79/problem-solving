package io.webboy.verify.labs.cloudnative.probe;

/**
 * 객체 하나가 힙에서 몇 바이트인지 어림한다 — 컴팩트 객체 헤더(JEP 519) 실측용.
 *
 * <p>{@code new Object()} 는 필드가 없으므로 크기 = 헤더 + 정렬 패딩이다. 기본(압축 클래스 포인터)
 * 헤더는 12 바이트라 8 바이트 정렬로 16, 컴팩트 헤더는 8 바이트라 8 이다. 200만 개를 만들어
 * 사용 힙의 차이를 개수로 나눈다. GC 잡음이 있으므로 <b>자릿수(16 vs 8)만</b> 읽는다.
 */
public final class HeaderProbe {

    public static void main(String[] args) throws Exception {
        int n = 2_000_000;
        Runtime runtime = Runtime.getRuntime();
        Object[] keep = new Object[n];
        settle();
        long before = runtime.totalMemory() - runtime.freeMemory();
        for (int i = 0; i < n; i++) {
            keep[i] = new Object();
        }
        settle();
        long after = runtime.totalMemory() - runtime.freeMemory();
        double perObject = (after - before) / (double) n;
        System.out.printf("BYTES_PER_OBJECT=%.1f%n", perObject);
        System.out.println("KEPT=" + keep.length);
    }

    private static void settle() throws InterruptedException {
        System.gc();
        Thread.sleep(200);
        System.gc();
        Thread.sleep(100);
    }
}
