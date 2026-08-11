package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class AsyncContextCase extends VerificationCase {

    private final AsyncProbeService probe;
    private final TransactionTemplate tx;

    public AsyncContextCase(AsyncProbeService probe, TransactionTemplate tx) {
        this.probe = probe;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "SPRING-06";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "@Async 메서드는 호출한 쪽의 트랜잭션과 ThreadLocal 컨텍스트를 이어받습니까?";
    }

    @Override
    public String claim() {
        return "다른 스레드에서 실행되므로 트랜잭션도 ThreadLocal 도 전파되지 않는다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String callerThread = Thread.currentThread().getName();
        AsyncProbeService.CONTEXT.set("caller-value");
        try {
            Map<String, String> snapshot = tx.execute(status -> {
                try {
                    return probe.probe().get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            evidence.fact("호출 스레드", callerThread);
            evidence.fact("비동기 실행 스레드", snapshot.get("thread"));
            evidence.fact("비동기 쪽 트랜잭션 활성", snapshot.get("transactionActive"));
            evidence.fact("비동기 쪽 ThreadLocal 값", snapshot.get("threadLocal"));

            evidence.expect("비동기 작업은 다른 스레드에서 실행된다", !callerThread.equals(snapshot.get("thread")));
            evidence.expectEquals("트랜잭션이 전파되지 않는다", "false", snapshot.get("transactionActive"));
            evidence.expectEquals("ThreadLocal 이 전파되지 않는다", "null", snapshot.get("threadLocal"));

            evidence.note("MDC, SecurityContext, RequestContextHolder 도 같은 이유로 유실된다 — TaskDecorator 로 복사해야 한다.");
        } finally {
            AsyncProbeService.CONTEXT.remove();
        }
    }
}
