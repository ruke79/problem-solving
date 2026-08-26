package io.webboy.verify.labs.perfbook.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PERF-10C 의 검증 대상 서버 — 같은 정보를 두 가지 출력으로 서빙한다.
 *
 * <p>책 10장의 첫 번째 권고("출력을 줄여라")를 재기 위한 장치다. 두 엔드포인트는
 * <b>정확히 같은 정보</b>(종목 {@value #ROWS}개의 심볼·가격)를 담는다 —
 * 다른 것은 표현뿐이다.
 *
 * <ul>
 *   <li>{@code /payload/full} — 흔한 "그냥 다 내보내는" API. 장황한 필드 이름, 행마다 반복되는
 *       고지문·통화·거래소 코드, pretty print. 책이 지적하는 바로 그 출력이다.</li>
 *   <li>{@code /payload/trimmed} — 클라이언트가 실제로 쓰는 것만. 짧은 키, 공백 없음.</li>
 * </ul>
 *
 * <p>HTTP 압축은 <b>일부러 켜지 않는다</b>(Spring Boot 기본값 그대로) — 이 케이스가 재는 것은
 * "다듬기"이고, 압축은 책이 그 다음 단계로 논하는 별개의 트레이드오프다.
 *
 * <p>데이터는 시드 고정 결정적이다 — 클라이언트(케이스)가 두 응답의 내용 동치를
 * 행 수·가격 합계·경계 심볼로 검증할 수 있어야 하기 때문이다.
 */
@RestController
@RequestMapping("/payload")
public class PayloadController {

    static final int ROWS = 2_000;

    /** i 번째 종목의 가격(센트) — 시드 고정. 클라이언트도 같은 식으로 합계를 예측할 수 있다. */
    static long priceCents(int i) {
        return (i * 9973L) % 100_000 + 100;
    }

    static String symbol(int i) {
        return String.format("S%04d", i);
    }

    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "OK";
    }

    @GetMapping(value = "/full", produces = MediaType.APPLICATION_JSON_VALUE)
    public String full() {
        StringBuilder out = new StringBuilder(ROWS * 320);
        out.append("{\n  \"responseMetadata\": {\n")
           .append("    \"apiVersionIdentifier\": \"v1.0.0\",\n")
           .append("    \"totalNumberOfQuotationRecords\": ").append(ROWS).append(",\n")
           .append("    \"responseGeneratedByServiceName\": \"interview-verify-lab-perfbook\"\n")
           .append("  },\n  \"stockQuotationRecords\": [\n");
        for (int i = 0; i < ROWS; i++) {
            out.append("    {\n")
               .append("      \"stockTickerSymbolIdentifier\": \"").append(symbol(i)).append("\",\n")
               .append("      \"tradedPriceInMinorCurrencyUnits\": ").append(priceCents(i)).append(",\n")
               .append("      \"currencyCode\": \"USD\",\n")
               .append("      \"exchangeMarketIdentifierCode\": \"XNAS\",\n")
               .append("      \"regulatoryDisclaimerNotice\": \"This quotation is provided for ")
               .append("informational purposes only and does not constitute investment advice.\"\n")
               .append("    }").append(i < ROWS - 1 ? "," : "").append('\n');
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    @GetMapping(value = "/trimmed", produces = MediaType.APPLICATION_JSON_VALUE)
    public String trimmed() {
        StringBuilder out = new StringBuilder(ROWS * 20);
        out.append("{\"n\":").append(ROWS).append(",\"q\":[");
        for (int i = 0; i < ROWS; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("[\"").append(symbol(i)).append("\",").append(priceCents(i)).append(']');
        }
        out.append("]}");
        return out.toString();
    }
}
