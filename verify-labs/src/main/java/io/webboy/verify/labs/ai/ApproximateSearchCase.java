package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Q78 — ANN 은 만능 정답이 아니라 재현율과 속도를 맞바꾼 선택이다. */
@Component
public class ApproximateSearchCase extends VerificationCase {

    private static final int CORPUS = 20_000;
    private static final int DIMENSION = 64;
    private static final int QUERIES = 20;
    private static final int TOP_K = 10;
    private static final int CLUSTERS = 64;

    @Override
    public String id() {
        return "AI-03";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "벡터 검색에서 ANN(근사 최근접 탐색)이 필수인 이유는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "완전 탐색은 건수에 대해 O(N)이라 규모가 커지면 못 쓴다. ANN 은 탐색 폭 파라미터로 재현율과 속도를 맞바꾸며, 넓게 볼수록 재현율은 오르고 속도는 떨어진다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) {
        float[][] corpus = Vectors.random(42L, CORPUS, DIMENSION);
        float[][] queries = Vectors.random(7L, QUERIES, DIMENSION);
        List<Integer> all = Vectors.allIndexes(CORPUS);

        // 완전 탐색 기준선
        List<List<Integer>> exact = new ArrayList<>();
        long exactBegan = System.nanoTime();
        for (float[] query : queries) {
            exact.add(Vectors.topK(query, corpus, all, TOP_K, DIMENSION));
        }
        long exactMillis = (System.nanoTime() - exactBegan) / 1_000_000L;

        // IVF 방식 근사 탐색: 클러스터를 나누고 가까운 nprobe 개만 뒤진다
        InvertedFileIndex index = new InvertedFileIndex(corpus, CLUSTERS, DIMENSION);
        Result probe1 = index.search(queries, exact, 1);
        Result probe8 = index.search(queries, exact, 8);
        Result probe32 = index.search(queries, exact, 32);

        evidence.fact("코퍼스 크기 / 차원 / top-k", CORPUS + " / " + DIMENSION + " / " + TOP_K);
        evidence.fact("완전 탐색 소요(ms)", exactMillis);
        evidence.fact("완전 탐색이 계산한 거리 수(쿼리당)", CORPUS);
        evidence.fact("nprobe=1  재현율 / 소요(ms) / 계산 수", format(probe1));
        evidence.fact("nprobe=8  재현율 / 소요(ms) / 계산 수", format(probe8));
        evidence.fact("nprobe=32 재현율 / 소요(ms) / 계산 수", format(probe32));

        evidence.expect("근사 탐색은 후보를 좁히므로 계산량이 줄어든다", probe1.scanned < CORPUS);
        evidence.expect("탐색 폭이 좁으면 진짜 근방을 놓친다", probe1.recall < 1.0);
        evidence.expectFlaky("탐색 폭을 넓히면 재현율이 올라간다",
                probe1.recall < probe8.recall && probe8.recall <= probe32.recall);
        evidence.expect("탐색 폭을 넓히면 계산량도 함께 늘어난다", probe1.scanned < probe32.scanned);
        evidence.expectFlaky("근사 탐색이 완전 탐색보다 빠르다", probe1.millis <= exactMillis);

        evidence.note("HNSW 의 ef_search 가 여기서의 nprobe 에 해당한다. 기본값을 그대로 쓰면 정확도 부족을 모른 채 운영하게 된다.");
        evidence.note("먼저 평가 데이터셋으로 Recall@k 를 재고, 허용 가능한 정확도 범위 안에서 파라미터를 속도 쪽으로 조정하는 순서가 실무적이다.");
        evidence.note("건수가 적거나 정확도가 최우선이면 굳이 전수 탐색(Flat Index)을 쓰는 판단도 정당하다.");
    }

    private String format(Result result) {
        return String.format("%.3f / %d / %d", result.recall, result.millis, result.scanned);
    }

    private static final class Result {
        final double recall;
        final long millis;
        final int scanned;

        Result(double recall, long millis, int scanned) {
            this.recall = recall;
            this.millis = millis;
            this.scanned = scanned;
        }
    }

    private static final class InvertedFileIndex {
        private final float[][] corpus;
        private final int dimension;
        private final float[][] centroids;
        private final List<List<Integer>> buckets;

        InvertedFileIndex(float[][] corpus, int clusters, int dimension) {
            this.corpus = corpus;
            this.dimension = dimension;
            this.centroids = new float[clusters][];
            this.buckets = new ArrayList<>();
            for (int c = 0; c < clusters; c++) {
                centroids[c] = corpus[c * (corpus.length / clusters)];
                buckets.add(new ArrayList<>());
            }
            for (int i = 0; i < corpus.length; i++) {
                buckets.get(nearestCentroid(corpus[i], 1).get(0)).add(i);
            }
        }

        private List<Integer> nearestCentroid(float[] vector, int count) {
            List<int[]> scored = new ArrayList<>(centroids.length);
            for (int c = 0; c < centroids.length; c++) {
                scored.add(new int[]{c, (int) (Vectors.dot(vector, centroids[c], dimension) * 1_000_000)});
            }
            scored.sort(Comparator.comparingInt((int[] pair) -> pair[1]).reversed());
            List<Integer> result = new ArrayList<>(count);
            for (int i = 0; i < Math.min(count, scored.size()); i++) {
                result.add(scored.get(i)[0]);
            }
            return result;
        }

        Result search(float[][] queries, List<List<Integer>> exact, int nprobe) {
            int hits = 0;
            int total = 0;
            int scanned = 0;
            long began = System.nanoTime();
            for (int q = 0; q < queries.length; q++) {
                List<Integer> candidates = new ArrayList<>();
                for (int cluster : nearestCentroid(queries[q], nprobe)) {
                    candidates.addAll(buckets.get(cluster));
                }
                scanned += candidates.size();
                List<Integer> approximate = Vectors.topK(queries[q], corpus, candidates, TOP_K, dimension);
                Set<Integer> truth = new HashSet<>(exact.get(q));
                for (int index : approximate) {
                    if (truth.contains(index)) {
                        hits++;
                    }
                }
                total += truth.size();
            }
            long millis = (System.nanoTime() - began) / 1_000_000L;
            return new Result((double) hits / total, millis, scanned / queries.length);
        }
    }
}
