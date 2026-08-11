package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

@Component
public class StrategyInjectionCase extends VerificationCase {

    private final PaymentRouter router;

    public StrategyInjectionCase(PaymentRouter router) {
        this.router = router;
    }

    @Override
    public String id() {
        return "SPRING-07";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "Strategy 패턴을 쓸 때 전략 선택 로직은 어디에 둡니까?";
    }

    @Override
    public String claim() {
        return "Map<빈이름, Strategy> 자동 주입으로 런타임 해결하면 호출부에 if-else 분기가 남지 않고, 새 전략은 @Component 하나로 등록된다";
    }

    @Override
    protected void verify(Evidence evidence) {
        evidence.fact("주입된 전략 키", router.supportedTypes());
        evidence.fact("CREDIT_CARD 결과", router.pay("CREDIT_CARD", 1000));
        evidence.fact("PAYPAY 결과", router.pay("PAYPAY", 2000));
        evidence.fact("나중에 추가된 BANK_TRANSFER 결과", router.pay("BANK_TRANSFER", 3000));

        evidence.expect("등록된 모든 전략이 자동 주입된다",
                router.supportedTypes().containsAll(java.util.List.of("CREDIT_CARD", "PAYPAY", "BANK_TRANSFER")));
        evidence.expectEquals("빈 이름을 키로 런타임 해결된다", "PAYPAY:2000", router.pay("PAYPAY", 2000));
        evidence.expectEquals("라우터 코드를 고치지 않아도 신규 수단이 동작한다",
                "BANK_TRANSFER:3000", router.pay("BANK_TRANSFER", 3000));

        String unknown;
        try {
            router.pay("BITCOIN", 1);
            unknown = "예외 없음";
        } catch (IllegalArgumentException e) {
            unknown = "IllegalArgumentException";
        }
        evidence.fact("미등록 수단 호출", unknown);
        evidence.expectEquals("미등록 수단은 명시적으로 실패한다", "IllegalArgumentException", unknown);

        evidence.note("전략마다 전·후처리가 다르면 Strategy 단독으로는 부족하다 — Template Method 와 조합해 골격은 템플릿에 둔다.");
        evidence.note("키를 빈 이름으로 쓰면 오타가 런타임 오류가 되므로, 운영에서는 enum + @Component 이름 규약을 함께 검증하는 편이 안전하다.");
    }
}
