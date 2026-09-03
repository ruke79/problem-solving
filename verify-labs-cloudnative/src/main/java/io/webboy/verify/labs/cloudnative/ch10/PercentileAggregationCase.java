package io.webboy.verify.labs.cloudnative.ch10;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;

import java.util.Arrays;

/**
 * 10장 안티패턴 「메트릭에 억지로 밀어 넣기」 — "퍼센타일을 집계하지 마라. 원본 없이 계산한 퍼센타일의 평균은
 * 진실로 위장한 거짓이다." 두 인스턴스의 p99 를 평균 낸 값과 전체 데이터의 p99 를 실제 Micrometer
 * DistributionSummary 로 비교한다.
 */
public class PercentileAggregationCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-10A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 10장 — 인스턴스별 p99 를 평균 내면 왜 안 되는가?";
    }

    @Override
    public String claim() {
        return "퍼센타일은 원본 분포가 있어야 계산된다. 인스턴스 A(p99≈1000)와 B(p99≈10)의 p99 평균은 505 이지만 "
                + "두 데이터를 합친 진짜 p99 는 10 이다 — 미리 계산된 퍼센타일은 재집계할 수 없다";
    }

    @Override
    protected void verify(Evidence evidence) {
        MeterRegistry registry = new SimpleMeterRegistry();
        DistributionSummary a = summary(registry, "a");
        DistributionSummary b = summary(registry, "b");
        DistributionSummary union = summary(registry, "union");

        // A: 1,000건 중 980건은 10ms, 20건은 1000ms (2% 가 느리다) → p99 = 1000
        // B: 1,000건 전부 10ms → p99 = 10
        double[] dataA = new double[1_000];
        double[] dataB = new double[1_000];
        for (int i = 0; i < 1_000; i++) {
            dataA[i] = i < 980 ? 10 : 1_000;
            dataB[i] = 10;
        }
        for (double v : dataA) {
            a.record(v);
            union.record(v);
        }
        for (double v : dataB) {
            b.record(v);
            union.record(v);
        }

        double p99A = p99(a);
        double p99B = p99(b);
        double averaged = (p99A + p99B) / 2;
        double p99Union = p99(union);
        double exactUnion = exactPercentile(concat(dataA, dataB), 0.99);

        evidence.fact("Micrometer 버전", String.valueOf(MeterRegistry.class.getPackage().getImplementationVersion()));
        evidence.fact("A 의 p99", String.format("%.0f", p99A));
        evidence.fact("B 의 p99", String.format("%.0f", p99B));
        evidence.fact("두 p99 의 평균 (재집계 흉내)", String.format("%.0f", averaged));
        evidence.fact("합친 데이터의 p99 (Micrometer)", String.format("%.0f", p99Union));
        evidence.fact("합친 데이터의 p99 (정확 계산)", String.format("%.0f", exactUnion));

        evidence.expect("A 의 p99 는 1000 근처다", p99A > 900);
        evidence.expect("B 의 p99 는 10 근처다", p99B < 20);
        evidence.expect("진짜 p99(합집합)는 10 근처다 — 느린 요청은 전체의 1% 뿐이라서", p99Union < 20 && exactUnion <= 10);
        evidence.expect("p99 의 평균(≈505)은 진짜 p99 와 자릿수가 다르다", averaged > 400);
        evidence.expect("반대로 개수(count)와 합(sum)은 재집계된다",
                a.count() + b.count() == union.count()
                        && Math.abs(a.totalAmount() + b.totalAmount() - union.totalAmount()) < 1e-6);
        evidence.note("책 11장이 다시 강조하는 대목: count·sum 은 차원/인스턴스 간에 더해도 되지만 publishPercentiles 로 "
                + "미리 계산한 값은 안 된다. 인스턴스 간 p99 가 필요하면 히스토그램 버킷(publishPercentileHistogram)을 "
                + "내보내고 서버(Prometheus histogram_quantile)에서 계산한다.");
    }

    private static DistributionSummary summary(MeterRegistry registry, String name) {
        return DistributionSummary.builder("lab.latency." + name)
                .publishPercentiles(0.99)
                .percentilePrecision(2)
                .register(registry);
    }

    private static double p99(DistributionSummary summary) {
        return Arrays.stream(summary.takeSnapshot().percentileValues())
                .filter(v -> Math.abs(v.percentile() - 0.99) < 1e-9)
                .mapToDouble(ValueAtPercentile::value)
                .findFirst()
                .orElseThrow();
    }

    private static double exactPercentile(double[] data, double p) {
        double[] sorted = data.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double[] concat(double[] x, double[] y) {
        double[] all = Arrays.copyOf(x, x.length + y.length);
        System.arraycopy(y, 0, all, x.length, y.length);
        return all;
    }
}
