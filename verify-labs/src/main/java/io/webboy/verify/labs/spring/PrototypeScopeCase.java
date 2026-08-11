package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

@Component
public class PrototypeScopeCase extends VerificationCase {

    private final SingletonHolder holder;

    public PrototypeScopeCase(SingletonHolder holder) {
        this.holder = holder;
    }

    @Override
    public String id() {
        return "SPRING-05";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "싱글턴 빈에 프로토타입 빈을 주입하면 매번 새 인스턴스가 생깁니까?";
    }

    @Override
    public String claim() {
        return "생성자 주입은 싱글턴 생성 시점에 한 번만 일어나므로 계속 같은 인스턴스다. 매번 새로 받으려면 ObjectProvider/Lookup 이 필요하다";
    }

    @Override
    protected void verify(Evidence evidence) {
        long injected1 = holder.injectedSerial();
        long injected2 = holder.injectedSerial();
        long lookup1 = holder.lookupSerial();
        long lookup2 = holder.lookupSerial();

        evidence.fact("주입된 인스턴스 serial (1회차/2회차)", injected1 + " / " + injected2);
        evidence.fact("ObjectProvider 조회 serial (1회차/2회차)", lookup1 + " / " + lookup2);

        evidence.expect("주입 방식은 항상 같은 인스턴스다", injected1 == injected2);
        evidence.expect("ObjectProvider 는 호출마다 새 인스턴스를 만든다", lookup1 != lookup2);

        evidence.note("프로토타입 빈은 컨테이너가 소멸 콜백(@PreDestroy)을 호출하지 않는다 — 자원 정리는 호출자 책임이다.");
    }
}
