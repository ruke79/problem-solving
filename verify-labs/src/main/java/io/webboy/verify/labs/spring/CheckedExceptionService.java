package io.webboy.verify.labs.spring;

import io.webboy.verify.labs.jpa.Note;
import io.webboy.verify.labs.jpa.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckedExceptionService {

    private final NoteRepository notes;

    public CheckedExceptionService(NoteRepository notes) {
        this.notes = notes;
    }

    /** 기본 규칙: RuntimeException/Error 만 롤백. checked 예외는 커밋된다. */
    @Transactional
    public void writeThenThrowChecked(String text) throws Exception {
        notes.save(new Note(text));
        throw new Exception("checked exception");
    }

    /** rollbackFor 로 checked 예외까지 롤백 대상에 포함시킨다. */
    @Transactional(rollbackFor = Exception.class)
    public void writeThenThrowCheckedWithRollbackFor(String text) throws Exception {
        notes.save(new Note(text));
        throw new Exception("checked exception");
    }

    @Transactional
    public void writeThenThrowRuntime(String text) {
        notes.save(new Note(text));
        throw new IllegalStateException("runtime exception");
    }
}
