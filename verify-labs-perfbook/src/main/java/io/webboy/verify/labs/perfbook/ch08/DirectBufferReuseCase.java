package io.webboy.verify.labs.perfbook.ch08;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Timing;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * 8장 — 다이렉트 바이트 버퍼는 할당이 비싸므로 재사용하라.
 *
 * <p>책 8장의 권고다: {@code allocateDirect()} 는 힙 밖 네이티브 메모리를 잡는 연산이라
 * 힙 할당보다 비싸고, 해제도 GC 가 래퍼 객체를 수거해야 일어난다. 그래서
 * <b>다이렉트 버퍼는 반복 할당하지 말고 재사용(풀링)하라</b>가 7장의
 * "객체 풀은 대부분 나쁘다"에 대한 몇 안 되는 예외로 제시된다.
 *
 * <p>측정: 같은 쓰기 작업을 (a) 반복마다 새 다이렉트 버퍼 할당, (b) 버퍼 하나 재사용으로
 * 수행해 비교한다. 힙 버퍼 할당과의 비교도 함께 남긴다.
 */
@Component
public class DirectBufferReuseCase extends VerificationCase {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int ITERATIONS = 300;
    private static final int ROUNDS = 3;

    @Override
    public String id() {
        return "PERF-08";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 8장 — 다이렉트 바이트 버퍼는 왜 재사용해야 하나?";
    }

    @Override
    public String claim() {
        return "allocateDirect() 는 힙 밖 네이티브 메모리를 할당하므로 힙 버퍼 할당보다 비싸다. "
                + "반복 사용한다면 매번 할당하지 말고 하나를 재사용해야 한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        // 검증 대상의 결정적 성질부터 — 다이렉트 버퍼는 실제로 힙 밖이다
        ByteBuffer direct = ByteBuffer.allocateDirect(BUFFER_SIZE);
        ByteBuffer heap = ByteBuffer.allocate(BUFFER_SIZE);
        evidence.expect("allocateDirect() 의 버퍼는 isDirect() 다", direct.isDirect());
        evidence.expect("allocate() 의 버퍼는 힙이다 (hasArray)", heap.hasArray() && !heap.isDirect());

        // 워밍업 — JIT 과 네이티브 할당 경로 양쪽
        allocateEveryTime(true);
        allocateEveryTime(false);
        reuse(direct);

        long allocDirectMicros = Timing.minMicros(ROUNDS, () -> allocateEveryTime(true));
        long allocHeapMicros = Timing.minMicros(ROUNDS, () -> allocateEveryTime(false));
        long reuseMicros = Timing.minMicros(ROUNDS, () -> reuse(direct));

        evidence.fact("반복마다 다이렉트 할당 (" + ITERATIONS + "회)", allocDirectMicros + " us");
        evidence.fact("반복마다 힙 할당 (" + ITERATIONS + "회)", allocHeapMicros + " us");
        evidence.fact("다이렉트 1개 재사용 (" + ITERATIONS + "회)", reuseMicros + " us");

        evidence.expect("측정 해상도가 확보된다", allocDirectMicros > 0 && reuseMicros > 0);
        evidence.expectFlaky("재사용이 반복 할당보다 빠르다 (최소 1.5배)",
                reuseMicros * 3 <= allocDirectMicros * 2);
        evidence.expectFlaky("다이렉트 할당이 힙 할당보다 느리다", allocHeapMicros < allocDirectMicros);
        evidence.note("다이렉트 메모리 해제는 래퍼 객체의 GC 에 얹혀 있다 — 이 측정에는 그 비용이 "
                + "(할당 경로가 내부적으로 유발하는 GC 로) 일부만 섞인다. 재사용의 이점은 실제로는 더 크다.");
    }

    private static void allocateEveryTime(boolean direct) {
        for (int i = 0; i < ITERATIONS; i++) {
            ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(BUFFER_SIZE) : ByteBuffer.allocate(BUFFER_SIZE);
            fill(buffer, i);
        }
    }

    private static void reuse(ByteBuffer buffer) {
        for (int i = 0; i < ITERATIONS; i++) {
            buffer.clear();
            fill(buffer, i);
        }
    }

    private static void fill(ByteBuffer buffer, int seed) {
        for (int i = 0; i < 128; i++) {
            buffer.putLong(seed + i);
        }
        buffer.flip();
    }
}
