package io.webboy.verify.labs.resilience;

import java.util.function.LongSupplier;

/** RES-05 검증용 최소 구현. 시계를 주입받아 타이밍 의존 없이 결정적으로 검증한다. */
public final class RateLimiters {

    private RateLimiters() {
    }

    /** 고정 윈도: 경계를 넘는 순간 제한의 2배가 통과할 수 있다. */
    public static final class FixedWindow {
        private final int limit;
        private final long windowMillis;
        private final LongSupplier clock;
        private long currentWindow = -1;
        private int count;

        public FixedWindow(int limit, long windowMillis, LongSupplier clock) {
            this.limit = limit;
            this.windowMillis = windowMillis;
            this.clock = clock;
        }

        public boolean tryAcquire() {
            long window = clock.getAsLong() / windowMillis;
            if (window != currentWindow) {
                currentWindow = window;
                count = 0;
            }
            if (count < limit) {
                count++;
                return true;
            }
            return false;
        }
    }

    /** 토큰 버킷: 일정 레이트로 보충되고 버스트는 용량까지만 허용한다. */
    public static final class TokenBucket {
        private final double capacity;
        private final double refillPerMillis;
        private final LongSupplier clock;
        private double tokens;
        private long lastRefill;

        public TokenBucket(double capacity, double refillPerSecond, LongSupplier clock) {
            this.capacity = capacity;
            this.refillPerMillis = refillPerSecond / 1000.0;
            this.clock = clock;
            this.tokens = capacity;
            this.lastRefill = clock.getAsLong();
        }

        public boolean tryAcquire() {
            long now = clock.getAsLong();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMillis);
            lastRefill = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
