package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Q125 — 이벤트 소싱에서 "현재 상태를 안다"는 것의 비용.
 *
 * <p>집계의 현재 상태를 알려면 이론상 그 집계의 모든 이벤트를 처음부터 재생해야 한다.
 * 이벤트가 쌓일수록 재생 비용은 <b>선형으로</b> 늘고, 스냅샷은 그 선을 끊는다.
 */
@Component
public class EventSourcingSnapshotCase extends VerificationCase {

    private static final int EVENTS = 20_000;
    private static final int SNAPSHOT_EVERY = 1_000;

    private final JdbcTemplate jdbc;

    public EventSourcingSnapshotCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-22";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "이벤트 소싱과 CQRS 를 도입할 때 주의할 점은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "이벤트 소싱은 현재 상태를 얻기 위해 이벤트를 전부 재생해야 하므로 이벤트가 쌓일수록 조회 비용이 선형으로 늘어난다. 스냅샷을 주기적으로 남기면 그 이후 이벤트만 재생하면 되므로 비용이 일정하게 유지된다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 시간 측정이 섞인다
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS es_events");
        jdbc.execute("DROP TABLE IF EXISTS es_snapshots");
        jdbc.execute("CREATE TABLE es_events (seq bigserial PRIMARY KEY, aggregate_id int, delta int)");
        jdbc.execute("CREATE TABLE es_snapshots (aggregate_id int, up_to_seq bigint, state int)");
        jdbc.update("INSERT INTO es_events (aggregate_id, delta) SELECT 1, 1 FROM generate_series(1, " + EVENTS + ")");
        jdbc.execute("CREATE INDEX idx_es_events ON es_events (aggregate_id, seq)");
        jdbc.execute("ANALYZE es_events");

        long fullReplayed = countReplayed(0);
        long fullMicros = timed(() -> replay(0));
        int fullState = replay(0);

        // 스냅샷: 마지막 스냅샷 지점까지의 상태를 미리 계산해 둔다
        long snapshotSeq = jdbc.queryForObject(
                "SELECT max(seq) FROM (SELECT seq FROM es_events WHERE aggregate_id = 1 ORDER BY seq "
                        + "LIMIT " + (EVENTS - SNAPSHOT_EVERY) + ") s", Long.class);
        jdbc.update("INSERT INTO es_snapshots VALUES (1, ?, ?)", snapshotSeq, replayUpTo(snapshotSeq));

        long deltaReplayed = countReplayed(snapshotSeq);
        long snapshotMicros = timed(() -> replayFromSnapshot());
        int snapshotState = replayFromSnapshot();

        evidence.fact("이벤트 수 / 스냅샷 주기", EVENTS + " / " + SNAPSHOT_EVERY);
        evidence.fact("[전량 재생] 읽은 이벤트 수", fullReplayed);
        evidence.fact("[전량 재생] 소요(us)", fullMicros);
        evidence.fact("[전량 재생] 복원한 상태", fullState);
        evidence.fact("[스냅샷 + 델타] 읽은 이벤트 수", deltaReplayed);
        evidence.fact("[스냅샷 + 델타] 소요(us)", snapshotMicros);
        evidence.fact("[스냅샷 + 델타] 복원한 상태", snapshotState);

        evidence.expectEquals("두 방식의 복원 결과는 같아야 한다", fullState, snapshotState);
        evidence.expectEquals("스냅샷을 쓰면 스냅샷 이후 이벤트만 읽는다", (long) SNAPSHOT_EVERY, deltaReplayed);
        evidence.expect("읽는 이벤트 수가 한 자릿수 배 이상 줄어든다", deltaReplayed * 10 <= fullReplayed);
        evidence.fact("전량 재생 / 스냅샷 배수",
                snapshotMicros == 0 ? "측정 불가" : String.format("%.1f배", (double) fullMicros / snapshotMicros));
        // `<=` 로 두면 둘 다 0 이어도 통과한다. 마이크로초로 재고 여유를 요구한다(실측 약 2.6배).
        evidence.expectFlaky("재생 시간이 1.5배 이상 줄어든다", snapshotMicros * 3 < fullMicros * 2);

        jdbc.execute("DROP TABLE IF EXISTS es_events");
        jdbc.execute("DROP TABLE IF EXISTS es_snapshots");

        evidence.note("스냅샷 주기는 '재생 비용'과 '스냅샷 저장·관리 비용'의 절충이다. 너무 촘촘하면 스냅샷 자체가 부담이고, 너무 성기면 재생이 다시 길어진다.");
        evidence.note("더 큰 함정은 이벤트 스키마의 진화다. 한 번 저장한 이벤트는 불변인데 요건이 바뀌면 과거 수백만 건이 옛 형식으로 남는다 — 읽는 쪽이 신·구를 모두 이해하도록 버저닝해야 한다(KAFKA-07 의 호환성 규칙과 같은 문제다).");
        evidence.note("CQRS 와 짝을 이루면 조회는 읽기 모델에서 하므로 재생 비용이 조회 경로에서 빠진다. 대신 읽기 모델이 결과적 일관성이 되어 '방금 쓴 것이 안 보이는' 문제가 생긴다(DB-24).");
        evidence.note("이 패턴의 복잡성은 초기 구축이 아니라 시간이 지나 요건이 바뀔 때 드러난다 — 도입 판단에서 그 점을 먼저 말해야 한다.");
    }

    private int replay(long fromSeq) {
        Integer sum = jdbc.queryForObject(
                "SELECT coalesce(sum(delta), 0) FROM es_events WHERE aggregate_id = 1 AND seq > ?",
                Integer.class, fromSeq);
        return sum == null ? 0 : sum;
    }

    private int replayUpTo(long seq) {
        Integer sum = jdbc.queryForObject(
                "SELECT coalesce(sum(delta), 0) FROM es_events WHERE aggregate_id = 1 AND seq <= ?",
                Integer.class, seq);
        return sum == null ? 0 : sum;
    }

    private int replayFromSnapshot() {
        Integer state = jdbc.queryForObject(
                "SELECT s.state + coalesce(sum(e.delta), 0) FROM es_snapshots s "
                        + "LEFT JOIN es_events e ON e.aggregate_id = s.aggregate_id AND e.seq > s.up_to_seq "
                        + "WHERE s.aggregate_id = 1 GROUP BY s.state", Integer.class);
        return state == null ? 0 : state;
    }

    private long countReplayed(long fromSeq) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM es_events WHERE aggregate_id = 1 AND seq > ?", Long.class, fromSeq);
    }

    private long timed(Runnable work) {
        long began = System.nanoTime();
        work.run();
        return (System.nanoTime() - began) / 1_000L;
    }
}
