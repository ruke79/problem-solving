package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Q104 — 프리페어드 스테이트먼트가 "구조상" 안전한 이유. */
@Component
public class SqlInjectionCase extends VerificationCase {

    private static final String PAYLOAD = "' OR '1'='1";
    private static final Set<String> SORTABLE_COLUMNS = Set.of("id", "username");

    private final JdbcTemplate jdbc;

    public SqlInjectionCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-04";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "프리페어드 스테이트먼트는 왜 안전합니까? 이스케이프 처리와 무엇이 다릅니까?";
    }

    @Override
    public String claim() {
        return "구문 트리가 먼저 확정되므로 바인딩된 값은 SQL 구문의 일부가 될 수 없다. 다만 컬럼명·테이블명은 바인딩 대상이 아니라 화이트리스트가 따로 필요하다";
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS injection_demo");
        jdbc.execute("CREATE TABLE injection_demo (id INT PRIMARY KEY, username VARCHAR(50), secret VARCHAR(50))");
        jdbc.update("INSERT INTO injection_demo VALUES (1, 'alice', 's1'), (2, 'bob', 's2'), (3, 'carol', 's3')");

        String concatenated = "SELECT count(*) FROM injection_demo WHERE username = '" + PAYLOAD + "'";
        Long concatCount = jdbc.queryForObject(concatenated, Long.class);

        Long boundCount = jdbc.queryForObject(
                "SELECT count(*) FROM injection_demo WHERE username = ?", Long.class, PAYLOAD);

        Long normalCount = jdbc.queryForObject(
                "SELECT count(*) FROM injection_demo WHERE username = ?", Long.class, "alice");

        boolean allowedColumn = SORTABLE_COLUMNS.contains("username");
        boolean rejectedColumn = !SORTABLE_COLUMNS.contains("username; DROP TABLE injection_demo");

        evidence.fact("공격 페이로드", PAYLOAD);
        evidence.fact("전체 행 수", 3);
        evidence.fact("문자열 결합 쿼리가 반환한 행 수", concatCount);
        evidence.fact("바인딩 쿼리가 반환한 행 수", boundCount);
        evidence.fact("정상 값 바인딩 시 행 수", normalCount);
        evidence.fact("ORDER BY 화이트리스트 통과 (username)", allowedColumn);
        evidence.fact("ORDER BY 화이트리스트 차단 (주입 시도)", rejectedColumn);

        evidence.expectEquals("문자열 결합은 조건이 무력화되어 전건이 노출된다", 3L, concatCount);
        evidence.expectEquals("바인딩하면 페이로드는 '문자열 값'일 뿐이라 0건이다", 0L, boundCount);
        evidence.expectEquals("정상 값 바인딩은 정상 동작한다", 1L, normalCount);
        evidence.expect("컬럼명은 화이트리스트로만 통과시킨다", allowedColumn && rejectedColumn);

        jdbc.execute("DROP TABLE IF EXISTS injection_demo");

        evidence.note("이스케이프는 '위험한 문자를 무해하게 바꾸는' 사후 대증요법이라 DB 종류·문자 인코딩에 따라 빠짐이 생긴다.");
        evidence.note("동적 ORDER BY / 테이블명은 플레이스홀더로 바인딩할 수 없으므로 이 보증의 바깥이다 — 별도 방어가 필요하다.");
    }
}
