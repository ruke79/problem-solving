package io.webboy.verify.labs.security;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Q104 심화 — 프리페어드 스테이트먼트가 <b>정확히 무엇을</b> 막는가.
 *
 * <p>{@code DB-04} 는 "문자열 결합은 전건 노출, 바인딩은 0건"까지 보여 준다. 여기서는 한 걸음 더 들어가
 * 다중 구문(stacked query, {@code ...; DROP TABLE ...})을 시도해, 방어가 성립하는 지점이
 * <b>이스케이프가 아니라 구문 확정</b>이라는 것을 관측한다.
 *
 * <p>결론부터: <b>"PreparedStatement 를 쓰면 다중 구문이 막힌다"는 통념은 PgJDBC 에서 성립하지 않는다.</b>
 * 드라이버가 SQL 문자열을 세미콜론 기준으로 쪼개 차례로 보내므로, {@code ?} 유무와 관계없이
 * 템플릿에 세미콜론이 있으면 뒤 구문도 실행된다(이 케이스를 쓰다가 실제로 표가 두 번 날아갔다).
 *
 * <p>방어가 성립하는 자리는 따로 있다 — <b>값</b>이다. 공격자가 넣을 수 있는 것은 값이고,
 * 값에 세미콜론이 있어도 새 구문이 되지 못한다. 그래서 정확한 명제는
 * "PreparedStatement 를 쓰면 안전하다"가 아니라 <b>"사용자 입력이 값 자리에 바인딩되면 안전하다"</b> 이다.
 */
@Component
public class StackedQueryCase extends VerificationCase {

    private static final String INJECTION = "1; DROP TABLE stacked_secret";

    private final JdbcTemplate jdbc;

