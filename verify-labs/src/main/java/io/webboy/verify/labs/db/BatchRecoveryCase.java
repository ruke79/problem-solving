package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Q2 — "언제 몇 번 재실행해도 결과가 같아지는 상태"를 무엇이 만드는가.
 *
 * <p>세 가지를 한 번에 비교한다.
 * <ul>
 *   <li>체크포인트가 없으면 재실행이 <b>처음부터</b> 다시 돈다</li>
 *   <li>쓰기가 단순 INSERT 면 재실행이 <b>이중 집계</b>를 만든다</li>
 *   <li>체크포인트 + UPSERT 면 재실행해도 결과가 같다</li>
 * </ul>
 */
@Component
public class BatchRecoveryCase extends VerificationCase {

    private static final int TOTAL = 100;
    private static final int CHUNK = 10;
    private static final int FAIL_AT = 55;

    private final JdbcTemplate jdbc;

    public BatchRecoveryCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-21";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "배치 처리 중 에러가 났을 때의 리커버리 전략을 설명해 주세요.";
    }

    @Override
    public String claim() {
        return "청크 단위로 '어디까지 성공했는지'를 영속화하면 실패 지점부터 재개할 수 있고, 쓰기를 UPSERT 로 하면 재실행해도 이중 집계가 생기지 않는다 — 이 둘이 있어야 '몇 번 돌려도 같은 결과'가 성립한다";
    }

    @Override
    protected void verify(Evidence evidence) {
        Result naive = run(false, false);
        Result checkpointOnly = run(true, false);
        Result full = run(true, true);

        evidence.fact("전체 건수 / 청크 크기 / 실패 지점", TOTAL + " / " + CHUNK + " / " + FAIL_AT + "번째");
        evidence.fact("[체크포인트 없음 + INSERT] 재실행이 처리한 건수", naive.reprocessed);
        evidence.fact("[체크포인트 없음 + INSERT] 최종 저장 행 수", naive.rows);
        evidence.fact("[체크포인트 + INSERT] 재실행이 처리한 건수", checkpointOnly.reprocessed);
        evidence.fact("[체크포인트 + INSERT] 최종 저장 행 수", checkpointOnly.rows);
        evidence.fact("[체크포인트 + UPSERT] 재실행이 처리한 건수", full.reprocessed);
        evidence.fact("[체크포인트 + UPSERT] 최종 저장 행 수", full.rows);

        evidence.expectEquals("체크포인트가 없으면 재실행이 처음부터 전건을 다시 돈다", TOTAL, naive.reprocessed);
        evidence.expect("체크포인트가 있으면 실패한 청크부터만 다시 돈다", checkpointOnly.reprocessed < TOTAL);
        evidence.expect("INSERT 로만 쓰면 재실행 구간이 이중으로 쌓인다", checkpointOnly.rows > TOTAL);
        evidence.expectEquals("체크포인트 + UPSERT 면 재실행해도 정확히 전건이다", TOTAL, full.rows);

        jdbc.execute("DROP TABLE IF EXISTS batch_output");
        jdbc.execute("DROP TABLE IF EXISTS batch_checkpoint");

        evidence.note("Spring Batch 의 JobRepository 가 하는 일이 이 체크포인트다. 직접 만들 때도 '처리한 마지막 키'를 같은 트랜잭션에서 갱신해야 한다 — 별도 트랜잭션이면 그 사이에서 죽었을 때 어긋난다.");
        evidence.note("멱등성의 근거는 UPSERT 이거나 처리 완료 키의 유니크 제약이다(DB-12 와 같은 원리). 재시도가 전제인 시스템에서 '한 번만 실행'을 코드로 보장하려 들면 반드시 샌다.");
        evidence.note("데이터 불량 같은 항구적 오류는 재시도해도 소용없으므로 스킵하고 에러 테이블·DLQ 로 뺀다. 다만 스킵 건수에 임계치를 두고 넘으면 잡 자체를 실패시켜야 조용한 유실을 막는다.");
        evidence.note("성공 여부와 처리 건수를 반드시 지표로 내보낸다 — '잡이 돌긴 했는데 0건 처리했다'를 아무도 모르는 것이 실무에서 가장 흔한 사고다.");
    }

    private Result run(boolean useCheckpoint, boolean useUpsert) {
        jdbc.execute("DROP TABLE IF EXISTS batch_output");
        jdbc.execute("DROP TABLE IF EXISTS batch_checkpoint");
        jdbc.execute("CREATE TABLE batch_output (item_id int " + (useUpsert ? "PRIMARY KEY" : "") + ", value text)");
        jdbc.execute("CREATE TABLE batch_checkpoint (job text PRIMARY KEY, last_done int NOT NULL)");
        jdbc.update("INSERT INTO batch_checkpoint VALUES ('demo', 0)");

        try {
            process(0, useCheckpoint, useUpsert, true);    // 1차 실행 — 도중에 죽는다
        } catch (IllegalStateException expected) {
            // 장애 발생
        }

        int resumeFrom = useCheckpoint
                ? jdbc.queryForObject("SELECT last_done FROM batch_checkpoint WHERE job = 'demo'", Integer.class)
                : 0;
        int reprocessed = process(resumeFrom, useCheckpoint, useUpsert, false);   // 재실행

        long rows = jdbc.queryForObject("SELECT count(*) FROM batch_output", Long.class);
        return new Result(reprocessed, rows);
    }

    private int process(int from, boolean useCheckpoint, boolean useUpsert, boolean failMidway) {
        int processed = 0;
        for (int start = from; start < TOTAL; start += CHUNK) {
            int end = Math.min(start + CHUNK, TOTAL);
            for (int item = start + 1; item <= end; item++) {
                if (failMidway && item == FAIL_AT) {
                    throw new IllegalStateException("배치 도중 장애");
                }
                if (useUpsert) {
                    jdbc.update("INSERT INTO batch_output VALUES (?, ?) "
                            + "ON CONFLICT (item_id) DO UPDATE SET value = EXCLUDED.value", item, "v" + item);
                } else {
                    jdbc.update("INSERT INTO batch_output VALUES (?, ?)", item, "v" + item);
                }
                processed++;
            }
            if (useCheckpoint) {
                jdbc.update("UPDATE batch_checkpoint SET last_done = ? WHERE job = 'demo'", end);
            }
        }
        return processed;
    }

    private record Result(int reprocessed, long rows) {}
}
