package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Q51 — CDC. Debezium·Kafka 없이 PostgreSQL 의 논리 복제 슬롯만으로 두 가지를 확인한다.
 *
 * <ul>
 *   <li>DELETE 는 잡히지만 <b>기본 설정으로는 PK 만</b> 남는다 — 삭제된 행의 내용을 쓰려면
 *       {@code REPLICA IDENTITY FULL} 이 필요하다</li>
 *   <li>소비되지 않은 슬롯은 <b>WAL 을 계속 붙잡는다</b> — 컨슈머가 죽으면 디스크가 찬다</li>
 * </ul>
 *
 * <p>서버가 {@code wal_level=logical} 이어야 한다(compose.yaml 에 설정돼 있다).
 * 그렇지 않은 서버에서는 판정을 내리지 않고 INCONCLUSIVE 로 남긴다.
 */
@Component
public class ChangeDataCaptureCase extends VerificationCase {

    private static final String SLOT = "verify_cdc_slot";

    private final JdbcTemplate jdbc;

    public ChangeDataCaptureCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-10";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "CDC 로 DB 변경을 흘려보낼 때 주의할 점은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "CDC 는 DELETE 도 잡지만 기본 설정에서는 PK 만 남아 '무엇이 지워졌는지'를 알 수 없다(REPLICA IDENTITY FULL 필요). 그리고 소비되지 않은 복제 슬롯은 WAL 을 계속 붙잡아 디스크를 채운다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // wal_level 설정과 WAL 보존량은 서버 환경에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) {
        String walLevel = jdbc.queryForObject("SHOW wal_level", String.class);
        evidence.fact("서버 wal_level", walLevel);

        if (!"logical".equals(walLevel)) {
            evidence.expectFlaky("논리 디코딩에는 wal_level=logical 이 필요하다 — 이 서버는 " + walLevel, false);
            evidence.note("compose.yaml 은 -c wal_level=logical 로 띄운다. 직접 띄운 PostgreSQL 이라면 같은 설정이 필요하고 재기동해야 적용된다.");
            return;
        }

        dropSlotIfExists();
        jdbc.execute("DROP TABLE IF EXISTS cdc_demo");
        jdbc.execute("CREATE TABLE cdc_demo (id int PRIMARY KEY, name text, amount int)");
        jdbc.queryForObject("SELECT slot_name FROM pg_create_logical_replication_slot(?, 'test_decoding')",
                String.class, SLOT);

        try {
            // 1) 기본 REPLICA IDENTITY(= PK) 상태에서의 변경
            jdbc.update("INSERT INTO cdc_demo VALUES (1, 'alice', 100)");
            jdbc.update("UPDATE cdc_demo SET amount = 150 WHERE id = 1");
            jdbc.update("DELETE FROM cdc_demo WHERE id = 1");
            List<String> defaultIdentity = changes();

            // 2) REPLICA IDENTITY FULL 로 바꾼 뒤의 변경
            jdbc.execute("ALTER TABLE cdc_demo REPLICA IDENTITY FULL");
            jdbc.update("INSERT INTO cdc_demo VALUES (2, 'bob', 200)");
            jdbc.update("DELETE FROM cdc_demo WHERE id = 2");
            List<String> fullIdentity = changes();

            String defaultDelete = firstMatching(defaultIdentity, "DELETE");
            String fullDelete = firstMatching(fullIdentity, "DELETE");

            // 3) 소비하지 않고 쓰기만 하면 슬롯이 WAL 을 붙잡는다
            jdbc.update("INSERT INTO cdc_demo SELECT g, 'padding', g FROM generate_series(100, 3000) g");
            long retainedBytes = jdbc.queryForObject(
                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint "
                            + "FROM pg_replication_slots WHERE slot_name = ?", Long.class, SLOT);
            boolean active = jdbc.queryForObject(
                    "SELECT active FROM pg_replication_slots WHERE slot_name = ?", Boolean.class, SLOT);

            evidence.fact("기본 identity 의 변경 스트림", String.join(" | ", defaultIdentity));
            evidence.fact("기본 identity 의 DELETE 항목", defaultDelete);
            evidence.fact("REPLICA IDENTITY FULL 의 DELETE 항목", fullDelete);
            evidence.fact("소비하지 않은 슬롯이 붙잡은 WAL(bytes)", retainedBytes);
            evidence.fact("슬롯 활성 여부(컨슈머 접속 중인가)", active);

            evidence.expect("INSERT/UPDATE/DELETE 가 모두 스트림에 나타난다",
                    containsAll(defaultIdentity, "INSERT", "UPDATE", "DELETE"));
            evidence.expect("기본 identity 의 DELETE 에는 PK 만 남고 다른 컬럼은 없다",
                    defaultDelete.contains("id[integer]:1") && !defaultDelete.contains("alice"));
            evidence.expect("REPLICA IDENTITY FULL 이면 삭제된 행의 전체 값이 남는다",
                    fullDelete.contains("bob") && fullDelete.contains("200"));
            evidence.expectFlaky("소비되지 않은 슬롯은 WAL 을 붙잡는다", retainedBytes > 0);
        } finally {
            dropSlotIfExists();
            jdbc.execute("DROP TABLE IF EXISTS cdc_demo");
        }

        evidence.note("Debezium 이 하는 일의 핵심이 이 슬롯이다. 커넥터가 멈춘 채 방치되면 슬롯이 WAL 을 계속 붙잡아 '애플리케이션은 멀쩡한데 DB 디스크가 찬다'는 장애가 난다 — pg_replication_slots 를 반드시 모니터링한다.");
        evidence.note("REPLICA IDENTITY FULL 은 UPDATE/DELETE 마다 이전 행 전체를 WAL 에 기록하므로 WAL 량과 부하가 늘어난다. '삭제된 값이 필요한 테이블에만' 켜는 판단이 필요하다.");
        evidence.note("애플리케이션이 이벤트를 직접 발행하는 Outbox(MSA-01)와의 차이: CDC 는 코드를 안 건드리는 대신 스키마 변경에 취약하고, Outbox 는 코드가 이벤트를 책임지는 대신 표를 하나 더 관리한다.");
        evidence.note("truncate 는 별도 이벤트로 잡히고, DDL 은 논리 디코딩에 아예 잡히지 않는다 — 스키마 변경은 CDC 파이프라인 밖에서 따로 조율해야 한다.");
    }

    private List<String> changes() {
        return jdbc.queryForList(
                "SELECT data FROM pg_logical_slot_get_changes(?, NULL, NULL)", String.class, SLOT);
    }

    private String firstMatching(List<String> changes, String keyword) {
        return changes.stream().filter(c -> c.contains(keyword)).findFirst().orElse("(없음)");
    }

    private boolean containsAll(List<String> changes, String... keywords) {
        for (String keyword : keywords) {
            if (changes.stream().noneMatch(c -> c.contains(keyword))) {
                return false;
            }
        }
        return true;
    }

    private void dropSlotIfExists() {
        // SELECT 이므로 update() 가 아니라 조회로 호출해야 한다 (결과 행이 돌아온다)
        jdbc.queryForList("SELECT pg_drop_replication_slot(slot_name)::text FROM pg_replication_slots WHERE slot_name = ?",
                String.class, SLOT);
    }
}