    public StackedQueryCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "SEC-05";
    }

    @Override
    public String category() {
        return "security";
    }

    @Override
    public String question() {
        return "PreparedStatement 는 SQL 인젝션을 어떻게 막습니까? 이스케이프와 무엇이 다릅니까?";
    }

    @Override
    public String claim() {
        return "PreparedStatement 는 값을 이스케이프하는 것이 아니라 SQL 구문을 먼저 확정한 뒤 값을 따로 전달한다. 그래서 값 안에 세미콜론이나 SQL 키워드가 들어와도 새 구문이 되지 못하고 값으로 남는다. 문자열 결합은 구문이 나중에 정해지므로 같은 입력이 실제 명령이 된다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        setUp();

        // (1) 문자열 결합 — 세미콜론이 진짜 구분자가 된다
        String concatenatedSql = "SELECT count(*) FROM stacked_secret WHERE id = " + INJECTION;
        String concatenationResult = runRaw(concatenatedSql);
        boolean tableSurvivedConcatenation = tableExists();

        setUp();   // 표가 날아갔을 수 있으므로 되돌린다

        // (2) 바인딩 — 같은 문자열을 값으로 넘긴다
        String bindingResult = runBound("SELECT count(*) FROM stacked_secret WHERE name = ?", INJECTION);
        boolean tableSurvivedBinding = tableExists();

        // (3) PreparedStatement 인데 '?' 가 하나도 없는 경우 — 드라이버가 어떻게 처리하나
        String preparedNoParams = runBound("SELECT 1; DROP TABLE stacked_secret", null);
        boolean tableSurvivedPreparedNoParams = tableExists();

        setUp();

        // (4) PreparedStatement 에 '?' 가 있는 상태로 다중 구문을 준비시키면
        // 파라미터는 text 컬럼에 바인딩한다 — int 컬럼에 문자열을 넣으면 타입 오류(42883)가 먼저 나서
        // '다중 구문이 거절됐다'가 아니라 엉뚱한 이유로 통과해 버린다(실제로 한 번 그렇게 짰다).
        String preparedWithParam = runBound(
                "SELECT count(*) FROM stacked_secret WHERE name = ?; DROP TABLE stacked_secret", "alice");
        boolean tableSurvivedPreparedWithParam = tableExists();

        setUp();

        // (5) 값으로 들어간 문자열이 그대로 저장되는지 — 이스케이프가 아니라는 증거
        jdbc.update("INSERT INTO stacked_secret (id, name) VALUES (?, ?)", 99, INJECTION);
        String storedBack = jdbc.queryForObject(
                "SELECT name FROM stacked_secret WHERE id = ?", String.class, 99);

        evidence.fact("주입 문자열", INJECTION);
        evidence.fact("[문자열 결합] 실행 결과", concatenationResult);
        evidence.fact("[문자열 결합] 이후 표가 남아 있는가", tableSurvivedConcatenation);
        evidence.fact("[바인딩] 실행 결과", bindingResult);
        evidence.fact("[바인딩] 이후 표가 남아 있는가", tableSurvivedBinding);
        evidence.fact("[PreparedStatement, ? 없음] 결과", preparedNoParams);
        evidence.fact("[PreparedStatement, ? 없음] 이후 표가 남아 있는가", tableSurvivedPreparedNoParams);
        evidence.fact("[PreparedStatement, ? 있음] 결과", preparedWithParam);
        evidence.fact("[PreparedStatement, ? 있음] 이후 표가 남아 있는가", tableSurvivedPreparedWithParam);
        evidence.fact("값으로 저장한 뒤 다시 읽은 문자열", storedBack);

        evidence.expect("문자열 결합은 세미콜론 뒤를 진짜 명령으로 실행해 표를 날린다",
                !tableSurvivedConcatenation);
        evidence.expect("같은 문자열을 바인딩하면 아무 일도 일어나지 않는다", tableSurvivedBinding);
        evidence.expect("바인딩된 조회는 오류 없이 0건을 돌려준다", bindingResult.startsWith("행 수=0"));

        evidence.expect("PreparedStatement 를 써도 SQL 템플릿에 세미콜론이 있으면 다중 구문이 실행된다(? 없음)",
                !tableSurvivedPreparedNoParams);
        evidence.expect("? 가 있어도 마찬가지다 — PgJDBC 가 구문을 클라이언트에서 쪼개 보낸다",
                !tableSurvivedPreparedWithParam);
        evidence.expect("즉 PreparedStatement 는 '다중 구문'을 막는 장치가 아니다 — 막는 것은 값이 구문이 되는 것뿐이다",
                tableSurvivedBinding && !tableSurvivedPreparedNoParams && !tableSurvivedPreparedWithParam);

        evidence.expectEquals("값으로 넘긴 문자열은 이스케이프되지 않고 원문 그대로 저장된다",
                INJECTION, storedBack);

        jdbc.execute("DROP TABLE IF EXISTS stacked_secret");

        evidence.note("마지막 관측값이 핵심이다. 저장된 문자열이 `" + INJECTION + "` 원문 그대로다 — 드라이버가 따옴표를 덧붙이거나 문자를 바꾼 것이 아니다. 방어의 정체는 '이스케이프'가 아니라 **구문을 먼저 확정하고 값을 별도 경로로 보내는 것**이다. 면접에서 '특수문자를 치환해 준다'고 답하면 이 지점에서 틀린다.");
        evidence.note("이 랩이 케이스를 쓰다가 실제로 표를 두 번 날려 먹은 지점이 (3)·(4)다. **PreparedStatement 를 썼는데도 다중 구문이 그대로 실행됐다.** PgJDBC 는 SQL 문자열을 클라이언트에서 세미콜론 기준으로 쪼개 차례로 보내기 때문이고, `?` 유무와 관계없이 그렇다. '프리페어드 스테이트먼트를 쓰면 stacked query 가 막힌다'는 흔한 설명은 적어도 PgJDBC 에서는 사실이 아니다.");
        evidence.note("그러면 방어는 어디서 성립하는가 — (2)다. 공격자가 넣을 수 있는 것은 **값**이고, 값에 세미콜론이 있어도 새 구문이 되지 못한다. 위험한 세미콜론은 전부 개발자가 직접 쓴 SQL 템플릿 안에 있었다. 즉 정확한 명제는 'PreparedStatement 를 쓰면 안전하다'가 아니라 **'사용자 입력이 값 자리에 바인딩되면 안전하다'** 이다.");
        evidence.note("실무적으로도 결론이 갈린다. 통념대로 믿으면 '어차피 드라이버가 막아 준다'며 문자열 결합을 남겨 두게 되는데, 위 (1)이 그 결과다. 반대로 정확히 알면 점검 대상이 분명해진다 — 세미콜론이 아니라 **입력이 값 자리 밖으로 나가는 지점**을 찾으면 된다.");
        evidence.note("이 성질은 DBMS·드라이버마다 다르다. MySQL 은 커넥션 옵션(allowMultiQueries)에 따라 다중 구문이 막히거나 열린다. '우리 DB 는 안 되니 괜찮다'는 이유로 결합을 남기면 이관 한 번에 취약점이 된다.");
        evidence.note("그리고 바인딩이 만능은 아니다. 값이 아닌 자리(테이블명·컬럼명·ORDER BY·LIMIT 의 일부)는 애초에 바인딩할 수 없어 화이트리스트가 유일한 방어다 — DB-19 가 그 경우를 따로 다룬다. 'PreparedStatement 를 썼으니 안전하다'가 아니라 '어디가 값이고 어디가 구문인가'로 봐야 한다.");
    }

    private void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS stacked_secret");
        jdbc.execute("CREATE TABLE stacked_secret (id int PRIMARY KEY, name text)");
        jdbc.update("INSERT INTO stacked_secret (id, name) VALUES (1, 'alice'), (2, 'bob')");
    }

    /** 일반 Statement — 구문이 문자열이 다 만들어진 뒤에 정해진다. */
    private String runRaw(String sql) {
        return jdbc.execute((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                boolean hasResultSet = statement.execute(sql);
                return "실행됨(첫 결과가 ResultSet 인가=" + hasResultSet + ")";
            } catch (SQLException e) {
                return describe(e);
            }
        });
    }

    /** PreparedStatement — 구문을 먼저 서버에 보내 고정한 뒤 값을 따로 전달한다. */
    private String runBound(String sql, String parameter) {
        return jdbc.execute((Connection connection) -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (parameter != null) {
                    statement.setString(1, parameter);
                }
                try (var rs = statement.executeQuery()) {
                    int rows = 0;
                    long firstValue = -1;
                    while (rs.next()) {
                        if (rows == 0) {
                            firstValue = rs.getLong(1);
                        }
                        rows++;
                    }
                    return "행 수=" + (firstValue >= 0 ? firstValue : rows) + " (오류 없음)";
                }
            } catch (SQLException e) {
                return describe(e);
            }
        });
    }

    private boolean tableExists() {
        Boolean exists = jdbc.queryForObject(
                "SELECT to_regclass('stacked_secret') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private String describe(SQLException e) {
        return e.getClass().getSimpleName() + " (SQLState=" + e.getSQLState() + "): "
                + e.getMessage().replaceAll("\\s+", " ").trim();
    }
}
