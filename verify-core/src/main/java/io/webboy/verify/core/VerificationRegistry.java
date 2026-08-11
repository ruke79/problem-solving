package io.webboy.verify.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VerificationRegistry {

    private final Map<String, VerificationCase> byId = new LinkedHashMap<>();

    public VerificationRegistry(List<VerificationCase> cases) {
        List<VerificationCase> sorted = new ArrayList<>(cases);
        sorted.sort(Comparator.comparing(VerificationCase::category).thenComparing(VerificationCase::id));
        for (VerificationCase c : sorted) {
            VerificationCase previous = byId.put(c.id(), c);
            if (previous != null) {
                throw new IllegalStateException("중복된 케이스 id: " + c.id()
                        + " (" + previous.getClass().getName() + " vs " + c.getClass().getName() + ")");
            }
        }
    }

    public List<VerificationCase> all() {
        return List.copyOf(byId.values());
    }

    public List<VerificationCase> byCategory(String category) {
        return byId.values().stream()
                .filter(c -> c.category().equalsIgnoreCase(category))
                .toList();
    }

    public List<String> categories() {
        return byId.values().stream().map(VerificationCase::category).distinct().sorted().toList();
    }

    public Optional<VerificationCase> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int size() {
        return byId.size();
    }
}
