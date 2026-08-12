package io.webboy.verify.labs.api;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Q10 — REST 에서 "멱등하다"는 말의 실제 의미.
 *
 * <p>같은 요청을 세 번 보냈을 때 서버 상태가 어떻게 달라지는지를 메서드별로 잰다.
 * PUT/DELETE 는 몇 번을 보내도 결과가 같고 POST 는 보낼 때마다 자원이 늘어난다 —
 * 그래서 결제처럼 중복이 치명적인 POST 에는 Idempotency-Key 가 필요하다(DB-12).
 */
@Component
public class HttpIdempotencyCase extends VerificationCase {

    private static final int RETRIES = 3;

    private final JdbcTemplate jdbc;

    public HttpIdempotencyCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "API-03";
    }

    @Override
    public String category() {
        return "api";
    }

    @Override
    public String question() {
        return "RESTful API 설계에서 가장 중요한 베스트 프랙티스는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "GET·PUT·DELETE 는 몇 번을 재시도해도 서버 상태가 같아 클라이언트나 게이트웨이가 안심하고 재시도할 수 있다. POST 는 멱등하지 않아 재시도가 곧 중복 생성이므로, 중복이 치명적인 곳에는 Idempotency-Key 로 멱등성을 따로 만들어 줘야 한다";
    }

    @Override
    protected void verify(Evidence evidence) {
        jdbc.execute("DROP TABLE IF EXISTS rest_resource");
        jdbc.execute("CREATE TABLE rest_resource (id text PRIMARY KEY, name text, idempotency_key text UNIQUE)");

        // PUT — 같은 자원을 같은 내용으로 덮어쓴다
        for (int i = 0; i < RETRIES; i++) {
            jdbc.update("INSERT INTO rest_resource (id, name) VALUES ('user-1', 'alice') "
                    + "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name");
        }
        long afterPut = count();

        // POST — 매번 새 자원을 만든다
        for (int i = 0; i < RETRIES; i++) {
            jdbc.update("INSERT INTO rest_resource (id, name) VALUES (?, 'order')", "order-" + java.util.UUID.randomUUID());
        }
        long afterPost = count() - afterPut;

        // POST + Idempotency-Key — 키가 같으면 한 번만 만들어진다
        int created = 0;
        for (int i = 0; i < RETRIES; i++) {
            created += jdbc.update("INSERT INTO rest_resource (id, name, idempotency_key) VALUES (?, 'payment', 'key-42') "
                    + "ON CONFLICT (idempotency_key) DO NOTHING", "payment-" + java.util.UUID.randomUUID());
        }

        // DELETE — 두 번째부터는 지울 것이 없지만 최종 상태는 같다
        int firstDelete = jdbc.update("DELETE FROM rest_resource WHERE id = 'user-1'");
        int secondDelete = jdbc.update("DELETE FROM rest_resource WHERE id = 'user-1'");
        long userRows = jdbc.queryForObject("SELECT count(*) FROM rest_resource WHERE id = 'user-1'", Long.class);

        evidence.fact("재시도 횟수", RETRIES);
        evidence.fact("PUT 3회 후 생성된 자원 수", afterPut);
        evidence.fact("POST 3회 후 생성된 자원 수", afterPost);
        evidence.fact("POST + Idempotency-Key 3회 중 실제 생성 수", created);
        evidence.fact("DELETE 1회차 영향 행 수 / 2회차", firstDelete + " / " + secondDelete);
        evidence.fact("DELETE 반복 후 남은 자원 수", userRows);

        evidence.expectEquals("PUT 은 몇 번을 보내도 자원이 하나다", 1L, afterPut);
        evidence.expectEquals("POST 는 보낼 때마다 자원이 늘어난다", (long) RETRIES, afterPost);
        evidence.expectEquals("Idempotency-Key 를 붙이면 POST 도 한 번만 실행된다", 1, created);
        evidence.expectEquals("DELETE 는 두 번째부터 영향 행이 0이지만 최종 상태는 같다", 0, secondDelete);
        evidence.expectEquals("반복 DELETE 후에도 자원은 없는 상태로 동일하다", 0L, userRows);

        jdbc.execute("DROP TABLE IF EXISTS rest_resource");

        evidence.note("멱등성은 '응답이 같다'가 아니라 '서버 상태가 같다'로 정의된다. DELETE 2회차가 404 를 돌려줘도 멱등이 깨진 것은 아니다 — 상태는 이미 '없음'으로 같다.");
        evidence.note("게이트웨이·로드밸런서·클라이언트 라이브러리는 이 규약을 믿고 자동 재시도를 건다. GET 에 부수효과를 넣는 순간(조회 API 가 카운터를 올리는 등) 그 신뢰가 깨져 원인 불명의 중복이 생긴다.");
        evidence.note("에러를 200 으로 돌려주고 바디에 플래그를 넣는 설계는 재시도·모니터링·서킷브레이커가 전부 오동작하게 만든다 — 상태 코드는 인프라가 읽는 인터페이스다.");
        evidence.note("Idempotency-Key 의 실제 보장은 DB 유니크 제약이 만든다(DB-12). 키 행은 무한히 쌓이므로 보존 기간을 정해 파티션(DB-08)이나 배치로 지운다.");
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM rest_resource", Long.class);
    }
}
