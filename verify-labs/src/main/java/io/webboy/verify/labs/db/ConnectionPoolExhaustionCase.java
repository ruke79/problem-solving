package io.webboy.verify.labs.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ConnectionPoolExhaustionCase extends VerificationCase {

    private static final int POOL_SIZE = 2;
    private static final long CONNECTION_TIMEOUT_MILLIS = 500L;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public ConnectionPoolExhaustionCase(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:sa}") String username,
            @Value("${spring.datasource.password:}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public String id() {
        return "DB-02";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "커넥션 풀이 고갈되면 애플리케이션은 어떻게 동작합니까?";
    }

    @Override
    public String claim() {
        return "대기하다가 connectionTimeout 이 지나면 SQLTransientConnectionException 으로 실패한다 — 무한 대기가 아니다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setPoolName("exhaustion-demo");
        config.setInitializationFailTimeout(-1);

        List<Connection> held = new ArrayList<>();
        try (HikariDataSource pool = new HikariDataSource(config)) {
            for (int i = 0; i < POOL_SIZE; i++) {
                held.add(pool.getConnection());
            }

            long began = System.nanoTime();
            String outcome;
            String exceptionType = "-";
            try (Connection extra = pool.getConnection()) {
                outcome = "획득 성공 (예상 밖) " + extra.isValid(1);
            } catch (SQLTransientConnectionException e) {
                outcome = "SQLTransientConnectionException";
                exceptionType = e.getClass().getName();
            } catch (SQLException e) {
                outcome = "SQLException";
                exceptionType = e.getClass().getName();
            }
            long waitedMillis = (System.nanoTime() - began) / 1_000_000L;

            held.get(0).close();
            boolean recovered;
            try (Connection afterRelease = pool.getConnection()) {
                recovered = afterRelease.isValid(1);
            }

            evidence.fact("풀 최대 크기", POOL_SIZE);
            evidence.fact("connectionTimeout(ms)", CONNECTION_TIMEOUT_MILLIS);
            evidence.fact("초과 요청 결과", outcome);
            evidence.fact("예외 타입", exceptionType);
            evidence.fact("실제 대기 시간(ms)", waitedMillis);
            evidence.fact("커넥션 반납 후 재획득", recovered);

            evidence.expectEquals("풀이 고갈되면 타임아웃 예외로 실패한다",
                    "SQLTransientConnectionException", outcome);
            evidence.expect("connectionTimeout 근처에서 실패한다 (무한 대기가 아니다)",
                    waitedMillis >= CONNECTION_TIMEOUT_MILLIS - 100 && waitedMillis < CONNECTION_TIMEOUT_MILLIS + 2_000);
            evidence.expect("커넥션을 반납하면 즉시 회복된다", recovered);

            evidence.note("풀 고갈의 실제 원인은 대부분 '풀이 작아서'가 아니라 '커넥션을 오래 붙잡아서'다 — "
                    + "OSIV, 트랜잭션 안의 외부 API 호출, REQUIRES_NEW 중첩이 대표적이다.");
            evidence.note("모니터링 지표: hikaricp_connections_pending, hikaricp_connections_acquire_seconds (Micrometer).");
            evidence.note("커넥션 타임아웃은 스레드 풀 고갈로 번지고 결국 전체 장애가 된다 — 벌크헤드로 격리해야 하는 이유다.");
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 정리 단계
                }
            }
        }
    }
}
