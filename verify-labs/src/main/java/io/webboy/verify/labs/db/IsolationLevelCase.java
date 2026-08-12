package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Component
public class IsolationLevelCase extends VerificationCase {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public IsolationLevelCase(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-01";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "READ COMMITTED 와 REPEATABLE READ 의 차이를 실제 현상으로 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "READ COMMITTED 는 같은 트랜잭션 안에서 두 번 읽으면 값이 달라질 수 있고(non-repeatable read), REPEATABLE READ 는 달라지지 않는다";
    }

    /** PostgreSQL 의 MVCC 스냅샷은 결정적이라 H2 때와 달리 환경 의존으로 두지 않는다. */
    @Override
    public boolean nondeterministic() {
        return false;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS iso_demo");
        jdbc.execute("CREATE TABLE iso_demo (id INT PRIMARY KEY, val INT)");
        jdbc.update("INSERT INTO iso_demo VALUES (1, 100) ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val");

        int readCommittedDelta = readTwiceWhileUpdated(Connection.TRANSACTION_READ_COMMITTED);
        int repeatableReadDelta = readTwiceWhileUpdated(Connection.TRANSACTION_REPEATABLE_READ);

        evidence.fact("DB 제품", databaseProduct());
        evidence.fact("READ COMMITTED — 두 번째 읽기 - 첫 번째 읽기", readCommittedDelta);
        evidence.fact("REPEATABLE READ — 두 번째 읽기 - 첫 번째 읽기", repeatableReadDelta);

        evidence.expect("READ COMMITTED 에서는 재읽기 값이 달라진다(non-repeatable read)", readCommittedDelta != 0);
        evidence.expect("REPEATABLE READ 에서는 스냅샷이 유지된다", repeatableReadDelta == 0);

        jdbc.execute("DROP TABLE IF EXISTS iso_demo");

        evidence.note("이 랩의 DB 는 PostgreSQL 16 이다. PostgreSQL 의 REPEATABLE READ 는 트랜잭션 첫 문장 시점의 "
                + "스냅샷을 끝까지 유지하는 방식(스냅샷 격리)이라 팬텀 리드까지 함께 막힌다 — "
                + "gap lock 으로 막는 MySQL InnoDB 와는 메커니즘이 다르므로 '표준 표'를 그대로 외워 말하면 안 된다.");
        evidence.note("면접에서 표 암기보다 중요한 것은 '내가 쓰는 DB 의 기본 격리 수준이 무엇인지' 다 — "
                + "PostgreSQL/Oracle 은 READ COMMITTED, MySQL InnoDB 는 REPEATABLE READ 가 기본이다.");
        evidence.note("MySQL InnoDB 와의 비교가 필요하면 compose.yaml 에 MySQL 서비스를 추가하고 "
                + "DB_URL 만 바꿔 같은 케이스를 돌리면 된다.");
    }

    private int readTwiceWhileUpdated(int isolationLevel) throws Exception {
        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(isolationLevel);
            int first = readValue(reader);

            try (Connection writer = dataSource.getConnection()) {
                writer.setAutoCommit(true);
                try (PreparedStatement ps = writer.prepareStatement("UPDATE iso_demo SET val = val + 1 WHERE id = 1")) {
                    ps.executeUpdate();
                }
            }

            int second = readValue(reader);
            reader.rollback();
            return second - first;
        }
    }

    private String databaseProduct() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName()
                    + " " + connection.getMetaData().getDatabaseProductVersion();
        }
    }

    private int readValue(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT val FROM iso_demo WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
