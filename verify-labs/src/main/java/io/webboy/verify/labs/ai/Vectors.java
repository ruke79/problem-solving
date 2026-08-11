package io.webboy.verify.labs.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** AI-03 / AI-04 에서 쓰는 최소 벡터 유틸. 시드를 고정해 재현 가능하게 만든다. */
public final class Vectors {

    private Vectors() {
    }

    public static float[][] random(long seed, int count, int dimension) {
        Random random = new Random(seed);
        float[][] vectors = new float[count][dimension];
        for (int i = 0; i < count; i++) {
            double norm = 0;
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] = (float) random.nextGaussian();
                norm += vectors[i][d] * vectors[i][d];
            }
            norm = Math.sqrt(norm);
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] /= (float) norm;
            }
        }
        return vectors;
    }

    public static double dot(float[] a, float[] b, int dimension) {
        double sum = 0;
        for (int i = 0; i < dimension; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** 코사인 유사도 상위 k 개의 인덱스. 후보 집합을 지정할 수 있다. */
    public static List<Integer> topK(float[] query, float[][] corpus, List<Integer> candidates,
                                     int k, int dimension) {
        List<int[]> scored = new ArrayList<>(candidates.size());
        for (int index : candidates) {
            scored.add(new int[]{index, (int) (dot(query, corpus[index], dimension) * 1_000_000)});
        }
        scored.sort(Comparator.comparingInt((int[] pair) -> pair[1]).reversed());
        List<Integer> result = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            result.add(scored.get(i)[0]);
        }
        return result;
    }

    public static List<Integer> allIndexes(int count) {
        List<Integer> indexes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indexes.add(i);
        }
        return indexes;
    }
}
