package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Q14 심화 — 프리페어드 스테이트먼트로도 못 막는 자리.
 *
 * <p>{@code DB-04} 는 값(value)을 바인딩하면 주입이 막힌다는 것을 보였다. 그런데 <b>컬럼명과
 * 테이블명은 바인딩 대상이 아니다.</b> 원고가 "실무에서 이 동적 정렬 기능이 가장 큰 구멍"이라고
 * 말한 그 지점을 실제로 뚫어 보고, 화이트리스트가 유일한 방어임을 확인한다.
 */
@Component
public class DynamicSortInjectionCase extends VerificationCase {

    private static final Set<String> ALLOWED_SORT_COLUMNS = Set.of("id", "username", "created_at");

    private final JdbcTemplate jdbc;

    public DynamicSortInjectionCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-19";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "프리페어드 스테이트먼트를 쓰면 SQL 인젝션은 끝난 겁니까?";
    }

    @Override
    public String claim() {
        return "값은 바인딩되지만 ORDER BY 의 컬럼명·테이블명은 바인딩 대상이 아니다. 정렬 컬럼을 사용자 입력으로 문자열 결합하면 프리페어드 스테이트먼트를 써도 뚫리며, 이 자리의 방어는 화이트리스트뿐이다";
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS sort_demo");
        jdbc.execute("CREATE TABLE sort_demo (id int PRIMARY KEY, username text, secret text, created_at timestamptz DEFAULT now())");
        // 정렬 효과를 구분하려면 저장 순서가 알파벳순이 아니어야 한다
        jdbc.update("INSERT INTO sort_demo (id, username, secret) VALUES (1,'carol','C-SECRET'),(2,'alice','A-SECRET'),(3,'bob','B-SECRET')");

        // 공격자는 정렬 파라미터에 표현식을 넣어 '데이터를 정렬 기준으로' 뽑아낸다 (블라인드 추출)
        String attack = "CASE WHEN (SELECT secret FROM sort_demo WHERE id=1) LIKE 'A%' THEN username ELSE secret END";

        String boundResult = sortWithBoundColumn("username");
        List<String> injected = jdbc.queryForList(
                "SELECT username FROM sort_demo ORDER BY " + attack, String.class);
        boolean oracleLeaked = !injected.equals(List.of("carol", "alice", "bob"));   // 주입식이 실제로 정렬에 관여했다

        String rejected = safeSort(attack);
        List<String> safe = jdbc.queryForList(
                "SELECT username FROM sort_demo ORDER BY " + safeSort("username"), String.class);

        evidence.fact("정렬 컬럼을 ? 로 바인딩했을 때 반환 순서", boundResult);
        evidence.fact("저장 순서", "[carol, alice, bob]");
        evidence.fact("주입된 정렬식", attack);
        evidence.fact("주입 후 반환 순서", injected.toString());
        evidence.fact("주입으로 비밀값의 첫 글자를 알아낼 수 있는가", oracleLeaked);
        evidence.fact("화이트리스트가 주입 문자열에 내린 판정", rejected);
        evidence.fact("화이트리스트 통과 시 정상 정렬", safe.toString());

        evidence.expect("정렬 컬럼을 바인딩하면 예외는 안 나지만 '상수로 정렬'이라 아무 효과가 없다",
                boundResult.equals("[carol, alice, bob]"));   // 저장 순서 그대로 = 정렬 안 됨
        evidence.expect("문자열 결합한 정렬식은 그대로 실행되어 정보가 새어 나간다", oracleLeaked);
        evidence.expectEquals("화이트리스트는 허용 목록에 없는 입력을 기본값으로 되돌린다", "id", rejected);
        evidence.expectEquals("허용된 컬럼은 그대로 쓴다", "username", safeSort("username"));
        evidence.expectEquals("화이트리스트를 거친 정렬은 정상 동작한다", List.of("alice","bob","carol"), safe);

        jdbc.execute("DROP TABLE IF EXISTS sort_demo");

        evidence.note("정렬 컬럼을 ? 로 넘기면 오류가 나지 않는다는 점이 특히 위험하다. PostgreSQL 은 그 값을 상수로 취급해 정렬이 조용히 사라지므로, '바인딩했으니 됐다'고 넘어가면 기능은 망가진 채 취약점은 그대로 남는다.");
        evidence.note("이 공격은 데이터를 화면에 직접 뿌리지 않아도 된다 — 정렬 '순서'만 봐도 조건의 참·거짓을 알 수 있어 한 글자씩 비밀을 복원할 수 있다(블라인드 인젝션).");
        evidence.note("방어는 '이스케이프'가 아니라 '허용 목록'이다. 사용자 입력을 컬럼명으로 쓰지 말고, 입력을 미리 정한 컬럼 집합에 매핑한다. 정렬 방향(ASC/DESC)도 같은 취급이 필요하다.");
        evidence.note("MyBatis 의 ${} 는 문자열 치환이라 정확히 이 문제를 일으킨다. #{} 는 바인딩이므로 안전하지만, 정렬 컬럼에는 #{} 를 쓸 수 없다는 점이 함정이다.");
        evidence.note("Spring Data 의 Pageable/Sort 도 결국 컬럼명을 SQL 에 넣으므로, 외부에서 받은 정렬 필드는 화이트리스트 검증을 거쳐야 한다.");
    }

    /**
     * 정렬 컬럼을 ? 로 넘기면 PostgreSQL 은 그것을 '컬럼'이 아니라 '문자열 상수'로 받는다.
     * 예외는 나지 않지만 모든 행의 정렬 키가 같아져 정렬이 사실상 일어나지 않는다 —
     * "바인딩했으니 안전하다"고 착각하기 딱 좋은 동작이다.
     */
    private String sortWithBoundColumn(String column) {
        return jdbc.queryForList("SELECT username FROM sort_demo ORDER BY ?", String.class, column).toString();
    }

    /** 허용 목록에 없으면 기본 컬럼으로 되돌린다. */
    private String safeSort(String requested) {
        return ALLOWED_SORT_COLUMNS.contains(requested) ? requested : "id";
    }
}
