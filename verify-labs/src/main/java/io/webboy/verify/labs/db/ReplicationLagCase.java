package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Q157 (Q65 의 실무 부작용) — "글을 올렸는데 목록에 안 나온다".
 *
 * <p>레플리카는 비동기로 따라오므로 쓰기 직후 레플리카를 읽으면 옛 값이 보인다.
 * 해결책으로 흔히 쓰는 '세션 플래그 + 일정 시간 프라이머리 읽기'보다 정확한 방법이
 * <b>LSN 비교</b>다 — 내가 쓴 위치까지 레플리카가 따라왔는지 직접 확인한다.
 *
 * <p>레플리카가 없으면 판정하지 않고 INCONCLUSIVE 로 남긴다(compose.yaml 의 postgres-replica).
 */
@Component
public class ReplicationLagCase extends VerificationCase {

    /** 재생 지연(recovery_min_apply_delay)이 걸려 있어도 넉넉히 따라잡을 만큼 준다. */
    private static final long REPLAY_TIMEOUT_MILLIS = 30_000;

    private final JdbcTemplate primary;
    private final String replicaUrl;
    private final String username;
    private final String password;

    public ReplicationLagCase(
            JdbcTemplate primary,
            @Value("${verify.replica.url:jdbc:postgresql://localhost:5433/verifylab}") String replicaUrl,
            @Value("${spring.datasource.username:verifylab}") String username,
            @Value("${spring.datasource.password:verifylab}") String password) {
        this.primary = primary;
        this.replicaUrl = replicaUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public String id() {
        return "DB-24";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "레플리카를 붙였더니 사용자가 방금 쓴 데이터를 못 읽는 문제가 생겼습니다.";
    }

    @Override
    public String claim() {
        return "복제는 비동기라 쓰기 직후 레플리카를 읽으면 옛 값이 보인다(read-your-writes 위반). 시간 기반 추측 대신 쓰기 시점의 LSN 을 들고 다니며 레플리카가 거기까지 따라왔는지 확인하면, 따라온 경우에만 레플리카로 보내 프라이머리 부하를 최소화할 수 있다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 복제 지연은 환경에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!replicaAvailable()) {
            evidence.fact("레플리카 URL", replicaUrl);
            evidence.expectFlaky("검증에는 스트리밍 레플리카가 필요하다 — 접속되지 않는다", false);
            evidence.note("`docker compose up -d postgres-replica` 로 띄우면 검증된다. 다른 레플리카를 쓰려면 verify.replica.url 로 지정한다.");
            return;
        }

        primary.execute("DROP TABLE IF EXISTS replica_demo");
        primary.execute("CREATE TABLE replica_demo (id int PRIMARY KEY, note text)");
        String ddlLsn = primary.queryForObject("SELECT pg_current_wal_insert_lsn()::text", String.class);

        try (Connection replica = DriverManager.getConnection(replicaUrl, username, password)) {
            // DDL 이 레플리카에 반영될 때까지 — 시간을 추측하지 않고 LSN 으로 기다린다
            waitUntilReplayed(replica, ddlLsn, REPLAY_TIMEOUT_MILLIS);

            boolean readOnly = queryBoolean(replica, "SELECT pg_is_in_recovery()");
            String writeAttempt = tryWriteOnReplica(replica);
            // 레플리카에 인위적 재생 지연이 걸려 있는가(compose.yaml 의 recovery_min_apply_delay)
            String applyDelay = queryString(replica, "SHOW recovery_min_apply_delay");
            boolean delayConfigured = !"0".equals(applyDelay.trim());

            // 쓰기 직후 곧바로 레플리카를 읽는다
            primary.update("INSERT INTO replica_demo VALUES (1, 'just-written')");
            String writeLsn = primary.queryForObject("SELECT pg_current_wal_insert_lsn()::text", String.class);
            long immediately = countOnReplica(replica);

            // LSN 이 따라왔는지 확인한 뒤 읽는다
            boolean caughtUp = waitUntilReplayed(replica, writeLsn, REPLAY_TIMEOUT_MILLIS);
            long afterCatchUp = countOnReplica(replica);
            String lag = primary.queryForObject(
                    "SELECT coalesce(max(pg_wal_lsn_diff(sent_lsn, replay_lsn)), 0)::text FROM pg_stat_replication",
                    String.class);

            evidence.fact("레플리카가 복구 모드인가(읽기 전용)", readOnly);
            evidence.fact("레플리카에 쓰기를 시도한 결과", writeAttempt);
            evidence.fact("레플리카의 recovery_min_apply_delay", applyDelay);
            evidence.fact("쓰기 직후 레플리카에서 읽은 행 수", immediately);
            evidence.fact("쓰기 시점 LSN", writeLsn);
            evidence.fact("LSN 까지 따라온 뒤 레플리카에서 읽은 행 수", afterCatchUp);
            evidence.fact("현재 복제 지연(bytes)", lag);

            evidence.expect("레플리카는 읽기 전용이다", readOnly);
            evidence.expect("레플리카에는 쓸 수 없다", writeAttempt.contains("SQLState"));
            evidence.expect("LSN 확인 후에는 자기가 쓴 데이터를 읽을 수 있다", caughtUp && afterCatchUp == 1);

            // 재생 지연이 설정돼 있으면 '쓰기 직후엔 안 보인다'가 결정적으로 관측된다.
            // 설정이 없으면(같은 호스트라 수 ms 안에 따라잡으면) 관측 창이 안 열릴 수 있으므로
            // 판정을 단정하지 않고 INCONCLUSIVE 로 남긴다.
            String staleRead = "쓰기 직후 즉시 읽으면 아직 안 보인다(read-your-writes 위반)";
            if (delayConfigured) {
                evidence.expect(staleRead + " — 재생 지연 " + applyDelay + " 로 관측", immediately == 0);
            } else {
                evidence.expectFlaky(staleRead, immediately == 0);
            }
        } finally {
            primary.execute("DROP TABLE IF EXISTS replica_demo");
        }

        evidence.note("이 랩은 레플리카에 recovery_min_apply_delay=2s 를 걸어 WAL 재생을 실제로 늦춘다. 프라이머리와 레플리카가 같은 도커 호스트에 있으면 복제가 수 ms 안에 따라잡아 '옛 값이 보인다'는 창 자체가 열리지 않기 때문이다. 결과를 만든 것이 아니라, 실무에서 불규칙하게 나타나는 지연을 관측 가능한 크기로 고정한 것이다 — 지연이 걸리지 않은 환경에서는 이 항목을 단정하지 않고 INCONCLUSIVE 로 남긴다.");
        evidence.note("세션 플래그로 'N초간 프라이머리 읽기'를 하는 방식이 흔하지만, N 을 실측 지연의 p99 보다 크게 잡아야 하고 그만큼 프라이머리 부하가 늘어난다. LSN 비교는 정확한 대신 구현이 더 든다.");
        evidence.note("모든 읽기를 대상으로 할 필요는 없다. '방금 쓴 것을 곧바로 읽는 화면'(작성 완료, 마이페이지)에만 적용하면 프라이머리 부하 증가를 최소화할 수 있다.");
        evidence.note("동기 복제(synchronous_commit=on + synchronous_standby_names)로 지연을 0 으로 만들 수도 있지만, 그러면 쓰기 지연이 레플리카 왕복만큼 늘고 레플리카가 죽으면 쓰기가 멈춘다 — 가용성과 일관성의 교환이다.");
        evidence.note("이 현상은 MSA-02 의 결과적 일관성과 같은 문제다. 기술적으로는 정상 동작인데 사용자에게는 버그로 보인다는 점이 핵심이다.");
    }

