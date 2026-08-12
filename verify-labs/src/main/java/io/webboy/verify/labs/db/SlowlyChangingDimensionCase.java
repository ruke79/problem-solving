package io.webboy.verify.labs.db;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Q1 — 분석용 데이터 모델에서 "구매 시점의 값"을 지키는 방법.
 *
 * <p>디멘션을 덮어쓰면(Type 1) 과거 매출이 <b>현재 가격</b>으로 계산되어 왜곡된다.
 * 이력을 남기면(Type 2) 그 시점의 값으로 집계된다. 가격을 올린 뒤 같은 과거 구간을 집계해 비교한다.
 */
@Component
public class SlowlyChangingDimensionCase extends VerificationCase {

    private final JdbcTemplate jdbc;

    public SlowlyChangingDimensionCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "DB-20";
    }

    @Override
    public String category() {
        return "db";
    }

    @Override
    public String question() {
        return "대규모 EC 구매 데이터를 분석하려면 데이터 모델을 어떻게 설계합니까?";
    }

    @Override
    public String claim() {
        return "상품 가격이나 고객 등급처럼 시간에 따라 변하는 디멘션을 덮어쓰면 과거 매출이 현재 값으로 다시 계산되어 왜곡된다. SCD Type 2 로 이력을 남기면 '구매한 시점의 값'으로 집계된다";
    }

    @Override
    protected void verify(Evidence evidence) {
        setUp();

        long beforeChange = revenueType1();

        // 상품 가격을 1000 → 2000 으로 인상한다 (과거 주문은 1000 에 팔렸다)
        jdbc.update("UPDATE dim_product_type1 SET price = 2000 WHERE product_id = 1");
        jdbc.update("UPDATE dim_product_type2 SET valid_to = DATE '2025-06-01' WHERE product_id = 1 AND valid_to IS NULL");
        jdbc.update("INSERT INTO dim_product_type2 (product_id, price, valid_from, valid_to) VALUES (1, 2000, DATE '2025-06-01', NULL)");

        long type1After = revenueType1();
        long type2After = revenueType2();
        long actualPaid = jdbc.queryForObject("SELECT sum(quantity * unit_price) FROM fact_order", Long.class);

        evidence.fact("과거 주문", "2025-01-15 에 개당 1000 으로 3개 (실제 결제액 3000)");
        evidence.fact("가격 인상", "2025-06-01 부터 2000");
        evidence.fact("가격 인상 전 Type 1 집계", beforeChange);
        evidence.fact("가격 인상 후 Type 1 집계 (덮어쓰기)", type1After);
        evidence.fact("가격 인상 후 Type 2 집계 (이력 유지)", type2After);
        evidence.fact("팩트 테이블에 박제된 실제 결제액", actualPaid);

        evidence.expectEquals("Type 1 은 과거 매출이 현재 가격으로 다시 계산된다", 6000L, type1After);
        evidence.expectEquals("Type 2 는 구매 시점 가격으로 집계된다", 3000L, type2After);
        evidence.expectEquals("Type 2 집계는 실제 결제액과 일치한다", actualPaid, type2After);
        evidence.expect("가격 변경 전후로 Type 1 의 과거 집계가 달라진다", beforeChange != type1After);

        cleanUp();

        evidence.note("가장 확실한 방어는 팩트 테이블에 '그때의 값'을 함께 박제하는 것이다(unit_price). 디멘션 조인 없이도 과거가 고정된다 — 이 케이스에서 Type 2 집계와 실제 결제액이 일치하는 이유다.");
        evidence.note("그래도 SCD Type 2 가 필요한 이유는 가격 외의 속성(고객 등급·카테고리·담당 조직) 때문이다. '그때 이 고객은 골드였는가'를 물으려면 이력이 있어야 한다.");
        evidence.note("Type 2 는 행이 늘어난다. valid_from/valid_to 범위 조인이 필수라 (product_id, valid_from) 복합 인덱스가 사실상 강제된다(DB-14).");
        evidence.note("OLTP 를 직접 분석 쿼리로 때리지 않는 것이 전제다. CDC(DB-10)로 분석계에 흘려보내는 구성이 Q1 답변의 앞부분이다.");
    }

    private long revenueType1() {
        return jdbc.queryForObject(
                "SELECT sum(f.quantity * d.price) FROM fact_order f "
                        + "JOIN dim_product_type1 d ON d.product_id = f.product_id", Long.class);
    }

    private long revenueType2() {
        return jdbc.queryForObject(
                "SELECT sum(f.quantity * d.price) FROM fact_order f "
                        + "JOIN dim_product_type2 d ON d.product_id = f.product_id "
                        + "AND f.ordered_at >= d.valid_from AND (d.valid_to IS NULL OR f.ordered_at < d.valid_to)",
                Long.class);
    }

    private void setUp() {
        cleanUp();
        jdbc.execute("CREATE TABLE dim_product_type1 (product_id int PRIMARY KEY, price int)");
        jdbc.execute("CREATE TABLE dim_product_type2 (id serial PRIMARY KEY, product_id int, price int, valid_from date, valid_to date)");
        jdbc.execute("CREATE TABLE fact_order (id serial PRIMARY KEY, product_id int, quantity int, unit_price int, ordered_at date)");

        jdbc.update("INSERT INTO dim_product_type1 VALUES (1, 1000)");
        jdbc.update("INSERT INTO dim_product_type2 (product_id, price, valid_from, valid_to) VALUES (1, 1000, DATE '2024-01-01', NULL)");
        jdbc.update("INSERT INTO fact_order (product_id, quantity, unit_price, ordered_at) VALUES (1, 3, 1000, DATE '2025-01-15')");
    }

    private void cleanUp() {
        jdbc.execute("DROP TABLE IF EXISTS dim_product_type1");
        jdbc.execute("DROP TABLE IF EXISTS dim_product_type2");
        jdbc.execute("DROP TABLE IF EXISTS fact_order");
    }
}
