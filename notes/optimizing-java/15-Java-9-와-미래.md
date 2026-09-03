# 15장 — Java 9 와 미래 (Java 9 and the Future)

> *Optimizing Java* 1판(2018) 15장 요약. [`00-검토-2018년-책과-현재.md`](00-검토-2018년-책과-현재.md) §7 은 이 장을 **"요약이 아니라 대조로"** 쓰라고 했고, **§5 「예측 성적표」가 이미 그 대조표다.** 이 파일은 그 성적표를 가리키고, 장의 내용 자체와 **실행 케이스(PERF-15A · CN-03A)** 로 이어지는 부분만 보탠다. 표기 — ✅ 실행해 확인 · 📄 문서로만 아는 것.
>
> **2판(2024)의 대응 장은** [15장 「현대 성능과 미래」](../optimizing-java-2nd/15-현대-성능과-미래.md) — 같은 자리에서 **구조적 동시성·ScopedValue·Panama·Leyden·Valhalla** 를 다룬다. 1판이 예고한 것 중 무엇이 어떻게 됐는지는 §3.

---

## 1. 이 장의 핵심 주장 (2018년 시점)

1. **Java 9 는 "모듈, 그리고 나머지"** — 모듈은 성능과 무관하니 다루지 않고 작은 개선만 본다.
2. **작은 성능 개선**: **분할 코드 캐시**(비메서드/프로파일/비프로파일 영역 — 스위퍼 시간 단축, 지역성; 한 영역만 차는 단점) · **Compact Strings**(`char[]` → `byte[]` + `coder`; Latin-1 이면 절반; `-XX:-CompactStrings` 로 끌 수 있다; ElasticSearch·캐시류는 이것만으로도 이주 가치) · **새 문자열 연결**(`StringBuilder` 바이트코드 → `invokedynamic` `makeConcatWithConstants`, "SQL 의 prepared statement 와 비슷"; 성능 영향은 크지 않고 **`invokedynamic` 확산의 방향**을 보여 준다) · **C2 개선**(성숙해서 큰 개선은 불가 — SIMD 인트린직: 마스크 벡터 후행 루프, SuperWord 언롤링, 범위 검사 다중 버전, `sqrt` 벡터화, 병렬 스트림 벡터화, AVX CMovVD; 인트린직은 **점 수정**이지 일반 기법이 아니다) · **G1 이 기본 수집기**(8→9 이주 시 알고리즘이 바뀐다 — 전면 성능 테스트 필요; Oracle 의 "훨씬 낫다" 주장은 공개 근거 없음).
3. **새 릴리스 모델**: Java 10 부터 **6개월 기능 릴리스**, LTS 는 Oracle 사유 JDK, 나머지는 GPL+CPE OpenJDK 바이너리. 기능 주도 릴리스가 9 를 지연시킨 반성.
4. **Java 10**: JEP 286 `var`(Wadler 의 법칙 — 구문 논쟁의 감정 강도) · 296 단일 저장소 · 304 GC 인터페이스 · **307 G1 병렬 풀 GC**(9 까지 단일 스레드 mark-sweep-compact) · **310 AppCDS**(Oracle 전용 → 오픈) · **312 스레드 로컬 핸드셰이크**(전역 세이프포인트 없이 개별 스레드 콜백 — 스택 샘플 비용 감소, 편향 락 회수 개선, 배리어 제거).
5. **Unsafe**: 사실상 표준이 된 내부 API. Java 9 는 `--illegal-access` 스위치와 `jdk.unsupported` 모듈(`sun.misc.Unsafe`·`Signal`·`ReflectionFactory`·`getCallerClass` 등)로 **마지못해** 유지. 대체 — StackWalker(JEP 259), **VarHandle**(JEP 193: CAS·volatile·배열·메모리 순서 모드; `AtomicIntegerWithVarHandles` 예제). **`AtomicInteger` 자체는 순환 의존 때문에 아직 Unsafe 위.**
6. **Valhalla 값 타입**: 참조 배열의 간접 참조·헤더 오버헤드(`Point3D` 배열 그림) → 구조체 배열. 난점은 자바 타입 시스템에 **최상위 타입이 없다**는 것(`List<int>` 불가). 당시 설계 — **R/Q/U 세 종류 타입**, 새 opcode `vdefault`·`withfield`. **"저자들의 최선의 추측은 2019년 릴리스 중 하나."**
7. **Graal 과 Truffle**: **"C2 는 수명이 끝났고 대체돼야 한다"** — C++ 이라 위험하고 유지 어려움. Graal = 자바로 쓴 JIT(JVMCI, JEP 243; `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+UseJVMCICompiler`), 부분 탈출 분석 등. Truffle = 인터프리터에서 JIT 를 자동 생성(Futamura 사영). **Project Metropolis** = VM 을 자바로 다시 쓰기. **`jaotc`**(Java 9, `java.base` 만·Linux/ELF; "지원이 다음 릴리스들에서 확대될 것"). **SubstrateVM** = JVM 없이 도는 정적 네이티브 실행 파일("수 KB, 수 ms").
8. **바이트코드의 미래**: `const` vs `ldc`, 상수 풀의 `MethodHandle`·`MethodType` 엔트리, **constant dynamic**(링크 시 미해결 상수를 첫 만남에 계산) 예고.
9. **동시성의 미래**: 수동 락 → 런타임 관리 동시성. **Project Loom** — 스택이 비싼 OS 스레드 대신 OS 에 안 보이는 경량 실행 단위(고루틴·파이버·컨티뉴에이션), **`ForkJoinPool` 이 스케줄러가 될 것.**
10. **결론**: 언급만 하고 못 다룬 것 — **Project Panama**, **ZGC**.

