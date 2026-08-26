package io.webboy.verify.labs.perfbook.ch11;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Database;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.cfg.Configuration;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

/**
 * 11장 — L2 캐시는 엔티티를 담지, 쿼리 결과를 담지 않는다.
 *
 * <p>책 11장의 JPA 절에서 가장 오해가 잦다고 지목되는 부분이다: L2 캐시는 <b>엔티티를 id 로</b>
 * 담는 저장소라, {@code find(id)} 는 캐시를 타지만 <b>JPQL 쿼리는 엔티티가 전부 캐시에 있어도
 * SQL 을 실행한다</b>. 쿼리 결과(어떤 id 들이 조건에 맞는가)를 담으려면 <b>쿼리 캐시를 따로 켜고
 * 쿼리마다 cacheable 표시</b>를 해야 한다.
 *
 * <p>검증은 흉내가 아니라 실제 Hibernate + JCache(Ehcache) 로 하고, 판정은 시간이 아니라
 * <b>Hibernate 통계 카운터</b>(SQL prepare 횟수, L2/쿼리 캐시 히트)로 한다 — 전부 결정적이다.
 */
@Component
public class L2QueryCacheCase extends VerificationCase {

    private static final long BOOK_ID = 7L;

    private final Database database;

    public L2QueryCacheCase(Database database) {
        this.database = database;
    }

    @Override
    public String id() {
        return "PERF-11C";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 11장 — 엔티티가 L2 캐시에 다 있는데 왜 JPQL 은 여전히 SQL 을 실행하나?";
    }

    @Override
    public String claim() {
        return "L2 캐시는 엔티티를 id 로 담는다. find(id) 는 새 세션에서도 SQL 없이 캐시를 탄다. "
                + "그러나 JPQL 쿼리는 '어떤 id 가 조건에 맞는가'를 몰라 엔티티가 전부 캐시에 있어도 "
                + "SQL 을 실행한다 — 쿼리 결과까지 담으려면 쿼리 캐시를 따로 켜고 쿼리마다 표시해야 한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // DB 부재 게이트 (카운터 판정 자체는 결정적이다)
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!database.available()) {
            database.markUnavailable(evidence);
            return;
        }

