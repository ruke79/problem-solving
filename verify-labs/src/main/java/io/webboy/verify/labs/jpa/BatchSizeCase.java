package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class BatchSizeCase extends VerificationCase {

    private static final int BATCH_SIZE = 10;

    private final AuthorRepository authors;
    private final PublisherRepository publishers;
    private final SeedService seed;
    private final HibernateStats stats;
    private final TransactionTemplate tx;

    public BatchSizeCase(AuthorRepository authors, PublisherRepository publishers, SeedService seed,
                         HibernateStats stats, TransactionTemplate tx) {
        this.authors = authors;
        this.publishers = publishers;
        this.seed = seed;
        this.stats = stats;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-05";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "페이징이 필요해서 fetch join 을 못 쓸 때 N+1 을 어떻게 줄입니까?";
    }

    @Override
    public String claim() {
        return "@BatchSize(default_batch_fetch_size)를 쓰면 자식 조회가 IN 절로 묶여 1+N 이 1+ceil(N/batch) 로 줄어든다";
    }

    @Override
    protected void verify(Evidence evidence) {
        seed.ensureSeeded();

        long withoutBatch = stats.countStatements(() -> tx.executeWithoutResult(status ->
                authors.findAll().forEach(a -> a.getBooks().size())));

        long withBatch = stats.countStatements(() -> tx.executeWithoutResult(status ->
                publishers.findAll().forEach(p -> p.getMagazines().size())));

        long naiveForPublishers = 1L + SeedService.PUBLISHERS;
        long expectedBatched = 1L + (long) Math.ceil((double) SeedService.PUBLISHERS / BATCH_SIZE);

        evidence.fact("BatchSize 없음 — 부모 " + SeedService.AUTHORS + "건 SQL 수", withoutBatch);
        evidence.fact("BatchSize " + BATCH_SIZE + " — 부모 " + SeedService.PUBLISHERS + "건 SQL 수", withBatch);
        evidence.fact("BatchSize 없었다면 예상 SQL 수", naiveForPublishers);
        evidence.fact("이론적 기대값 1+ceil(N/batch)", expectedBatched);

        evidence.expect("배치 페치는 N+1 보다 SQL 수가 훨씬 적다", withBatch < naiveForPublishers);
        evidence.expectEqualsFlaky("SQL 수가 이론값과 일치한다", expectedBatched, withBatch);

        evidence.note("전역 적용은 spring.jpa.properties.hibernate.default_batch_fetch_size 로 한다.");
        evidence.note("이 랩은 전역 설정을 켜지 않았다 — 켜면 JPA-01 의 N+1 재현이 사라진다.");
    }
}
