package io.webboy.verify.core.web;

import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import io.webboy.verify.core.VerificationRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${verify.base-path:/verify}")
public class VerificationController {

    private final VerificationRunner runner;
    private final VerificationRegistry registry;

    public VerificationController(VerificationRunner runner, VerificationRegistry registry) {
        this.runner = runner;
        this.registry = registry;
    }

    @GetMapping("/cases")
    public List<Map<String, Object>> cases() {
        return registry.all().stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.id(),
                        "category", c.category(),
                        "question", c.question(),
                        "claim", c.claim(),
                        "nondeterministic", c.nondeterministic()))
                .toList();
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return registry.categories();
    }

    @PostMapping("/run")
    public List<VerificationResult> run(@RequestParam(required = false) String category) {
        return (category == null || category.isBlank()) ? runner.runAll() : runner.runCategory(category);
    }

    @PostMapping("/run/{id}")
    public VerificationResult runOne(@PathVariable String id) {
        return runner.runOne(id);
    }

    @GetMapping(value = "/report.md", produces = MediaType.TEXT_MARKDOWN_VALUE + ";charset=UTF-8")
    public String report(@RequestParam(required = false) String category) {
        List<VerificationResult> results =
                (category == null || category.isBlank()) ? runner.runAll() : runner.runCategory(category);
        return VerificationReport.toMarkdown(results);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("cases", registry.size(), "categories", registry.categories());
    }
}
