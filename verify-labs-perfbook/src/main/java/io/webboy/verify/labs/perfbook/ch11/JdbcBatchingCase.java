package io.webboy.verify.labs.perfbook.ch11;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Database;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 11장 — 쓰기는 배치로 묶어라: 왕복과 커밋을 줄이는 것이 가장 큰 이득이다.
 *
 * <p>책 11장의 수치는 극단적이다 — 같은 데이터 로딩이 건별 autocommit 에서 2220초,
 * 배치 + 단일 커밋에서 11.55초. 원인은 두 겹이다: (a) 문장마다 네트워크 왕복,
 * (b) <b>커밋마다 WAL fsync</b>. 배칭은 (a)를, 트랜잭션 묶기는 (b)를 없앤다.
 *
 * <p>측정: PostgreSQL 16 실물에 같은 2,000행을 (a) autocommit 건별 실행,
 * (b) 단일 트랜잭션 + {@code addBatch/executeBatch} 로 넣어 비교한다.
 * 행 수 일치는 결정적으로, 시간은 {@code expectFlaky} 로 확인한다.
 */
@Component
public class JdbcBatchingCase extends VerificationCase {

    private static final String TABLE = "perfbook_batch";
    private static final int ROWS = 2_000;

    private final Database database;

    public JdbcBatchingCase(Database database) {
        this.database = database;
    }

    @Override
    public String id() {
        return "PERF-11A";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 11장 — 대량 INSERT 는 왜 배치로 묶어야 하나?";
    }

    @Override
    public String claim() {
        return "건별 autocommit 실행은 문장마다 왕복하고 커밋마다 fsync 한다. "
                + "배치 + 단일 트랜잭션은 왕복과 fsync 를 묶어서 같은 데이터를 자릿수 단위로 빨리 넣는다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // DB 부재 게이트 + 시간 비교
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!database.available()) {
            database.markUnavailable(evidence);
            return;
        }

        try (Connection connection = database.connect()) {
            recreateTable(connection);

            // 워밍업 소량 — 커넥션·테이블·플래너 준비
            insertOneByOne(connection, 50);
            recreateTable(connection);
            insertBatched(connection, 50);
            recreateTable(connection);

            long oneByOneMicros = timedMicros(() -> insertOneByOne(connection, ROWS));
            long countAfterOneByOne = count(connection);
            recreateTable(connection);

            long batchedMicros = timedMicros(() -> insertBatched(connection, ROWS));
            long countAfterBatch = count(connection);

            evidence.fact("건별 autocommit (" + ROWS + "행)", oneByOneMicros + " us");
            evidence.fact("배치 + 단일 커밋 (" + ROWS + "행)", batchedMicros + " us");
            evidence.fact("배율", String.format("%.1f배", (double) oneByOneMicros / Math.max(1, batchedMicros)));

            evidence.expectEquals("건별 실행이 전 행을 넣었다", ROWS, countAfterOneByOne);
            evidence.expectEquals("배치 실행도 같은 행 수를 넣었다", ROWS, countAfterBatch);
            evidence.expect("측정 해상도가 확보된다", oneByOneMicros > 0 && batchedMicros > 0);
            evidence.expectFlaky("배치가 최소 2배 빠르다", batchedMicros * 2 <= oneByOneMicros);
            evidence.note("책의 2220초 → 11.55초는 원격 DB(네트워크 왕복) 기준이다. 여기는 로컬 도커라 "
                    + "왕복 비용이 작아 배율이 줄지만, 커밋당 fsync 차이는 그대로 관측된다. "
                    + "PgJDBC 는 배치를 다중 문장 파이프라인으로 보내 왕복 자체도 줄인다.");
        } finally {
            dropQuietly();
        }
    }

    private interface DbWork {
        void run() throws Exception;
    }

    private static long timedMicros(DbWork work) throws Exception {
        long began = System.nanoTime();
        work.run();
        return (System.nanoTime() - began) / 1_000L;
    }

    private void insertOneByOne(Connection connection, int rows) throws Exception {
        connection.setAutoCommit(true);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)")) {
            for (int i = 0; i < rows; i++) {
                insert.setInt(1, i);
                insert.setString(2, "value-" + i);
                insert.executeUpdate();          // 문장마다 왕복 + 자동 커밋(fsync)
            }
        }
    }

    private void insertBatched(Connection connection, int rows) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)")) {
            for (int i = 0; i < rows; i++) {
                insert.setInt(1, i);
                insert.setString(2, "value-" + i);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();                 // fsync 한 번
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void recreateTable(Connection connection) throws Exception {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("DROP TABLE IF EXISTS " + TABLE);
            ddl.execute("CREATE TABLE " + TABLE + " (id int PRIMARY KEY, val text NOT NULL)");
        }
    }

    private long count(Connection connection) throws Exception {
        try (Statement query = connection.createStatement();
             ResultSet rs = query.executeQuery("SELECT count(*) FROM " + TABLE)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void dropQuietly() {
        try (Connection connection = database.connect();
             Statement ddl = connection.createStatement()) {
            ddl.execute("DROP TABLE IF EXISTS " + TABLE);
        } catch (Exception ignored) {
            // DB 가 사라졌다면 지울 것도 없다
        }
    }
}
