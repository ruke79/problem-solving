package io.webboy.verify.labs.concurrency;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Component
public class CounterStrategyCase extends VerificationCase {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 200_000;

    /** 측정 전에 한 번 돌려 JIT 컴파일과 LongAdder 의 셀 확장을 끝내 둔다. */
    private static final int WARMUP_ITERATIONS = 20_000;

    /** 장비가 바쁘면 한 번의 측정이 통째로 밀린다. 여러 번 재고 가장 빠른 값을 쓴다. */
    private static final int ROUNDS = 5;

    private long guarded;

    @Override
    public String id() {
        return "CON-02";
    }

    @Override
    public String category() {
        return "concurrency";
    }

    @Override
    public String question() {
        return "카운터를 synchronized, AtomicLong, LongAdder 로 구현할 때의 차이는 무엇입니까?";
    }

    @Override
    public String claim() {
        return "셋 다 정확하지만 경합이 심할수록 LongAdder 가 유리하다 — 셀 분산으로 CAS 재시도를 줄이기 때문이다";
    }

    @Override
    public boolean nondeterministic() {
        return true;
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Object lock = new Object();
        guarded = 0;
        AtomicLong atomic = new AtomicLong();
        LongAdder adder = new LongAdder();

        Runnable syncWork = () -> {
            synchronized (lock) {
                guarded++;
            }
        };
        Runnable atomicWork = atomic::incrementAndGet;
        Runnable adderWork = adder::increment;

        List<Measurement> measured = measureInterleaved(List.of(syncWork, atomicWork, adderWork));
        Measurement sync = measured.get(0);
        Measurement atomicRun = measured.get(1);
        Measurement adderRun = measured.get(2);
        long syncValue = guarded;

        // 워밍업도 같은 카운터를 올린다(LongAdder 의 셀 확장은 인스턴스에 남아야 의미가 있으므로
        // 워밍업용 인스턴스를 따로 쓸 수 없다). 그래서 기대 합계에 워밍업 몫을 함께 넣는다 —
        // 여기를 빼먹어서 '측정은 고쳤는데 정확성 검증이 깨지는' 상태를 한 번 만들었다.
        long expectedAfterRuns = (long) THREADS * (WARMUP_ITERATIONS + (long) ITERATIONS * ROUNDS);

        evidence.fact("스레드 수 / 스레드당 증가", THREADS + " / " + ITERATIONS);
        evidence.fact("측정 방식",
                "워밍업 " + WARMUP_ITERATIONS + "회 후 세 방식을 번갈아 " + ROUNDS + "회 측정, 최소값 사용");
        evidence.fact("기대 합계(워밍업 + 측정 " + ROUNDS + "회)", expectedAfterRuns);
        evidence.fact("synchronized 결과 / 최소 소요(ms)", syncValue + " / " + sync.best());
        evidence.fact("AtomicLong 결과 / 최소 소요(ms)", atomic.get() + " / " + atomicRun.best());
        evidence.fact("LongAdder 결과 / 최소 소요(ms)", adder.sum() + " / " + adderRun.best());
        evidence.fact("측정 회차별 소요(ms) — sync / atomic / adder",
                sync.rounds() + " / " + atomicRun.rounds() + " / " + adderRun.rounds());
        evidence.fact("LongAdder 가 AtomicLong 보다 빠른 배수",
                adderRun.best() <= 0 ? "측정 불가" : String.format("%.1f배", (double) atomicRun.best() / adderRun.best()));
        evidence.fact("가용 코어", Runtime.getRuntime().availableProcessors());

        evidence.expectEquals("synchronized 는 정확하다", expectedAfterRuns, syncValue);
        evidence.expectEquals("AtomicLong 은 정확하다", expectedAfterRuns, atomic.get());
        evidence.expectEquals("LongAdder 는 정확하다", expectedAfterRuns, adder.sum());
        evidence.expectFlaky("높은 경합에서 LongAdder 가 AtomicLong 보다 빠르다",
                adderRun.best() < atomicRun.best());

        evidence.note("LongAdder 는 sum() 이 스냅샷이 아니다 — 정확한 순간값이 필요하면 AtomicLong 을 쓴다.");
        evidence.note("이 측정은 JMH 가 아니라 벽시계 측정이라 절대값이 아니라 '자릿수'만 신뢰한다. 다만 두 가지는 반드시 챙겨야 한다 — **워밍업**(JIT 컴파일 전이면 세 방식이 다 인터프리터 속도라 차이가 뭉개지고, LongAdder 는 셀이 아직 확장되지 않아 AtomicLong 과 같아진다)과 **여러 번 재서 최소값 쓰기**(장비가 바쁘면 한 회차가 통째로 밀린다).");
        evidence.note("이 랩이 실제로 겪은 일이다. 처음에는 워밍업 없이 한 번만 쟀고, 그때 이 항목이 9회 중 6회 INCONCLUSIVE 로 흔들렸다. 더 큰 원인은 따로 있었다 — 작업을 `Consumer<Integer>` 로 받아 **매 반복 오토박싱**이 일어났고(스레드당 20만 개), 그 할당 비용이 CAS 경합 차이를 통째로 덮었다. `Runnable` 로 바꾸자 LongAdder 가 한 자릿수 밀리초로 떨어졌다.");
        evidence.note("한 가지 더 — 세 방식을 **번갈아** 재야 한다. 한 방식을 몰아서 다 재고 넘어가면 그 사이 부하가 바뀌었을 때 한쪽만 손해를 본다. 전건 실행에서 실제로 그렇게 뒤집혔다(AtomicLong 이 조용한 순간을 잡아 24ms, LongAdder 34ms). 이 랩은 회차마다 sync → atomic → adder 순으로 돌아가며 잰다.");
        evidence.note("그래도 이 항목은 `expectFlaky` 로 남겨 둔다. 4코어 장비에서 다른 작업과 CPU 를 나눠 쓰면 LongAdder 의 셀 분산 이점이 줄어들어 실제로 뒤집힐 수 있다. 원고도 '경합이 심할수록 유리하다'고 조건부로 말하고 있고, 그 조건이 성립하지 않는 환경에서는 **INCONCLUSIVE 가 정직한 답**이다.");
        evidence.note("측정 코드가 측정 대상을 가리는 전형적인 사례다. '벤치마크에서 차이가 안 보인다'면 대상보다 하네스를 먼저 의심해야 한다 — 박싱, 워밍업 부족, 죽은 코드 제거(JIT), 한 번만 재기가 단골이다.");
    }

