package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Q106·Q107 — UUID v4 를 PK 로 쓸 때의 대가와 UUIDv7 대안. */
@Component
public class IdStrategyCase extends VerificationCase {

    private static final int ROWS = 20_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** RFC 9562 6.2절 단조 카운터 상태 — {@link #uuidV7()} 가 synchronized 로 보호한다. */
    private static long lastMillis = -1;
    private static long counter = 0;

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
        Insertion sequential = insertBenchmark("id_seq", i -> String.format("%020d", i));
        Insertion uuidV4 = insertBenchmark("id_uuid_v4", i -> UUID.randomUUID().toString());
        Insertion uuidV7Naive = insertBenchmark("id_uuid_v7_naive", i -> uuidV7Naive());
        Insertion uuidV7 = insertBenchmark("id_uuid_v7", i -> uuidV7());

        // 20000개를 만드는 동안 타임스탬프가 몇 개나 바뀌는지 — v7 의 정렬성이 실제로 얼마나 확보되는지의 근거
        Set<Long> millisBuckets = new HashSet<>();
        for (int i = 0; i < ROWS; i++) {
            millisBuckets.add(System.currentTimeMillis());
        }

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

        double bloat = (double) uuidV4.indexBytes() / uuidV7.indexBytes();
        double naiveBloat = (double) uuidV7Naive.indexBytes() / uuidV7.indexBytes();

        evidence.fact("삽입 행 수", ROWS);
        evidence.fact("순차 ID 삽입 시간(ms)", sequential.millis());
        evidence.fact("UUID v4 삽입 시간(ms)", uuidV4.millis());
        evidence.fact("UUIDv7 삽입 시간(ms)", uuidV7.millis());
        evidence.fact(ROWS + "개를 만드는 동안 바뀐 밀리초 수", millisBuckets.size());
        evidence.fact("밀리초당 생성 개수", ROWS / Math.max(1, millisBuckets.size()));
        evidence.fact("UUID v4 PK 인덱스 크기(bytes)", uuidV4.indexBytes());
        evidence.fact("UUIDv7(타임스탬프만) PK 인덱스 크기(bytes)", uuidV7Naive.indexBytes());
        evidence.fact("UUIDv7(단조 카운터) PK 인덱스 크기(bytes)", uuidV7.indexBytes());
        evidence.fact("v4 ÷ v7(카운터) 인덱스 크기 배수", String.format("%.2f배", bloat));
        evidence.fact("v7(타임스탬프만) ÷ v7(카운터) 배수", String.format("%.2f배", naiveBloat));
        evidence.fact("UUIDv7 생성 순서 == 사전순 정렬", v7Sortable);
        evidence.fact("UUID v4 생성 순서 == 사전순 정렬", v4Sortable);
        evidence.fact("UUIDv7 샘플", generated.get(0));

        evidence.expect("UUIDv7 은 생성 순서대로 정렬된다", v7Sortable);
        evidence.expect("UUID v4 는 생성 순서와 정렬 순서가 무관하다", !v4Sortable);
        // 삽입 '시간'은 20000행 규모에서 캐시·WAL 잡음에 묻혀 순서가 뒤집힌다(실제로 INCONCLUSIVE 가 났다).
        // 주장의 메커니즘은 속도가 아니라 페이지 분할이므로, 그 결과인 인덱스 팽창을 직접 잰다.
        // 키 길이(둘 다 36자)와 행 수가 같으므로 차이는 삽입 위치의 무작위성에서만 온다.
        evidence.expect("랜덤 UUID 는 페이지 분할 때문에 시간순 UUID 보다 인덱스가 크게 부푼다",
                bloat > 1.1);
        // 이쪽 배수는 '20000개가 몇 개의 밀리초에 몰렸나'에 좌우되어 실행마다 1.1~1.4 사이에서 움직이고,
        // 장비가 바쁘면 그 아래로도 내려간다. 이건 원고의 주장이 아니라 이 랩이 덤으로 찾은 관찰이므로,
        // 못 봤을 때 REFUTED(원고가 틀렸다)가 아니라 INCONCLUSIVE(이번엔 못 봤다)로 남는 것이 맞다.
        evidence.expectFlaky("타임스탬프만 쓴 UUIDv7 은 밀리초 안이 랜덤이라 카운터판보다 인덱스가 부푼다",
                naiveBloat > 1.05);

        evidence.note("삽입 시간으로는 이 차이를 안정적으로 못 잡는다 — 위 시간 관측값은 실행마다 순서가 뒤집힌다. 데이터가 메모리에 다 들어가는 규모에서는 페이지 분할 비용이 WAL·체크포인트 잡음에 묻히기 때문이다. 그래서 '느려진다'가 아니라 '인덱스가 부푼다'를 판정 근거로 삼았다. 실무에서도 UUID PK 의 첫 증상은 지연이 아니라 인덱스 용량과 캐시 적중률 저하로 나타난다.");
        evidence.note("이 랩이 실측하다 찾은 함정: 'UUIDv7 = 앞부분이 타임스탬프 = 시간순 정렬' 이라는 설명만으로는 부족하다. 위 관측값처럼 " + ROWS + "개를 " + millisBuckets.size() + "개의 밀리초 안에 만들어 버리면 같은 타임스탬프를 가진 수천 개가 서로 랜덤이라, v7 인데도 v4 에 가깝게 인덱스가 부푼다. 그래서 RFC 9562 는 밀리초 안에서 증가하는 단조 카운터(6.2절)를 함께 규정한다 — 라이브러리를 고를 때 '카운터를 구현했는가'를 봐야 한다.");
        evidence.note("정직한 고지: PostgreSQL 은 힙 테이블 + 별도 B-tree 인덱스 구조라, PK 순서가 곧 물리적 저장 순서인 "
                + "InnoDB 의 클러스터 인덱스와 다르다. 여기서 재는 것은 'PK 인덱스에 랜덤 삽입할 때의 비용'이고, "
                + "InnoDB 특유의 페이지 분할 비용은 MySQL 에서 따로 재야 한다.");
        evidence.note("실무 절충안은 '내부 PK 는 BIGINT 자동 채번, 외부 공개 ID 는 별도 UUID 컬럼'의 2계층 구성이다.");
        evidence.note("연번 ID 를 외부에 노출하면 총 사용자 수·주문량이 추측된다는 정보 유출 측면도 함께 고려한다.");
    }

