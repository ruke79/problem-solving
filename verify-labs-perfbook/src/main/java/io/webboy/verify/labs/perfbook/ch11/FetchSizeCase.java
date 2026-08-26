package io.webboy.verify.labs.perfbook.ch11;

import com.sun.management.ThreadMXBean;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.Database;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 11장 — fetch size: 기본값은 "전부 한 번에"이고, 커서 모드에는 조건이 붙는다.
 *
 * <p>책 11장의 트레이드오프: fetch size 를 키우면 왕복이 줄지만 <b>클라이언트 메모리가
 * 결과 전체를 담아야</b> 하고, 줄이면 메모리는 아끼지만 왕복이 늘어난다. PgJDBC 의 기본값은
 * {@code fetchSize=0} — <b>executeQuery 시점에 전 행을 드라이버 버퍼로 가져온다.</b>
 * 대량 결과에서 OOM 이 나는 전형적 원인이다.
 *
 * <p>함정이 하나 더 있다: PgJDBC 의 커서 모드는 <b>autocommit 이 꺼져 있어야만</b> 동작한다.
 * autocommit 이 켜진 채 {@code setFetchSize(100)} 을 불러도 <b>조용히 무시되고 전부 가져온다.</b>
 *
 * <p>측정: 시간이 아니라 <b>executeQuery 동안 이 스레드가 할당한 바이트</b>
 * ({@link ThreadMXBean#getCurrentThreadAllocatedBytes()})로 관측한다 — "전부 vs 첫 청크만"이
 * 할당량 자릿수 차이로 그대로 드러난다.
 */
@Component
public class FetchSizeCase extends VerificationCase {

    private static final String TABLE = "perfbook_fetch";
    private static final int ROWS = 20_000;
    private static final int FETCH_SIZE = 100;

    private final Database database;

    public FetchSizeCase(Database database) {
        this.database = database;
    }

    @Override
    public String id() {
        return "PERF-11D";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 11장 — fetch size 기본값으로 대량 결과를 읽으면 무슨 일이 일어나나?";
    }

    @Override
    public String claim() {
        return "PgJDBC 기본값(fetchSize=0)은 executeQuery 시점에 전 행을 클라이언트 메모리로 가져온다. "
                + "fetchSize 를 주고 autocommit 을 끄면 커서로 청크씩 가져와 메모리가 결과 크기와 무관해진다. "
                + "단 autocommit 이 켜져 있으면 fetchSize 는 조용히 무시된다 — 왕복과 메모리의 트레이드오프다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // DB 부재 게이트 + 할당량에는 약간의 런타임 잡음이 있다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!database.available()) {
            database.markUnavailable(evidence);
            return;
        }

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported()) {
            evidence.expectFlaky("스레드 할당량 측정을 지원하는 JVM 이어야 한다 — 지금은 아니다", false);
            return;
        }
        threads.setThreadAllocatedMemoryEnabled(true);

        try (Connection connection = database.connect()) {
            prepareRows(connection);

            // 워밍업 — 드라이버·클래스 로딩의 일회성 할당이 첫 측정에 섞이지 않게 한다
            readAll(connection, 0, true);
            readAll(connection, FETCH_SIZE, false);
            readAll(connection, FETCH_SIZE, true);

            Measured all = readAll(connection, 0, true);                 // 기본값
            Measured cursor = readAll(connection, FETCH_SIZE, false);    // 커서 모드
            Measured ignored = readAll(connection, FETCH_SIZE, true);    // 함정: autocommit 켠 채

            evidence.fact("행 수", ROWS + " (행당 텍스트 ~120 bytes)");
            evidence.fact("기본값(fetchSize=0) — executeQuery 할당", kb(all.executeBytes));
            evidence.fact("fetchSize=" + FETCH_SIZE + " + autocommit off — executeQuery 할당", kb(cursor.executeBytes));
            evidence.fact("fetchSize=" + FETCH_SIZE + " + autocommit on — executeQuery 할당", kb(ignored.executeBytes));

            evidence.expectEquals("기본값도 전 행을 읽는다", ROWS, all.rows);
            evidence.expectEquals("커서 모드도 전 행을 읽는다 — 결과는 같다", ROWS, cursor.rows);
            evidence.expectEquals("autocommit 켠 쪽도 전 행을 읽는다", ROWS, ignored.rows);

            evidence.expectFlaky("기본값은 executeQuery 시점에 결과 대부분을 할당한다 (커서 모드의 10배 이상)",
                    cursor.executeBytes * 10 <= all.executeBytes);
            evidence.expectFlaky("autocommit 이 켜져 있으면 fetchSize 는 무시된다 — 할당이 기본값의 절반 이상",
                    ignored.executeBytes * 2 >= all.executeBytes);
            evidence.note("커서 모드의 대가는 왕복이다 — " + ROWS + "행 ÷ " + FETCH_SIZE + " = "
                    + (ROWS / FETCH_SIZE) + "번 서버를 다시 부른다. 책의 결론대로 fetch size 는 "
                    + "'클수록 좋다'가 아니라 메모리와 왕복의 트레이드오프이고, PgJDBC 에서는 "
                    + "autocommit off + forward-only 라는 커서 조건까지 알아야 실제로 동작한다.");
        } finally {
            dropQuietly();
        }
    }

    private record Measured(long executeBytes, int rows) {}

    /**
     * {@code executeQuery()} 동안의 스레드 할당 바이트를 재고, 이어서 전 행을 순회한다.
     * 할당량은 GC 와 무관하게 누적치라 시간 측정보다 잡음이 훨씬 작다.
     */
    private Measured readAll(Connection connection, int fetchSize, boolean autoCommit) throws Exception {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        connection.setAutoCommit(autoCommit);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, val FROM " + TABLE,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            query.setFetchSize(fetchSize);

            long before = threads.getCurrentThreadAllocatedBytes();
            ResultSet rs = query.executeQuery();
            long executeBytes = threads.getCurrentThreadAllocatedBytes() - before;

            int rows = 0;
            long blackhole = 0;
            while (rs.next()) {
                blackhole += rs.getInt(1) + rs.getString(2).length();
                rows++;
            }
            rs.close();
            if (blackhole == Long.MIN_VALUE) {
                throw new IllegalStateException();   // 죽은 코드 제거 방지
            }
            if (!autoCommit) {
                connection.commit();                 // 커서(포털)를 잡고 있던 트랜잭션을 닫는다
            }
            return new Measured(executeBytes, rows);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void prepareRows(Connection connection) throws Exception {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("DROP TABLE IF EXISTS " + TABLE);
            ddl.execute("CREATE TABLE " + TABLE + " (id int PRIMARY KEY, val text NOT NULL)");
            // 한 문장으로 넣는다 — 이 케이스의 관심은 읽기다 (쓰기 배칭은 PERF-11A)
            ddl.execute("INSERT INTO " + TABLE + " SELECT g, repeat('x', 120) FROM generate_series(1, "
                    + ROWS + ") g");
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

    private static String kb(long bytes) {
        return String.format("%,d KB", bytes / 1024);
    }
}
