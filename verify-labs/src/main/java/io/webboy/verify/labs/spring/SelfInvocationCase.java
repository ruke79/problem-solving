package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

@Component
public class SelfInvocationCase extends VerificationCase {

    private final SelfInvocationService service;

    public SelfInvocationCase(SelfInvocationService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "SPRING-01";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "같은 클래스 안에서 @Transactional 메서드를 호출하면 트랜잭션이 걸립니까?";
    }

    @Override
    public String claim() {
        return "Spring AOP 는 프록시 기반이라 this 호출은 프록시를 우회한다 — 자기 호출에서는 @Transactional 이 무시된다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String viaThis = service.callInnerDirectly();
        String viaProxy = service.callInnerThroughProxy();
        String direct = service.innerTransactional();

        evidence.fact("자기 호출(this.innerTransactional())", viaThis);
        evidence.fact("프록시 재조회 호출", viaProxy);
        evidence.fact("외부에서 직접 호출", direct);
        evidence.fact("빈이 CGLIB 프록시인가", AopUtils.isCglibProxy(service));

        evidence.expectEquals("자기 호출에서는 트랜잭션이 없다", "NONE", viaThis);
        evidence.expectEquals("외부 호출에서는 트랜잭션이 열린다", "ACTIVE", direct);
        evidence.expectEquals("프록시를 다시 거치면 트랜잭션이 열린다", "ACTIVE", viaProxy);

        evidence.note("@Retryable, @Cacheable, @Async 도 동일한 함정에 걸린다.");
        evidence.note("근본 해법은 ObjectProvider 자기 주입이 아니라 별도 빈으로 책임을 분리하는 것이다.");
    }
}
