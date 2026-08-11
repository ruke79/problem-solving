package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Q113 — 차원 수·양자화·마트료시카 절단의 실제 대가. */
@Component
public class EmbeddingDimensionCase extends VerificationCase {

    private static final int CORPUS = 5_000;
    private static final int FULL_DIMENSION = 1024;
    private static final int TRUNCATED_DIMENSION = 256;
    private static final int QUERIES = 20;
    private static final int TOP_K = 10;

    @Override
    public String id() {
        return "AI-04";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "임베딩의 차원 수는 성능에 어떤 영향을 줍니까?";
    }

    @Override
    public String claim() {
        return "차원이 늘면 메모리와 계산 지연이 함께 늘어난다. 양자화는 메모리를 1/4로 줄이면서 재현율 손실이 작지만, 마트료시카 절단은 그렇게 학습된 모델에서만 성립한다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) {
        float[][] corpus = Vectors.random(11L, CORPUS, FULL_DIMENSION);
        float[][] queries = Vectors.random(13L, QUERIES, FULL_DIMENSION);
        List<Integer> all = Vectors.allIndexes(CORPUS);

        long fullMillis = 0;
        long truncatedMillis = 0;
        int truncatedHits = 0;
        int quantizedHits = 0;
        int total = 0;

        byte[][] quantized = quantize(corpus);
        float[][] dequantized = dequantize(quantized);
        List<Integer> quantizedIndexes = Vectors.allIndexes(CORPUS);

        for (float[] query : queries) {
            long began = System.nanoTime();
            List<Integer> exact = Vectors.topK(query, corpus, all, TOP_K, FULL_DIMENSION);
            fullMillis += (System.nanoTime() - began) / 1_000_000L;

            began = System.nanoTime();
            List<Integer> truncated = Vectors.topK(query, corpus, all, TOP_K, TRUNCATED_DIMENSION);
            truncatedMillis += (System.nanoTime() - began) / 1_000_000L;

            List<Integer> fromQuantized =
                    Vectors.topK(query, dequantized, quantizedIndexes, TOP_K, FULL_DIMENSION);

            Set<Integer> truth = new HashSet<>(exact);
            truncatedHits += (int) truncated.stream().filter(truth::contains).count();
            quantizedHits += (int) fromQuantized.stream().filter(truth::contains).count();
            total += truth.size();
        }

        double truncationRecall = (double) truncatedHits / total;
        double quantizationRecall = (double) quantizedHits / total;
        long fullBytes = (long) CORPUS * FULL_DIMENSION * 4;
        long truncatedBytes = (long) CORPUS * TRUNCATED_DIMENSION * 4;
        long quantizedBytes = (long) CORPUS * FULL_DIMENSION;

        evidence.fact("코퍼스 / 차원", CORPUS + " / " + FULL_DIMENSION);
        evidence.fact("float32 메모리(MB)", fullBytes / (1024 * 1024));
        evidence.fact("256차원 절단 메모리(MB)", truncatedBytes / (1024 * 1024));
        evidence.fact("int8 양자화 메모리(MB)", quantizedBytes / (1024 * 1024));
        evidence.fact("full 차원 검색 소요(ms)", fullMillis);
        evidence.fact("256차원 검색 소요(ms)", truncatedMillis);
        evidence.fact("양자화 Recall@10", String.format("%.3f", quantizationRecall));
        evidence.fact("단순 절단 Recall@10", String.format("%.3f", truncationRecall));

        evidence.expectEquals("절단하면 메모리가 차원 비율만큼 준다", fullBytes / 4, truncatedBytes);
        evidence.expectEquals("int8 양자화는 메모리를 1/4 로 줄인다", fullBytes / 4, quantizedBytes);
        evidence.expect("양자화는 재현율 손실이 작다", quantizationRecall > 0.9);
        evidence.expect("마트료시카 학습이 안 된 임베딩을 그냥 자르면 재현율이 무너진다",
                truncationRecall < 0.5);
        evidence.expectFlaky("차원이 줄면 검색이 빨라진다", truncatedMillis <= fullMillis);

        evidence.note("정직한 고지: 여기 쓰는 벡터는 랜덤이라 실제 임베딩의 의미 구조가 없다. "
                + "그래서 '단순 절단이 무너진다'는 결과는 '마트료시카는 학습 기법이지 공짜 연산이 아니다'라는 명제의 근거로만 읽어야 한다.");
        evidence.note("양자화 → (부족하면) 차원 확대 순서로 판단하는 편이 대규모 벡터 DB 운영 비용에 유리하다.");
    }

    private byte[][] quantize(float[][] corpus) {
        byte[][] result = new byte[corpus.length][FULL_DIMENSION];
        for (int i = 0; i < corpus.length; i++) {
            for (int d = 0; d < FULL_DIMENSION; d++) {
                result[i][d] = (byte) Math.max(-127, Math.min(127, Math.round(corpus[i][d] * 127 / 0.25f)));
            }
        }
        return result;
    }

    private float[][] dequantize(byte[][] quantized) {
        float[][] restored = new float[quantized.length][FULL_DIMENSION];
        for (int i = 0; i < quantized.length; i++) {
            for (int d = 0; d < FULL_DIMENSION; d++) {
                restored[i][d] = quantized[i][d] * 0.25f / 127f;
            }
        }
        return restored;
    }
}
