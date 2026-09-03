package io.webboy.verify.labs.cloudnative.ch13;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 13장·15장 — "ThreadLocal 은 가상 스레드와 궁합이 나쁘니 ScopedValue(15장, 프리뷰)를 보라."
 * JDK 25 에서 ScopedValue 가 정식(JEP 506)이 됐다 — 프리뷰 플래그 없이 컴파일된 이 클래스 자체가 증거다.
 * 책이 말하는 성질(스코프에 묶인 수명, 재바인딩, 자동 상속 없음)을 확인한다.
 */
public class ScopedValueCase extends VerificationCase {

    private static final ScopedValue<String> TENANT = ScopedValue.newInstance();

    @Override
    public String id() {
        return "CN-13C";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 13장·15장 — ScopedValue 는 ThreadLocal 과 무엇이 다른가?";
    }

    @Override
    public String claim() {
        return "ScopedValue 는 where(...).run(...) 스코프 안에서만 바인딩되고 스코프를 벗어나면 풀린다(isBound=false, "
                + "get 은 NoSuchElementException). 중첩 스코프는 재바인딩이며 바깥값을 덮어쓰지 않고, 스코프 안에서 "
                + "직접 만든 스레드에는 자동 상속되지 않는다 — JDK 25 에서 프리뷰 없이 쓸 수 있다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        evidence.fact("java.version", Runtime.version().toString());
        evidence.fact("ScopedValue 클래스", ScopedValue.class.getName() + " (프리뷰 플래그 없이 컴파일·로드됨)");
        evidence.expect("스코프 밖에서는 바인딩되지 않았다", !TENANT.isBound());

        AtomicReference<String> inside = new AtomicReference<>();
        AtomicReference<String> nested = new AtomicReference<>();
        AtomicReference<String> afterNested = new AtomicReference<>();
        AtomicReference<Boolean> childBound = new AtomicReference<>();

        ScopedValue.where(TENANT, "outer").run(() -> {
            inside.set(TENANT.get());
            ScopedValue.where(TENANT, "inner").run(() -> nested.set(TENANT.get()));
            afterNested.set(TENANT.get());
            try {
                Thread child = Thread.ofVirtual().unstarted(() -> childBound.set(TENANT.isBound()));
                child.start();
                child.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        String afterScope;
        try {
            afterScope = TENANT.get();
        } catch (NoSuchElementException e) {
            afterScope = "NoSuchElementException";
        }

        evidence.fact("스코프 안", inside.get());
        evidence.fact("중첩 스코프 안", nested.get());
        evidence.fact("중첩이 끝난 뒤 바깥 스코프", afterNested.get());
        evidence.fact("스코프 안에서 만든 가상 스레드의 isBound()", childBound.get());
        evidence.fact("스코프 밖의 get()", afterScope);

        evidence.expect("스코프 안에서는 값이 보인다", "outer".equals(inside.get()));
        evidence.expect("중첩 스코프는 재바인딩이다", "inner".equals(nested.get()));
        evidence.expect("중첩이 끝나면 바깥값으로 돌아온다 — 덮어쓰기가 아니다", "outer".equals(afterNested.get()));
        evidence.expect("직접 만든 스레드에는 자동 상속되지 않는다", Boolean.FALSE.equals(childBound.get()));
        evidence.expect("스코프를 벗어나면 풀린다 — isBound=false", !TENANT.isBound());
        evidence.expect("스코프 밖의 get() 은 NoSuchElementException", "NoSuchElementException".equals(afterScope));
        evidence.note("상속은 StructuredTaskScope 의 fork 를 통해서만 일어나는데, 그쪽(JEP 505)은 25 에서도 프리뷰라 이 모듈은 "
                + "쓰지 않았다(--enable-preview 없이 컴파일 실패를 이 세션에서 확인). ThreadLocal 과의 차이는 "
                + "'set 이 없고 수명이 스코프에 묶인다'는 것이고, 그래서 수백만 가상 스레드에서 누수가 나지 않는다.");
    }
}
