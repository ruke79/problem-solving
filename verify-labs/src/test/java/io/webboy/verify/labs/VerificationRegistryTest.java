package io.webboy.verify.labs;

import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.core.VerificationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VerificationRegistryTest {

    @Autowired
    private VerificationRegistry registry;

    @Test
    void 케이스_메타데이터가_모두_채워져_있다() {
        List<VerificationCase> cases = registry.all();
        assertThat(cases).isNotEmpty();
        for (VerificationCase c : cases) {
            assertThat(c.id()).as("id").isNotBlank();
            assertThat(c.category()).as(c.id() + " category").isNotBlank();
            assertThat(c.question()).as(c.id() + " question").isNotBlank();
            assertThat(c.claim()).as(c.id() + " claim").isNotBlank();
        }
    }

    @Test
    void 분류가_예상_범위_안에_있다() {
        assertThat(registry.categories())
                .containsExactlyInAnyOrder("ai", "api", "concurrency", "db", "jpa", "jvm", "msa", "observability", "resilience", "security", "spring");
    }
}
