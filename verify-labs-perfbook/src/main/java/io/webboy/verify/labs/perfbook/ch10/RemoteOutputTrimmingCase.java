package io.webboy.verify.labs.perfbook.ch10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 10장 — "출력을 줄여라": 같은 정보라도 바이트를 줄이면 원격 클라이언트가 빨라진다. (PERF-10C)
 *
 * <p>책 10장의 실측은 같은 페이지가 LAN 에서 20ms, 대역폭이 낮은 링크에서 1003ms 였다 —
 * <b>느린 링크일수록 응답 시간을 지배하는 것은 서버가 아니라 바이트</b>라는 것이 요지다.
 * 그래서 이 명제는 링크가 병목이 아닌 곳에서 재면 근거가 되지 못한다. 이 랩이 오래
 * "못 하는 것"(docs/02 §9-1)으로 분류했던 이유이고, EC2 배포(docs/11)가 그 전제 조건을 열었다.
 *
 * <p>구조: 이 모듈의 앱이 같은 정보를 두 표현으로 서빙한다({@code /payload/full} 장황판,
 * {@code /payload/trimmed} 다듬은 판 — {@code PayloadController}). 이 케이스는
 * {@code VERIFY_REMOTE_BASE_URL} 이 가리키는 배포본을 HTTP 로 재서 확인한다.
 *
 * <p>판정의 정직성 규칙:
 * <ul>
 *   <li><b>내용 동치·바이트 감소는 어디서든 결정적으로</b> 확인한다 — 이건 페이로드 설계의 성질이다.</li>
 *   <li><b>시간 비교는 원격 링크에서만</b> 단정한다. 대상이 루프백/사설망이면 명제의 조건
 *       (대역폭 제약)이 성립하지 않으므로 INCONCLUSIVE 로 남긴다 — 통과시키지 않는다.</li>
 * </ul>
 */
@Component
public class RemoteOutputTrimmingCase extends VerificationCase {

