package io.webboy.verify.labs.db;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.List;

/**
 * Q18 — 누수를 "의심"하는 단계에서 "범인 메서드를 지목"하는 단계로 넘어가게 해 주는 설정.
 *
 * <p>{@code DB-06} 은 지표 모양(active·usage·pending)으로 누수와 슬로우 쿼리를 <b>구분</b>했다.
 * 여기서는 그다음 — {@code leakDetectionThreshold} 를 켜면 임계치를 넘겨 반환되지 않은 커넥션에 대해
 * <b>빌린 시점의 스택 트레이스</b>가 경고 로그에 실제로 찍히는지를 로그를 잡아 확인한다.
 */
@Component
public class LeakDetectionCase extends VerificationCase {

    /** HikariCP 는 2초 미만이면 누수 감지를 조용히 비활성화한다 — 그래서 최소값을 지켜야 한다. */
    private static final long THRESHOLD_MILLIS = 2_000L;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public LeakDetectionCase(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:verifylab}") String username,
            @Value("${spring.datasource.password:verifylab}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public String id() {
        return "DB-18";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "커넥션 누수가 의심될 때 Spring Boot 에서 어떻게 탐지·조사합니까?";
    }

    @Override
    public String claim() {
        return "leakDetectionThreshold 를 켜면 임계치를 넘겨 반환되지 않은 커넥션에 대해 '빌린 시점의 스택 트레이스'가 경고 로그로 남아 원인 메서드를 직접 지목할 수 있다 — 지표만 보고 추측하는 단계에서 벗어난다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // 감시 스레드의 타이밍에 좌우된다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Logger poolLogger = (Logger) LoggerFactory.getLogger("com.zaxxer.hikari.pool.ProxyLeakTask");
        Level originalLevel = poolLogger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        poolLogger.addAppender(captured);
        poolLogger.setLevel(Level.WARN);

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(2);
            config.setLeakDetectionThreshold(THRESHOLD_MILLIS);
            config.setPoolName("leak-detection");

            try (HikariDataSource pool = new HikariDataSource(config)) {
                Connection leaked = pool.getConnection();          // 일부러 반납하지 않는다
                leaked.createStatement().execute("SELECT 1");
                Thread.sleep(THRESHOLD_MILLIS * 2);
                leaked.close();
            }

            List<ILoggingEvent> events = List.copyOf(captured.list);
            boolean warned = events.stream().anyMatch(e -> e.getLevel() == Level.WARN);
            boolean hasStackTrace = events.stream()
                    .anyMatch(e -> e.getThrowableProxy() != null || e.getFormattedMessage().contains("stack trace"));
            String culprit = events.stream()
                    .filter(e -> e.getThrowableProxy() != null)
                    .findFirst()
                    .map(e -> topFrames(e))
                    .orElse("(스택 없음)");

            evidence.fact("leakDetectionThreshold(ms)", THRESHOLD_MILLIS);
            evidence.fact("경고 로그 건수", events.size());
            evidence.fact("첫 경고 메시지", events.isEmpty() ? "(없음)" : events.get(0).getFormattedMessage());
            evidence.fact("경고에 실린 스택 트레이스(앞부분)", culprit);

            evidence.expect("임계치를 넘긴 커넥션에 대해 경고가 남는다", warned);
            evidence.expect("경고에는 '어디서 빌렸는지' 스택 트레이스가 함께 실린다", hasStackTrace);
            evidence.expect("스택 트레이스가 이 케이스 코드를 가리킨다", culprit.contains("LeakDetectionCase"));
        } finally {
            poolLogger.detachAppender(captured);
            poolLogger.setLevel(originalLevel);
        }

        evidence.note("임계치를 2초 미만으로 주면 HikariCP 가 '너무 짧다'며 감지를 조용히 꺼 버린다(경고 로그 한 줄이 전부다). 켰다고 생각했는데 안 켜져 있는 흔한 함정이라, 설정 후 실제로 경고가 나오는지 한 번은 확인해야 한다.");
        evidence.note("이 로그는 '느린 쿼리'가 아니라 '반환되지 않은 대여'를 잡는다. 임계치를 넘겨도 나중에 반환되면 Hikari 는 오탐이었다는 로그를 추가로 남긴다 — 그래서 상시 켜 둘 수 있을 만큼 가볍다.");
        evidence.note("임계치는 '정상 처리의 최대 시간'보다 넉넉히 잡는다. 너무 짧으면 배치나 대용량 처리가 매번 경고로 잡혀 로그가 무의미해진다.");
        evidence.note("DB-06 이 지표로 '누수인가 슬로우 쿼리인가'를 가르고, 이 설정이 '누수라면 어느 코드인가'를 지목한다. 둘은 순서대로 쓰는 도구다.");
        evidence.note("실무에서 가장 흔한 범인 3종은 try-with-resources 누락, @Transactional 안의 외부 API 호출, OSIV 로 뷰 렌더링까지 세션 유지다(JPA-03 참고).");
    }

    private String topFrames(ILoggingEvent event) {
        var proxy = event.getThrowableProxy();
        StringBuilder sb = new StringBuilder();
        var frames = proxy.getStackTraceElementProxyArray();
        for (int i = 0; i < Math.min(frames.length, 12); i++) {
            String line = frames[i].getSTEAsString();
            if (line.contains("io.webboy.verify")) {
                sb.append(line).append(" | ");
            }
        }
        return sb.length() == 0 ? frames.length + "프레임 (앱 코드 없음)" : sb.toString();
    }
}
