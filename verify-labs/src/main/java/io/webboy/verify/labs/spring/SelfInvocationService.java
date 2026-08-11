package io.webboy.verify.labs.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SelfInvocationService {

    private final ObjectProvider<SelfInvocationService> self;

    public SelfInvocationService(ObjectProvider<SelfInvocationService> self) {
        this.self = self;
    }

    /** 안티패턴: this 로 호출하면 프록시를 거치지 않아 @Transactional 이 무시된다. */
    public String callInnerDirectly() {
        return innerTransactional();
    }

    /** 우회책: 프록시 빈을 다시 꺼내 호출한다. */
    public String callInnerThroughProxy() {
        return self.getObject().innerTransactional();
    }

    @Transactional
    public String innerTransactional() {
        return TransactionSynchronizationManager.isActualTransactionActive() ? "ACTIVE" : "NONE";
    }
}
