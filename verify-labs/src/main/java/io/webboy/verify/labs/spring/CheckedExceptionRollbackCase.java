package io.webboy.verify.labs.spring;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.jpa.NoteRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CheckedExceptionRollbackCase extends VerificationCase {

    private final CheckedExceptionService service;
    private final NoteRepository notes;

    public CheckedExceptionRollbackCase(CheckedExceptionService service, NoteRepository notes) {
        this.service = service;
        this.notes = notes;
    }

    @Override
    public String id() {
        return "SPRING-04";
    }

    @Override
    public String category() {
        return "spring";
    }

    @Override
    public String question() {
        return "@Transactional 은 어떤 예외에서 롤백합니까?";
    }

    @Override
    public String claim() {
        return "기본은 RuntimeException/Error 만 롤백이고, checked 예외는 커밋된다 — rollbackFor 로 명시해야 한다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        String checkedText = "checked-" + tag;
        try {
            service.writeThenThrowChecked(checkedText);
        } catch (Exception ignored) {
            // 기대된 예외
        }

        String rollbackForText = "rollbackfor-" + tag;
        try {
            service.writeThenThrowCheckedWithRollbackFor(rollbackForText);
        } catch (Exception ignored) {
            // 기대된 예외
        }

        String runtimeText = "runtime-" + tag;
        try {
            service.writeThenThrowRuntime(runtimeText);
        } catch (RuntimeException ignored) {
            // 기대된 예외
        }

        long checkedCount = notes.countByText(checkedText);
        long rollbackForCount = notes.countByText(rollbackForText);
        long runtimeCount = notes.countByText(runtimeText);

        evidence.fact("checked 예외 후 남은 행", checkedCount);
        evidence.fact("rollbackFor=Exception 후 남은 행", rollbackForCount);
        evidence.fact("RuntimeException 후 남은 행", runtimeCount);

        evidence.expectEquals("checked 예외는 롤백되지 않고 커밋된다", 1L, checkedCount);
        evidence.expectEquals("rollbackFor 지정 시 checked 예외도 롤백된다", 0L, rollbackForCount);
        evidence.expectEquals("RuntimeException 은 기본 롤백 대상이다", 0L, runtimeCount);

        evidence.note("Spring 이 checked 예외를 '비즈니스 예외 = 복구 가능'으로 간주하기 때문이며, JTA/EJB 관례를 따른 것이다.");
    }
}
