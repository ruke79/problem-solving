package io.webboy.verify.labs.cloudnative.ch12;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.consumer.RecordingStream;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 12장 — JFR 은 헤드리스 프로파일링 엔진이고 이벤트 스트리밍(RecordingStream)으로 앱 안에서 즉시 소비할 수 있다.
 * JDK 25 에는 책이 모르는 이벤트가 있다: CPU 시간 샘플링(JEP 509)·메서드 타이밍/트레이싱(JEP 520).
 */
public class JfrEventsCase extends VerificationCase {

    @Name("lab.Ping")
    @Label("Lab ping")
    static class PingEvent extends Event {
        String origin;
    }

    @Override
    public String id() {
        return "CN-12A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 12장 — JFR 이벤트 스트리밍은 동작하고, JDK 25 에는 어떤 이벤트가 더 있는가?";
    }

    @Override
    public String claim() {
        return "RecordingStream 으로 커스텀 이벤트를 프로세스 안에서 바로 받을 수 있고, JDK 25 의 이벤트 목록에는 "
                + "jdk.CPUTimeSample(JEP 509)·jdk.MethodTrace/MethodTiming(JEP 520)·jdk.VirtualThreadPinned 이 들어 있다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 스트리밍 수신은 시간이 걸린다 — 목록 검사는 결정적, 수신은 flaky 로 둔다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Set<String> events = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .map(EventType::getName).collect(Collectors.toSet());
        evidence.fact("JFR 이벤트 타입 수", events.size());
        for (String name : new String[]{"jdk.ExecutionSample", "jdk.CPUTimeSample", "jdk.MethodTrace",
                "jdk.MethodTiming", "jdk.VirtualThreadPinned", "jdk.ObjectAllocationSample", "jdk.OldObjectSample",
                "jdk.SafepointBegin", "jdk.GCPhasePause"}) {
            evidence.fact(name, events.contains(name) ? "있음" : "없음");
        }
        evidence.expect("책이 다루는 실행 샘플·할당 샘플·누수 후보 이벤트가 있다",
                events.containsAll(Set.of("jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.OldObjectSample")));
        evidence.expect("JDK 25 의 CPU 시간 샘플링 이벤트(JEP 509)가 있다", events.contains("jdk.CPUTimeSample"));
        evidence.expect("JDK 25 의 메서드 타이밍·트레이싱 이벤트(JEP 520)가 있다",
                events.contains("jdk.MethodTrace") && events.contains("jdk.MethodTiming"));
        evidence.expect("가상 스레드 피닝 이벤트가 있다", events.contains("jdk.VirtualThreadPinned"));

        CountDownLatch received = new CountDownLatch(1);
        String[] origin = new String[1];
        try (RecordingStream stream = new RecordingStream()) {
            stream.enable("lab.Ping");
            stream.onEvent("lab.Ping", event -> {
                origin[0] = event.getString("origin");
                received.countDown();
            });
            stream.startAsync();
            PingEvent ping = new PingEvent();
            ping.origin = "CN-12A";
            ping.commit();
            boolean ok = received.await(15, TimeUnit.SECONDS);
            evidence.fact("RecordingStream 으로 받은 커스텀 이벤트", ok ? "origin=" + origin[0] : "15초 안에 못 받음");
            evidence.expectFlaky("커스텀 이벤트가 스트리밍으로 도착한다", ok && "CN-12A".equals(origin[0]));
        }
        evidence.note("이벤트 스트리밍은 JDK 14(JEP 349)부터다 — 책은 '17·21 에 있다'고만 쓴다. 17·21 의 jfr metadata 에는 "
                + "CPUTimeSample·MethodTrace·MethodTiming 이 없었다(이 세션에서 확인). 책 12장의 '샘플링 프로파일러는 "
                + "벽시계 샘플' 서술은 25 에서 CPU 시간 기준이라는 선택지가 생겼다고 보충해야 한다.");
    }
}
