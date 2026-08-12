package io.webboy.verify.labs.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Q122 (Q13·Q18 의 연장) — 서버리스에서 커넥션이 폭발하는 이유.
 *
 * <p>Lambda 처럼 단명한 실행 환경이 병렬로 뜨면 인스턴스 수만큼 커넥션이 동시에 열린다.
 * 애플리케이션 쪽 풀 크기와 무관하게 <b>서버 쪽 상한</b>이 먼저 걸리는데, PostgreSQL 은
 * 커넥션이 프로세스라 이 상한이 실재한다. 롤 단위 상한으로 그 순간을 재현한다.
 */
@Component
public class ServerConnectionLimitCase extends VerificationCase {

    private static final String LIMITED_ROLE = "verify_limited_app";
    private static final int ROLE_LIMIT = 3;
    private static final int CONCURRENT_LAMBDAS = 6;

    private final JdbcTemplate jdbc;
    private final String jdbcUrl;

    public ServerConnectionLimitCase(JdbcTemplate jdbc,
                                     @Value("${spring.datasource.url}") String jdbcUrl) {
        this.jdbc = jdbc;
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public String id() {
        return "DB-25";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "서버리스(Lambda)를 도입할 때의 함정은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "일반 애플리케이션 서버는 커넥션 풀로 접속을 재사용하지만 서버리스는 인스턴스마다 새로 연결하므로, 동시 실행 수만큼 커넥션이 열려 DB 의 최대 접속 수를 먼저 소진한다. 이때 실패는 애플리케이션이 아니라 서버가 거절하는 형태로 나타나며, 해결은 풀 크기 조정이 아니라 RDS Proxy 같은 중개 계층이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) {
        if (!prepareRole(evidence)) {
            return;
        }

        List<Connection> opened = new ArrayList<>();
        try {
            String serverLimit = jdbc.queryForObject("SHOW max_connections", String.class);
            String rejection = "(거절 없음)";
            int succeeded = 0;

            for (int lambda = 0; lambda < CONCURRENT_LAMBDAS; lambda++) {
                try {
                    // 서버리스 인스턴스 하나 = 풀 하나 = 커넥션 하나
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(jdbcUrl);
                    config.setUsername(LIMITED_ROLE);
                    config.setPassword("verifylab");
                    config.setMaximumPoolSize(1);
                    config.setConnectionTimeout(2_000);
                    config.setPoolName("lambda-" + lambda);
                    config.setInitializationFailTimeout(-1);

                    HikariDataSource pool = new HikariDataSource(config);
                    Connection connection = pool.getConnection();
                    connection.createStatement().execute("SELECT 1");
                    opened.add(connection);
                    succeeded++;
                } catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    rejection = root.getClass().getSimpleName() + ": "
                            + String.valueOf(root.getMessage()).replaceAll("\\s+", " ").trim();
                }
            }

            evidence.fact("서버 max_connections", serverLimit);
            evidence.fact("롤 단위 상한(CONNECTION LIMIT)", ROLE_LIMIT);
            evidence.fact("동시에 뜬 서버리스 인스턴스 수", CONCURRENT_LAMBDAS);
            evidence.fact("접속에 성공한 인스턴스 수", succeeded);
            evidence.fact("거절 메시지", rejection);

            evidence.expectEquals("상한만큼만 접속에 성공한다", ROLE_LIMIT, succeeded);
            evidence.expect("나머지는 애플리케이션이 아니라 서버가 거절한다",
                    rejection.contains("too many connections"));
        } finally {
            opened.forEach(connection -> {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 정리 실패는 판정에 영향 없음
                }
            });
            cleanUp();
        }

        evidence.note("PostgreSQL 은 커넥션 하나가 프로세스 하나라 메모리 비용이 크다. MySQL 은 스레드라 상대적으로 가볍지만, 어느 쪽이든 '앱 풀 크기 × 인스턴스 수' 가 서버 상한을 넘으면 같은 결과가 난다.");
        evidence.note("해결은 애플리케이션 쪽 풀을 줄이는 것이 아니라 중개 계층(RDS Proxy·PgBouncer)을 두어 커넥션을 다중화하는 것이다. 서버리스에서는 아예 접속 개념이 없는 저장소(DynamoDB)를 쓰는 설계 변경도 선택지다.");
        evidence.note("이 실패는 DB-02 의 풀 고갈과 증상이 비슷해 보이지만 원인이 다르다. DB-02 는 '앱의 풀이 비었다'이고 이건 '서버가 더 못 받는다'다 — 전자는 풀 크기를 늘리면 완화되지만 후자는 늘릴수록 악화된다.");
        evidence.note("Micrometer 로 앱 쪽 지표만 보면 이 상황이 안 보인다. DB 쪽 pg_stat_activity 커넥션 수도 함께 모니터링해야 한다.");
    }

    private boolean prepareRole(Evidence evidence) {
        try {
            dropRole();
            jdbc.execute("CREATE ROLE " + LIMITED_ROLE + " LOGIN PASSWORD 'verifylab' CONNECTION LIMIT " + ROLE_LIMIT);
            jdbc.execute("GRANT CONNECT ON DATABASE verifylab TO " + LIMITED_ROLE);
            return true;
        } catch (Exception e) {
            evidence.fact("준비 실패", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
            evidence.expectFlaky("롤 생성 권한이 필요하다 — 이 계정에는 없다", false);
            return false;
        }
    }

    private void cleanUp() {
        dropRole();
    }

    /** 부여한 권한을 먼저 회수해야 롤이 지워진다 — GRANT 가 남아 있으면 DROP ROLE 이 거절된다. */
    private void dropRole() {
        try {
            jdbc.execute("REVOKE ALL ON DATABASE verifylab FROM " + LIMITED_ROLE);
        } catch (Exception ignored) {
            // 롤이 없으면 회수할 것도 없다
        }
        try {
            jdbc.execute("DROP ROLE IF EXISTS " + LIMITED_ROLE);
        } catch (Exception ignored) {
            // 남아 있어도 다음 실행에서 다시 만든다
        }
    }
}
