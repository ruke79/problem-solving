package io.webboy.verify.labs.cloudnative.ch13;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 13장 — "newVirtualThreadPerTaskExecutor() 는 풀이 아니라 태스크마다 새 가상 스레드를 만들고, executor 자체가
 * 싸서 블록 스코프(try-with-resources)로 써도 된다." 그리고 "스레드 병목: 플랫폼 스레드 2만 개는 20GB".
 */
public class VirtualThreadPerTaskCase extends VerificationCase {

    private static final int TASKS = 10_000;

    @Override
    public String id() {
        return "CN-13B";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 13장 — 가상 스레드 executor 는 정말 태스크마다 새 스레드를 만드는가? 1만 개를 동시에 재워도 되는가?";
    }

    @Override
    public String claim() {
        return "newVirtualThreadPerTaskExecutor() 에 1만 개의 태스크를 내면 1만 개의 서로 다른 가상 스레드(전부 데몬)가 "
                + "동시에 잠들 수 있고 전체는 잠든 시간(50ms) 근처에 끝난다 — 플랫폼 스레드였다면 스택 예약만 10GB 다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 전체 소요 시간은 환경 의존 — 스레드 수는 결정적
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Set<Long> threadIds = ConcurrentHashMap.newKeySet();
        AtomicInteger virtual = new AtomicInteger();
        AtomicInteger daemon = new AtomicInteger();
        long began = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TASKS; i++) {
                executor.submit(() -> {
                    Thread t = Thread.currentThread();
                    threadIds.add(t.threadId());
                    if (t.isVirtual()) {
                        virtual.incrementAndGet();
                    }
                    if (t.isDaemon()) {
                        daemon.incrementAndGet();
                    }
                    Thread.sleep(50);
                    return null;
                });
            }
        }   // close() 는 모든 태스크가 끝날 때까지 기다린다
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;

        evidence.fact("태스크 수", TASKS);
        evidence.fact("서로 다른 스레드 id 수", threadIds.size());
        evidence.fact("가상 / 데몬 스레드 수", virtual.get() + " / " + daemon.get());
        evidence.fact("전체 소요", elapsedMs + " ms (태스크마다 50ms sleep)");
        evidence.fact("플랫폼 스레드였다면 스택 예약", TASKS + " × 1MB ≈ " + (TASKS / 1024) + " GB (책의 계산)");
        evidence.expect("태스크 수만큼 서로 다른 스레드가 쓰였다 — 풀이 아니다", threadIds.size() == TASKS);
        evidence.expect("전부 가상 스레드다", virtual.get() == TASKS);
        evidence.expect("전부 데몬이다", daemon.get() == TASKS);
        evidence.expectFlaky("1만 개가 동시에 잠들었다 — 전체가 3초 안에 끝난다 (순차라면 500초)", elapsedMs < 3_000);
        evidence.note("책의 웹 서버 예제(serveVT)가 이 패턴이다. 계산 집약 태스크에는 이득이 없다 — 양보할 블로킹이 없어 "
                + "캐리어(기본 = 코어 수)만큼만 병렬로 돈다. 그래서 책은 'I/O 를 (적어도 일부) 하는 태스크용'이라고 못 박는다.");
    }
}
