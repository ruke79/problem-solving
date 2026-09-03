package io.webboy.verify.labs.cloudnative.probe;

import java.lang.reflect.Field;

/**
 * {@code sun.misc.Unsafe} 의 메모리 접근 메서드를 일부러 부른다 — JEP 471/498 의 폐기 경고가
 * 실제로 나오는지, {@code --sun-misc-unsafe-memory-access=deny} 가 실제로 막는지 보기 위해서다.
 * 컴파일 경고([removal])는 의도된 것이다.
 */
@SuppressWarnings({"removal", "deprecation"})
public final class UnsafeProbe {

    public static void main(String[] args) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        long address = unsafe.allocateMemory(8);
        try {
            unsafe.putLong(address, 42L);
            System.out.println("UNSAFE_READ=" + unsafe.getLong(address));
        } finally {
            unsafe.freeMemory(address);
        }
    }
}
