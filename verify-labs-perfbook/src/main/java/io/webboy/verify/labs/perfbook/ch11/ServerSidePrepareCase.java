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
 * 11장 — prepared statement 의 이득은 "재사용"부터다: 드라이버는 임계값을 넘어야 서버에 준비시킨다.
 *
 * <p>책 11장은 "prepared statement 풀을 반드시 써라"고 하면서, 준비 비용은 <b>재사용될 때만</b>
 * 회수된다고 강조한다. PgJDBC 가 정확히 이 논리를 구현한다 — 같은 문장이
 * {@code prepareThreshold}(기본 5)회 실행되기 전에는 서버측 prepare 를 만들지 않고,
 * 넘어서면 그때 만들어 이후 실행부터 파싱·플래닝을 건너뛴다.
 *
 * <p>측정: 같은 커넥션에서 {@code pg_prepared_statements} 뷰를 읽어 서버측 prepare 가
 * <b>언제 생기는지</b>를 직접 관측한다. 시간이 아니라 존재 여부라 전부 결정적이다.
 */
@Component
public class ServerSidePrepareCase extends VerificationCase {

    private static final String QUERY = "SELECT 42 + ?";
    private static final int THRESHOLD = 5;   // PgJDBC prepareThreshold 기본값

    private final Database database;

    public ServerSidePrepareCase(Database database) {
        this.database = database;
    }

    @Override
    public String id() {
        return "PERF-11B";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 11장 — prepared statement 는 언제부터 '준비된' 문장인가?";
    }

    @Override
    public String claim() {
        return "PreparedStatement 객체를 만들었다고 서버에 준비되는 게 아니다. PgJDBC 는 같은 문장이 "
                + "prepareThreshold(기본 5)회 실행된 뒤에야 서버측 prepare 를 만든다 — "
                + "준비 비용은 재사용될 문장에만 지불한다는 책의 논리가 드라이버에 구현돼 있다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // DB 부재 게이트 (관측 자체는 결정적이다)
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        if (!database.available()) {
            database.markUnavailable(evidence);
            return;
        }

        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(QUERY)) {

            evidence.fact("prepareThreshold", THRESHOLD + " (PgJDBC 기본값)");

            long before = serverPreparedCount(connection);
            evidence.expectEquals("실행 전에는 서버측 prepare 가 없다", 0L, before);

            // 임계값 미만 실행
            for (int i = 1; i < THRESHOLD; i++) {
                execute(statement, i);
            }
            long belowThreshold = serverPreparedCount(connection);
            evidence.fact(THRESHOLD - 1 + "회 실행 후 pg_prepared_statements", belowThreshold);
            evidence.expectEquals("임계값 미만에서는 여전히 없다", 0L, belowThreshold);

            // 임계값 도달
            execute(statement, THRESHOLD);
            long atThreshold = serverPreparedCount(connection);
            evidence.fact(THRESHOLD + "회 실행 후 pg_prepared_statements", atThreshold);
            evidence.expect("임계값에 도달하면 서버측 prepare 가 생긴다", atThreshold >= 1);

            evidence.note("pg_prepared_statements 는 세션(커넥션) 단위 뷰다 — 서버측 prepare 가 "
                    + "커넥션에 묶인다는 것 자체가 '커넥션 풀 없이는 문장 풀도 없다'는 책 주장의 근거다. "
                    + "커넥션이 끊기면 준비된 문장도 사라진다.");
        }
    }

    private static void execute(PreparedStatement statement, int argument) throws Exception {
        statement.setInt(1, argument);
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
        }
    }

    /** 같은 커넥션의 서버측 prepared statement 수. (이 조회 자체는 1회 실행이라 집계에 안 잡힌다) */
    private static long serverPreparedCount(Connection connection) throws Exception {
        try (Statement query = connection.createStatement();
             ResultSet rs = query.executeQuery("SELECT count(*) FROM pg_prepared_statements")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
