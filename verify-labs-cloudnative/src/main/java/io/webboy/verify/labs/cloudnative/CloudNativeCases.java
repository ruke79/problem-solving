package io.webboy.verify.labs.cloudnative;

import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.appendixa.DeadCodeEliminationCase;
import io.webboy.verify.labs.cloudnative.ch03.SunsetCase;
import io.webboy.verify.labs.cloudnative.ch04.ContainerMemoryCase;
import io.webboy.verify.labs.cloudnative.ch04.GcErgonomicsCase;
import io.webboy.verify.labs.cloudnative.ch05.CompactObjectHeadersCase;
import io.webboy.verify.labs.cloudnative.ch05.GenerationalShenandoahCase;
import io.webboy.verify.labs.cloudnative.ch05.GenerationalZgcCase;
import io.webboy.verify.labs.cloudnative.ch06.AotCacheCase;
import io.webboy.verify.labs.cloudnative.ch06.CodeCacheCase;
import io.webboy.verify.labs.cloudnative.ch07.MemoryLocalityCase;
import io.webboy.verify.labs.cloudnative.ch09.ProcessorCountCase;
import io.webboy.verify.labs.cloudnative.ch10.PercentileAggregationCase;
import io.webboy.verify.labs.cloudnative.ch11.MicrometerFacadeCase;
import io.webboy.verify.labs.cloudnative.ch12.JfrEventsCase;
import io.webboy.verify.labs.cloudnative.ch13.ScopedValueCase;
import io.webboy.verify.labs.cloudnative.ch13.VirtualThreadPerTaskCase;
import io.webboy.verify.labs.cloudnative.ch13.VirtualThreadPinningCase;
import io.webboy.verify.labs.cloudnative.ch15.ForeignFunctionCase;
import io.webboy.verify.labs.cloudnative.ch15.UnsafeDeprecationCase;

import java.util.List;

/**
 * 이 모듈의 케이스 목록. Spring 컴포넌트 스캔 대신 손으로 나열한다 — 케이스 id 는 {@code CN-<2판 장번호>} 를 따른다.
 * 새 케이스를 더하면 여기와 README 의 표에 함께 적는다.
 */
public final class CloudNativeCases {

    private CloudNativeCases() {
    }

    public static List<VerificationCase> all() {
        return List.of(
                new SunsetCase(),                    // CN-03A
                new GcErgonomicsCase(),              // CN-04A
                new ContainerMemoryCase(),           // CN-04B
                new GenerationalZgcCase(),           // CN-05A
                new GenerationalShenandoahCase(),    // CN-05B
                new CompactObjectHeadersCase(),      // CN-05C
                new AotCacheCase(),                  // CN-06A
                new CodeCacheCase(),                 // CN-06B
                new MemoryLocalityCase(),            // CN-07A
                new ProcessorCountCase(),            // CN-09A
                new PercentileAggregationCase(),     // CN-10A
                new MicrometerFacadeCase(),          // CN-11A
                new JfrEventsCase(),                 // CN-12A
                new VirtualThreadPinningCase(),      // CN-13A
                new VirtualThreadPerTaskCase(),      // CN-13B
                new ScopedValueCase(),               // CN-13C
                new UnsafeDeprecationCase(),         // CN-15A
                new ForeignFunctionCase(),           // CN-15B
                new DeadCodeEliminationCase()        // CN-A01
        );
    }
}
