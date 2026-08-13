package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Q158 — "LIKE 검색은 인덱스가 안 들어 Elasticsearch 가 필요하다"는 말의 정확한 경계.
 *
 * <p>정확히는 <b>전방 일치는 인덱스를 타고 중간 일치가 못 탄다.</b> 이유는 B+Tree 가 값의
 * 앞부분부터 정렬돼 있기 때문이고(DB-14 와 같은 원리), PostgreSQL 은 그 한계를 GIN 인덱스로 넘는다.
 * ES 도입 판단은 "RDBMS 안에서 못 푸는가"를 확인한 뒤에 하는 것이 순서다.
 */
@Component
public class TextSearchCase extends VerificationCase {

    private static final int ROWS = 100_000;

    private final JdbcTemplate jdbc;

    public TextSearchCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-15";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "RDBMS 의 텍스트 검색은 어디서 한계가 옵니까? Elasticsearch 는 언제 넣습니까?";
    }

    @Override
    public String claim() {
        return "B+Tree 인덱스는 값의 앞에서부터 정렬돼 있으므로 LIKE '키워드%'(전방 일치)는 타지만 LIKE '%키워드%'(중간 일치)는 못 타고 전건 스캔이 된다. 다만 PostgreSQL 은 전문 검색(GIN) 인덱스로 그 한계를 넘을 수 있으므로, Elasticsearch 도입은 그것으로도 안 될 때 판단할 일이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS text_demo");
        jdbc.execute("CREATE TABLE text_demo (id serial PRIMARY KEY, title text)");
        // 검색어가 희소해야 옵티마이저가 인덱스를 고를 이유가 생긴다.
        // 전건이 같은 단어를 갖고 있으면 인덱스를 타나 마나이므로 100건 중 1건만 'ergonomic' 을 갖게 한다.
        jdbc.update("INSERT INTO text_demo (title) SELECT 'product ' || g || ' premium '"
                + " || CASE WHEN g % 100 = 0 THEN 'ergonomic' ELSE 'standard' END || ' keyboard' "
                + "FROM generate_series(1, " + ROWS + ") g");
        jdbc.execute("CREATE INDEX idx_text_title ON text_demo (title text_pattern_ops)");
        jdbc.execute("ANALYZE text_demo");

        String prefixPlan = explain("SELECT count(*) FROM text_demo WHERE title LIKE 'product 42%'");
        long prefixMicros = timed("SELECT count(*) FROM text_demo WHERE title LIKE 'product 42%'");

        String infixPlan = explain("SELECT count(*) FROM text_demo WHERE title LIKE '%ergonomic key%'");
        long infixMicros = timed("SELECT count(*) FROM text_demo WHERE title LIKE '%ergonomic key%'");

        // PostgreSQL 의 전문 검색 인덱스 — 형태소 분석은 아니지만 어휘 단위 검색은 인덱스로 처리된다
        jdbc.execute("CREATE INDEX idx_text_fts ON text_demo USING GIN (to_tsvector('simple', title))");
        jdbc.execute("ANALYZE text_demo");
        String ftsPlan = explain("SELECT count(*) FROM text_demo "
                + "WHERE to_tsvector('simple', title) @@ to_tsquery('simple', 'ergonomic')");
        long ftsMicros = timed("SELECT count(*) FROM text_demo "
                + "WHERE to_tsvector('simple', title) @@ to_tsquery('simple', 'ergonomic')");

        boolean prefixUsesIndex = prefixPlan.toLowerCase(Locale.ROOT).contains("idx_text_title");
        boolean infixIsSeqScan = infixPlan.toLowerCase(Locale.ROOT).contains("seq scan");
        boolean ftsUsesIndex = ftsPlan.toLowerCase(Locale.ROOT).contains("idx_text_fts");

        evidence.fact("행 수", ROWS);
        evidence.fact("전방 일치 LIKE 'product 42%' 계획", prefixPlan);
        evidence.fact("전방 일치 소요(us)", prefixMicros);
        evidence.fact("중간 일치 LIKE '%ergonomic key%' 계획", infixPlan);
        evidence.fact("중간 일치 소요(us)", infixMicros);
        evidence.fact("GIN 전문 검색 계획", ftsPlan);
        evidence.fact("GIN 전문 검색 소요(us)", ftsMicros);

        evidence.expect("전방 일치는 B+Tree 인덱스를 탄다", prefixUsesIndex);
        evidence.expect("중간 일치는 인덱스를 못 타고 전건 스캔이 된다", infixIsSeqScan);
        evidence.expect("PostgreSQL 의 GIN 전문 검색 인덱스는 어휘 검색을 인덱스로 처리한다", ftsUsesIndex);
        evidence.fact("중간 일치 / 전방 일치 배수",
                prefixMicros == 0 ? "측정 불가" : String.format("%.1f배", (double) infixMicros / prefixMicros));
        // `>=` 로 두면 둘 다 0 이어도 통과한다. 마이크로초로 재고 여유를 요구한다(실측 약 10배).
        evidence.expectFlaky("전건 스캔이 인덱스 검색보다 3배 이상 느리다", infixMicros > prefixMicros * 3);

        jdbc.execute("DROP TABLE IF EXISTS text_demo");

        evidence.note("전방 일치에 인덱스를 쓰려면 로케일에 따라 text_pattern_ops 연산자 클래스가 필요하다 — 기본 인덱스로는 LIKE 가 안 타는 경우가 있어 '인덱스를 만들었는데 왜 안 타지'의 흔한 원인이다.");
        evidence.note("중간 일치까지 인덱스로 처리하려면 pg_trgm 확장의 GIN/GiST 인덱스를 쓴다. 즉 '중간 일치는 무조건 풀스캔'이 아니라 '기본 B+Tree 로는 불가능'이 정확한 표현이다.");
        evidence.note("GIN 전문 검색은 어휘 단위 매칭이라 형태소 분석·동의어·오타 보정·랭킹은 별개 문제다. 일본어·한국어 형태소 분석이 필요하면 사전이 필요하고, 그 지점이 Elasticsearch 도입을 진지하게 검토할 선이다.");
        evidence.note("ES 를 넣는 순간 '검색 전용 시스템 운영'과 'DB→ES 동기화 파이프라인(DB-10 의 CDC)'이 새로 생긴다. 검색이 프로덕트의 핵심 가치가 아니라면 그 비용이 이득보다 크다.");
    }

    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
        return String.join(" / ", lines).replaceAll("\\s+", " ").trim();
    }

    private long timed(String sql) {
        long began = System.nanoTime();
        jdbc.queryForObject(sql, Long.class);
        return (System.nanoTime() - began) / 1_000L;
    }
}
