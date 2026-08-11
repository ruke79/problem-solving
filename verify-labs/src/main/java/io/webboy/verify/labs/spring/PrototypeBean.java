package io.webboy.verify.labs.spring;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PrototypeBean {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long serial = SEQUENCE.incrementAndGet();

    public long serial() {
        return serial;
    }
}