    private boolean replicaAvailable() {
        try (Connection connection = DriverManager.getConnection(replicaUrl, username, password)) {
            return queryBoolean(connection, "SELECT true");
        } catch (Exception e) {
            return false;
        }
    }

    private String tryWriteOnReplica(Connection replica) {
        try (var statement = replica.createStatement()) {
            statement.execute("INSERT INTO replica_demo VALUES (99, 'nope')");
            return "쓰기 성공(예상 밖)";
        } catch (SQLException e) {
            return e.getClass().getSimpleName() + " (SQLState=" + e.getSQLState() + ")";
        }
    }

    private long countOnReplica(Connection replica) throws SQLException {
        try (var rs = replica.createStatement().executeQuery("SELECT count(*) FROM replica_demo")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 레플리카가 특정 LSN 까지 재생했는지 확인한다 — 시간 추측이 아니라 위치 비교다. */
    private boolean waitUntilReplayed(Connection replica, String targetLsn, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (var rs = replica.createStatement().executeQuery(
                    "SELECT pg_last_wal_replay_lsn() >= '" + targetLsn + "'::pg_lsn")) {
                rs.next();
                if (rs.getBoolean(1)) {
                    return true;
                }
            }
            Thread.sleep(50);
        }
        return false;
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (var rs = connection.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (var rs = connection.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