## 2. 절별 상세 요약

- **Small Performance Enhancements in Java 9** — `Concat` 클래스의 Java 8 바이트코드(StringBuilder 8 호출) vs Java 9(`invokedynamic #2` 한 줄)와 부트스트랩 메서드 상수 풀, `String.value`/`coder` 소스 인용, SIMD 이슈 목록.
- **Java 10 and Future Versions** — 릴리스 모델, JEP 6개 각각의 성능 영향.
- **Unsafe in Java 9 and Beyond / VarHandles** — `jdk.unsupported` 모듈 선언 전문, `AtomicIntegerWithVarHandles` 코드.
- **Project Valhalla and Value Types** — 그림 15-1~15-4, R/Q/U, 고정 폭 값 문제, `vdefault`·`withfield`, Goetz 인용.
- **Graal and Truffle** — JVMCI, Metropolis, `jaotc --output libjava.base.so --module java.base`, SubstrateVM.
- **Future Directions in Bytecode / Concurrency** — `showConstsAndLdc` 디스어셈블, 메서드 핸들 상수, Loom·ForkJoinPool.

## 3. 예측 성적표 → [`00-검토` §5](00-검토-2018년-책과-현재.md#5-15장의-예측-성적표--책이-본-미래-vs-실제)

**여기에 다시 쓰지 않는다.** 맞은 것 10 · 빗나간 것 4 · 방식이 달라진 것 2 · 그대로인 것 1 이 그 절에 있다. 한 줄 요약만 —

| | 1판이 본 미래 | 실제 |
|---|---|---|
| 맞음 | 6개월 릴리스 · Compact Strings · 분할 코드 캐시 · indy 연결 · G1 기본 · JEP 307/310/312 · condy(11) · **Loom = ForkJoinPool 위 가상 스레드(21)** · SubstrateVM → Native Image · Panama(22) | ✅ 대부분 실행 확인 |
| 빗나감 | **C2 → Graal 대체** · **`jaotc` 확대** · **값 타입 2019** · `--illegal-access` 로 제어 | ❌ Graal JIT·`jaotc` 는 JDK 17 에서 제거, C2 현역; Valhalla 미출시; `--illegal-access` 는 17 에서 기능 제거 |
| 방식 다름 | 편향 락 개선 · LTS 만 Oracle 상용 | 편향 락 자체 제거(15 폐기, 18 삭제) · Oracle NFTC + Temurin/Corretto |
| 그대로 | `AtomicInteger` 는 Unsafe 위 | 17·21·25 모두 `Unsafe U; long VALUE` ✅ |

2판 15장은 이 중 **Valhalla 절을 "1판의 설명은 이제 완전히 틀렸다"** 고 스스로 정정했다(R/Q/U·새 opcode 대신 `value` 키워드 하나, 새 바이트코드 없음, `if_acmpeq` 의 재귀 비교 문제).

## 4. JDK 17 / 25 기준 — 실행으로 확인한 것

이 장의 문장 중 실행으로 판정 가능한 것을 **두 케이스**로 묶었다.

**PERF-15A**(`verify-labs-perfbook`, JDK 17 — CONFIRMED ✅):

