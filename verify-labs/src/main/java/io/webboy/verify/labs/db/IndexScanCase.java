package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
        jdbc.update("INSERT INTO scan_demo SELECT X, MOD(X, " + ROWS + "), 'payload' FROM SYSTEM_RANGE(1, " + ROWS + ")");
        jdbc.execute("ANALYZE");

        String selectiveQuery = "SELECT count(*) FROM scan_demo WHERE k = 42";

        String planBefore = explain(selectiveQuery);
        long millisBefore = timeOf(selectiveQuery);

        jdbc.execute("CREATE INDEX " + INDEX_NAME + " ON scan_demo (k)");
        jdbc.execute("ANALYZE");

        String planAfter = explain(selectiveQuery);
        long millisAfter = timeOf(selectiveQuery);

        String planLowSelectivity = explain("SELECT count(*) FROM scan_demo WHERE k >= 0");

        evidence.fact("행 수", ROWS);
        evidence.fact("인덱스 전 실행계획", planBefore);
        evidence.fact("인덱스 후 실행계획", planAfter);
        evidence.fact("낮은 선택도(k >= 0) 실행계획", planLowSelectivity);
        evidence.fact("인덱스 전 소요(ms)", millisBefore);
        evidence.fact("인덱스 후 소요(ms)", millisAfter);

        evidence.expect("인덱스 생성 전후로 실행계획이 달라진다", !planBefore.equals(planAfter));
        evidence.expectFlaky("높은 선택도 조건에서 인덱스가 선택된다",
                planAfter.toUpperCase().contains(INDEX_NAME));
        evidence.expectFlaky("인덱스 적용 후가 더 빠르다", millisAfter <= millisBefore);
        evidence.expectFlaky("낮은 선택도에서는 옵티마이저가 인덱스를 쓰지 않는다",
                !planLowSelectivity.toUpperCase().contains(INDEX_NAME));

        jdbc.execute("DROP TABLE IF EXISTS scan_demo");

        evidence.note("전환점(인덱스 → 풀스캔)은 선택도가 아니라 '읽어야 하는 페이지 비율'이 결정한다 — 클러스터링(correlation)의 함수다.");
        evidence.note("H2 의 옵티마이저는 PostgreSQL 만큼 정교하지 않다. 실제 전환점 측정은 PostgreSQL 에서 해야 의미가 있다.");
        evidence.note("인덱스는 공짜가 아니다 — DML 마다 인덱스도 갱신되고 HOT update 최적화가 깨진다.");
    }

    private String explain(String sql) {
        String plan = jdbc.queryForObject("EXPLAIN " + sql, String.class);
        return plan == null ? "" : plan.replaceAll("\\s+", " ").trim();
    }

    private long timeOf(String sql) {
        long began = System.nanoTime();
        jdbc.queryForObject(sql, Long.class);
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
