package io.webboy.verify.labs.perfbook.ch10;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * 10장 — 직렬화 "최적화"의 객체 참조 함정.
 *
 * <p>책 10장의 사례: 큰 그래프의 일부를 {@code transient} 로 빼고 {@code writeObject} 에서
 * 손으로 쪼개 쓰는 최적화를 하면, <b>같은 객체를 가리키던 참조 두 개가 별개의 복제본으로
 * 되살아난다.</b> {@link ObjectOutputStream} 의 참조 추적은 <b>한 스트림 세션 안에서만</b>
 * 동작하기 때문이다 — 쪼개 쓰면 추적이 끊긴다.
 *
 * <p>이 케이스는 시간이 아니라 <b>동일성(==)</b>을 재므로 전부 결정적 {@code expect} 다.
 */
@Component
public class SerializationReferenceTrapCase extends VerificationCase {

    @Override
    public String id() {
        return "PERF-10A";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "책 10장 — 직렬화를 손으로 쪼개는 최적화는 무엇을 깨뜨리나?";
    }

    @Override
    public String claim() {
        return "ObjectOutputStream 은 한 스트림 세션 안에서만 참조를 추적한다. 기본 직렬화는 같은 객체를 "
                + "가리키는 참조 두 개를 복원 후에도 같은 객체로 유지하지만, 필드를 쪼개 별도로 쓰면 "
                + "복원 후 별개의 복제본이 된다 — 동일성에 의존하는 코드가 조용히 깨진다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Point shared = new Point(3, 7);

        // (a) 기본 직렬화 — 같은 스트림에 통째로 쓴다
        Pair pair = new Pair(shared, shared);
        Pair restored = (Pair) roundTrip(out -> out.writeObject(pair));
        evidence.fact("기본 직렬화 복원 후 first == second", restored.first == restored.second);
        evidence.expect("기본 직렬화는 참조 동일성을 보존한다", restored.first == restored.second);
        evidence.expect("값도 보존된다", restored.first.equals(shared));

        // (b) "최적화" — 두 필드를 각자 따로 쓴다 (스트림은 같아도 flush/reset 없이 별개 세션처럼
        //     쪼개는 흔한 실수를 재현하기 위해, 아예 별도 스트림 세션으로 쓴다)
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream first = new ObjectOutputStream(bytes)) {
            first.writeObject(shared);
        }
        int firstSegment = bytes.size();
        try (ObjectOutputStream second = new ObjectOutputStream(bytes)) {
            second.writeObject(shared);
        }
        Point restoredFirst;
        Point restoredSecond;
        ByteArrayInputStream in = new ByteArrayInputStream(bytes.toByteArray());
        try (ObjectInputStream firstIn = new ObjectInputStream(in)) {
            restoredFirst = (Point) firstIn.readObject();
        }
        // 첫 스트림이 남긴 위치부터 두 번째 세션을 읽는다
        ByteArrayInputStream rest = new ByteArrayInputStream(bytes.toByteArray(), firstSegment,
                bytes.size() - firstSegment);
        try (ObjectInputStream secondIn = new ObjectInputStream(rest)) {
            restoredSecond = (Point) secondIn.readObject();
        }

        evidence.fact("쪼개 쓴 뒤 복원한 두 참조가 같은 객체인가", restoredFirst == restoredSecond);
        evidence.expect("쪼개 쓰면 같은 객체가 복제본 둘로 갈라진다", restoredFirst != restoredSecond);
        evidence.expect("값은 같아서 equals 로는 이 문제가 안 보인다",
                restoredFirst.equals(restoredSecond));
        evidence.note("책의 결론: 직렬화 최적화는 반드시 참조 그래프를 보고 해야 한다. "
                + "equals 만 검사하는 테스트는 이 회귀를 잡지 못한다 — == 가 갈라진 것이 증거다.");
    }

    private interface Writer {
        void write(ObjectOutputStream out) throws Exception;
    }

    private static Object roundTrip(Writer writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            writer.write(out);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    /** 책의 예제와 같은 단순 값 객체. equals 는 값으로, 함정은 == 로 드러난다. */
    record Point(int x, int y) implements Serializable {}

    record Pair(Point first, Point second) implements Serializable {}
}
