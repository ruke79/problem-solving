package io.webboy.verify.labs.kafka;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.springframework.stereotype.Component;

/**
 * Q146 (Q4·Q51 의 심화) — 전방 호환과 후방 호환은 방향이 반대다.
 *
 * <p>말로는 자주 섞이는 개념이라 Avro 의 호환성 검사기로 직접 판정한다.
 * <ul>
 *   <li><b>후방 호환</b>: 새 스키마의 <b>컨슈머</b>가 옛 데이터를 읽을 수 있다 → 컨슈머 먼저 배포 가능</li>
 *   <li><b>전방 호환</b>: 옛 스키마의 컨슈머가 <b>새 데이터</b>를 읽을 수 있다 → 프로듀서 먼저 배포 가능</li>
 * </ul>
 *
 * <p>Schema Registry 가 하는 일이 정확히 이 판정이고, Q4 의 포이즌 필 사고를 미연에 막는 장치다.
 */
@Component
public class SchemaCompatibilityCase extends VerificationCase {

    private static final String BASE = """
            {"type":"record","name":"Order","fields":[
              {"name":"id","type":"string"},
              {"name":"amount","type":"int"}
            ]}""";

    private static final String FIELD_ADDED_WITH_DEFAULT = """
            {"type":"record","name":"Order","fields":[
              {"name":"id","type":"string"},
              {"name":"amount","type":"int"},
              {"name":"currency","type":"string","default":"JPY"}
            ]}""";

    private static final String FIELD_ADDED_WITHOUT_DEFAULT = """
            {"type":"record","name":"Order","fields":[
              {"name":"id","type":"string"},
              {"name":"amount","type":"int"},
              {"name":"currency","type":"string"}
            ]}""";

    private static final String FIELD_REMOVED = """
            {"type":"record","name":"Order","fields":[
              {"name":"id","type":"string"}
            ]}""";

    private static final String TYPE_CHANGED = """
            {"type":"record","name":"Order","fields":[
              {"name":"id","type":"string"},
              {"name":"amount","type":"string"}
            ]}""";

    @Override
    public String id() {
        return "KAFKA-07";
    }

    @Override
    public String category() {
        return "kafka";
    }

    @Override
    public String question() {
        return "스키마 진화에 어떻게 대응합니까? 전방 호환과 후방 호환의 차이는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "기본값이 있는 필드 추가만이 양방향으로 안전하다. 기본값 없는 필드 추가는 옛 데이터를 못 읽어 후방 호환이 깨지고, 필드 삭제는 옛 컨슈머가 새 데이터를 못 읽어 전방 호환이 깨지며, 타입 변경은 양쪽 다 깨진다";
    }

    @Override
    protected void verify(Evidence evidence) {
        boolean addWithDefaultBackward = readerCanRead(FIELD_ADDED_WITH_DEFAULT, BASE);
        boolean addWithDefaultForward = readerCanRead(BASE, FIELD_ADDED_WITH_DEFAULT);
        boolean addNoDefaultBackward = readerCanRead(FIELD_ADDED_WITHOUT_DEFAULT, BASE);
        boolean removeBackward = readerCanRead(FIELD_REMOVED, BASE);
        boolean removeForward = readerCanRead(BASE, FIELD_REMOVED);
        boolean typeChangeBackward = readerCanRead(TYPE_CHANGED, BASE);
        boolean typeChangeForward = readerCanRead(BASE, TYPE_CHANGED);

        evidence.fact("기준 스키마", "Order { id: string, amount: int }");
        evidence.fact("[기본값 있는 필드 추가] 후방 호환(새 컨슈머 ← 옛 데이터)", addWithDefaultBackward);
        evidence.fact("[기본값 있는 필드 추가] 전방 호환(옛 컨슈머 ← 새 데이터)", addWithDefaultForward);
        evidence.fact("[기본값 없는 필드 추가] 후방 호환", addNoDefaultBackward);
        evidence.fact("[필드 삭제] 후방 호환", removeBackward);
        evidence.fact("[필드 삭제] 전방 호환", removeForward);
        evidence.fact("[타입 변경 int→string] 후방 호환", typeChangeBackward);
        evidence.fact("[타입 변경 int→string] 전방 호환", typeChangeForward);

        evidence.expect("기본값이 있는 필드 추가는 후방 호환된다 — 컨슈머를 먼저 배포해도 안전",
                addWithDefaultBackward);
        evidence.expect("기본값이 있는 필드 추가는 전방 호환도 된다 — 옛 컨슈머는 새 필드를 무시한다",
                addWithDefaultForward);
        evidence.expect("기본값 없는 필드 추가는 후방 호환이 깨진다 — 옛 데이터에 그 필드가 없다",
                !addNoDefaultBackward);
        evidence.expect("필드 삭제는 전방 호환이 깨진다 — 옛 컨슈머가 기대하는 필드가 사라졌다",
                !removeForward);
        evidence.expect("타입 변경은 양방향 모두 깨진다", !typeChangeBackward && !typeChangeForward);

        evidence.note("실무에서 어느 쪽을 강제할지는 '누구를 먼저 배포하는가'로 정해진다. 컨슈머를 먼저 올리는 조직은 후방 호환(BACKWARD)을, 프로듀서를 먼저 올리는 조직은 전방 호환(FORWARD)을 건다.");
        evidence.note("Q4 의 포이즌 필 사고 — 스키마 변경으로 추가된 필드의 역직렬화가 실패해 같은 메시지를 무한 재시도하며 파티션이 멈추는 것 — 이 바로 이 검사를 안 걸었을 때 나는 사고다(KAFKA-03 이 그 정지를 실측한다).");
        evidence.note("허용되는 변경이 '기본값 있는 필드 추가'뿐이라는 점은 Q108 의 API 버저닝에서 말한 '가산적 변경'과 같은 원칙이다 — 계약을 깨지 않고 늘리기만 한다.");
        evidence.note("이 판정은 Schema Registry 서버 없이 Avro 라이브러리만으로 한 것이다. 실제 운영에서는 레지스트리가 등록 시점에 같은 규칙으로 거절해 주는 것이 핵심 가치다.");
    }

    /** reader 스키마로 writer 가 쓴 데이터를 읽을 수 있는가. */
    private boolean readerCanRead(String readerSchema, String writerSchema) {
        Schema reader = new Schema.Parser().parse(readerSchema);
        Schema writer = new Schema.Parser().parse(writerSchema);
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer)
                .getType() == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE;
    }
}
