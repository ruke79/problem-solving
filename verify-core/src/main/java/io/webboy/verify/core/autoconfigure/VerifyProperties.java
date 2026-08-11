package io.webboy.verify.core.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "verify")
public class VerifyProperties {

    /** 검증 하네스 전체 활성화 여부 */
    private boolean enabled = true;

    /** 애플리케이션 기동 직후 전체 케이스를 실행하고 콘솔에 출력할지 */
    private boolean runOnStartup = false;

    /** 기동 실행 결과를 마크다운으로 저장할 경로 (비우면 저장하지 않음) */
    private String reportPath = "build/reports/verification.md";

    /** REST 엔드포인트 base path */
    private String basePath = "/verify";

    private final Web web = new Web();

    public static class Web {
        /** REST 엔드포인트 노출 여부 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRunOnStartup() {
        return runOnStartup;
    }

    public void setRunOnStartup(boolean runOnStartup) {
        this.runOnStartup = runOnStartup;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public Web getWeb() {
        return web;
    }
}
