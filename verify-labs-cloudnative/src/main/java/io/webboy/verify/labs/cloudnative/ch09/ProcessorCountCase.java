package io.webboy.verify.labs.cloudnative.ch09;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.RuntimeProbe;

import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * 9장·13장 — "commonPool 의 병렬성은 availableProcessors() − 1 이고, JVM 은 OS 가 말해 주는 CPU 수를 믿는다."
 * 컨테이너 CPU 제한(여기서는 {@code -XX:ActiveProcessorCount} 로 흉내)이 병렬 스트림의 병렬성을 어떻게 바꾸는지 본다.
 */
public class ProcessorCountCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-09A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 9장·13장 — 컨테이너 CPU 제한은 parallelStream() 의 병렬성에 어떻게 반영되는가?";
    }

    @Override
    public String claim() {
        return "ForkJoinPool.commonPool() 의 병렬성은 availableProcessors() − 1(최소 1)이다. CPU 를 2개로 제한하면 1, "
                + "1개로 제한하면 1 이 되어 parallelStream() 의 이득이 사라진다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        int processors = Runtime.getRuntime().availableProcessors();
        int parallelism = ForkJoinPool.commonPool().getParallelism();
        evidence.fact("이 JVM 의 availableProcessors / commonPool 병렬성", processors + " / " + parallelism);
        evidence.expect("commonPool = max(1, 코어 − 1)", parallelism == Math.max(1, processors - 1));

        for (int cpus : new int[]{1, 2, 3}) {
            Jvm.Result result = Jvm.run(List.of("-XX:ActiveProcessorCount=" + cpus), RuntimeProbe.class);
            int seen = value(result, "AVAILABLE_PROCESSORS=");
            int pool = value(result, "COMMON_POOL_PARALLELISM=");
            evidence.fact("ActiveProcessorCount=" + cpus + " → availableProcessors / commonPool", seen + " / " + pool);
            evidence.expect("CPU " + cpus + "개로 제한하면 availableProcessors 도 " + cpus + " 다", seen == cpus);
            evidence.expect("CPU " + cpus + "개면 commonPool 은 " + Math.max(1, cpus - 1) + " 다",
                    pool == Math.max(1, cpus - 1));
        }
        evidence.note("cgroup CPU 쿼터가 있는 컨테이너에서는 JVM 이 쿼터를 올림한 값을 availableProcessors 로 쓴다"
                + "(UseContainerSupport). 책 13장의 16-4-2 머신 사례(하드웨어 스레드 128 을 16 으로 보고)와 반대 방향의 같은 문제다 — "
                + "'VM 은 OS 를 믿는다'. 병렬성이 필요하면 -Djava.util.concurrent.ForkJoinPool.common.parallelism 으로 명시한다.");
    }

    private static int value(Jvm.Result result, String key) {
        return result.stdout().lines()
                .filter(line -> line.startsWith(key))
                .map(line -> Integer.parseInt(line.substring(key.length()).trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(key + " 가 없다: " + result.all()));
    }
}
