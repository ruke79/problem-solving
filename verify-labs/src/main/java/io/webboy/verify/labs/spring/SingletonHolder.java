package io.webboy.verify.labs.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SingletonHolder {

    private final PrototypeBean injectedOnce;
    private final ObjectProvider<PrototypeBean> provider;

    public SingletonHolder(PrototypeBean injectedOnce, ObjectProvider<PrototypeBean> provider) {
        this.injectedOnce = injectedOnce;
        this.provider = provider;
    }

    /** 싱글턴 생성 시점에 한 번 주입된 인스턴스 — 매번 같다. */
    public long injectedSerial() {
        return injectedOnce.serial();
    }

    /** 호출할 때마다 새 프로토타입 인스턴스를 얻는다. */
    public long lookupSerial() {
        return provider.getObject().serial();
    }
}
