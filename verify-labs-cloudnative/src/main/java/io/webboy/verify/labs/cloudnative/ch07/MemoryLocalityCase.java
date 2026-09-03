package io.webboy.verify.labs.cloudnative.ch07;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Timing;

/**
 * 7장 「하드웨어와 OS」 — 메모리 계층: 캐시 라인 순서대로 읽는 것과 캐시 라인을 건너뛰며 읽는 것은
 * 같은 연산 수인데도 시간이 다르다. 책의 캐시 라인 예제(행 우선 vs 열 우선 순회)를 그대로 잰다.
 * 시간 비교이므로 {@code docs/02} §1-2 규칙대로 최솟값·배율만 본다.
 */
public class MemoryLocalityCase extends VerificationCase {

    private static final int N = 2_048;   // 2048×2048 int = 16MB, L2 를 확실히 넘는다

    @Override
    public String id() {
        return "CN-07A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 7장 — 같은 배열을 행 우선으로 읽을 때와 열 우선으로 읽을 때 왜 시간이 다른가?";
    }

    @Override
    public String claim() {
        return "행 우선 순회는 캐시 라인(64바이트 = int 16개)을 통째로 쓰지만 열 우선 순회는 라인마다 하나만 쓰고 "
                + "버리므로, 연산 수가 같아도 열 우선이 몇 배 느리다 — 메모리 접근 패턴이 알고리즘 복잡도만큼 중요하다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        int[] matrix = new int[N * N];
        for (int i = 0; i < matrix.length; i++) {
            matrix[i] = i;
        }
        long[] sink = new long[1];
        // 워밍업 — 두 순회를 JIT 가 같은 수준으로 컴파일하게 한다
        for (int i = 0; i < 3; i++) {
            sink[0] += rowMajor(matrix) + columnMajor(matrix);
        }
        long row = Timing.minMicros(5, () -> sink[0] += rowMajor(matrix));
        long column = Timing.minMicros(5, () -> sink[0] += columnMajor(matrix));

        evidence.fact("배열", N + "×" + N + " int (" + (N * N * 4L / (1024 * 1024)) + " MB)");
        evidence.fact("행 우선 순회 최솟값", row + " µs");
        evidence.fact("열 우선 순회 최솟값", column + " µs");
        evidence.fact("배율", String.format("%.1f배", column / (double) Math.max(1, row)));
        evidence.expect("두 순회의 합은 같다 (연산 수가 같다)", rowMajor(matrix) == columnMajor(matrix));
        evidence.expectFlaky("열 우선이 행 우선보다 2배 이상 느리다", column >= row * 2);
        if (sink[0] == Long.MIN_VALUE) {
            throw new IllegalStateException();   // 죽은 코드 제거 방지
        }
        evidence.note("배율은 캐시 크기·프리페처·다른 프로세스의 간섭에 좌우된다(이 러너는 4코어 공유 머신). "
                + "2판 7장은 이것을 '기계적 공감'의 첫 예로 들고, 13장의 거짓 공유(PERF-09A)와 같은 뿌리다.");
    }

    private static long rowMajor(int[] m) {
        long sum = 0;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                sum += m[r * N + c];
            }
        }
        return sum;
    }

    private static long columnMajor(int[] m) {
        long sum = 0;
        for (int c = 0; c < N; c++) {
            for (int r = 0; r < N; r++) {
                sum += m[r * N + c];
            }
        }
        return sum;
    }
}
