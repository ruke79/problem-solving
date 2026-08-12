package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Q106·Q107 — UUID v4 를 PK 로 쓸 때의 대가와 UUIDv7 대안. */
@Component
public class IdStrategyCase extends VerificationCase {

    private static final int ROWS = 20_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public IdStrategyCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-05";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "UUID 를 RDB 의 PK 로 쓰면 어떤 단점이 있고, 대안은 무엇입니까?";
    }

    @Override
    public String claim() {
        return "UUID v4 는 완전 랜덤이라 인덱스 삽입 위치를 예측할 수 없어 페이지 분할이 잦다. UUIDv7 은 앞부분이 타임스탬프라 시간순 정렬이 되므로 이 문제를 피한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        long sequentialMillis = insertBenchmark("id_seq", i -> String.format("%020d", i));
        long uuidV4Millis = insertBenchmark("id_uuid_v4", i -> UUID.randomUUID().toString());
        long uuidV7Millis = insertBenchmark("id_uuid_v7", i -> uuidV7());

        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            generated.add(uuidV7());
            Thread.sleep(3);
        }
        List<String> sorted = new ArrayList<>(generated);
        sorted.sort(Comparator.naturalOrder());
        boolean v7Sortable = generated.equals(sorted);

        List<String> v4Generated = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            v4Generated.add(UUID.randomUUID().toString());
        }
        List<String> v4Sorted = new ArrayList<>(v4Generated);
        v4Sorted.sort(Comparator.naturalOrder());
        boolean v4Sortable = v4Generated.equals(v4Sorted);

        evidence.fact("삽입 행 수", ROWS);
        evidence.fact("순차 ID 삽입 시간(ms)", sequentialMillis);
        evidence.fact("UUID v4 삽입 시간(ms)", uuidV4Millis);
        evidence.fact("UUIDv7 삽입 시간(ms)", uuidV7Millis);
        evidence.fact("UUIDv7 생성 순서 == 사전순 정렬", v7Sortable);
        evidence.fact("UUID v4 생성 순서 == 사전순 정렬", v4Sortable);
        evidence.fact("UUIDv7 샘플", generated.get(0));

        evidence.expect("UUIDv7 은 생성 순서대로 정렬된다", v7Sortable);
        evidence.expect("UUID v4 는 생성 순서와 정렬 순서가 무관하다", !v4Sortable);
        evidence.expectFlaky("랜덤 UUID 삽입이 순차 ID 삽입보다 느리다", uuidV4Millis >= sequentialMillis);

        evidence.note("정직한 고지: PostgreSQL 은 힙 테이블 + 별도 B-tree 인덱스 구조라, PK 순서가 곧 물리적 저장 순서인 "
                + "InnoDB 의 클러스터 인덱스와 다르다. 여기서 재는 것은 'PK 인덱스에 랜덤 삽입할 때의 비용'이고, "
                + "InnoDB 특유의 페이지 분할 비용은 MySQL 에서 따로 재야 한다.");
        evidence.note("실무 절충안은 '내부 PK 는 BIGINT 자동 채번, 외부 공개 ID 는 별도 UUID 컬럼'의 2계층 구성이다.");
        evidence.note("연번 ID 를 외부에 노출하면 총 사용자 수·주문량이 추측된다는 정보 유출 측면도 함께 고려한다.");
    }

    private long insertBenchmark(String table, java.util.function.IntFunction<String> idGenerator) {
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE " + table + " (id VARCHAR(40) PRIMARY KEY, payload VARCHAR(60))");

        List<Object[]> batch = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            batch.add(new Object[]{idGenerator.apply(i), "payload"});
        }

        long began = System.nanoTime();
        jdbc.batchUpdate("INSERT INTO " + table + " VALUES (?, ?)", batch);
        long elapsed = (System.nanoTime() - began) / 1_000_000L;

        jdbc.execute("DROP TABLE IF EXISTS " + table);
        return elapsed;
    }

    /** RFC 9562 UUIDv7: 상위 48비트 = 유닉스 밀리초, 이후 버전/변형 비트 + 랜덤. */
    static String uuidV7() {
        byte[] value = new byte[16];
        long millis = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            value[i] = (byte) (millis >>> (8 * (5 - i)));
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, value, 6, 10);
        value[6] = (byte) ((value[6] & 0x0F) | 0x70);          // version 7
        value[8] = (byte) ((value[8] & 0x3F) | (byte) 0x80);   // variant 10
        return format(value);
    }

    private static String format(byte[] value) {
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                sb.append('-');
            }
            sb.append(String.format("%02x", value[i]));
        }
        return sb.toString();
    }
}
