package io.webboy.verify.labs.perfbook.ch12;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Timing;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 12장 — 버퍼링 없는 I/O 는 1바이트 읽기마다 시스템 콜을 낸다.
 *
 * <p>책 12장의 첫 번째 팁이다: {@code FileInputStream.read()} 를 바이트 단위로 부르면
 * 호출마다 커널까지 내려간다. {@code BufferedInputStream} 으로 감싸면 8KB 씩 미리 읽어
 * 대부분의 {@code read()} 가 메모리 접근이 된다. 책의 압축 예제는 이것만으로 6배 차이가 났다.
 *
 * <p>측정: 시간보다 먼저 <b>하부 스트림 호출 횟수를 센다</b> — 이것이 명제의 메커니즘이고,
 * 횟수는 결정적이다. 시간 비교는 {@code expectFlaky} 로 곁들인다.
 */
@Component
public class BufferedIoCase extends VerificationCase {

    private static final int FILE_SIZE = 512 * 1024;
    private static final int BUFFER_SIZE = 8192;   // BufferedInputStream 기본값

    @Override
    public String id() {
        return "PERF-12A";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 12장 — 버퍼링 없는 스트림 I/O 는 왜 그렇게 느린가?";
    }

    @Override
    public String claim() {
        return "버퍼링 없는 read() 는 바이트마다 하부 스트림(시스템 콜)까지 내려간다. "
                + "BufferedInputStream 으로 감싸면 하부 호출이 버퍼 크기 분의 1로 줄고, "
                + "나머지 read() 는 메모리 접근이 된다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 호출 횟수는 결정적이지만 시간 비교가 있다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Path file = Files.createTempFile("perfbook-io", ".bin");
        try {
            byte[] content = new byte[FILE_SIZE];
            ThreadLocalRandom.current().nextBytes(content);
            Files.write(file, content);

            // 메커니즘 확인 — 하부 스트림 호출 횟수 (결정적)
            AtomicLong unbufferedCalls = new AtomicLong();
            long unbufferedSum = readAllBytesOneByOne(
                    new CountingInputStream(new FileInputStream(file.toFile()), unbufferedCalls));

            AtomicLong bufferedCalls = new AtomicLong();
            long bufferedSum = readAllBytesOneByOne(new BufferedInputStream(
                    new CountingInputStream(new FileInputStream(file.toFile()), bufferedCalls), BUFFER_SIZE));

            evidence.fact("파일 크기", FILE_SIZE + " bytes");
            evidence.fact("버퍼 없이 — 하부 read 호출", unbufferedCalls.get() + "회");
            evidence.fact("8KB 버퍼 — 하부 read 호출", bufferedCalls.get() + "회");

            evidence.expect("읽은 내용이 같다 (체크섬 일치)", unbufferedSum == bufferedSum);
            evidence.expect("버퍼 없이는 바이트 수만큼 하부 호출이 난다",
                    unbufferedCalls.get() >= FILE_SIZE);
            evidence.expect("버퍼를 쓰면 하부 호출이 1% 미만으로 준다",
                    bufferedCalls.get() * 100 <= unbufferedCalls.get());

            // 시간 확인 (flaky) — 위 횟수 차이가 실제 시간으로 이어지는가
            long unbufferedMicros = Timing.minMicros(3, () -> readAllBytesOneByOne(
                    new CountingInputStream(new FileInputStream(file.toFile()), new AtomicLong())));
            long bufferedMicros = Timing.minMicros(3, () -> readAllBytesOneByOne(new BufferedInputStream(
                    new CountingInputStream(new FileInputStream(file.toFile()), new AtomicLong()), BUFFER_SIZE)));

            evidence.fact("버퍼 없이", unbufferedMicros + " us");
            evidence.fact("8KB 버퍼", bufferedMicros + " us");
            evidence.expectFlaky("버퍼가 시간도 줄인다 (최소 2배)", bufferedMicros * 2 <= unbufferedMicros);
            evidence.note("책의 6배는 zip/gzip 스트림 기준이다. 여기 배율은 파일 캐시가 뜨거운 상태의 "
                    + "시스템 콜 비용만 반영한다 — 메커니즘(호출 횟수 " + FILE_SIZE + " → "
                    + bufferedCalls.get() + ")은 결정적으로 확인됐다.");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** 바이트 단위 read() 로 전부 읽고 합을 돌려준다 (죽은 코드 제거 방지 겸 체크섬). */
    private static long readAllBytesOneByOne(InputStream in) throws IOException {
        try (in) {
            long sum = 0;
            int b;
            while ((b = in.read()) != -1) {
                sum += b;
            }
            return sum;
        }
    }

    /** 하부 스트림으로 내려간 read 호출 수를 센다 — "시스템 콜에 근접한 횟수"의 대리 지표다. */
    private static final class CountingInputStream extends FilterInputStream {
        private final AtomicLong calls;

        CountingInputStream(InputStream in, AtomicLong calls) {
            super(in);
            this.calls = calls;
        }

        @Override
        public int read() throws IOException {
            calls.incrementAndGet();
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            calls.incrementAndGet();
            return super.read(b, off, len);
        }
    }
}
