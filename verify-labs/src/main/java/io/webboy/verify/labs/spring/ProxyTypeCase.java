package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

@Component
public class ProxyTypeCase extends VerificationCase {

    public interface Greeter {
        String greet();
    }

    public static class GreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "hi";
        }
    }

    @Override
    public String id() {
        return "SPRING-02";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "JDK 동적 프록시와 CGLIB 프록시의 차이는 무엇이고 Spring Boot 는 무엇을 씁니까?";
    }

    @Override
    public String claim() {
        return "인터페이스가 있으면 JDK 프록시가 가능하지만, Spring Boot 는 proxyTargetClass=true 가 기본이라 CGLIB 를 쓴다";
    }

    @Override
    protected void verify(Evidence evidence) {
        MethodInterceptor passThrough = invocation -> invocation.proceed();

        ProxyFactory jdkFactory = new ProxyFactory(new GreeterImpl());
        jdkFactory.setInterfaces(Greeter.class);
        jdkFactory.addAdvice(passThrough);
        Object jdkProxy = jdkFactory.getProxy();

        ProxyFactory cglibFactory = new ProxyFactory(new GreeterImpl());
        cglibFactory.setProxyTargetClass(true);
        cglibFactory.addAdvice(passThrough);
        Object cglibProxy = cglibFactory.getProxy();

        evidence.fact("JDK 프록시 클래스", jdkProxy.getClass().getName());
        evidence.fact("CGLIB 프록시 클래스", cglibProxy.getClass().getName());
        evidence.fact("JDK 프록시가 GreeterImpl 타입인가", GreeterImpl.class.isInstance(jdkProxy));
        evidence.fact("CGLIB 프록시가 GreeterImpl 타입인가", GreeterImpl.class.isInstance(cglibProxy));

        evidence.expect("인터페이스만 지정하면 JDK 동적 프록시가 생성된다", AopUtils.isJdkDynamicProxy(jdkProxy));
        evidence.expect("proxyTargetClass=true 면 CGLIB 프록시가 생성된다", AopUtils.isCglibProxy(cglibProxy));
        evidence.expect("JDK 프록시는 구현 클래스로 캐스팅할 수 없다", !GreeterImpl.class.isInstance(jdkProxy));
        evidence.expect("CGLIB 프록시는 구현 클래스의 서브클래스다", GreeterImpl.class.isInstance(cglibProxy));

        evidence.note("Spring Boot 2.0 부터 spring.aop.proxy-target-class 기본값이 true 다.");
        evidence.note("CGLIB 는 상속 기반이므로 final 클래스/final 메서드에는 어드바이스가 적용되지 않는다.");
    }
}
