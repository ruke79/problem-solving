package io.webboy.verify.labs.spring;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class AsyncProbeService {

    public static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    @Async
    public CompletableFuture<Map<String, String>> probe() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("thread", Thread.currentThread().getName());
        snapshot.put("transactionActive",
                String.valueOf(TransactionSynchronizationManager.isActualTransactionActive()));
        snapshot.put("threadLocal", String.valueOf(CONTEXT.get()));
        return CompletableFuture.completedFuture(snapshot);
    }
}
