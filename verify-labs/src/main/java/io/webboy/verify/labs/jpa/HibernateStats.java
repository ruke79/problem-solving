package io.webboy.verify.labs.jpa;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Hibernate 통계로 "실제 발생한 SQL 문 수"를 세는 도우미.
 * {@code spring.jpa.properties.hibernate.generate_statistics=true} 가 전제다.
 */
@Component
public class HibernateStats {

    private final Statistics statistics;

    public HibernateStats(EntityManagerFactory emf) {
        this.statistics = emf.unwrap(SessionFactory.class).getStatistics();
        this.statistics.setStatisticsEnabled(true);
    }

    /** block 실행 중 준비된 JDBC statement 수를 반환한다. */
    public synchronized long countStatements(Runnable block) {
        statistics.clear();
        block.run();
        return statistics.getPrepareStatementCount();
    }

    public synchronized <T> Measured<T> measure(Supplier<T> block) {
        statistics.clear();
        T value = block.get();
        return new Measured<>(value, statistics.getPrepareStatementCount(),
                statistics.getEntityUpdateCount(), statistics.getEntityInsertCount());
    }

    public record Measured<T>(T value, long statements, long entityUpdates, long entityInserts) {}
}
