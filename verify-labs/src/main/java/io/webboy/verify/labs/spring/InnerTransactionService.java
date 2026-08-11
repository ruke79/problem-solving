package io.webboy.verify.labs.spring;

import io.webboy.verify.labs.jpa.Note;
import io.webboy.verify.labs.jpa.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InnerTransactionService {

    private final NoteRepository notes;

    public InnerTransactionService(NoteRepository notes) {
        this.notes = notes;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInNewTransaction(String text) {
        notes.save(new Note(text));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void writeInSameTransaction(String text) {
        notes.save(new Note(text));
    }
}
