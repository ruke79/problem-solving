package io.webboy.verify.labs.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Q16 · Q17 — maxLifetime 은 왜 "DB 가 끊는 시간보다 짧아야" 하는가.
 *
 * <p>DB(또는 경로상의 방화벽)가 먼저 끊은 커넥션을 풀이 "아직 살아 있다"고 믿고 계속 쥐고 있으면,
 * 다음에 그것을 빌린 요청이 <b>원인을 알기 어려운 통신 오류</b>로 죽는다. 여기서는 PostgreSQL 의
 * {@code idle_session_timeout} 으로 서버가 먼저 끊는 상황을 만들어 그 오류를 실제로 재현한다.
 */
@Component
public class MaxLifetimeCase extends VerificationCase {

    private static final long SERVER_IDLE_TIMEOUT_MILLIS = 1_500L;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MaxLifetimeCase(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:verifylab}") String username,
            @Value("${spring.datasource.password:verifylab}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public String id() {
        return "DB-17";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "HikariCP 의 maxLifetime 은 DB 설정과 어떻게 연동해야 합니까?";
    }

    @Override
    public String claim() {
        return "DB 나 경로상의 장비가 먼저 끊은 커넥션을 풀이 쥐고 있으면 다음 요청이 원인 불명의 통신 오류로 죽는다. maxLifetime 을 서버 타임아웃보다 짧게 두면 풀이 먼저 버리므로 그 오류가 사라진다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 타이밍에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String rawAfterKill = rawConnectionAfterServerKill();
        Pooled tooLong = attempt(30_000L);    // 서버가 먼저 끊는 설정
        Pooled shortEnough = attempt(700L);   // 풀이 먼저 버리는 설정

        evidence.fact("서버 측 idle_session_timeout(ms)", SERVER_IDLE_TIMEOUT_MILLIS);
        evidence.fact("풀 없이 죽은 커넥션을 그대로 쓴 결과", rawAfterKill);
        evidence.fact("[maxLifetime 30초 = 서버보다 김] 재사용 결과", tooLong.outcome);
        evidence.fact("[maxLifetime 30초] 물리 커넥션이 교체됐는가", tooLong.replaced);
        evidence.fact("[maxLifetime 0.7초 = 서버보다 짧음] 재사용 결과", shortEnough.outcome);
        evidence.fact("[maxLifetime 0.7초] 물리 커넥션이 교체됐는가", shortEnough.replaced);

        evidence.expect("서버가 먼저 끊은 커넥션을 검증 없이 쓰면 요청이 통신 오류로 죽는다",
                !rawAfterKill.equals("정상"));
        evidence.expectEquals("풀은 대여 시 검증으로 그 오류를 애플리케이션에 노출하지 않는다",
                "정상", tooLong.outcome);
        evidence.expect("대신 죽은 커넥션이 조용히 폐기되고 새 물리 커넥션으로 교체된다", tooLong.replaced);
        evidence.expectEquals("maxLifetime 을 짧게 두면 애초에 죽은 커넥션을 갖지 않는다",
                "정상", shortEnough.outcome);

        evidence.note("풀이 '오류를 안 보여 준다'는 것이 '문제가 없다'는 뜻은 아니다. 죽은 커넥션을 매번 새로 만드는 만큼 지연과 부하가 생기고, 대여 시 검증조차 통과해 버리는 미묘한 시점(검증 직후 서버가 끊는 경우)에는 그대로 실패한다.");
        evidence.note("HikariCP 는 maxLifetime 에 수 % 의 편차를 줘서 모든 커넥션이 동시에 폐기되는 것을 피한다 — 그 부분은 맡겨도 된다.");
        evidence.note("실무에서 더 무서운 것은 DB 가 아니라 경로상의 NLB·방화벽이다. DB 설정만 보고 안심하지 말고 '경로에서 가장 짧은 타임아웃'을 기준으로 잡는다.");
        evidence.note("보험으로 keepaliveTime 을 켜면 유휴 커넥션에 주기적으로 가벼운 검증을 보내 경로가 살아 있는 것으로 보이게 한다. 다만 대증요법이고, 근본은 maxLifetime 쪽이다.");
        evidence.note("이 케이스는 서버 측 idle_session_timeout 으로 '먼저 끊는 쪽'을 만들었다. 실제 장애에서는 이 타임아웃이 DB 의 wait_timeout 이거나 LB 의 유휴 타임아웃이다.");
    }

    /** 풀 없이(=검증 없이) 서버가 끊은 커넥션을 그대로 쓰면 애플리케이션이 보는 오류. */
    private String rawConnectionAfterServerKill() throws Exception {
        try (Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.createStatement().execute("SET idle_session_timeout = " + SERVER_IDLE_TIMEOUT_MILLIS);
            connection.createStatement().execute("SELECT 1");
            Thread.sleep(SERVER_IDLE_TIMEOUT_MILLIS + 1_500);   // 서버가 세션을 끊는다
            try {
                connection.createStatement().execute("SELECT 1");
                return "정상";
            } catch (SQLException e) {
                return e.getClass().getSimpleName() + " (SQLState=" + e.getSQLState() + ")";
            }
        }
    }

    /** 커넥션 1개를 빌렸다 반납하고, 서버 타임아웃보다 오래 놀린 뒤 다시 빌려 쓴다. */
    private Pooled attempt(long maxLifetimeMillis) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setMaxLifetime(maxLifetimeMillis);
        config.setConnectionTimeout(3_000);
        config.setPoolName("maxlifetime-" + maxLifetimeMillis);
        // 반납 시 검증을 끄고 '죽은 커넥션을 그대로 빌려주는' 상황을 만든다
        config.setConnectionTestQuery(null);
        config.setInitializationFailTimeout(-1);

        try (HikariDataSource pool = new HikariDataSource(config)) {
            long firstPid;
            try (Connection connection = pool.getConnection()) {
                connection.createStatement().execute(
                        "SET idle_session_timeout = " + SERVER_IDLE_TIMEOUT_MILLIS);
                firstPid = backendPid(connection);
            }

            Thread.sleep(SERVER_IDLE_TIMEOUT_MILLIS + 1_200);   // 서버가 끊을 시간을 준다

            try (Connection reused = pool.getConnection()) {
                long secondPid = backendPid(reused);
                return new Pooled("정상", firstPid != secondPid);
            } catch (SQLException e) {
                return new Pooled(e.getClass().getSimpleName() + " (SQLState=" + e.getSQLState() + ")", true);
            }
        }
    }

    /** 서버 쪽 프로세스 ID — 값이 바뀌면 물리 커넥션이 교체된 것이다. */
    private long backendPid(Connection connection) throws SQLException {
        try (var rs = connection.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private record Pooled(String outcome, boolean replaced) {}
}
