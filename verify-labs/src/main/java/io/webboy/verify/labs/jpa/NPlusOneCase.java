package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NPlusOneCase extends VerificationCase {

    private final AuthorRepository authors;
    private final SeedService seed;
    private final HibernateStats stats;
    private final TransactionTemplate tx;

    public NPlusOneCase(AuthorRepository authors, SeedService seed, HibernateStats stats, TransactionTemplate tx) {
        this.authors = authors;
        this.seed = seed;
        this.stats = stats;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-01";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "N+1 문제가 무엇이고 실제로 쿼리가 몇 번 나가는지 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "지연 로딩 컬렉션을 순회하면 1(부모) + N(자식) 번의 쿼리가 나가고, fetch join 이면 1번으로 줄어든다";
    }

    @Override
    protected void verify(Evidence evidence) {
        seed.ensureSeeded();

        long naive = stats.countStatements(() -> tx.executeWithoutResult(status ->
                authors.findAll().forEach(a -> a.getBooks().size())));

        long fetchJoin = stats.countStatements(() -> tx.executeWithoutResult(status ->
                authors.findAllWithBooks().forEach(a -> a.getBooks().size())));

        long expectedNaive = 1L + SeedService.AUTHORS;

        evidence.fact("작가 수 (N)", SeedService.AUTHORS);
        evidence.fact("findAll() + 컬렉션 접근 시 SQL 수", naive);
        evidence.fact("fetch join 시 SQL 수", fetchJoin);

        evidence.expectEquals("지연 로딩은 1 + N 번의 SQL 을 발생시킨다", expectedNaive, naive);
        evidence.expectEquals("fetch join 은 1번으로 줄인다", 1L, fetchJoin);
        evidence.expect("fetch join 이 항상 더 적다", fetchJoin < naive);

        evidence.note("컬렉션 fetch join 은 페이징과 함께 쓰면 Hibernate 가 전체를 메모리로 올린다(HHH90003004) — 그때는 @BatchSize 가 정답이다.");
    }
}
