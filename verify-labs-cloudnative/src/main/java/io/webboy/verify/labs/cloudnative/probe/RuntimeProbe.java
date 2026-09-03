package io.webboy.verify.labs.cloudnative.probe;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ForkJoinPool;

/**
 * 자식 JVM 이 "자기 자신을 어떻게 구성했는지" 한 줄씩 찍는다 — GC 선택, 힙 상한, CPU 수, commonPool.
 * 프로브는 JDK 외에 아무것도 의존하지 않는다(부모의 클래스패스를 물려받지 않으므로).
 */
public final class RuntimeProbe {

    public static void main(String[] args) {
        StringBuilder gc = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.append('[').append(bean.getName()).append(']');
        }
        System.out.println("GC_BEANS=" + gc);
        System.out.println("MAX_MEMORY_MB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("AVAILABLE_PROCESSORS=" + Runtime.getRuntime().availableProcessors());
        System.out.println("COMMON_POOL_PARALLELISM=" + ForkJoinPool.commonPool().getParallelism());
        System.out.println("JAVA_VERSION=" + Runtime.version());
    }
}
