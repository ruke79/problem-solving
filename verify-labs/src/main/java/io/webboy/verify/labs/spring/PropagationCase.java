package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.jpa.NoteRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PropagationCase extends VerificationCase {

    private final PropagationService service;
    private final NoteRepository notes;

    public PropagationCase(PropagationService service, NoteRepository notes) {
        this.service = service;
        this.notes = notes;
    }

    @Override
    public String id() {
        return "SPRING-03";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "REQUIRED 와 REQUIRES_NEW 의 차이를 롤백 관점에서 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "REQUIRES_NEW 는 물리적으로 별도 트랜잭션이라 바깥이 롤백돼도 남고, REQUIRED 는 같은 트랜잭션이라 함께 사라진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        String outerNew = "outer-new-" + tag;
        String innerNew = "inner-new-" + tag;
        try {
            service.outerFailsAfterInnerRequiresNew(outerNew, innerNew);
        } catch (IllegalStateException expected) {
            evidence.fact("REQUIRES_NEW 시나리오 예외", expected.getMessage());
        }

        String outerSame = "outer-same-" + tag;
        String innerSame = "inner-same-" + tag;
        try {
            service.outerFailsAfterInnerRequired(outerSame, innerSame);
        } catch (IllegalStateException expected) {
            evidence.fact("REQUIRED 시나리오 예외", expected.getMessage());
        }

        long outerNewCount = notes.countByText(outerNew);
        long innerNewCount = notes.countByText(innerNew);
        long outerSameCount = notes.countByText(outerSame);
        long innerSameCount = notes.countByText(innerSame);

        evidence.fact("바깥(REQUIRES_NEW 시나리오) 저장 건수", outerNewCount);
        evidence.fact("내부 REQUIRES_NEW 저장 건수", innerNewCount);
        evidence.fact("바깥(REQUIRED 시나리오) 저장 건수", outerSameCount);
        evidence.fact("내부 REQUIRED 저장 건수", innerSameCount);

        evidence.expectEquals("바깥 트랜잭션 데이터는 롤백된다", 0L, outerNewCount);
        evidence.expectEquals("REQUIRES_NEW 내부 데이터는 살아남는다", 1L, innerNewCount);
        evidence.expectEquals("REQUIRED 시나리오는 바깥도 롤백", 0L, outerSameCount);
        evidence.expectEquals("REQUIRED 내부도 함께 롤백된다", 0L, innerSameCount);

        evidence.note("REQUIRES_NEW 는 커넥션을 하나 더 점유한다 — 풀 크기 산정 시 반드시 고려한다.");
    }
}
