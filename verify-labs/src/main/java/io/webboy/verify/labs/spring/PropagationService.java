package io.webboy.verify.labs.spring;

import io.webboy.verify.labs.jpa.Note;
import io.webboy.verify.labs.jpa.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropagationService {

    private final NoteRepository notes;
    private final InnerTransactionService inner;

    public PropagationService(NoteRepository notes, InnerTransactionService inner) {
        this.notes = notes;
        this.inner = inner;
    }

    /** 바깥 트랜잭션은 롤백되지만 REQUIRES_NEW 로 쓴 내부 트랜잭션은 살아남는다. */
    @Transactional
    public void outerFailsAfterInnerRequiresNew(String outerText, String innerText) {
        notes.save(new Note(outerText));
        inner.writeInNewTransaction(innerText);
        throw new IllegalStateException("의도적 실패 — 바깥 트랜잭션 롤백");
    }

    /** 내부가 REQUIRED 면 같은 트랜잭션이므로 함께 롤백된다. */
    @Transactional
    public void outerFailsAfterInnerRequired(String outerText, String innerText) {
        notes.save(new Note(outerText));
        inner.writeInSameTransaction(innerText);
        throw new IllegalStateException("의도적 실패 — 바깥 트랜잭션 롤백");
    }
}
