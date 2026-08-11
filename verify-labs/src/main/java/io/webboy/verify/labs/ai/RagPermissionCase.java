package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Q59 · Q79 — RAG 에서 가장 놓치기 쉬운 계층은 검색 계층이다. */
@Component
public class RagPermissionCase extends VerificationCase {

    private record Document(String id, String content, Set<String> allowedRoles, double score) {
    }

    private static final List<Document> INDEX = List.of(
            new Document("hr-eval-2026", "임원 인사 평가 결과", Set.of("HR", "EXEC"), 0.95),
            new Document("board-minutes", "이사회 의사록", Set.of("EXEC"), 0.92),
            new Document("leave-policy", "휴가 규정 안내", Set.of("HR", "EXEC", "EMPLOYEE"), 0.88),
            new Document("expense-guide", "경비 정산 가이드", Set.of("HR", "EXEC", "EMPLOYEE"), 0.81));

    private static final int TOP_K = 2;

    @Override
    public String id() {
        return "AI-06";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "사내 LLM 시스템에서 기밀 정보 유출을 어떻게 막습니까?";
    }

    @Override
    public String claim() {
        return "가장 놓치기 쉬운 곳이 검색 계층이다. 문서에 권한 메타데이터를 붙여 검색 시점에 필터링해야 하며, 상위 k 를 뽑은 뒤 거르는 후필터는 결과가 비는 문제가 생긴다";
    }

    @Override
    protected void verify(Evidence evidence) {
        String role = "EMPLOYEE";

        List<String> noFilter = INDEX.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(TOP_K)
                .map(Document::id)
                .toList();

        List<String> postFilter = INDEX.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(TOP_K)
                .filter(d -> d.allowedRoles().contains(role))
                .map(Document::id)
                .toList();

        List<String> preFilter = INDEX.stream()
                .filter(d -> d.allowedRoles().contains(role))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(TOP_K)
                .map(Document::id)
                .toList();

        boolean leaked = noFilter.contains("hr-eval-2026") || noFilter.contains("board-minutes");

        evidence.fact("요청자 역할", role);
        evidence.fact("필터 없음 - LLM 에 넘어가는 문서", noFilter);
        evidence.fact("후필터(top-k 후 제거)", postFilter);
        evidence.fact("전필터(검색 시 권한 조건 적용)", preFilter);
        evidence.fact("기밀 문서 유출 여부(필터 없음)", leaked);

        evidence.expect("필터가 없으면 인사·임원 문서가 일반 직원 답변에 섞인다", leaked);
        evidence.expectEquals("후필터는 컨텍스트가 비어버린다", 0, postFilter.size());
        evidence.expectEquals("전필터는 허용 문서로 top-k 를 채운다", TOP_K, preFilter.size());
        evidence.expect("전필터 결과에는 기밀 문서가 없다",
                preFilter.stream().noneMatch(id -> id.equals("hr-eval-2026") || id.equals("board-minutes")));

        evidence.note("기존 권한 모델을 그대로 벡터 검색에 가져오는 설계가 필요하다 — 벡터 DB 에 넣는 순간 전 직원이 전 문서에 접근 가능해지기 쉽다.");
        evidence.note("프롬프트 인젝션 방어의 본질도 같다: LLM 의 출력을 신뢰하지 않고, LLM 이 호출 가능한 툴의 권한을 사용자 원래 권한 이상으로 넓히지 않는다.");
        evidence.note("시스템 프롬프트에 기밀 자체를 쓰지 않으면 유출될 방법이 없다 — '이 키는 알려주지 마'라고 쓰는 것은 대책이 아니다.");
    }
}
