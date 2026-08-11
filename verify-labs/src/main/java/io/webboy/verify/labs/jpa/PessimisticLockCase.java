package io.webboy.verify.labs.jpa;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Q97 — 비관적 락은 다른 트랜잭션을 실제로 "기다리게" 한다. */
@Component
public class PessimisticLockCase extends VerificationCase {

    private static final long HOLD_MILLIS = 600L;

    private final NoteRepository notes;
    private final TransactionTemplate tx;

    public PessimisticLockCase(NoteRepository notes, TransactionTemplate tx) {
        this.notes = notes;
        this.tx = tx;
    }

    @Override
    public String id() {
        return "JPA-07";
    }

    @Override
    public String category() {
        return "jpa";
    }

    @Override
    public String question() {
        return "비관적 락과 낙관적 락은 실제 동작이 어떻게 다릅니까?";
    }

    @Override
    public String claim() {
        return "비관적 락(SELECT FOR UPDATE)은 경합 시 뒤 트랜잭션을 락 해제까지 블로킹한다 — 락 구간 안에 긴 처리를 넣으면 전체 스루풋이 떨어진다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long id = tx.execute(status -> notes.save(new Note("pess-" + tag)).getId());

        CountDownLatch acquired = new CountDownLatch(1);
        AtomicLong holderElapsed = new AtomicLong();

        Thread holder = new Thread(() -> tx.executeWithoutResult(status -> {
            Note locked = notes.findByIdForUpdate(id).orElseThrow();
            locked.setText("held-by-holder-" + tag);
            acquired.countDown();
            long began = System.nanoTime();
            sleep(HOLD_MILLIS);
            holderElapsed.set((System.nanoTime() - began) / 1_000_000L);
        }), "pessimistic-holder");
        holder.setDaemon(true);
        holder.start();

        boolean started = acquired.await(5, TimeUnit.SECONDS);

        // (a) 락을 걸지 않는 단순 조회는 기다리지 않는다
        long readBegan = System.nanoTime();
        String plainRead = tx.execute(status -> notes.findById(id).orElseThrow().getText());
        long plainReadMillis = (System.nanoTime() - readBegan) / 1_000_000L;

        // (b) 같은 행을 다시 FOR UPDATE 로 잡으면 holder 가 커밋할 때까지 기다린다
        AtomicReference<String> waiterText = new AtomicReference<>();
        long waitBegan = System.nanoTime();
        tx.executeWithoutResult(status -> waiterText.set(notes.findByIdForUpdate(id).orElseThrow().getText()));
        long waitedMillis = (System.nanoTime() - waitBegan) / 1_000_000L;

        holder.join(5_000);

        evidence.fact("holder 가 락을 잡았는가", started);
        evidence.fact("holder 락 보유 시간(ms)", holderElapsed.get());
        evidence.fact("락 없는 조회 소요(ms)", plainReadMillis);
        evidence.fact("락 없는 조회가 본 값", plainRead);
        evidence.fact("FOR UPDATE 대기 소요(ms)", waitedMillis);
        evidence.fact("대기 후 본 값", waiterText.get());

        evidence.expect("락 없는 조회는 블로킹되지 않는다", plainReadMillis < HOLD_MILLIS);
        evidence.expectFlaky("FOR UPDATE 는 락 해제까지 대기한다", waitedMillis >= HOLD_MILLIS / 2);
        evidence.expect("대기 후에는 holder 가 커밋한 값을 본다",
                ("held-by-holder-" + tag).equals(waiterText.get()));

        evidence.note("락 구간 안에서 외부 API 를 호출하면 그 지연이 그대로 다른 트랜잭션의 대기 시간이 된다(Q48·Q97 공통 원칙).");
        evidence.note("락 획득 순서를 통일하지 않으면 비관적 락은 그대로 데드락이 된다 — DB-07 참고.");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
