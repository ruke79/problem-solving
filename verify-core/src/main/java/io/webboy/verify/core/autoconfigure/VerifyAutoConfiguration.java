package io.webboy.verify.core.autoconfigure;

import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.core.VerificationRegistry;
import io.webboy.verify.core.VerificationReport;
import io.webboy.verify.core.VerificationResult;
import io.webboy.verify.core.VerificationRunner;
import io.webboy.verify.core.web.VerificationController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(VerifyProperties.class)
@ConditionalOnProperty(prefix = "verify", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VerifyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VerifyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public VerificationRegistry verificationRegistry(ObjectProvider<VerificationCase> cases) {
        return new VerificationRegistry(cases.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public VerificationRunner verificationRunner(VerificationRegistry registry) {
        return new VerificationRunner(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "verify", name = "run-on-startup", havingValue = "true")
    public ApplicationRunner verificationStartupRunner(VerificationRunner runner, VerifyProperties properties) {
        return args -> {
            List<VerificationResult> results = runner.runAll();
            log.info(VerificationReport.toConsole(results));
            String reportPath = properties.getReportPath();
            if (reportPath != null && !reportPath.isBlank()) {
                Path written = VerificationReport.write(results, Path.of(reportPath));
                log.info("검증 리포트 저장: {}", written.toAbsolutePath());
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "verify.web", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class WebEndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        VerificationController verificationController(VerificationRunner runner, VerificationRegistry registry) {
            return new VerificationController(runner, registry);
        }
    }
}
