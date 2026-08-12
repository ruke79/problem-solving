package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Q126 — 멀티테넌트 SaaS 의 풀 모델에서 가장 무서운 것은 개발자가 {@code WHERE tenant_id} 를
 * 한 번 빼먹는 것이다. 그 실수를 사람의 주의력이 아니라 <b>DB 가 막아 준다</b>는 것을 확인한다.
 *
 * <p>PostgreSQL 의 Row-Level Security 는 정책에 맞지 않는 행을 아예 보여주지 않는다.
 * 조건을 빼먹은 전체 조회(`SELECT * FROM ...`)를 그대로 날려도 자기 테넌트 행만 돌아온다.
 *
 * <p>주의: 슈퍼유저와 테이블 소유자는 기본적으로 RLS 를 우회한다. 그래서 이 케이스는
 * 일반 롤을 만들어 {@code SET ROLE} 로 갈아탄 뒤 관측한다 — 애플리케이션이 붙는 방식과 같다.
 */
@Component
public class RowLevelSecurityCase extends VerificationCase {

    private static final String APP_ROLE = "verify_tenant_app";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public RowLevelSecurityCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-13";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "멀티테넌트 SaaS 에서 테넌트 간 데이터 유출을 어떻게 막습니까?";
    }

    @Override
    public String claim() {
        return "풀 모델(같은 테이블 + tenant_id)의 진짜 위험은 WHERE 절 누락이다. PostgreSQL 의 Row-Level Security 를 켜면 조건을 빼먹은 조회에도 자기 테넌트 행만 돌아오고, 남의 테넌트 행은 UPDATE·DELETE 도 되지 않는다 — 애플리케이션 버그가 있어도 DB 가 마지막 방파제가 된다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!prepare(evidence)) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            // RLS 없이: 조건을 빼먹으면 전 테넌트가 다 보인다
            long withoutRls = countAll(connection, null);

            jdbc.execute("ALTER TABLE tenant_demo ENABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER TABLE tenant_demo FORCE ROW LEVEL SECURITY");
            jdbc.execute("DROP POLICY IF EXISTS tenant_isolation ON tenant_demo");
            jdbc.execute("CREATE POLICY tenant_isolation ON tenant_demo "
                    + "USING (tenant_id = current_setting('app.tenant_id', true))");
            jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_demo TO " + APP_ROLE);

            long tenantA = countAll(connection, "tenant-a");
            long tenantB = countAll(connection, "tenant-b");
            long crossUpdate = updateOtherTenant(connection, "tenant-a");
            String leakedNames = namesVisible(connection, "tenant-a");

            evidence.fact("전체 행 수 (테넌트 2개 × 2건)", withoutRls);
            evidence.fact("RLS 없이 조건을 빼먹은 조회 결과 행 수", withoutRls);
            evidence.fact("RLS 적용 + tenant-a 세션의 같은 조회 결과 행 수", tenantA);
            evidence.fact("RLS 적용 + tenant-b 세션의 같은 조회 결과 행 수", tenantB);
            evidence.fact("tenant-a 세션에서 보이는 값", leakedNames);
            evidence.fact("tenant-a 세션이 tenant-b 행을 UPDATE 한 건수", crossUpdate);

            evidence.expectEquals("RLS 가 없으면 조건 누락 시 전 테넌트가 노출된다", 4L, withoutRls);
            evidence.expectEquals("RLS 를 켜면 조건을 빼먹어도 자기 테넌트 행만 보인다", 2L, tenantA);
            evidence.expectEquals("다른 테넌트 세션도 자기 것만 본다", 2L, tenantB);
            evidence.expect("남의 테넌트 행은 보이지도 않으므로 UPDATE 도 0건이다", crossUpdate == 0);
            evidence.expect("조회 결과에 남의 테넌트 값이 섞이지 않는다", !leakedNames.contains("b-"));
        } finally {
            cleanup();
        }

        evidence.note("RLS 는 애플리케이션 코드를 신뢰하지 않는 다층 방어다. ORM 이 만든 쿼리든 수기 SQL 이든, 심지어 운영자가 psql 로 붙어도(슈퍼유저가 아니라면) 같은 정책이 적용된다.");
        evidence.note("세션 변수(current_setting('app.tenant_id'))는 커넥션 풀에서 재사용되므로, 커넥션을 빌릴 때마다 SET 하고 반납 전에 RESET 하는 규율이 필요하다 — 안 그러면 이전 요청의 테넌트가 새 요청에 남는다(CON-04 의 ThreadLocal 누수와 같은 구조다).");
        evidence.note("슈퍼유저와 BYPASSRLS 권한을 가진 롤은 정책을 우회한다. 애플리케이션 계정은 반드시 일반 롤이어야 하고, 소유자까지 막으려면 FORCE ROW LEVEL SECURITY 가 필요하다.");
        evidence.note("성능은 공짜가 아니다 — 정책 조건이 모든 쿼리에 AND 로 붙으므로 tenant_id 를 선두로 한 복합 인덱스(DB-14)가 사실상 필수다.");
    }

    /** 테스트용 일반 롤과 데이터를 만든다. 권한이 없으면 판정하지 않고 INCONCLUSIVE 로 남긴다. */
    private boolean prepare(Evidence evidence) {
        try {
            jdbc.execute("DROP TABLE IF EXISTS tenant_demo");
            jdbc.execute("CREATE TABLE tenant_demo (id serial PRIMARY KEY, tenant_id text NOT NULL, name text)");
            jdbc.update("INSERT INTO tenant_demo (tenant_id, name) VALUES "
                    + "('tenant-a','a-1'),('tenant-a','a-2'),('tenant-b','b-1'),('tenant-b','b-2')");
            jdbc.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                    + "CREATE ROLE " + APP_ROLE + " NOLOGIN; END IF; END $$");
            return true;
        } catch (Exception e) {
            evidence.fact("준비 단계 실패", e.getClass().getSimpleName() + ": " + e.getMessage());
            evidence.expectFlaky("RLS 검증에는 롤 생성 권한이 필요하다 — 이 계정에는 없다", false);
            evidence.note("compose.yaml 의 계정(POSTGRES_USER)은 슈퍼유저라 이 케이스가 돈다. 직접 띄운 DB 에서 일반 계정을 쓰면 판정하지 않고 넘어간다.");
            return false;
        }
    }

    /** 조건을 일부러 빼먹은 조회. tenantId 가 있으면 RLS 정책이 적용되는 일반 롤로 실행한다. */
    private long countAll(Connection connection, String tenantId) throws SQLException {
        try (var statement = connection.createStatement()) {
            if (tenantId != null) {
                statement.execute("SET app.tenant_id = '" + tenantId + "'");
                statement.execute("SET ROLE " + APP_ROLE);
            }
            try (var rs = statement.executeQuery("SELECT count(*) FROM tenant_demo")) {   // WHERE 절 없음
                rs.next();
                return rs.getLong(1);
            } finally {
                statement.execute("RESET ROLE");
            }
        }
    }

    private long updateOtherTenant(Connection connection, String tenantId) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET app.tenant_id = '" + tenantId + "'");
            statement.execute("SET ROLE " + APP_ROLE);
            try {
                return statement.executeUpdate("UPDATE tenant_demo SET name = 'hacked' WHERE tenant_id = 'tenant-b'");
            } finally {
                statement.execute("RESET ROLE");
            }
        }
    }

    private String namesVisible(Connection connection, String tenantId) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET app.tenant_id = '" + tenantId + "'");
            statement.execute("SET ROLE " + APP_ROLE);
            List<String> names = new java.util.ArrayList<>();
            try (var rs = statement.executeQuery("SELECT name FROM tenant_demo ORDER BY name")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            } finally {
                statement.execute("RESET ROLE");
            }
            return String.join(", ", names);
        }
    }

    private void cleanup() {
        try {
            jdbc.execute("DROP TABLE IF EXISTS tenant_demo");
            jdbc.execute("DROP ROLE IF EXISTS " + APP_ROLE);
        } catch (Exception ignored) {
            // 뒷정리 실패는 판정에 영향을 주지 않는다
        }
    }
}