    private static final int ROUNDS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return "PERF-10C";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 10장 — 같은 정보를 주는데 응답을 다듬으면 원격 클라이언트가 정말 빨라지나?";
    }

    @Override
    public String claim() {
        return "정보를 줄이지 않고 표현만 다듬어도(장황한 필드 이름·반복 고지문·pretty print 제거) "
                + "전송량이 자릿수로 줄고, 원격 링크에서는 그 차이가 클라이언트의 체감 시간이 된다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 원격 배포가 없으면 게이트로 INCONCLUSIVE — 그것이 설계다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String baseUrl = System.getenv().getOrDefault("VERIFY_REMOTE_BASE_URL",
                System.getProperty("verify.remote.base-url", ""));
        if (baseUrl.isBlank()) {
            evidence.expectFlaky("원격 배포본이 있어야 검증할 수 있다 — VERIFY_REMOTE_BASE_URL 이 비어 있다", false);
            evidence.note("EC2 에 배포한 뒤 VERIFY_REMOTE_BASE_URL=http://<호스트>:8080 로 다시 돌린다 — "
                    + "절차는 docs/11 §5, 워크플로는 .github/workflows/remote-network.yml.");
            return;
        }
        evidence.fact("대상", baseUrl);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String full;
        String trimmed;
        try {
            fetch(client, baseUrl + "/payload/health");     // 접속 확인 겸 커넥션 워밍업
            full = fetch(client, baseUrl + "/payload/full");
            trimmed = fetch(client, baseUrl + "/payload/trimmed");
        } catch (Exception e) {
            evidence.expectFlaky("대상에 접속할 수 있어야 검증할 수 있다 — 지금은 접속되지 않는다", false);
            evidence.note("접속 오류: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " — 보안 그룹의 서버 포트 인바운드와 서버 기동 여부를 확인한다(docs/11 §5).");
            return;
        }

        // ① 페이로드 설계의 성질 — 어디서든 결정적
        Summary fullSummary = summarizeFull(full);
        Summary trimmedSummary = summarizeTrimmed(trimmed);
        evidence.fact("장황판 크기", full.getBytes().length + " bytes");
        evidence.fact("다듬은 판 크기", trimmed.getBytes().length + " bytes");
        evidence.fact("크기 비율", String.format("%.1f배", (double) full.getBytes().length / trimmed.getBytes().length));
        evidence.expect("정보는 같다 — 행 수·가격 합계·경계 심볼이 일치한다", fullSummary.equals(trimmedSummary));
        evidence.expect("표현만 다듬어도 전송량이 5배 이상 준다",
                trimmed.getBytes().length * 5 <= full.getBytes().length);

        // ② 시간 — 원격 링크에서만 단정한다
        boolean nearby = isNearby(URI.create(baseUrl).getHost());
        long fullMicros = Long.MAX_VALUE;
        long trimmedMicros = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {       // 번갈아 재서 순간 부하를 공평하게 나눈다
            fullMicros = Math.min(fullMicros, timedFetch(client, baseUrl + "/payload/full"));
            trimmedMicros = Math.min(trimmedMicros, timedFetch(client, baseUrl + "/payload/trimmed"));
        }
        evidence.fact("장황판 응답 시간 (" + ROUNDS + "회 최솟값)", fullMicros + " us");
        evidence.fact("다듬은 판 응답 시간", trimmedMicros + " us");
        evidence.fact("시간 비율", String.format("%.2f배", (double) fullMicros / Math.max(1, trimmedMicros)));

        if (nearby) {
            evidence.expectFlaky("시간 차이는 원격 링크에서만 단정한다 — 대상이 루프백/사설망이라 "
                    + "대역폭 제약이라는 명제의 조건이 성립하지 않는다", false);
            evidence.note("루프백 측정값은 참고로만 남긴다. 여기서 관측되는 차이는 대역폭이 아니라 "
                    + "**서버가 큰 응답을 만들고 소켓에 복사하는 비용**이다 — 방향은 같아 보여도 "
                    + "원인이 다르므로 이 주장의 근거로 쓰지 않는다. (실제로 이 랩의 루프백 측정에서도 "
                    + "3배가 넘게 벌어졌다. '루프백이면 차이가 사라진다'가 아니라 "
                    + "'루프백에서는 링크 대역폭 성분만 사라진다'가 정확한 표현이다.)");
            return;
        }
        evidence.expect("측정 해상도가 확보된다", trimmedMicros > 0);
        evidence.expectFlaky("원격 링크에서는 다듬은 판이 유의미하게 빠르다 (최소 1.3배)",
                trimmedMicros * 13 <= fullMicros * 10);
        evidence.note("비율은 링크 대역폭·RTT 에 좌우된다 — 책의 50배(20ms vs 1003ms)는 느린 무선 링크 "
                + "기준이고, 러너↔EC2 같은 고대역 WAN 에서는 훨씬 작게 나온다. 방향만 단정한다. "
                + "HTTP 압축은 서버에서 일부러 끈 상태다 — 다듬기와 압축은 별개의 단계다(10장).");
    }

    private static String fetch(HttpClient client, String url) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(url + " → HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static long timedFetch(HttpClient client, String url) throws Exception {
        long began = System.nanoTime();
        String body = fetch(client, url);
        long micros = (System.nanoTime() - began) / 1_000L;
        if (body.isEmpty()) {
            throw new IllegalStateException("빈 응답: " + url);
        }
        return micros;
    }

    /** 루프백이거나 사설 대역이면 "원격"이 아니다 — 시간 단정을 하지 않는 기준. */
    private static boolean isNearby(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress();
        } catch (Exception e) {
            return true;   // 판별이 안 되면 단정하지 않는 쪽으로
        }
    }

    /** 두 표현이 같은 정보인지 확인하는 요약 — 행 수·가격 합계·경계 심볼. */
    private record Summary(int rows, long priceSum, String firstSymbol, String lastSymbol) {}

    private static Summary summarizeFull(String body) throws Exception {
        JsonNode records = JSON.readTree(body).get("stockQuotationRecords");
        long sum = 0;
        for (JsonNode record : records) {
            sum += record.get("tradedPriceInMinorCurrencyUnits").asLong();
        }
        return new Summary(records.size(), sum,
                records.get(0).get("stockTickerSymbolIdentifier").asText(),
                records.get(records.size() - 1).get("stockTickerSymbolIdentifier").asText());
    }

    private static Summary summarizeTrimmed(String body) throws Exception {
        JsonNode quotes = JSON.readTree(body).get("q");
        long sum = 0;
        for (JsonNode quote : quotes) {
            sum += quote.get(1).asLong();
        }
        return new Summary(quotes.size(), sum,
                quotes.get(0).get(0).asText(),
                quotes.get(quotes.size() - 1).get(0).asText());
    }
}
