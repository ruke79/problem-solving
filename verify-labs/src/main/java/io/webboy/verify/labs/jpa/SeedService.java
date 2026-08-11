package io.webboy.verify.labs.jpa;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SeedService {

    public static final int AUTHORS = 5;
    public static final int BOOKS_PER_AUTHOR = 4;
    public static final int PUBLISHERS = 30;
    public static final int MAGAZINES_PER_PUBLISHER = 2;

    private final AuthorRepository authors;
    private final PublisherRepository publishers;
    private final TransactionTemplate tx;

    private volatile boolean seeded;

    public SeedService(AuthorRepository authors, PublisherRepository publishers, TransactionTemplate tx) {
        this.authors = authors;
        this.publishers = publishers;
        this.tx = tx;
    }

    /** 여러 케이스가 호출해도 한 번만 적재된다. */
    public synchronized void ensureSeeded() {
        if (seeded) {
            return;
        }
        tx.executeWithoutResult(status -> {
            if (authors.count() == 0) {
                for (int i = 1; i <= AUTHORS; i++) {
                    Author author = new Author("author-" + i);
                    for (int j = 1; j <= BOOKS_PER_AUTHOR; j++) {
                        author.addBook(new Book("book-" + i + "-" + j));
                    }
                    authors.save(author);
                }
            }
            if (publishers.count() == 0) {
                for (int i = 1; i <= PUBLISHERS; i++) {
                    Publisher publisher = new Publisher("publisher-" + i);
                    for (int j = 1; j <= MAGAZINES_PER_PUBLISHER; j++) {
                        publisher.addMagazine(new Magazine("magazine-" + i + "-" + j));
                    }
                    publishers.save(publisher);
                }
            }
        });
        seeded = true;
    }
}
