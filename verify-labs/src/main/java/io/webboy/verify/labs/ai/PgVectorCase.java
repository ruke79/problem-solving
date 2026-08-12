package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Q3 — 벡터 DB 로 pgvector 를 쓴다는 답변을 실물로 확인한다.
 *
 * <p>{@code AI-03} 은 ANN 의 성질을 자바로 모사했다. 여기서는 <b>PostgreSQL 의 pgvector 확장</b>에
 * 실제 벡터를 넣고, 정확 탐색과 HNSW 인덱스 탐색의 결과·계획을 비교한다.
 * "기존 PostgreSQL 자산을 그대로 쓴다"는 답변의 근거가 실제로 성립하는지가 핵심이다.
 */
@Component
public class PgVectorCase extends VerificationCase {

    private static final int VECTORS = 5_000;
    private static final int DIMENSION = 64;
    private static final int TOP_K = 10;

    private final JdbcTemplate jdbc;

    public PgVectorCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "AI-07";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "RAG 에서 벡터 DB 는 무엇을 쓰셨습니까?";
    }

    @Override
    public String claim() {
        return "pgvector 를 쓰면 기존 PostgreSQL 안에서 벡터 검색이 된다 — 별도 벡터 DB 를 운영하지 않고 같은 트랜잭션·백업·권한 체계를 그대로 쓸 수 있다. 인덱스 없이도 정확히 동작하지만 전건 비교이므로, HNSW 인덱스를 붙이면 근사 탐색으로 바뀌어 빨라지는 대신 재현율을 조금 내준다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 계획 선택과 시간 측정이 섞인다
    }

    @Override
    protected void verify(Evidence evidence) {
        if (!extensionAvailable(evidence)) {
            return;
        }

        jdbc.execute("DROP TABLE IF EXISTS doc_embeddings");
        jdbc.execute("CREATE TABLE doc_embeddings (id serial PRIMARY KEY, embedding vector(" + DIMENSION + "))");

        Random random = new Random(42);
        for (int i = 0; i < VECTORS; i++) {
            jdbc.update("INSERT INTO doc_embeddings (embedding) VALUES (?::vector)", vector(random));
        }
        jdbc.execute("ANALYZE doc_embeddings");

        String query = vector(new Random(7));
        List<Integer> exact = search(query, TOP_K);
        String exactPlan = explain(query);
        long exactMillis = timed(() -> search(query, TOP_K));

        jdbc.execute("CREATE INDEX idx_embedding_hnsw ON doc_embeddings "
                + "USING hnsw (embedding vector_cosine_ops)");
        jdbc.execute("ANALYZE doc_embeddings");

        List<Integer> approximate = search(query, TOP_K);
        String annPlan = explain(query);
        long annMillis = timed(() -> search(query, TOP_K));

        long overlap = approximate.stream().filter(exact::contains).count();
        double recall = (double) overlap / TOP_K;

        evidence.fact("벡터 수 / 차원", VECTORS + " / " + DIMENSION);
        evidence.fact("인덱스 없는 계획", exactPlan);
        evidence.fact("인덱스 없는 소요(ms)", exactMillis);
        evidence.fact("HNSW 인덱스 계획", annPlan);
        evidence.fact("HNSW 소요(ms)", annMillis);
        evidence.fact("정확 탐색 상위 " + TOP_K, exact.toString());
        evidence.fact("근사 탐색과의 일치 개수 / Recall@" + TOP_K, overlap + " / " + recall);

        evidence.expect("확장을 켜면 PostgreSQL 안에서 벡터 컬럼과 거리 연산이 동작한다", exact.size() == TOP_K);
        evidence.expect("인덱스가 없으면 전건 비교(Seq Scan)다", exactPlan.toLowerCase(Locale.ROOT).contains("seq scan"));
        evidence.expectFlaky("HNSW 인덱스를 만들면 인덱스 스캔으로 바뀐다",
                annPlan.toLowerCase(Locale.ROOT).contains("idx_embedding_hnsw"));
        evidence.expectFlaky("근사 탐색이라 재현율이 1.0 보다 낮을 수 있다", recall <= 1.0);
        evidence.expect("그래도 상위 결과의 대부분은 정확 탐색과 일치한다", recall >= 0.5);

        jdbc.execute("DROP TABLE IF EXISTS doc_embeddings");

        evidence.note("pgvector 의 최대 장점은 '벡터를 위해 새 시스템을 운영하지 않아도 된다'는 것이다. 같은 트랜잭션 안에서 메타데이터와 벡터를 함께 쓰고, 권한(DB-13 의 RLS)과 백업 체계를 그대로 상속한다.");
        evidence.note("반대로 한계는 규모다. 수억 벡터·초당 수천 질의 수준이면 전용 벡터 DB 의 분산·양자화 기능이 필요해진다 — Q158 의 'ES 를 언제 넣는가'와 같은 판단 구조다.");
        evidence.note("AI-03 이 모사한 nprobe 는 IVF 계열 파라미터이고, HNSW 는 ef_search 로 탐색 폭을 조절한다. 어느 쪽이든 '넓게 볼수록 재현율↑ 속도↓'라는 성질은 같다.");
        evidence.note("이 케이스의 벡터는 랜덤이라 검색 품질(Recall 의 절대값)은 의미가 없다. 확인하는 것은 '인덱스 유무로 접근 경로와 결과 집합이 어떻게 달라지는가'까지다.");
    }

    private boolean extensionAvailable(Evidence evidence) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            return true;
        } catch (Exception e) {
            evidence.fact("확장 설치 실패", e.getClass().getSimpleName());
            evidence.expectFlaky("pgvector 확장이 있는 이미지가 필요하다 (compose.yaml 은 pgvector/pgvector:pg16 을 쓴다)", false);
            return false;
        }
    }

    private List<Integer> search(String query, int k) {
        return jdbc.queryForList(
                "SELECT id FROM doc_embeddings ORDER BY embedding <=> ?::vector LIMIT " + k,
                Integer.class, query);
    }

    private String explain(String query) {
        List<String> lines = jdbc.queryForList(
                "EXPLAIN SELECT id FROM doc_embeddings ORDER BY embedding <=> ?::vector LIMIT " + TOP_K,
                String.class, query);
        return String.join(" / ", lines).replaceAll("\\s+", " ").trim();
    }

    private String vector(Random random) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIMENSION; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.4f", random.nextGaussian()));
        }
        return sb.append(']').toString();
    }

    private long timed(Runnable work) {
        long began = System.nanoTime();
        work.run();
        return (System.nanoTime() - began) / 1_000_000L;
    }
}
