package io.webboy.verify.labs.jvm;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

@Component
public class ReferenceTypeCase extends VerificationCase {

    @Override
    public String id() {
        return "JVM-04";
    }

    @Override
    public String category() {
        return "jvm";
    }

    @Override
    public String question() {
        return "Strong / Soft / Weak 참조의 차이는 무엇이고 캐시 구현에 어떻게 쓰입니까?";
    }

    @Override
    public String claim() {
        return "Weak 는 GC 가 돌면 바로 회수되고, Soft 는 메모리가 부족할 때까지 살아남는다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        ReferenceQueue<byte[]> queue = new ReferenceQueue<>();

        byte[] weakTarget = new byte[1024];
        WeakReference<byte[]> weak = new WeakReference<>(weakTarget, queue);

        byte[] softTarget = new byte[1024];
        SoftReference<byte[]> soft = new SoftReference<>(softTarget);

        byte[] strongTarget = new byte[1024];
        WeakReference<byte[]> weakButStronglyHeld = new WeakReference<>(strongTarget);

        weakTarget = null;
        softTarget = null;

        System.gc();
        Thread.sleep(200);
        Object enqueued = queue.poll();

        evidence.fact("GC 후 WeakReference.get()", weak.get() == null ? "null (회수됨)" : "살아 있음");
        evidence.fact("GC 후 SoftReference.get()", soft.get() == null ? "null (회수됨)" : "살아 있음");
        evidence.fact("강한 참조가 남아 있는 WeakReference.get()",
                weakButStronglyHeld.get() == null ? "null" : "살아 있음");
        evidence.fact("ReferenceQueue 에 등록됨", enqueued != null);
        evidence.fact("최대 힙(MB)", Runtime.getRuntime().maxMemory() / (1024 * 1024));

        evidence.expect("강한 참조가 있으면 Weak 대상도 회수되지 않는다", weakButStronglyHeld.get() != null);
        evidence.expectFlaky("GC 가 돌면 Weak 대상은 회수된다", weak.get() == null);
        evidence.expectFlaky("메모리에 여유가 있으면 Soft 대상은 살아남는다", soft.get() != null);

        evidence.note("System.gc() 는 요청일 뿐 보증이 아니다 — 이 케이스가 nondeterministic 인 이유다.");
        evidence.note("WeakHashMap 은 키가 Weak, 값은 Strong 이라 값이 키를 참조하면 절대 회수되지 않는다.");
        evidence.note("Soft 참조 캐시는 GC 압력을 늦게 해소해 오히려 GC 시간을 늘릴 수 있어, 실무에서는 Caffeine 같은 크기 기반 캐시를 선호한다.");
        evidence.note("강한 참조 확인용 더미 길이: " + strongTarget.length);
    }
}
