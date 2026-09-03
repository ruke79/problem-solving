package io.webboy.verify.labs.cloudnative.probe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * FFM API(JEP 454, JDK 22 정식)로 libc 의 {@code strlen} 을 부른다 — 2판 15장이 "Panama 는 아직"
 * 이라고 쓴 것과 달리 정식 API 이고, 대신 JEP 472 의 "제한된 메서드" 경고가 붙는다는 것을 본다.
 * 컴파일 경고([restricted])는 의도된 것이다.
 */
public final class FfmProbe {

    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle strlen = linker.downcallHandle(
                linker.defaultLookup().find("strlen").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            long length = (long) strlen.invokeExact(arena.allocateFrom("hello"));
            System.out.println("STRLEN=" + length);
        }
    }
}
