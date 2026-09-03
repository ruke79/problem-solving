package io.webboy.verify.labs.perfbook.probe;

/**
 * GC 를 몇 번 일으키기만 하는 프로브 — 부모가 {@code -Xlog:gc} 로 띄워 통합 로깅 형식을 읽는다(PERF-08A).
 * 작은 힙(-Xmx32m)에서 256바이트 배열 300만 개를 만들면 G1 Young 일시정지가 수십 번 찍힌다.
 *
 * <p>첫 판은 {@code byte[] garbage = new byte[256]; total += garbage.length;} 였다 — 배열이 루프 밖으로 탈출하지
 * 않아 C2 의 탈출 분석이 할당 자체를 없앴고, 768MB 를 "흘렸다"는 프로브가 GC 를 한 번밖에 못 일으켰다
 * (1판 10장·2판 부록 A 가 경고하는 바로 그 죽은 코드 제거). 그래서 참조를 {@code volatile} 필드로 흘려 보낸다.
 */
public final class GarbageProbe {

    /** 할당을 살려 두는 싱크 — volatile 쓰기는 컴파일러가 없애지 못한다. */
    private static volatile byte[] sink;

    public static void main(String[] args) {
        long total = 0;
        for (int i = 0; i < 3_000_000; i++) {
            byte[] garbage = new byte[256];
            garbage[0] = (byte) i;
            sink = garbage;
            total += garbage.length;
        }
        System.out.println("ALLOCATED_BYTES=" + total + " LAST=" + sink[0]);
    }
}
