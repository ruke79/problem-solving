package io.webboy.verify.labs.msa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Q40 · Q73 — 무중단 스키마 변경의 각 단계가 독립적으로 롤백 가능한지 확인한다. */
@Component
public class ExpandContractCase extends VerificationCase {

    private final JdbcTemplate jdbc;

    public ExpandContractCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "MSA-05";
    }

    @Override
    public String category() {
        return "msa";
    }

    @Override
    public String question() {
        return "무중단으로 DB 마이그레이션을 하는 절차를 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "Expand/Contract 는 각 단계가 독립적으로 롤백 가능하다. 구 컬럼 삭제(5단계)만이 되돌릴 수 없는 지점이다";
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS member_migration");
        jdbc.execute("CREATE TABLE member_migration (id INT PRIMARY KEY, name VARCHAR(50), phone VARCHAR(30))");
        jdbc.update("INSERT INTO member_migration VALUES (1, 'alice', '+81-90-1111'), (2, 'bob', '+82-10-2222')");

        List<String> oldCodeStatus = new ArrayList<>();

        // 1단계: 컬럼 추가 — 기존 읽기/쓰기에 아무 영향이 없다
        jdbc.execute("ALTER TABLE member_migration ADD COLUMN phone_country VARCHAR(5)");
        jdbc.execute("ALTER TABLE member_migration ADD COLUMN phone_number VARCHAR(30)");
        oldCodeStatus.add("1단계 후: " + runOldCode());

        // 2단계: 듀얼 라이트 — 신 컬럼 쓰기가 실패해도 본 처리를 깨지 않도록 방어적으로
        jdbc.update("INSERT INTO member_migration VALUES (3, 'carol', '+81-70-3333', '+81', '70-3333')");
        oldCodeStatus.add("2단계 후: " + runOldCode());

        // 3단계: 백필 — 운영 부하가 낮은 시간에 작은 배치로
        int backfilled = jdbc.update(
                "UPDATE member_migration SET phone_country = SUBSTRING(phone, 1, 3), "
                        + "phone_number = SUBSTRING(phone, 5) WHERE phone_country IS NULL");
        long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM member_migration WHERE phone_country IS NULL", Long.class);
        oldCodeStatus.add("3단계 후: " + runOldCode());

        // 4단계: 읽기 전환 — 여기서 문제가 나도 설정만 되돌리면 구 컬럼을 다시 읽을 수 있다
        String newRead = jdbc.queryForObject(
                "SELECT phone_country || ' / ' || phone_number FROM member_migration WHERE id = 1", String.class);
        oldCodeStatus.add("4단계 후: " + runOldCode());

        // 5단계: 구 컬럼 삭제 — 되돌릴 수 없는 지점
        jdbc.execute("ALTER TABLE member_migration DROP COLUMN phone");
        String afterDrop = runOldCode();
        oldCodeStatus.add("5단계 후: " + afterDrop);

        evidence.fact("각 단계에서 구 버전 코드의 조회 결과", oldCodeStatus);
        evidence.fact("백필된 행 수", backfilled);
        evidence.fact("백필 후 미처리 행 수", remaining);
        evidence.fact("신 컬럼 읽기 결과", newRead);

        evidence.expect("1~4단계 내내 구 버전 코드가 계속 동작한다",
                oldCodeStatus.subList(0, 4).stream().allMatch(s -> s.endsWith("OK")));
        evidence.expectEquals("백필 후 미처리 행이 없다", 0L, remaining);
        evidence.expectEquals("신 컬럼으로 읽어도 값이 동일하다", "+81 / 90-1111", newRead);
        evidence.expect("5단계에서만 구 버전 코드가 깨진다(되돌릴 수 없는 지점)", afterDrop.startsWith("FAILED"));

        jdbc.execute("DROP TABLE IF EXISTS member_migration");

        evidence.note("2단계(듀얼 라이트)가 가장 사고가 잦다 — 신 컬럼 쓰기 실패가 본 처리 예외로 번지지 않도록 방어적으로 구현한다.");
        evidence.note("애플리케이션 롤백은 되지만 DB 롤백은 안 되므로, 컬럼 추가와 삭제를 같은 릴리스에 넣지 않는 것이 핵심 규칙이다.");
        evidence.note("구 컬럼 삭제 전에는 로그·메트릭으로 '정말 아무도 참조하지 않는다'를 확인한다.");
    }

    /** 마이그레이션 이전 버전의 코드가 그대로 도는지 흉내낸다. */
    private String runOldCode() {
        try {
            jdbc.queryForObject("SELECT phone FROM member_migration WHERE id = 1", String.class);
            return "OK";
        } catch (DataAccessException e) {
            return "FAILED (" + e.getClass().getSimpleName() + ")";
        }
    }
}
