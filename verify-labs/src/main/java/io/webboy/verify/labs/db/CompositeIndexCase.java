package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Q5 · Q189 — 복합 인덱스 {@code (A, B)} 는 선행 컬럼 A 가 조건에 있어야 탄다.
 *
 * <p>원고는 이 명제를 두 번(Q5 인덱스 원리, Q189 단답) 말하고 서로를 참조한다.
 * 이유는 B+Tree 가 <b>A 로 먼저 정렬</b>돼 있어서, A 가 불확정이면 트리를 내려갈 출발점이 없기 때문이다.
 * 여기에 커버링 인덱스(Index Only Scan)까지 한 번에 관측한다.
 */
@Component
public class CompositeIndexCase extends VerificationCase {

    private static final int ROWS = 200_000;

    private final JdbcTemplate jdbc;

    public CompositeIndexCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-14";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "복합 인덱스 (A, B) 를 만들었는데 B 만 조건에 넣으면 어떻게 됩니까?";
    }

    @Override
    public String claim() {
        return "복합 인덱스는 선행 컬럼이 조건에 있어야 제대로 탄다. A 로 조회하면 인덱스를 타지만 B 만으로 조회하면 인덱스 스캔이 아니거나 비효율적인 경로가 되고, 조회 컬럼이 인덱스에 다 들어 있으면 테이블을 안 읽는 Index Only Scan 이 된다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 계획 선택은 통계와 비용 모델에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS composite_demo");
        jdbc.execute("CREATE TABLE composite_demo (a int, b int, payload text)");
        jdbc.update("INSERT INTO composite_demo SELECT g % 1000, g % 997, 'payload-' || g "
                + "FROM generate_series(1, " + ROWS + ") g");
        jdbc.execute("CREATE INDEX idx_composite_ab ON composite_demo (a, b)");
        jdbc.execute("ANALYZE composite_demo");

        String leading = explain("SELECT count(*) FROM composite_demo WHERE a = 42");
        String both = explain("SELECT count(*) FROM composite_demo WHERE a = 42 AND b = 42");
        String trailingOnly = explain("SELECT count(*) FROM composite_demo WHERE b = 42");
        String covering = explain("SELECT b FROM composite_demo WHERE a = 42");
        String needsHeap = explain("SELECT payload FROM composite_demo WHERE a = 42");

        boolean leadingUsesIndex = usesIndex(leading);
        boolean bothUsesIndex = usesIndex(both);
        boolean trailingUsesIndexScan = usesIndexScan(trailingOnly);
        boolean coveringIsIndexOnly = covering.toLowerCase(Locale.ROOT).contains("index only scan");
        boolean heapIsNotIndexOnly = !needsHeap.toLowerCase(Locale.ROOT).contains("index only scan");

        evidence.fact("행 수 / 인덱스", ROWS + " / (a, b)");
        evidence.fact("선행 컬럼만 (a=42)", leading);
        evidence.fact("두 컬럼 모두 (a=42 AND b=42)", both);
        evidence.fact("후행 컬럼만 (b=42)", trailingOnly);
        evidence.fact("조회 컬럼이 인덱스에 다 있음 (SELECT b WHERE a=42)", covering);
        evidence.fact("인덱스에 없는 컬럼 조회 (SELECT payload WHERE a=42)", needsHeap);

        evidence.expect("선행 컬럼 조건은 인덱스를 탄다", leadingUsesIndex);
        evidence.expect("두 컬럼 모두 주면 당연히 인덱스를 탄다", bothUsesIndex);
        evidence.expect("후행 컬럼만으로는 일반 Index Scan 이 되지 않는다", !trailingUsesIndexScan);
        evidence.expectFlaky("조회 컬럼이 인덱스에 다 있으면 Index Only Scan 이 된다", coveringIsIndexOnly);
        evidence.expectFlaky("인덱스에 없는 컬럼을 조회하면 테이블(힙)을 읽어야 한다", heapIsNotIndexOnly);

        jdbc.execute("DROP TABLE IF EXISTS composite_demo");

        evidence.note("이유는 B+Tree 의 정렬 순서다. (a, b) 인덱스는 a 로 먼저 정렬되고 같은 a 안에서 b 로 정렬된다 — a 가 불확정이면 트리를 내려갈 출발점이 없다. 전화번호부를 '이름'으로 못 찾는 것과 같다.");
        evidence.note("PostgreSQL 은 후행 컬럼만 있어도 인덱스 전체를 훑는 Index Only Scan(사실상 인덱스 풀스캔)을 고를 때가 있다. '인덱스를 탔다'는 표현에 속지 말고 Index Cond 인지 Filter 인지를 봐야 한다.");
        evidence.note("그래서 복합 인덱스의 컬럼 순서는 '선택도'가 아니라 '실제 쿼리에서 항상 조건에 들어가는 컬럼이 무엇인가'로 정한다. 멀티테넌트라면 tenant_id 가 사실상 항상 선두다(DB-13).");
        evidence.note("커버링 인덱스는 힙 접근을 없애 빠르지만, 인덱스가 넓어져 쓰기 비용과 캐시 점유가 늘어난다. PostgreSQL 은 INCLUDE 절로 키가 아닌 컬럼만 얹을 수도 있다.");
    }

    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
        return String.join(" / ", lines).replaceAll("\\s+", " ").trim();
    }

    /** 계획에 우리 인덱스 이름이 나오는가. */
    private boolean usesIndex(String plan) {
        return plan.toLowerCase(Locale.ROOT).contains("idx_composite_ab");
    }

    /** Index Cond 로 트리를 타고 내려간 '진짜' 인덱스 스캔인가 (인덱스 전체 훑기는 제외). */
    private boolean usesIndexScan(String plan) {
        String lower = plan.toLowerCase(Locale.ROOT);
        return lower.contains("idx_composite_ab") && lower.contains("index cond");
    }
}
