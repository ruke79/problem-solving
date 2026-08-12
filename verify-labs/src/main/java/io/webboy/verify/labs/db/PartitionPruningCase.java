package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Q47 — 파티셔닝 전략. PostgreSQL 16 이 랩에 들어오면서 비로소 실행 검증이 가능해진 질문이다.
 *
 * <p>확인하는 것은 두 가지다.
 * <ul>
 *   <li>파티션 키가 조건에 있으면 옵티마이저가 나머지 파티션을 아예 읽지 않는다(pruning)</li>
 *   <li>오래된 데이터 제거는 DELETE 가 아니라 DROP PARTITION 이어야 한다 — 비용이 다르다</li>
 * </ul>
 */
@Component
public class PartitionPruningCase extends VerificationCase {

    private static final int ROWS = 60_000;

    private final JdbcTemplate jdbc;

    public PartitionPruningCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-08";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "대용량 테이블에 파티셔닝을 어떻게 적용합니까? 효과는 어디서 나옵니까?";
    }

    @Override
    public String claim() {
        return "파티션 키가 조건에 있으면 옵티마이저가 해당 파티션만 읽는다(프루닝). 키가 빠지면 전 파티션을 훑으므로 이득이 사라지고, 오래된 데이터 제거는 DELETE 가 아니라 DROP PARTITION 이 정답이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 실행계획 선택과 소요 시간은 통계·장비에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) {
        create();

        String pruned = explain("SELECT count(*) FROM part_demo WHERE created >= DATE '2025-01-01'");
        String allPartitions = explain("SELECT count(*) FROM part_demo WHERE payload = 'x'");

        int prunedPartitions = countPartitionsInPlan(pruned);
        int scannedPartitions = countPartitionsInPlan(allPartitions);

        long rowsIn2024 = jdbc.queryForObject("SELECT count(*) FROM part_2024", Long.class);

        // 오래된 데이터 제거 — 같은 양을 지우는 두 방법의 비용 차이
        long dropMillis = timed(() -> jdbc.execute("DROP TABLE part_2024"));
        long deleteMillis = timed(() -> jdbc.update("DELETE FROM part_flat WHERE created < DATE '2025-01-01'"));

        // 통계는 백그라운드로 반영되므로 강제로 밀어낸 뒤 읽는다 (PostgreSQL 15+)
        jdbc.queryForList("SELECT pg_stat_force_next_flush()::text", String.class);
        long deadTuples = jdbc.queryForObject(
                "SELECT coalesce(n_dead_tup, 0) FROM pg_stat_user_tables WHERE relname = 'part_flat'", Long.class);

        evidence.fact("전체 행 수 / 파티션 수", ROWS + " / 2");
        evidence.fact("파티션 키 조건이 있는 조회의 계획", pruned);
        evidence.fact("파티션 키 조건이 없는 조회의 계획", allPartitions);
        evidence.fact("키가 있을 때 읽은 파티션 수", prunedPartitions);
        evidence.fact("키가 없을 때 읽은 파티션 수", scannedPartitions);
        evidence.fact("제거 대상 행 수(2024년)", rowsIn2024);
        evidence.fact("DROP PARTITION 소요(ms)", dropMillis);
        evidence.fact("같은 양을 DELETE 했을 때 소요(ms)", deleteMillis);
        evidence.fact("DELETE 후 남은 dead tuple 수", deadTuples);

        evidence.expectEquals("파티션 키가 조건에 있으면 한 파티션만 읽는다", 1, prunedPartitions);
        evidence.expect("파티션 키가 빠지면 전 파티션을 읽는다", scannedPartitions >= 2);
        evidence.expectFlaky("DROP PARTITION 이 같은 양의 DELETE 보다 빠르다", dropMillis <= deleteMillis);
        // 통계 반영 시점에 좌우되므로 flaky. DROP PARTITION 쪽은 테이블 자체가 사라져 dead tuple 개념이 없다.
        evidence.expectFlaky("DELETE 는 dead tuple 을 남기지만 DROP PARTITION 은 남기지 않는다", deadTuples > 0);

        cleanup();

        evidence.note("프루닝은 '파티션 키가 WHERE 에 있을 때만' 작동한다. 조회 패턴이 파티션 키를 안 쓰면 파티셔닝은 관리 편의만 남고 성능 이득이 없다 — 그래서 키 선정이 전부다.");
        evidence.note("파티셔닝의 진짜 값어치는 조회 성능보다 '오래된 데이터를 초 단위로 버릴 수 있다'는 운영성이다. DELETE 는 dead tuple 과 VACUUM 부담을 남긴다.");
        evidence.note("파티션마다 인덱스가 따로 생기므로 전역 유니크 제약을 걸려면 파티션 키가 제약에 포함돼야 한다 — 설계 초기에 걸리는 제약이다.");
    }

    private void create() {
        cleanup();
        jdbc.execute("CREATE TABLE part_demo (id int, created date, payload text) PARTITION BY RANGE (created)");
        jdbc.execute("CREATE TABLE part_2024 PARTITION OF part_demo FOR VALUES FROM ('2024-01-01') TO ('2025-01-01')");
        jdbc.execute("CREATE TABLE part_2025 PARTITION OF part_demo FOR VALUES FROM ('2025-01-01') TO ('2026-01-01')");
        jdbc.update("INSERT INTO part_demo SELECT g, DATE '2024-06-01' + (g % 500), 'x' FROM generate_series(1, " + ROWS + ") g");
        jdbc.execute("ANALYZE part_demo");

        // 비교군: 같은 데이터를 파티션 없이 한 테이블에 담은 것
        jdbc.execute("CREATE TABLE part_flat (id int, created date, payload text)");
        jdbc.update("INSERT INTO part_flat SELECT g, DATE '2024-06-01' + (g % 500), 'x' FROM generate_series(1, " + ROWS + ") g");
        jdbc.execute("ANALYZE part_flat");
    }

    private void cleanup() {
        jdbc.execute("DROP TABLE IF EXISTS part_demo CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS part_2024");
        jdbc.execute("DROP TABLE IF EXISTS part_2025");
        jdbc.execute("DROP TABLE IF EXISTS part_flat");
    }

    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
        return String.join(" / ", lines).replaceAll("\\s+", " ").trim();
    }

    /** 계획 문자열에 등장한 파티션 테이블(part_2024 / part_2025)의 종류 수. */
    private int countPartitionsInPlan(String plan) {
        String lower = plan.toLowerCase(Locale.ROOT);
        int count = 0;
        if (lower.contains("part_2024")) {
            count++;
        }
        if (lower.contains("part_2025")) {
            count++;
        }
        return count;
    }

    private long timed(Runnable work) {
        long began = System.nanoTime();
        work.run();
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
