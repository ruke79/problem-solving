package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class ThreadLocalLeakCase extends VerificationCase {

    @Override
    public String id() {
        return "CON-04";
    }

    @Override
    public String category() {
        return "concurrency";
    }

    @Override
    public String question() {
        return "스레드 풀 환경에서 ThreadLocal 을 쓸 때 주의할 점은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "풀 스레드는 재사용되므로 remove() 하지 않으면 이전 요청의 값이 다음 작업에 그대로 보인다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String firstThread = pool.submit(() -> {
                context.set("request-1-user");
                return Thread.currentThread().getName();
            }).get(5, TimeUnit.SECONDS);

            String leaked = pool.submit(context::get).get(5, TimeUnit.SECONDS);
            String secondThread = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

            pool.submit(() -> {
                try {
                    context.set("request-3-user");
                } finally {
                    context.remove();
                }
            }).get(5, TimeUnit.SECONDS);

            String afterRemove = pool.submit(context::get).get(5, TimeUnit.SECONDS);

            evidence.fact("1번 작업 스레드", firstThread);
            evidence.fact("2번 작업 스레드", secondThread);
            evidence.fact("2번 작업이 본 ThreadLocal 값", String.valueOf(leaked));
            evidence.fact("remove() 이후 다음 작업이 본 값", String.valueOf(afterRemove));

            evidence.expectEquals("동일 스레드가 재사용된다", firstThread, secondThread);
            evidence.expectEquals("이전 작업의 값이 그대로 보인다(누수)", "request-1-user", leaked);
            evidence.expect("try/finally 로 remove() 하면 값이 남지 않는다", afterRemove == null);

            evidence.note("웹 서버(Tomcat)의 요청 처리 스레드도 같은 구조다 — 사용자 A 의 컨텍스트가 사용자 B 에게 보이는 보안 사고로 이어진다.");
            evidence.note("ThreadLocalMap 의 key 는 WeakReference 지만 value 는 강한 참조라, 스레드가 살아 있는 동안 값이 회수되지 않는다.");
        } finally {
            pool.shutdownNow();
        }
    }
}
