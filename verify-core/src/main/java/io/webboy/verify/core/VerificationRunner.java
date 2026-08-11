package io.webboy.verify.core;

import java.util.List;

public class VerificationRunner {

    private final VerificationRegistry registry;

    public VerificationRunner(VerificationRegistry registry) {
        this.registry = registry;
    }

    public VerificationRegistry registry() {
        return registry;
    }

    public List<VerificationResult> runAll() {
        return registry.all().stream().map(VerificationCase::execute).toList();
    }

    public List<VerificationResult> runCategory(String category) {
        return registry.byCategory(category).stream().map(VerificationCase::execute).toList();
    }

    public VerificationResult runOne(String id) {
        return registry.find(id)
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 케이스 id: " + id))
                .execute();
    }
}