    /**
     * @param millis     삽입에 걸린 시간
     * @param indexBytes 삽입이 끝난 뒤 PK 인덱스가 차지한 바이트 수 — 페이지 분할의 흔적이 여기 남는다
     */
    private record Insertion(long millis, long indexBytes) {}

    private Insertion insertBenchmark(String table, java.util.function.IntFunction<String> idGenerator) {
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE " + table + " (id VARCHAR(40) PRIMARY KEY, payload VARCHAR(60))");

        List<Object[]> batch = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            batch.add(new Object[]{idGenerator.apply(i), "payload"});
        }

        long began = System.nanoTime();
        jdbc.batchUpdate("INSERT INTO " + table + " VALUES (?, ?)", batch);
        long elapsed = (System.nanoTime() - began) / 1_000_000L;

        Long indexBytes = jdbc.queryForObject(
                "SELECT pg_relation_size(?::regclass)", Long.class, table + "_pkey");

        jdbc.execute("DROP TABLE IF EXISTS " + table);
        return new Insertion(elapsed, indexBytes == null ? 0 : indexBytes);
    }

    /**
     * 타임스탬프만 쓰는 순진한 UUIDv7: 상위 48비트 = 유닉스 밀리초, 나머지는 전부 랜덤.
     *
     * <p>초당 수천 개를 만들면 같은 밀리초를 공유하는 값이 수천 개가 되고, 그 안에서는 서로 랜덤이라
     * 정렬성이 사실상 사라진다. 비교 대상으로만 남겨 둔다.
     */
    static String uuidV7Naive() {
        byte[] value = new byte[16];
        long millis = System.currentTimeMillis();
        writeTimestamp(value, millis);
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, value, 6, 10);
        value[6] = (byte) ((value[6] & 0x0F) | 0x70);          // version 7
        value[8] = (byte) ((value[8] & 0x3F) | (byte) 0x80);   // variant 10
        return format(value);
    }

    /**
     * RFC 9562 6.2절의 단조 카운터를 넣은 UUIDv7.
     *
     * <p>같은 밀리초 안에서는 28비트 카운터가 증가하므로 사전순 = 생성순이 유지된다.
     * 밀리초가 바뀌면 카운터를 0 으로 되돌린다 — 타임스탬프가 이미 커졌기 때문에 순서는 깨지지 않는다.
     */
    static synchronized String uuidV7() {
        long millis = System.currentTimeMillis();
        if (millis == lastMillis) {
            counter++;
        } else {
            lastMillis = millis;
            counter = 0;
        }
        long c = counter & 0x0FFF_FFFFL;   // 28비트

        byte[] value = new byte[16];
        writeTimestamp(value, millis);
        byte[] random = new byte[6];
        RANDOM.nextBytes(random);

        value[6] = (byte) (0x70 | ((c >>> 24) & 0x0F));            // version 7 + 카운터 c27..c24
        value[7] = (byte) ((c >>> 16) & 0xFF);                     // c23..c16
        value[8] = (byte) (0x80 | ((c >>> 10) & 0x3F));            // variant 10 + c15..c10
        value[9] = (byte) ((c >>> 2) & 0xFF);                      // c9..c2
        value[10] = (byte) (((c & 0x03) << 6) | (random[0] & 0x3F)); // c1..c0 + 랜덤
        System.arraycopy(random, 1, value, 11, 5);
        return format(value);
    }

    private static void writeTimestamp(byte[] value, long millis) {
        for (int i = 0; i < 6; i++) {
            value[i] = (byte) (millis >>> (8 * (5 - i)));
        }
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