        try (SessionFactory factory = buildSessionFactory()) {
            Statistics stats = factory.getStatistics();

            // 데이터 준비. 주의: persist 는 커밋 시점에 이미 L2 에 엔티티를 넣는다(쓰기 경유) —
            // 처음 이걸 모르고 재서 "첫 find 는 DB 로 간다"가 REFUTED 났다. 캐시를 비우고 시작한다.
            factory.inTransaction(session -> {
                for (long i = 1; i <= 20; i++) {
                    session.persist(new CachedBook(i, "book-" + i));
                }
            });
            factory.getCache().evictAllRegions();
            stats.clear();

            // ① find(id) — 첫 세션은 DB, 두 번째 세션은 L2
            factory.inSession(session -> session.find(CachedBook.class, BOOK_ID));
            long sqlAfterFirstFind = stats.getPrepareStatementCount();
            factory.inSession(session -> session.find(CachedBook.class, BOOK_ID));
            long sqlAfterSecondFind = stats.getPrepareStatementCount();

            evidence.fact("첫 find 까지 SQL", sqlAfterFirstFind);
            evidence.fact("두 번째 find(새 세션) 까지 SQL", sqlAfterSecondFind);
            evidence.fact("L2 히트", stats.getSecondLevelCacheHitCount());
            evidence.expect("첫 find 는 DB 로 간다", sqlAfterFirstFind >= 1);
            evidence.expect("두 번째 find 는 새 세션인데도 SQL 이 없다 — L2 를 탔다",
                    sqlAfterSecondFind == sqlAfterFirstFind
                            && stats.getSecondLevelCacheHitCount() >= 1);

            // ② JPQL — 엔티티 20개가 전부 L2 에 있어도 쿼리는 SQL 을 실행한다
            factory.inSession(session ->
                    session.createSelectionQuery("from CachedBook", CachedBook.class).getResultList());
            stats.clear();
            factory.inSession(session ->
                    session.createSelectionQuery("from CachedBook", CachedBook.class).getResultList());
            evidence.fact("반복 JPQL 의 쿼리 실행", stats.getQueryExecutionCount());
            evidence.fact("반복 JPQL 의 SQL prepare", stats.getPrepareStatementCount());
            evidence.expect("cacheable 표시 없는 JPQL 은 반복해도 매번 SQL 을 실행한다",
                    stats.getQueryExecutionCount() >= 1 && stats.getPrepareStatementCount() >= 1);
            evidence.expect("이때 쿼리 캐시 히트는 없다", stats.getQueryCacheHitCount() == 0);

            // ③ 같은 JPQL 에 cacheable 표시 — 두 번째 세션은 SQL 없이 결과를 얻는다
            factory.inSession(session ->
                    session.createSelectionQuery("from CachedBook", CachedBook.class)
                            .setCacheable(true).getResultList());
            long putCount = stats.getQueryCachePutCount();
            stats.clear();
            factory.inSession(session ->
                    session.createSelectionQuery("from CachedBook", CachedBook.class)
                            .setCacheable(true).getResultList());
            evidence.fact("쿼리 캐시 put (첫 실행)", putCount);
            evidence.fact("두 번째 실행의 쿼리 캐시 히트", stats.getQueryCacheHitCount());
            evidence.fact("두 번째 실행의 SQL prepare", stats.getPrepareStatementCount());
            evidence.expect("첫 cacheable 실행이 쿼리 캐시에 결과를 넣는다", putCount >= 1);
            evidence.expect("두 번째 cacheable 실행은 쿼리 캐시를 탄다", stats.getQueryCacheHitCount() >= 1);
            evidence.expect("그리고 SQL 을 실행하지 않는다", stats.getPrepareStatementCount() == 0);

            evidence.note("덤으로 하나 배웠다 — persist 는 커밋 시점에 이미 L2 에 엔티티를 넣는다. "
                    + "이걸 모르면 '첫 조회는 DB 로 간다'는 상식적 기대부터 틀린다(그래서 검증 전에 "
                    + "evictAllRegions 로 캐시를 비웠다).");
            evidence.note("쿼리 캐시가 담는 것은 id 목록이고 엔티티 본문은 L2 에서 온다 — 그래서 둘은 "
                    + "함께 켜야 의미가 있다. JOIN FETCH 가 L2 를 우회하는 문제는 연관관계가 필요해 "
                    + "이 케이스 범위 밖이다(verify-labs 의 JPA-06 인접 주제). "
                    + "책의 결론: JPA 성능 튜닝은 반드시 L2 캐시의 관여를 함께 고려해야 한다.");
        }
    }

    private SessionFactory buildSessionFactory() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(CachedBook.class);
        configuration.setProperty("hibernate.connection.url", database.url());
        configuration.setProperty("hibernate.connection.username", "verifylab");
        configuration.setProperty("hibernate.connection.password", "verifylab");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.generate_statistics", "true");
        configuration.setProperty("hibernate.cache.use_second_level_cache", "true");
        configuration.setProperty("hibernate.cache.use_query_cache", "true");
        configuration.setProperty("hibernate.cache.region.factory_class", "jcache");
        configuration.setProperty("hibernate.javax.cache.provider",
                "org.ehcache.jsr107.EhcacheCachingProvider");
        configuration.setProperty("hibernate.javax.cache.missing_cache_strategy", "create");
        return configuration.buildSessionFactory();
    }

    /** L2 대상 엔티티 — 캐시 설정이 케이스 안에서 다 보이도록 여기 둔다. */
    @Entity(name = "CachedBook")
    @Table(name = "perfbook_cached_book")
    @Cacheable
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "perfbook-books")
    static class CachedBook {

        @Id
        private Long id;
        private String title;

        protected CachedBook() {
        }

        CachedBook(Long id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}
