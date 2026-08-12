package io.webboy.verify.labs.resilience;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q128 — "우리 앱은 스테이트리스라 스케일 아웃하면 됩니다"의 함정.
 *
 * <p>레이트 리밋을 인메모리 카운터로 만들어 두면, 인스턴스가 2대가 되는 순간 각자 따로 세기 때문에
 * 실제 통과량이 <b>제한의 인스턴스 배수</b>가 된다. 중앙 저장소(여기서는 DB)로 세면 대수와 무관하게 지켜진다.
 */
@Component
public class StatelessIllusionCase extends VerificationCase {

    private static final int LIMIT = 10;
    private static final int INSTANCES = 3;
    private static final int REQUESTS_PER_INSTANCE = 10;

    private final JdbcTemplate jdbc;

    public StatelessIllusionCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "RES-10";
    }

    @Override
    public String category() {
        return "resilience";
    }

    @Override
    public String question() {
        return "트래픽이 100배가 되어도 견딥니까? 정말 스테이트리스입니까?";
    }

    @Override
    public String claim() {
        return "인메모리 변수로 만든 카운터·레이트 리밋은 인스턴스가 늘어나는 순간 각자 따로 세므로 제한이 인스턴스 배수만큼 샌다. 상태를 중앙(Redis·DB)으로 옮겨야 대수와 무관하게 제한이 지켜진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        int inMemoryPassed = withInMemoryCounter();
        int centralPassed = withCentralCounter();

        evidence.fact("설정한 제한 / 인스턴스 수", LIMIT + " / " + INSTANCES);
        evidence.fact("인스턴스당 요청 수", REQUESTS_PER_INSTANCE);
        evidence.fact("[인메모리 카운터] 실제 통과한 요청 수", inMemoryPassed);
        evidence.fact("[중앙 카운터] 실제 통과한 요청 수", centralPassed);

        evidence.expectEquals("인메모리 카운터는 인스턴스 수만큼 제한이 샌다",
                LIMIT * INSTANCES, inMemoryPassed);
        evidence.expectEquals("중앙 카운터는 인스턴스가 늘어도 제한을 지킨다", LIMIT, centralPassed);

        jdbc.execute("DROP TABLE IF EXISTS rate_counter");

        evidence.note("이 함정은 레이트 리밋뿐 아니라 인메모리 캐시(RES-02), 스케줄러(DB-23), 세션, 중복 요청 판정에도 똑같이 나타난다 — '한 대일 때만 맞는 코드'는 배포 구성이 바뀌는 순간 조용히 틀린다.");
        evidence.note("반대로 인메모리라서 좋은 것도 있다. 네트워크 홉이 없어 빠르고, 장애 시 외부 의존이 없다. 문제는 '분산돼도 맞는 것처럼' 말하는 것이지 인메모리 자체가 아니다.");
        evidence.note("중앙 저장소로 옮기면 그 저장소가 새로운 SPOF 가 된다. Redis 가 죽었을 때 전부 통과시킬지(페일 오픈) 전부 막을지(페일 클로즈)를 업무 성격으로 미리 정해 둬야 한다.");
        evidence.note("확인·소비를 두 번의 왕복으로 나누면 중앙에 두어도 경쟁 상태로 샌다(RES-05). 원자적 연산(Lua 스크립트, UPDATE ... RETURNING)이 필요하다.");
    }

    /** 인스턴스마다 자기 카운터를 갖는다. */
    private int withInMemoryCounter() {
        int passed = 0;
        for (int instance = 0; instance < INSTANCES; instance++) {
            AtomicInteger counter = new AtomicInteger();       // 인스턴스별로 새로 만들어진다
            for (int request = 0; request < REQUESTS_PER_INSTANCE; request++) {
                if (counter.incrementAndGet() <= LIMIT) {
                    passed++;
                }
            }
        }
        return passed;
    }

    /** 모든 인스턴스가 같은 행을 원자적으로 증가시킨다. */
    private int withCentralCounter() {
        jdbc.execute("DROP TABLE IF EXISTS rate_counter");
        jdbc.execute("CREATE TABLE rate_counter (bucket text PRIMARY KEY, used int NOT NULL)");
        jdbc.update("INSERT INTO rate_counter VALUES ('api', 0)");

        int passed = 0;
        for (int instance = 0; instance < INSTANCES; instance++) {
            for (int request = 0; request < REQUESTS_PER_INSTANCE; request++) {
                // 확인과 증가를 한 문장으로 — 조건에 안 맞으면 0행이 돌아온다
                List<Integer> used = jdbc.query(
                        "UPDATE rate_counter SET used = used + 1 WHERE bucket = 'api' AND used < ? RETURNING used",
                        (rs, rowNum) -> rs.getInt(1), LIMIT);
                if (!used.isEmpty()) {
                    passed++;
                }
            }
        }
        return passed;
    }
}
