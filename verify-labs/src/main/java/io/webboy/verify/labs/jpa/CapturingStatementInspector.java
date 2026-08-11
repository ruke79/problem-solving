package io.webboy.verify.labs.jpa;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Hibernate 가 실제로 발행한 SQL 문자열을 잡아 두는 인스펙터.
 * JPA-06 에서 "컬렉션 fetch join + 페이징" 의 SQL 에 limit 절이 없다는 것을 증명하는 데 쓴다.
 *
 * <p>start() 를 호출한 스레드에서만 수집하므로 평소에는 아무 비용도 남기지 않는다.
 */
public class CapturingStatementInspector implements StatementInspector {

    private static final ThreadLocal<List<String>> BUFFER = new ThreadLocal<>();

    public static void start() {
        BUFFER.set(new ArrayList<>());
    }

    public static List<String> stop() {
        List<String> captured = BUFFER.get();
        BUFFER.remove();
        return captured == null ? List.of() : List.copyOf(captured);
    }

    @Override
    public String inspect(String sql) {
        List<String> buffer = BUFFER.get();
        if (buffer != null) {
            buffer.add(sql);
        }
        return sql;
    }
}
