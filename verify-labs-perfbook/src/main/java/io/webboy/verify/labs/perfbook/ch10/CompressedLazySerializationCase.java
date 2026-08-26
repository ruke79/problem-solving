package io.webboy.verify.labs.perfbook.ch10;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Timing;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 10장 — 직렬화 데이터 압축: 바이트는 줄지만 CPU 를 내주며, 해제를 미루면 그 CPU 도 아낀다.
 *
 * <p>책 10장의 HTTP 세션 복제 사례: 세션의 큰 문자열을 (a) 그대로, (b) 압축해서 쓰고 읽을 때
 * 바로 해제, (c) 압축해서 쓰고 <b>실제로 접근할 때만 해제(지연)</b> 세 가지를 비교해
 * (c) 가 가장 빨랐다(12.7초 → 0.494초). 복제받는 쪽 인스턴스는 데이터에 접근하지 않는 경우가
 * 대부분이라 해제 비용 자체가 사라지기 때문이다.
 *
 * <p>측정: 크기는 결정적으로({@code expect}), 시간은 {@code expectFlaky} 로 확인한다.
 */
@Component
public class CompressedLazySerializationCase extends VerificationCase {

    private static final int STRINGS = 400;
    private static final int READS = 30;

    @Override
    public String id() {
        return "PERF-10B";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 10장 — 세션 복제 데이터를 압축하면 빨라지나, 느려지나?";
    }

    @Override
    public String claim() {
        return "압축은 전송 바이트를 줄이는 대신 CPU 를 쓴다. 읽는 쪽이 데이터에 접근하지 않는다면 "
                + "해제를 접근 시점까지 미루는 쪽이 가장 빠르다 — 해제 비용이 아예 발생하지 않기 때문이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String[] payload = new String[STRINGS];
        for (int i = 0; i < STRINGS; i++) {
            // 반복 구조가 있는 텍스트 — 세션에 흔히 들어가는 종류라 압축이 실제로 먹힌다
            payload[i] = ("user-" + i + ";role=MEMBER;theme=dark;locale=ko-KR;").repeat(20);
        }

        byte[] plainBytes = serializePlain(payload);
        byte[] gzipBytes = serializeCompressed(payload);

        evidence.fact("원본 직렬화", plainBytes.length + " bytes");
        evidence.fact("압축 직렬화", gzipBytes.length + " bytes");
        evidence.fact("압축률", String.format("%.1f%%", 100.0 * gzipBytes.length / plainBytes.length));
        evidence.expect("압축이 바이트를 줄인다", gzipBytes.length < plainBytes.length / 2);

        // 결정적 확인: 지연 해제해도 내용은 같다
        String[] eager = deserializeAndDecompress(gzipBytes);
        evidence.expect("해제 후 내용이 원본과 같다", eager[0].equals(payload[0])
                && eager[STRINGS - 1].equals(payload[STRINGS - 1]));

        // 워밍업
        deserializeAndDecompress(gzipBytes);
        deserializeOnly(gzipBytes);

        // (b) 읽자마자 해제 vs (c) 해제를 미룸 — 접근하지 않는 수신자를 재현한다
        long eagerMicros = Timing.minMicros(3, () -> {
            for (int i = 0; i < READS; i++) {
                deserializeAndDecompress(gzipBytes);
            }
        });
        long lazyMicros = Timing.minMicros(3, () -> {
            for (int i = 0; i < READS; i++) {
                deserializeOnly(gzipBytes);
            }
        });

        evidence.fact("읽기 " + READS + "회 — 즉시 해제", eagerMicros + " us");
        evidence.fact("읽기 " + READS + "회 — 지연(미접근)", lazyMicros + " us");
        evidence.expect("측정 해상도가 확보된다", eagerMicros > 0 && lazyMicros > 0);
        evidence.expectFlaky("접근하지 않으면 지연 해제가 훨씬 빠르다 (최소 2배)",
                lazyMicros * 2 <= eagerMicros);
        evidence.note("책의 수치(12.7초 → 0.494초)는 세션 수만 개 규모다. 여기서 확인한 것은 "
                + "메커니즘 — 해제를 미루면 그 비용이 접근할 때까지 발생하지 않는다는 것이다. "
                + "압축 쓰기 자체는 원본 쓰기보다 느리다(트레이드오프의 반대쪽).");
    }

    private static byte[] serializePlain(String[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(payload);
        }
        return bytes.toByteArray();
    }

    private static byte[] serializeCompressed(String[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(bytes))) {
            out.writeObject(payload);
        }
        return bytes.toByteArray();
    }

    /** 수신자가 데이터에 접근하는 경우 — 해제까지 다 한다. */
    private static String[] deserializeAndDecompress(byte[] gzipBytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(
                new GZIPInputStream(new ByteArrayInputStream(gzipBytes)))) {
            return (String[]) in.readObject();
        }
    }

    /** 수신자가 접근하지 않는 경우 — 압축된 바이트를 들고만 있는다 (책의 지연 해제 홀더). */
    private static byte[] deserializeOnly(byte[] gzipBytes) {
        // 세션 복제라면 네트워크에서 받은 바이트를 세션 맵에 넣는 것까지가 수신 비용이다
        byte[] held = new byte[gzipBytes.length];
        System.arraycopy(gzipBytes, 0, held, 0, gzipBytes.length);
        return held;
    }
}
