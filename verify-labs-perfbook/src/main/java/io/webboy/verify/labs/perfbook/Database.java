package io.webboy.verify.labs.perfbook;

import io.webboy.verify.core.Evidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * PERF-11* 가 공유하는 PostgreSQL 접속 도구.
 *
 * <p>풀을 쓰지 않고 {@link DriverManager} 로 raw 커넥션을 만든다 — 배칭(PERF-11A)과
 * 서버측 prepare(PERF-11B)는 <b>커넥션 하나 안에서 일어나는 일</b>이 검증 대상이라,
 * 풀의 커넥션 재사용·초기화 SQL 이 끼어들면 무엇을 잰 것인지 흐려진다.
 *
 * <p>DB 가 없으면 케이스가 {@link #markUnavailable} 로 INCONCLUSIVE 를 남긴다 —
 * "DB 가 없다"와 "명제가 틀렸다"를 구분하는 랩 공통 규칙이다.
 */
@Component
public class Database {

    private final String url;
    private final String username;
    private final String password;

    public Database(@Value("${verify.db.url}") String url,
                    @Value("${verify.db.username}") String username,
                    @Value("${verify.db.password}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public String url() {
        return url;
    }

    /** 기본 설정의 raw 커넥션. */
    public Connection connect() throws Exception {
        return connect(new Properties());
    }

    /** PgJDBC 드라이버 옵션(예: {@code prepareThreshold})을 지정한 raw 커넥션. */
    public Connection connect(Properties extra) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        // 접속 실패를 빨리 알아채기 위한 상한 — 검증 대상이 아니라 가용성 확인용이다
        props.setProperty("connectTimeout", "3");
        props.putAll(extra);
        return DriverManager.getConnection(url, props);
    }

    public boolean available() {
        try (Connection connection = connect()) {
            return connection.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    public void markUnavailable(Evidence evidence) {
        evidence.fact("db.url", url);
        evidence.expectFlaky("PostgreSQL 에 접속할 수 있어야 검증할 수 있다 — 지금은 접속되지 않는다", false);
        evidence.note("`docker compose up -d postgres` 로 띄우면 검증된다. "
                + "다른 인스턴스를 쓰려면 DB_URL/DB_HOST/DB_PORT 로 지정한다.");
    }
}
