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

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS iso_demo (id INT PRIMARY KEY, val INT)");
        jdbc.update("MERGE INTO iso_demo KEY(id) VALUES (1, 100)");

        int readCommittedDelta = readTwiceWhileUpdated(Connection.TRANSACTION_READ_COMMITTED);
        int repeatableReadDelta = readTwiceWhileUpdated(Connection.TRANSACTION_REPEATABLE_READ);

        evidence.fact("DB 제품", databaseProduct());
        evidence.fact("READ COMMITTED — 두 번째 읽기 - 첫 번째 읽기", readCommittedDelta);
        evidence.fact("REPEATABLE READ — 두 번째 읽기 - 첫 번째 읽기", repeatableReadDelta);

        evidence.expect("READ COMMITTED 에서는 재읽기 값이 달라진다(non-repeatable read)", readCommittedDelta != 0);
        evidence.expectFlaky("REPEATABLE READ 에서는 스냅샷이 유지된다", repeatableReadDelta == 0);

        evidence.note("이 랩의 기본 DB 는 H2 다. 격리 수준 구현은 제품마다 다르다 — "
                + "PostgreSQL 은 REPEATABLE READ 에서 팬텀까지 막지만 MySQL InnoDB 는 gap lock 으로 막는 등 메커니즘이 다르다.");
        evidence.note("면접에서 표 암기보다 중요한 것은 '내가 쓰는 DB 의 기본 격리 수준이 무엇인지' 다 — "
                + "PostgreSQL/Oracle 은 READ COMMITTED, MySQL InnoDB 는 REPEATABLE READ 가 기본이다.");
        evidence.note("정확한 비교는 docker-compose 로 PostgreSQL/MySQL 을 띄우고 같은 케이스를 돌려서 한다.");
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
