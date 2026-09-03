# verify-labs-jmh — "차이가 없다" 를 재는 모듈

이 저장소의 검증 케이스는 벽시계 측정이라 **자릿수 차이만** 믿는다(`docs/02` §4 「벤치마크가 아니다」).
그래서 `docs/02` §9-4 의 13번(`final` 은 성능과 무관)·14번(람다 vs 익명 클래스는 같다)처럼
**"차이가 없다"** 는 명제는 지금까지 만들지 못했다 — 잡음이 차이를 덮은 건지 진짜 없는 건지 벽시계로는 구분이 안 된다.

*Optimizing Java* 1판 5장·2판 부록 A 가 말하는 대로 **JMH**(포크·워밍업·Blackhole·오차 범위)를 들이되,
§4 의 전제를 바꾸지 않도록 **별도 모듈**로 둔다(`docs/00` 「이어서 할 일」 5번,
[`notes/optimizing-java/00-검토-2018년-책과-현재.md`](../notes/optimizing-java/00-검토-2018년-책과-현재.md) §7 의 첫 제안).

```bash
./gradlew :verify-labs-jmh:jmh                                    # 기본(짧은 설정), 1분 남짓
./gradlew :verify-labs-jmh:jmh -PjmhArgs="FinalField -f 2 -wi 5 -i 10"   # 골라서·길게
```

결과는 콘솔과 `build/reports/jmh-result.json` 에 남는다. **CI 는 이 태스크를 돌리지 않는다** — 2코어 공유 러너의
수치는 문서에 옮길 값이 못 된다(`workflow_dispatch` 로만 실행).

---

## 1. 벤치마크 세 개

| 클래스 | 명제 | 출처 |
|---|---|---|
| `FinalFieldBenchmark` | `final` 필드/메서드는 성능과 무관하다 | *Java Performance* 4장 (`docs/02` §9-4 13번) |
| `LambdaVsAnonymousBenchmark` | 람다와 익명 클래스의 정상 상태 호출 비용은 같다 (+ 직접 호출 대조군) | *Java Performance* 12장 (§9-4 14번, 책 87.2 vs 87.9 µs) |
| `LoopCounterBenchmark` | `int` 카운터 루프가 `long` 카운터보다 64% 빠르다 (언롤링·세이프포인트 폴 제거) | *Optimizing Java* 1판 10장 (JDK 8 수치) |

세 번째는 "차이가 없다"가 아니라 "차이가 있다"는 명제다 — 2판이 뺀 1판 10장의 예제를 **지금 JDK 에서도 그런지** 보려고 넣었다.

## 2. 실행 결과 (2026-09-03, 이 세션에서 한 번 실행)

- 환경: **JDK 17.0.19**(루트 툴체인), Linux, **4코어 공유 샌드박스**(다른 작업이 함께 도는 머신)
- 설정: `-f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc` — **짧다.** 결론이 바뀔 만한 값은 아래 §3 의 방법으로 다시 돌려야 한다.

```
Benchmark                                     Mode  Cnt     Score     Error   Units
FinalFieldBenchmark.finalFieldsAndMethod     thrpt    5    11.132 ±   0.366  ops/us
FinalFieldBenchmark.plainFieldsAndMethod     thrpt    5    11.053 ±   0.767  ops/us
LambdaVsAnonymousBenchmark.lambda            thrpt    5    12.202 ±   0.911  ops/us
LambdaVsAnonymousBenchmark.anonymousClass    thrpt    5    11.853 ±   0.685  ops/us
LambdaVsAnonymousBenchmark.directCall        thrpt    5    11.679 ±   2.290  ops/us
LoopCounterBenchmark.intStride1              thrpt    5  2848.949 ± 790.593   ops/s
LoopCounterBenchmark.longStride1             thrpt    5  2449.587 ± 118.418   ops/s
```
(`gc.alloc.rate.norm` 은 전부 ≈ 0 B/op — 어느 벤치마크도 할당하지 않는다. `gc.count` ≈ 0.)

### 읽는 법

| 명제 | 판정 | 근거 |
|---|---|---|
| `final` 은 성능과 무관 | **차이 없음** | 11.13 ± 0.37 vs 11.05 ± 0.77 — 오차 범위가 완전히 겹친다 |
| 람다 = 익명 클래스 | **차이 없음** | 12.20 ± 0.91 vs 11.85 ± 0.69, 직접 호출 11.68 ± 2.29 — 셋 다 겹친다. 둘 다 인라인돼 직접 계산과 같은 속도다 |
| `int` 루프가 `long` 루프보다 64% 빠르다 (JDK 8) | **이 실행에서는 확인 못 함** | 2,849 ± 790 vs 2,450 ± 118 — 평균은 16% 차이지만 `int` 쪽 오차가 커서 겹친다. 책의 64% 는 재현되지 않았다. 더 긴 설정으로 다시 돌릴 후보 |

**첫 두 줄은 이 모듈이 존재하는 이유를 그대로 보여 준다** — 벽시계로는 "0 == 0 헛통과"였을 명제가 오차 범위라는 형태로
"같다"고 말할 수 있게 됐다. 세 번째는 정직하게 "모른다"로 남긴다.

## 3. 다시 돌릴 때

- 4코어 공유 머신의 `±` 는 크다. 결론이 바뀔 것 같으면 `-f 3 -wi 5 -i 10` 으로, 그리고 **다른 부하가 없을 때** 돌린다.
- JDK 25 에서 돌리려면 이 모듈의 `build.gradle` 에 `java.toolchain.languageVersion = JavaLanguageVersion.of(25)` 를 더한다
  (JMH 1.37 은 25 에서도 돈다고 알고 있으나 **확인하지 않았다**).
- 새 벤치마크를 더할 후보(1판 10장): 이스케이프 분석 배열 63/64/65, 메가모픽 호출 지점 벗겨내기, `HugeMethodLimit` —
  [`notes/optimizing-java/10-JIT-컴파일-이해.md`](../notes/optimizing-java/10-JIT-컴파일-이해.md) §5.

## 4. 정직한 고지

- 한 번, 짧게, 공유 머신에서 돌린 수치다. **절대값을 인용하지 마라** — 이 문서가 말할 수 있는 것은 "오차 범위가 겹친다/안 겹친다" 뿐이다.
- JMH 결과가 "통제된 실험"이라고 가정하지 않는다(부록 A 의 정렬 벤치마크 교훈). 그래서 `-prof gc` 를 기본으로 켜 두었다 — 위 결과에서는 할당이 0 이라 GC 가 끼어들 여지가 없었다.
