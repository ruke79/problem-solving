package io.webboy.verify.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public final class VerificationReport {

    private VerificationReport() {
    }

    public static String toConsole(List<VerificationResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================== 면접 답변 검증 결과 ====================\n");
        sb.append(String.format("%-12s %-12s %-14s %8s  %s%n", "ID", "CATEGORY", "VERDICT", "TIME(ms)", "CLAIM"));
        sb.append("-".repeat(110)).append('\n');
        for (VerificationResult r : results) {
            sb.append(String.format("%-12s %-12s %-14s %8d  %s%n",
                    r.id(), r.category(), badge(r), r.elapsedMillis(), truncate(r.claim(), 60)));
        }
        sb.append("-".repeat(110)).append('\n');
        sb.append(summaryLine(results)).append('\n');
        return sb.toString();
    }

    public static String summaryLine(List<VerificationResult> results) {
        long confirmed = results.stream().filter(r -> r.verdict() == Verdict.CONFIRMED).count();
        long refuted = results.stream().filter(r -> r.verdict() == Verdict.REFUTED).count();
        long inconclusive = results.stream().filter(r -> r.verdict() == Verdict.INCONCLUSIVE).count();
        long error = results.stream().filter(r -> r.verdict() == Verdict.ERROR).count();
        return "합계 " + results.size() + "건 | CONFIRMED " + confirmed
                + " | REFUTED " + refuted + " | INCONCLUSIVE " + inconclusive + " | ERROR " + error;
    }

    public static String toMarkdown(List<VerificationResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 면접 답변 검증 리포트\n\n");
        sb.append("- 생성 시각: ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append('\n');
        sb.append("- 실행 환경: Java ").append(System.getProperty("java.version"))
          .append(" / ").append(System.getProperty("os.name"))
          .append(" / CPU ").append(Runtime.getRuntime().availableProcessors()).append("코어\n");
        sb.append("- ").append(summaryLine(results)).append("\n\n");

        sb.append("## 요약\n\n");
        sb.append("| ID | 분류 | 판정 | 소요(ms) | 주장 |\n|---|---|---|---|---|\n");
        for (VerificationResult r : results) {
            sb.append("| ").append(r.id())
              .append(" | ").append(r.category())
              .append(" | ").append(badge(r))
              .append(" | ").append(r.elapsedMillis())
              .append(" | ").append(escape(truncate(r.claim(), 80)))
              .append(" |\n");
        }
        sb.append('\n');

        sb.append("## 상세\n\n");
        for (VerificationResult r : results) {
            sb.append("### ").append(r.id()).append(" — ").append(badge(r)).append("\n\n");
            sb.append("**질문**: ").append(r.question()).append("\n\n");
            sb.append("**주장**: ").append(r.claim()).append("\n\n");
            if (r.error() != null) {
                sb.append("**오류**: `").append(r.error()).append("`\n\n");
            }
            if (!r.expectations().isEmpty()) {
                sb.append("**검증 항목**\n\n");
                for (Evidence.Expectation e : r.expectations()) {
                    sb.append("- ").append(e.satisfied() ? "[x] " : "[ ] ")
                      .append(e.description())
                      .append(e.flaky() ? "  _(환경 의존)_" : "")
                      .append('\n');
                }
                sb.append('\n');
            }
            if (!r.facts().isEmpty()) {
                sb.append("**관측값**\n\n| 항목 | 값 |\n|---|---|\n");
                for (Map.Entry<String, String> f : r.facts().entrySet()) {
                    sb.append("| ").append(f.getKey()).append(" | `")
                      .append(escape(f.getValue())).append("` |\n");
                }
                sb.append('\n');
            }
            if (!r.notes().isEmpty()) {
                sb.append("**메모**\n\n");
                for (String n : r.notes()) {
                    sb.append("- ").append(n).append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static Path write(List<VerificationResult> results, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, toMarkdown(results), StandardCharsets.UTF_8);
        return target;
    }

    private static String badge(VerificationResult r) {
        return switch (r.verdict()) {
            case CONFIRMED -> "CONFIRMED";
            case REFUTED -> "REFUTED";
            case INCONCLUSIVE -> r.nondeterministic() ? "INCONCLUSIVE*" : "INCONCLUSIVE";
            case ERROR -> "ERROR";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
