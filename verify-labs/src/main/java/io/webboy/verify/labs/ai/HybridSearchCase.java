package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Q54 — 벡터 검색의 약점(완전 일치 어휘)과 RRF 하이브리드. */
@Component
public class HybridSearchCase extends VerificationCase {

    private static final int RRF_K = 60;
    private static final int TOP_K = 3;

    /** 평가 데이터셋: 질문과, 그 답이 들어 있는 정답 문서 집합. */
    private static final String QUERY = "ABC-1234 반품 규정";
    private static final Set<String> GROUND_TRUTH = Set.of("doc-code", "doc-policy");

    @Override
    public String id() {
        return "AI-05";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "RAG 의 검색 정확도를 올리려면 무엇부터 합니까?";
    }

    @Override
    public String claim() {
        return "출발점은 평가 데이터셋과 Recall@k 계측이다. 벡터 검색은 제품 코드 같은 완전 일치 어휘에 약하므로 키워드 검색과 RRF 로 통합하면 양쪽의 누락을 메운다";
    }

    @Override
    protected void verify(Evidence evidence) {
        // 키워드 검색: 제품 코드는 정확히 잡지만 의미가 다른 표현은 놓친다
        List<String> lexical = List.of("doc-code", "doc-noise-1", "doc-noise-2");
        // 벡터 검색: 의미적으로 가까운 문서는 잡지만 제품 코드는 놓친다
        List<String> vector = List.of("doc-policy", "doc-noise-3", "doc-noise-4");

        List<String> fused = reciprocalRankFusion(List.of(lexical, vector));

        double lexicalRecall = recallAtK(lexical, TOP_K);
        double vectorRecall = recallAtK(vector, TOP_K);
        double hybridRecall = recallAtK(fused, TOP_K);

        evidence.fact("질문", QUERY);
        evidence.fact("정답 문서", GROUND_TRUTH);
        evidence.fact("키워드 검색 상위", lexical);
        evidence.fact("벡터 검색 상위", vector);
        evidence.fact("RRF 통합 상위", fused);
        evidence.fact("Recall@3 - 키워드만", String.format("%.2f", lexicalRecall));
        evidence.fact("Recall@3 - 벡터만", String.format("%.2f", vectorRecall));
        evidence.fact("Recall@3 - 하이브리드", String.format("%.2f", hybridRecall));

        evidence.expectEquals("키워드 검색은 제품 코드 문서만 잡는다", 0.5, lexicalRecall);
        evidence.expectEquals("벡터 검색은 의미 문서만 잡는다", 0.5, vectorRecall);
        evidence.expectEquals("RRF 하이브리드는 둘 다 상위에 올린다", 1.0, hybridRecall);
        evidence.expect("RRF 통합 상위 3건 안에 정답이 모두 들어간다",
                fused.subList(0, TOP_K).containsAll(GROUND_TRUTH));

        evidence.note("RRF 는 점수 스케일이 다른 검색기(BM25 점수 vs 코사인 유사도)를 정규화 없이 합칠 수 있어 실무에서 자주 쓰인다.");
        evidence.note("다음 단계는 리랭킹이다 — 벡터로 후보를 50건 넉넉히 뽑고 크로스 인코더로 5건으로 좁혀 재현율과 컨텍스트 정밀도를 양립시킨다.");
        evidence.note("평가 데이터셋이 없으면 이 표 자체를 만들 수 없다. 수십 건이라도 먼저 만드는 것이 출발점인 이유다.");
    }

    /** score(d) = Σ 1 / (k + rank_i(d)) — 순위만 쓰므로 점수 스케일이 달라도 합칠 수 있다. */
    private List<String> reciprocalRankFusion(List<List<String>> rankings) {
        Map<String, Double> scores = new HashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        List<String> fused = new ArrayList<>(scores.keySet());
        fused.sort(Comparator.comparingDouble((String doc) -> scores.get(doc)).reversed()
                .thenComparing(Comparator.<String>naturalOrder()));
        return fused;
    }

    private double recallAtK(List<String> ranking, int k) {
        long hits = ranking.subList(0, Math.min(k, ranking.size())).stream()
                .filter(GROUND_TRUTH::contains)
                .count();
        return (double) hits / GROUND_TRUTH.size();
    }
}
