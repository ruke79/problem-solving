package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** {@code cost=시작..총} 에서 총비용을 뽑는다. */
    private static final Pattern TOTAL_COST = Pattern.compile("cost=[0-9.]+\\.\\.([0-9.]+)");

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
        // ANALYZE 만으로는 Index Only Scan 이 나오지 않는다. PostgreSQL 은 인덱스 엔트리만으로
        // 답할 수 있어도 "그 행이 이 트랜잭션에 보이는가"를 알아야 하는데, 그 정보는 힙에 있다.
        // VACUUM 이 visibility map 에 '이 페이지의 행은 전부 보인다'를 기록해야 비로소
        // 힙 접근을 건너뛸 수 있다 — 방금 INSERT 만 한 테이블에서 Index Only Scan 이
        // 안 나오는 이유가 바로 이것이다.
        jdbc.execute("VACUUM (ANALYZE) composite_demo");

        String leading = explain("SELECT count(*) FROM composite_demo WHERE a = 42");
        String both = explain("SELECT count(*) FROM composite_demo WHERE a = 42 AND b = 42");
        String trailingOnly = explain("SELECT count(*) FROM composite_demo WHERE b = 42");
        String covering = explain("SELECT b FROM composite_demo WHERE a = 42");
        String needsHeap = explain("SELECT payload FROM composite_demo WHERE a = 42");

        boolean leadingUsesIndex = usesIndex(leading);
        boolean bothUsesIndex = usesIndex(both);
        double leadingCost = totalCost(leading);
        double trailingCost = totalCost(trailingOnly);
        boolean coveringIsIndexOnly = covering.toLowerCase(Locale.ROOT).contains("index only scan");
        boolean heapIsNotIndexOnly = !needsHeap.toLowerCase(Locale.ROOT).contains("index only scan");

        evidence.fact("행 수 / 인덱스", ROWS + " / (a, b)");
        evidence.fact("선행 컬럼만 (a=42)", leading);
        evidence.fact("두 컬럼 모두 (a=42 AND b=42)", both);
        evidence.fact("후행 컬럼만 (b=42)", trailingOnly);
        evidence.fact("조회 컬럼이 인덱스에 다 있음 (SELECT b WHERE a=42)", covering);
        evidence.fact("인덱스에 없는 컬럼 조회 (SELECT payload WHERE a=42)", needsHeap);

        evidence.fact("선행 컬럼 조회의 계획 비용", String.format("%.2f", leadingCost));
        evidence.fact("후행 컬럼만 조회의 계획 비용", String.format("%.2f", trailingCost));
        evidence.fact("비용 배수(후행 ÷ 선행)", String.format("%.0f배", trailingCost / leadingCost));

        evidence.expect("선행 컬럼 조건은 인덱스를 탄다", leadingUsesIndex);
        evidence.expect("두 컬럼 모두 주면 당연히 인덱스를 탄다", bothUsesIndex);
        evidence.expect("후행 컬럼만으로는 탐색 범위를 좁히지 못해 비용이 10배 이상으로 뛴다",
                trailingCost > leadingCost * 10);
        evidence.expect("조회 컬럼이 인덱스에 다 있고 VACUUM 이 끝났으면 Index Only Scan 이 된다", coveringIsIndexOnly);
        evidence.expect("인덱스에 없는 컬럼을 조회하면 테이블(힙)을 읽어야 한다", heapIsNotIndexOnly);

        jdbc.execute("DROP TABLE IF EXISTS composite_demo");

        evidence.note("이유는 B+Tree 의 정렬 순서다. (a, b) 인덱스는 a 로 먼저 정렬되고 같은 a 안에서 b 로 정렬된다 — a 가 불확정이면 트리를 내려갈 출발점이 없다. 전화번호부를 '이름'으로 못 찾는 것과 같다.");
        evidence.note("주의 — 후행 컬럼만 줘도 PostgreSQL 16 의 EXPLAIN 은 'Index Only Scan ... Index Cond: (b = 42)' 라고 찍는다. 이 랩도 처음에는 'Index Cond 가 있으면 제대로 탄 것'으로 판별했다가 틀렸다. Index Cond 로 나온다고 트리를 좁혀 내려간 것이 아니라, 인덱스 전체를 훑으면서 조건을 인덱스 안에서 확인한 것뿐이다.");
        evidence.note("그래서 '인덱스를 탔는가'는 스캔 노드 이름이나 Index Cond 유무가 아니라 비용·예상 행수·(EXPLAIN ANALYZE 라면) 실제 읽은 블록 수로 판단해야 한다. 위 관측값에서 선행 컬럼은 비용 8 대, 후행 컬럼만은 3700 대로 수백 배 차이가 난다 — 같은 'Index Cond' 인데도 그렇다.");
        evidence.note("그래서 복합 인덱스의 컬럼 순서는 '선택도'가 아니라 '실제 쿼리에서 항상 조건에 들어가는 컬럼이 무엇인가'로 정한다. 멀티테넌트라면 tenant_id 가 사실상 항상 선두다(DB-13).");
        evidence.note("면접에서 잘 안 나오는 실무 함정: Index Only Scan 은 '인덱스만으로 답할 수 있음'만으로는 부족하고 visibility map 이 세팅돼 있어야 한다. 그래서 방금 대량 적재한 테이블은 VACUUM 전까지 Index Only Scan 이 안 나온다. EXPLAIN 에 Heap Fetches 가 크게 찍힌다면 같은 이유다.");
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

    /**
     * 계획의 최상위 노드 총비용({@code cost=시작..총}의 뒤쪽 값)을 뽑는다.
     *
     * <p>'인덱스를 제대로 탔는가'를 노드 이름이나 Index Cond 유무로 판별할 수 없어서(위 메모)
     * 옵티마이저가 매긴 비용을 직접 비교한다.
     */
    private double totalCost(String plan) {
        Matcher matcher = TOTAL_COST.matcher(plan);
        if (!matcher.find()) {
            throw new IllegalStateException("계획에서 비용을 읽지 못했다: " + plan);
        }
        return Double.parseDouble(matcher.group(1));
    }
}
