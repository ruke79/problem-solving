package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndexScanCase extends VerificationCase {

    private static final int ROWS = 100_000;
    private static final String INDEX_NAME = "IDX_SCAN_DEMO_K";

    private final JdbcTemplate jdbc;

    public IndexScanCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-03";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "인덱스가 있으면 항상 빨라집니까? 실행계획으로 어떻게 확인합니까?";
    }

    @Override
    public String claim() {
        return "선택도가 높은 조건에서는 인덱스가 풀스캔을 이긴다. 확인 방법은 EXPLAIN 으로 접근 경로를 보는 것이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS scan_demo");
        jdbc.execute("CREATE TABLE scan_demo (id INT PRIMARY KEY, k INT, payload VARCHAR(60))");
        jdbc.update("INSERT INTO scan_demo SELECT g, g % " + ROWS + ", 'payload' FROM generate_series(1, " + ROWS + ") AS g");
        jdbc.execute("ANALYZE scan_demo");

        String selectiveQuery = "SELECT count(*) FROM scan_demo WHERE k = 42";

        String planBefore = explain(selectiveQuery);
        long microsBefore = timeOf(selectiveQuery);

        jdbc.execute("CREATE INDEX " + INDEX_NAME + " ON scan_demo (k)");
        jdbc.execute("ANALYZE scan_demo");

        String planAfter = explain(selectiveQuery);
        long microsAfter = timeOf(selectiveQuery);

        String planLowSelectivity = explain("SELECT count(*) FROM scan_demo WHERE k >= 0");

        evidence.fact("행 수", ROWS);
        evidence.fact("인덱스 전 실행계획", planBefore);
        evidence.fact("인덱스 후 실행계획", planAfter);
        evidence.fact("낮은 선택도(k >= 0) 실행계획", planLowSelectivity);
        evidence.fact("인덱스 전 소요(us)", microsBefore);
        evidence.fact("인덱스 후 소요(us)", microsAfter);

        evidence.expect("인덱스 생성 전후로 실행계획이 달라진다", !planBefore.equals(planAfter));
        evidence.expectFlaky("높은 선택도 조건에서 인덱스가 선택된다",
                planAfter.toUpperCase().contains(INDEX_NAME));
        evidence.fact("인덱스 적용 전/후 배수",
                microsAfter == 0 ? "측정 불가" : String.format("%.1f배", (double) microsBefore / microsAfter));
        // `after <= before` 로 두면 둘 다 0 이어도 통과한다 — 근거 없이 CONFIRMED 가 찍힌다.
        // 밀리초로 자르던 시절에는 실제로 0 대 0 이 될 수 있었다. 마이크로초로 재고 여유를 요구한다.
        evidence.expectFlaky("인덱스 적용 후가 3배 이상 빠르다", microsAfter * 3 < microsBefore);
        evidence.expectFlaky("낮은 선택도에서는 옵티마이저가 인덱스를 쓰지 않는다",
                !planLowSelectivity.toUpperCase().contains(INDEX_NAME));

        jdbc.execute("DROP TABLE IF EXISTS scan_demo");

        evidence.note("전환점(인덱스 → 풀스캔)은 선택도가 아니라 '읽어야 하는 페이지 비율'이 결정한다 — 클러스터링(correlation)의 함수다.");
        evidence.note("PostgreSQL 실물이므로 EXPLAIN 문자열(Seq Scan / Index Scan / Index Only Scan)과 비용 추정치를 그대로 읽을 수 있다. "
                + "다만 통계가 최신이어야 하므로 대량 INSERT 뒤에는 ANALYZE 가 선행돼야 한다 — 이 케이스도 매번 ANALYZE 한 뒤 측정한다.");
        evidence.note("count(*) 는 인덱스만으로 답이 나와 Index Only Scan 이 잡히지만, visibility map 이 갱신되기 전이면 힙 페치가 섞인다.");
        evidence.note("인덱스는 공짜가 아니다 — DML 마다 인덱스도 갱신되고 HOT update 최적화가 깨진다.");
    }

    /** PostgreSQL 의 EXPLAIN 은 계획 트리를 여러 행으로 돌려주므로 한 줄로 합쳐서 본다. */
    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
        return String.join(" / ", lines).replaceAll("\\s+", " ").trim();
    }

    private long timeOf(String sql) {
        long began = System.nanoTime();
        jdbc.queryForObject(sql, Long.class);
        return (System.nanoTime() - began) / 1_000L;
    }
}
