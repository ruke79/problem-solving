package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
public class OptimisticLockCase extends VerificationCase {

    private final NoteRepository notes;
    private final TransactionTemplate tx;

    public OptimisticLockCase(NoteRepository notes, TransactionTemplate tx) {
        this.notes = notes;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-04";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "낙관적 락(@Version)은 언제 어떤 예외로 충돌을 알려줍니까?";
    }

    @Override
    public String claim() {
        return "UPDATE 의 WHERE 절에 version 이 붙고, 영향 행이 0이면 flush 시점에 ObjectOptimisticLockingFailureException 이 난다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long id = tx.execute(status -> notes.save(new Note("optimistic-" + tag)).getId());

        Note stale = tx.execute(status -> notes.findById(id).orElseThrow());
        Long staleVersion = stale.getVersion();

        tx.executeWithoutResult(status -> {
            Note fresh = notes.findById(id).orElseThrow();
            fresh.setText("updated-by-other-" + tag);
        });

        Long currentVersion = tx.execute(status -> notes.findById(id).orElseThrow().getVersion());

        String outcome;
        try {
            tx.executeWithoutResult(status -> {
                stale.setText("conflicting-" + tag);
                notes.save(stale);
            });
            outcome = "예외 없음 (충돌 미검출)";
        } catch (ObjectOptimisticLockingFailureException e) {
            outcome = "ObjectOptimisticLockingFailureException";
        }

        String finalText = tx.execute(status -> notes.findById(id).orElseThrow().getText());

        evidence.fact("읽어둔(stale) 버전", staleVersion);
        evidence.fact("다른 트랜잭션 갱신 후 버전", currentVersion);
        evidence.fact("stale 엔티티 저장 결과", outcome);
        evidence.fact("최종 텍스트", finalText);

        evidence.expect("다른 트랜잭션 갱신으로 version 이 증가한다", currentVersion > staleVersion);
        evidence.expectEquals("stale 버전으로 저장하면 낙관적 락 예외가 난다",
                "ObjectOptimisticLockingFailureException", outcome);
        evidence.expect("충돌한 갱신은 반영되지 않는다", !finalText.startsWith("conflicting-"));

        evidence.note("낙관적 락은 '충돌이 드물다'는 가정 위에 있다 — 충돌이 잦으면 재시도 비용이 비관적 락보다 커진다.");
        evidence.note("재시도는 반드시 새 트랜잭션에서, 엔티티를 다시 읽어서 해야 한다.");
    }
}