    /**
     * @param rounds 회차별 소요(ms)
     */
    private record Measurement(List<Long> rounds) {
        long best() {
            return rounds.stream().mapToLong(Long::longValue).min().orElse(-1);
        }
    }

    /**
     * 워밍업 뒤 {@link #ROUNDS} 회 측정하되, <b>회차마다 세 방식을 번갈아</b> 잰다.
     *
     * <p>한 방식을 몰아서 다 재고 다음으로 넘어가면, 그 사이 장비 부하가 바뀌었을 때
     * <b>한쪽만 손해를 본다.</b> 실제로 전건 실행(케이스 87개가 먼저 돈 뒤)에서 그렇게 뒤집혔다 —
     * AtomicLong 이 조용한 순간을 잡아 24ms, LongAdder 는 34ms 로 나왔다.
     * 번갈아 재면 부하 변동이 세 방식에 고르게 걸린다.
     */
    private List<Measurement> measureInterleaved(List<Runnable> works) throws Exception {
        for (Runnable work : works) {
            race(work, WARMUP_ITERATIONS);
        }
        List<List<Long>> rounds = new ArrayList<>();
        for (int i = 0; i < works.size(); i++) {
            rounds.add(new ArrayList<>());
        }
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < works.size(); i++) {
                rounds.get(i).add(race(works.get(i), ITERATIONS));
            }
        }
        return rounds.stream().map(Measurement::new).toList();
    }

    /**
     * 작업을 {@link Runnable} 로 받는 것이 중요하다. {@code Consumer<Integer>} 로 받으면
     * 반복마다 오토박싱이 일어나 <b>할당 비용이 경합 차이를 덮어 버린다</b>.
     */
    private long race(Runnable work, int iterations) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            work.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            long began = System.nanoTime();
            start.countDown();
            done.await(60, TimeUnit.SECONDS);
            return (System.nanoTime() - began) / 1_000_000L;
        } finally {
            pool.shutdownNow();
        }
    }
}
