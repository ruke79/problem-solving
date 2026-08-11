package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
public class DirtyCheckingCase extends VerificationCase {

    private final NoteRepository notes;
    private final HibernateStats stats;
    private final TransactionTemplate tx;

    public DirtyCheckingCase(NoteRepository notes, HibernateStats stats, TransactionTemplate tx) {
        this.notes = notes;
        this.stats = stats;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-02";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "JPA 에서 save() 를 호출하지 않아도 UPDATE 가 나가는 이유는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "영속 상태 엔티티는 스냅샷과 비교하는 변경 감지(dirty checking)로 flush 시점에 UPDATE 가 자동 생성된다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String before = "dirty-before-" + UUID.randomUUID().toString().substring(0, 8);
        String after = "dirty-after-" + UUID.randomUUID().toString().substring(0, 8);

        Long id = tx.execute(status -> notes.save(new Note(before)).getId());

        HibernateStats.Measured<Void> measured = stats.measure(() -> {
            tx.executeWithoutResult(status -> {
                Note managed = notes.findById(id).orElseThrow();
                managed.setText(after);
                // 의도적으로 save() 를 호출하지 않는다
            });
            return null;
        });

        String reloaded = tx.execute(status -> notes.findById(id).orElseThrow().getText());

        evidence.fact("변경 전 텍스트", before);
        evidence.fact("트랜잭션 종료 후 조회된 텍스트", reloaded);
        evidence.fact("save() 없이 발생한 엔티티 UPDATE 수", measured.entityUpdates());

        evidence.expectEquals("save() 없이도 값이 반영된다", after, reloaded);
        evidence.expect("Hibernate 통계상 UPDATE 가 1건 이상 발생했다", measured.entityUpdates() >= 1);

        evidence.note("반대로 준영속(detached) 상태에서는 변경 감지가 동작하지 않는다 — merge 가 필요하다.");
        evidence.note("변경 감지는 flush 시 전체 엔티티를 스냅샷과 비교하므로, 영속성 컨텍스트에 대량 엔티티를 올리면 비용이 커진다.");
    }
}
