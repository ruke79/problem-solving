package io.webboy.verify.labs.cloudnative;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.util.Optional;

/** 실행 중인 JVM 에 플래그 값을 직접 묻는다 — 문서가 아니라 런타임이 답한다(PERF-A01 과 같은 방식). */
public final class Flags {

    private static final HotSpotDiagnosticMXBean DIAGNOSTICS =
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    private Flags() {
    }

    /** 플래그가 없으면 {@code Optional.empty()} — "없다" 자체가 증거인 경우가 많다. */
    public static Optional<String> value(String name) {
        try {
            return Optional.of(DIAGNOSTICS.getVMOption(name).getValue());
        } catch (IllegalArgumentException absent) {
            return Optional.empty();
        }
    }

    public static boolean isTrue(String name) {
        return value(name).map("true"::equals).orElse(false);
    }

    public static int featureVersion() {
        return Runtime.version().feature();
    }
}
