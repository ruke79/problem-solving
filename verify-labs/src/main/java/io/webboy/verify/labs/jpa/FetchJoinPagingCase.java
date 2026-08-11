package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;

/** Q99 — 1:N 컬렉션에 fetch join 을 걸고 페이징하면 DB 가 아니라 메모리에서 자른다. */
@Component
public class FetchJoinPagingCase extends VerificationCase {

    private static final int PAGE_SIZE = 2;

    private final SeedService seed;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public FetchJoinPagingCase(SeedService seed, TransactionTemplate tx) {
        this.seed = seed;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-06";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "1대다 컬렉션에 fetch join 을 쓰면서 페이징하면 어떤 일이 일어납니까?";
    }

    @Override
    public String claim() {
        return "컬렉션 fetch join + 페이징은 SQL 에 limit 이 붙지 않고 전건을 읽은 뒤 메모리에서 자른다 (대량 데이터에서 OOM 위험)";
    }

    @Override
    protected void verify(Evidence evidence) {
        seed.ensureSeeded();

        List<String> fetchJoinSql = capture(() ->
                em.createQuery("select distinct a from Author a left join fetch a.books", Author.class)
                        .setMaxResults(PAGE_SIZE)
                        .getResultList()
                        .size());

        List<String> plainSql = capture(() ->
                em.createQuery("select a from Author a", Author.class)
                        .setMaxResults(PAGE_SIZE)
                        .getResultList()
                        .size());

        boolean fetchJoinHasLimit = containsLimit(fetchJoinSql);
        boolean plainHasLimit = containsLimit(plainSql);

        evidence.fact("페이지 크기", PAGE_SIZE);
        evidence.fact("전체 작가 수", SeedService.AUTHORS);
        evidence.fact("fetch join + setMaxResults SQL", first(fetchJoinSql));
        evidence.fact("fetch join SQL 에 limit 절이 있는가", fetchJoinHasLimit);
        evidence.fact("일반 조회 + setMaxResults SQL", first(plainSql));
        evidence.fact("일반 조회 SQL 에 limit 절이 있는가", plainHasLimit);

        evidence.expect("일반 조회는 DB 에서 limit 으로 자른다", plainHasLimit);
        evidence.expect("컬렉션 fetch join 은 SQL 에 limit 이 붙지 않는다(메모리 페이징)", !fetchJoinHasLimit);

        evidence.note("Hibernate 는 이 상황에서 HHH90003004 경고를 남기고 전건을 메모리로 올린다. 경고를 무시하면 운영에서 OOM 이 된다.");
        evidence.note("1:1·N:1 단순 연관에는 fetch join 이 안전하고, 1:N 컬렉션 + 페이징에는 @BatchSize(JPA-05)가 정답이다.");
    }

    private List<String> capture(Runnable work) {
        CapturingStatementInspector.start();
        try {
            tx.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            CapturingStatementInspector.stop();
            throw e;
        }
        return CapturingStatementInspector.stop();
    }

    private boolean containsLimit(List<String> statements) {
        return statements.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(s -> s.contains("limit") || s.contains("fetch first") || s.contains("fetch next"));
    }

    private String first(List<String> statements) {
        return statements.isEmpty() ? "(수집된 SQL 없음)" : statements.get(0);
    }
}