| 15장의 문장 | 확인 |
|---|---|
| Compact Strings 기본 | `CompactStrings = true`; 한글 5자 = 10바이트(UTF16), ASCII 10자 = 10바이트(Latin-1) — PERF-11E |
| 분할 코드 캐시 기본 | `SegmentedCodeCache = true` |
| VarHandle | 존재 |
| 문자열 연결은 indy | 이 케이스의 클래스 파일 상수 풀에 `makeConcatWithConstants` 있음 |
| `jaotc` | 없음(JEP 410) |
| Graal 모듈 | `jdk.internal.vm.compiler`(17·21) / `jdk.graal.compiler`(25) — **`module-info` 하나뿐, 컴파일러 클래스 0개**. 첫 판은 부트 레이어에서 찾아 "모듈 없음"으로 잘못 봤다 → `ModuleFinder.ofSystem()` 으로 고쳤다 |
| `AtomicInteger` 는 Unsafe 위 | 필드 `jdk.internal.misc.Unsafe U` 존재 |
| `--illegal-access` | **경고 후 무시(rc=0)** — "Ignoring option --illegal-access=permit; support was removed in 17.0". 첫 판은 "Unrecognized 로 기동 실패"를 기대했다가 REFUTED 가 났다. §5 의 "제거"는 이 뜻이다 |

**CN-03A**(`verify-labs-cloudnative`, JDK 25 — CONFIRMED ✅): CMS·편향 락·`PrintGCApplicationStoppedTime`·`AggressiveOpts` 는 Unrecognized, `ZGenerational` 은 "support was removed in 24.0" 경고 후 무시, `System.setSecurityManager` 는 `UnsupportedOperationException`, `-XX:+UseJVMCICompiler -Xcomp` 는 **"Cannot use JVMCI compiler: No JVMCI compiler found"**. 즉 1판이 적은 Graal 활성화 스위치는 **25 에서 받아들여지지만 뒤에 컴파일러가 없다.**

그 밖에 📄: ZGC 는 11 실험 → 15 정식 → 21 세대형 옵션 → **24 부터 세대형만**(CN-05A ✅ 25 에서 `ZGenerational` 무시). constant dynamic 은 JDK 11(JEP 309). Loom 의 컨티뉴에이션은 25 에도 `jdk.internal.vm` 안이다.

## 5. 이 장을 우리 랩에 비춰 보면

| 책의 명제 | 이 저장소의 근거 | 상태 |
|---|---|---|
| Java 9 예고 항목의 현재 | **PERF-15A**(17) | **신규(§7 후보 "상시 실행하면 다음 JDK 의 변화가 자동으로 잡힌다")** |
| 옛 스위치의 JDK 25 상태, Graal 부재 | **CN-03A** | 2판 랩 케이스 |
| Compact Strings 바이트 수 | **PERF-11E** | 신규 |
| Loom → 가상 스레드 | **CN-13A·13B·13C** | 2판 랩 케이스 |
| Panama | **CN-15B** | 2판 랩 케이스 |
| AOT(jaotc 의 후신 = AOT 캐시) | **CN-06A** | 2판 랩 케이스 |
| Unsafe 의 운명 | **CN-15A** | 2판 랩 케이스 |
| Valhalla | 실행할 것이 없다(미출시) | — |

## 6. 면접에서 쓸 수 있는 문장

- "2018년 책은 C2 가 수명을 다해 Graal 로 대체된다고 예측했지만 정반대가 됐습니다. Graal JIT 은 JDK 17 에서 빠졌고 C2 가 현역입니다. 권위 있는 저자도 플랫폼 방향을 틀리게 보니, 버전을 확인하고 직접 실행해 보는 습관이 필요합니다."
- "`AtomicInteger` 는 2018년에도 '곧 VarHandle 로 옮긴다'고 했는데 JDK 25 에도 Unsafe 위입니다. 라이브러리 작성자 권고와 JDK 내부 구현은 다릅니다."
- "Loom 예측은 정확히 맞았습니다. 가상 스레드의 기본 스케줄러는 ForkJoinPool 입니다."

## 7. 관련 문서

- [`00-검토-2018년-책과-현재.md`](00-검토-2018년-책과-현재.md) §5 — 성적표 본문
- [`../optimizing-java-2nd/15-현대-성능과-미래.md`](../optimizing-java-2nd/15-현대-성능과-미래.md) — 2판의 미래 장
- [`../optimizing-java-2nd/01-최신-JDK-기준-평가.md`](../optimizing-java-2nd/01-최신-JDK-기준-평가.md) — JDK 25 실행 결과 모음
- [`../JVM-용어-변천사.md`](../JVM-용어-변천사.md) — C2·Graal·JVMCI·ZGC 의 연표
