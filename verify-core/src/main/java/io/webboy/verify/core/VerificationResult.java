package io.webboy.verify.core;

import java.util.List;
import java.util.Map;

public record VerificationResult(
        String id,
        String category,
        String question,
        String claim,
        Verdict verdict,
        boolean nondeterministic,
        long elapsedMillis,
        Map<String, String> facts,
        List<Evidence.Expectation> expectations,
        List<String> notes,
        String error
) {
    public boolean acceptable() {
        return verdict == Verdict.CONFIRMED
                || (nondeterministic && verdict == Verdict.INCONCLUSIVE);
    }
}
