package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Q124 — 분산 락만으로는 부족한 이유, 그리고 펜싱 토큰이 그것을 어떻게 메우는지.
 *
 * <p>시나리오는 Martin Kleppmann 이 Redlock 비판에서 든 바로 그 상황이다.
 * <ol>
 *   <li>클라이언트 A 가 락을 얻는다 (토큰 1)</li>
 *   <li>A 가 GC Stop-The-World 나 네트워크 지연으로 멈춘다 → 락 TTL 이 만료된다</li>
 *   <li>클라이언트 B 가 같은 락을 얻는다 (토큰 2) 후 쓰기를 완료한다</li>
 *   <li><b>A 가 깨어나 "나는 아직 락을 갖고 있다"고 믿고 쓴다</b> — 여기서 데이터가 깨진다</li>
 * </ol>
 *
 * <p>락만으로는 4번을 막을 수 없다. 저장소가 <b>단조 증가하는 토큰</b>을 함께 검사해야 막힌다.
 */
@Component
public class FencingTokenCase extends VerificationCase {

    private final JdbcTemplate jdbc;

    public FencingTokenCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-16";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "대규모 트래픽에서 분산 락을 구현할 때, 락만으로 충분합니까?";
    }

    @Override
    public String claim() {
        return "분산 락은 시간(TTL)에 의존하므로, 락을 잡은 클라이언트가 멈춘 사이 TTL 이 만료되면 두 클라이언트가 동시에 '내가 락을 가졌다'고 믿는 순간이 생긴다. 락만으로는 뒤늦게 깨어난 쪽의 쓰기를 막을 수 없고, 단조 증가하는 펜싱 토큰을 저장소가 검사해야 비로소 막힌다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String withoutFencing = runScenario(false);
        String withFencing = runScenario(true);

        evidence.fact("시나리오", "A가 락 획득(토큰1) → A 멈춤 → TTL 만료 → B가 락 획득(토큰2) → B 기록 → A가 깨어나 기록 시도");
        evidence.fact("[펜싱 토큰 없음] 최종 저장된 값", withoutFencing);
        evidence.fact("[펜싱 토큰 있음] 최종 저장된 값", withFencing);

        evidence.expectEquals("락만 쓰면 뒤늦게 깨어난 A 가 B 의 결과를 덮어쓴다",
                "written-by-A", withoutFencing);
        evidence.expectEquals("펜싱 토큰이 있으면 늦은 토큰의 쓰기가 거절되어 B 의 결과가 살아남는다",
                "written-by-B", withFencing);

        jdbc.execute("DROP TABLE IF EXISTS fencing_demo");

        evidence.note("핵심은 '락을 가졌다고 믿는 것'과 '실제로 쓸 자격이 있는 것'이 다르다는 점이다. 락은 조율(coordination)이고, 펜싱 토큰은 저장소 쪽의 최종 방어다 — DB-12 의 유니크 제약과 같은 성격이다.");
        evidence.note("토큰은 반드시 단조 증가해야 한다. PostgreSQL 이면 시퀀스, ZooKeeper 면 zxid, etcd 면 revision 이 그 역할을 한다. Redis 의 INCR 도 쓸 수 있지만 그 Redis 가 페일오버되면 값이 되돌아갈 수 있다는 점을 함께 봐야 한다.");
        evidence.note("Redlock 이 '틀렸다'는 게 아니라, 이중 실행의 피해가 큰 작업(결제·재고 확정)에는 락만으로 충분하지 않다는 뜻이다. 캐시 재생성처럼 이중 실행이 무해하면 락만으로 족하다.");
        evidence.note("GC 정지는 이론이 아니다 — CON-02 에서 보듯 JVM 은 언제든 수백 ms 를 멈출 수 있고, 그 시간이 락 TTL 을 넘으면 이 시나리오가 그대로 재현된다.");
    }

    /**
     * @param useFencing 저장소가 토큰을 검사하는가
     * @return 최종적으로 저장된 값
     */
    private String runScenario(boolean useFencing) {
        jdbc.execute("DROP TABLE IF EXISTS fencing_demo");
        jdbc.execute("CREATE TABLE fencing_demo (id int PRIMARY KEY, value text, fence bigint NOT NULL DEFAULT 0)");
        jdbc.update("INSERT INTO fencing_demo VALUES (1, 'initial', 0)");

        long tokenA = 1;   // A 가 락을 잡을 때 발급받은 토큰
        long tokenB = 2;   // TTL 만료 후 B 가 잡을 때 발급받은 토큰 (반드시 더 크다)

        // B 가 먼저 쓴다 (A 는 멈춰 있는 상태)
        write(1, "written-by-B", tokenB, useFencing);

        // A 가 뒤늦게 깨어나 자기 토큰으로 쓴다
        write(1, "written-by-A", tokenA, useFencing);

        return jdbc.queryForObject("SELECT value FROM fencing_demo WHERE id = 1", String.class);
    }

    private void write(int id, String value, long token, boolean useFencing) {
        if (useFencing) {
            // 저장소가 '더 새로운 토큰만' 받아들인다 — 늦게 온 A 의 쓰기는 0건이 되어 조용히 거절된다
            jdbc.update("UPDATE fencing_demo SET value = ?, fence = ? WHERE id = ? AND fence < ?",
                    value, token, id, token);
        } else {
            jdbc.update("UPDATE fencing_demo SET value = ? WHERE id = ?", value, id);
        }
    }
}
