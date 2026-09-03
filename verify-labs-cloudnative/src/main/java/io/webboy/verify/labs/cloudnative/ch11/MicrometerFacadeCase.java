package io.webboy.verify.labs.cloudnative.ch11;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 11장 — Micrometer 계측기의 성질을 책의 서술대로 확인한다: Counter 는 단조 증가, Timer 는 count·sum·max,
 * MeterFilter.denyNameStartsWith 는 등록 자체를 막는다. 백엔드 없이 SimpleMeterRegistry 로 본다.
 */
public class MicrometerFacadeCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-11A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 11장 — Micrometer 의 Counter·Timer·MeterFilter 는 책의 설명대로 동작하는가?";
    }

    @Override
    public String claim() {
        return "Counter 의 '단조 증가'는 계약이지 SimpleMeterRegistry 가 강제하는 성질이 아니다 — increment(-5) 를 부르면 "
                + "count 가 음수가 된다. Timer 는 개수·총합·최댓값을 함께 들고 있으며, denyNameStartsWith 필터에 걸린 미터는 "
                + "등록되지 않아 레지스트리에서 찾을 수 없다";
    }

    @Override
    protected void verify(Evidence evidence) {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(MeterFilter.denyNameStartsWith("internal"));
        evidence.fact("Micrometer 버전", String.valueOf(MeterRegistry.class.getPackage().getImplementationVersion()));

        // ① Counter — 단조 증가
        Counter battles = Counter.builder("battles.total").description("Total number of battles fought").register(registry);
        battles.increment();
        battles.increment(2);
        double beforeNegative = battles.count();
        battles.increment(-5);
        evidence.fact("increment(1), increment(2) 뒤 count", beforeNegative);
        evidence.fact("increment(-5) 뒤 count", battles.count());
        evidence.expect("카운터는 3 이다", beforeNegative == 3.0);
        // 첫 판은 "음수 증가는 무시된다"를 기대했다가 REFUTED 가 났다 — count 가 -2.0 이 됐다.
        // 책이 말하는 '단조 증가'는 Counter 의 의미 계약이고, 인메모리 레지스트리는 그것을 검사하지 않는다.
        // (Prometheus 레지스트리는 음수를 버린다고 알고 있으나 여기서는 확인하지 않았다 — 백엔드마다 다를 수 있다.)
        evidence.expect("SimpleMeterRegistry 는 음수 증가를 막지 않는다 — 단조 증가는 호출자의 책임이다 (count=-2)",
                battles.count() == -2.0);

        // ② Timer — count · totalTime · max
        Timer timer = Timer.builder("response.time").register(registry);
        timer.record(Duration.ofMillis(10));
        timer.record(Duration.ofMillis(30));
        timer.record(Duration.ofMillis(20));
        evidence.fact("Timer count / total(ms) / max(ms)", timer.count() + " / "
                + timer.totalTime(TimeUnit.MILLISECONDS) + " / " + timer.max(TimeUnit.MILLISECONDS));
        evidence.expect("Timer 는 세 값을 센다 (count=3)", timer.count() == 3);
        evidence.expect("총합은 60ms 다", Math.abs(timer.totalTime(TimeUnit.MILLISECONDS) - 60.0) < 1e-6);
        evidence.expect("최댓값은 30ms 다", Math.abs(timer.max(TimeUnit.MILLISECONDS) - 30.0) < 1e-6);

        // ③ MeterFilter — 등록 거부
        Counter internal = registry.counter("internal.debug.count");
        internal.increment();
        boolean registered = registry.find("internal.debug.count").counter() != null;
        evidence.fact("거부된 미터의 구현 클래스", internal.getClass().getSimpleName());
        evidence.fact("registry.find(\"internal.debug.count\")", registered ? "찾음" : "없음");
        evidence.expect("denyNameStartsWith(\"internal\") 에 걸린 미터는 레지스트리에 없다", !registered);
        evidence.expect("거부된 미터는 Noop 구현이라 증가시켜도 아무 일도 없다",
                internal.getClass().getSimpleName().contains("Noop") && internal.count() == 0.0);
        evidence.expect("필터에 안 걸린 미터는 찾을 수 있다", registry.find("battles.total").counter() != null);
        evidence.note("책의 예제와 같은 이름(battles.total, response.time)을 썼다. 백엔드(Prometheus/OTLP)는 붙이지 않았다 — "
                + "그것은 인프라가 필요하고 이 저장소의 compose 에는 없다. 파사드가 하는 일(계측기 의미론)만 확인한 것이고, "
                + "그 과정에서 '카운터는 단조 증가' 가 라이브러리의 보장이 아니라 사용 규약임을 배웠다 — 감소를 기록하려면 Gauge 다.");
    }
}
