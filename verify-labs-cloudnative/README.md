# verify-labs-cloudnative — *Optimizing Cloud Native Java* 2판 명제를 JDK 25 에서

2판(2024)은 **JDK 21** 기준이다. 이 모듈은 그 책의 명제를 **JDK 25** 에서 실행해 "그대로다 / 결말이 났다 / 틀렸다" 를 판정한다 —
장별 요약([`notes/optimizing-java-2nd/`](../notes/optimizing-java-2nd/README.md))의 ✅ 표시와 [`01-최신-JDK-기준-평가.md`](../notes/optimizing-java-2nd/01-최신-JDK-기준-평가.md) 의 명령·출력이 전부 여기서 나온다.

```bash
./gradlew :verify-labs-cloudnative:test                          # 19건 전부 (JDK 25 툴체인 필요 — 자동 감지 또는 foojay 다운로드)
./gradlew :verify-labs-cloudnative:test -Dverify.only=CN-05A,CN-13A
cat verify-labs-cloudnative/build/reports/verification-cloudnative.md
```

JDK 25 가 없으면 테스트는 **실패가 아니라 skip** 된다(`Assumptions.assumeTrue(feature >= 25)`). 인프라(DB·Kafka)는 필요 없다.

## 1. 설계 — 왜 Spring 이 없나

- 루트는 모든 모듈에 Java 17 툴체인을 준다. 이 모듈만 `languageVersion = 25` 로 덮어쓴다 — **17 과 25 의 차이가 검증 대상**이므로.
- 검증 대상 API(`ScopedValue` 정식, FFM, AOT 캐시 플래그, 컴팩트 헤더)는 25 에서만 컴파일된다 → 클래스 파일 69. 이 저장소의 Spring Boot 3.3.5(프레임워크 6.1)는 문서상 JDK 23 까지가 지원 범위라 **시험하지 않고 피했다.**
- 그래서 `verify-core` 의 하네스(`VerificationCase`·`Evidence`·`VerificationRegistry`·`CaseFilter`·`VerificationReport`)만 쓰고 레지스트리는 테스트에서 손으로 만든다(`CloudNativeCases.all()`). 리포트 형식·판정 규칙(`expect`/`expectFlaky`, `nondeterministic`)은 다른 모듈과 같다.
- 자식 JVM 이 필요한 케이스(GC 선택·플래그 수용 여부·AOT 캐시·고정)는 `Jvm.run(...)` 으로 **같은 JDK 25 바이너리**를 띄운다. `JAVA_TOOL_OPTIONS`·`_JAVA_OPTIONS`·`JDK_JAVA_OPTIONS` 는 제거하고 띄운다 — 환경의 에이전트 옵션이 결과를 오염시키지 않도록.
- 컴파일 경고를 오류로 올리지 않는다(`-Xlint:removal -Xlint:restricted`) — `CN-15A`·`CN-15B` 는 일부러 폐기·제한 메서드를 부른다.

## 2. 케이스 19건 (`CN-<장><글자>`)

| id | 장 | 질문 | 판정 |
|---|---|---|---|
| `CN-03A` | 3·부록 B | 책(과 1판)이 말하는 JVM 스위치·API 중 25 에서 사라진 것은? | CMS·편향 락·`PrintGCApplicationStoppedTime`·`AggressiveOpts` 기동 실패 / `ZGenerational`·`--illegal-access` 경고 후 무시 / SecurityManager 예외 / Graal 모듈은 빈 껍데기, JVMCI "No JVMCI compiler found" |
| `CN-04A` | 4·9 | CPU 1개 컨테이너에서 어떤 GC 를 고르나? | Serial(MaxRAM 8g 를 줘도). 2 CPU → G1. **`MaxRAM` 은 GC 선택에 무관**(첫 판 REFUTED 에서 얻은 관측) |
| `CN-04B` | 4·8 | 메모리 2 GB 를 주면 힙은? | 495 MB(25%), `MaxRAMPercentage=50` → 1024 MB |
| `CN-05A` | 5 | `-XX:+UseZGC` 는 세대형인가? | 25 세대형만(`ZGC Minor/Major`), `-ZGenerational` 은 "removed in 24.0" 경고 |
| `CN-05B` | 5 | Shenandoah 에 세대형이 있나? | 25 `Mode: Generational`(기본 satb); 21 은 옵션 모름 |
| `CN-05C` | 4·5 | 컴팩트 객체 헤더는 객체를 얼마나 줄이나? | 빈 객체 16.0 → 8.0 바이트, 플래그 기본 false |
| `CN-06A` | 6·15 | Leyden AOT 캐시는 25 에서 쓸 수 있나? | 한 단계·두 단계 명령 모두 동작, 캐시 사용 시 "shared objects file" 클래스 761 vs 670 vs 0. **클래스패스는 JAR 이어야**("Cannot have non-empty directory in paths") |
| `CN-06B` | 6 | 계층형 컴파일·분할 코드 캐시 기본값은? | `TieredStopAtLevel=4`, `SegmentedCodeCache=true`, 코드 캐시 240 MB(25 는 4 KB 더 큼) |
| `CN-07A` | 7 | 행 우선 vs 열 우선 순회 | 열 우선이 자릿수로 느리다(`expectFlaky`) |
| `CN-09A` | 9·13 | CPU 제한이 `parallelStream()` 병렬성에? | commonPool 4코어 3, `ActiveProcessorCount=2` → 1 |
| `CN-10A` | 10 | 인스턴스별 p99 를 평균 내면? | 전체 p99 와 다르다 — Micrometer 히스토그램으로 |
| `CN-11A` | 11 | Micrometer Counter·Timer·MeterFilter 는 책대로? | MeterFilter deny/rename ✓. **Counter 는 음수 증가를 거부하지 않는다**(count −2.0) — 단조성은 계약 |
| `CN-12A` | 12 | JFR 스트리밍은 되고 25 에는 어떤 이벤트가? | 스트리밍 ✓, 25 만 `jdk.CPUTimeSample`·`jdk.MethodTrace`·`jdk.MethodTiming` |
| `CN-13A` | 13 | `synchronized` 안에서 블로킹하는 가상 스레드는 고정되나? | 캐리어 1개 실험: 21 607 ms, **25 10 ms**(JEP 491) |
| `CN-13B` | 13 | 가상 스레드 executor 는 태스크마다 새 스레드? | ✓, 데몬 |
| `CN-13C` | 13·15 | `ScopedValue` 는 `ThreadLocal` 과 무엇이 다른가? | 정식(25), 재바인딩·복원, 밖에서 `NoSuchElementException`, 자식 가상 스레드에 비상속 |
| `CN-15A` | 13·15 | `sun.misc.Unsafe` 메모리 접근은? | "terminally deprecated method" 경고, `--sun-misc-unsafe-memory-access=deny` → 예외; 21 옵션 없음 |
| `CN-15B` | 15 | FFM 은 25 에서? | `strlen` 다운콜 ✓, `--enable-native-access` 없으면 "restricted method" 경고; 21 미리보기 |
| `CN-A01` | 부록 A | 결과를 안 쓰는 벤치마크 루프는 JIT 에 지워지나? | 지워진다(시간 자릿수 차이) — `PERF-08A` 프로브가 실제로 당했다 |

**마지막 전체 실행**: 19/19 CONFIRMED (JDK 25.0.4, Linux 4코어). 리포트 `build/reports/verification-cloudnative.md`.

## 3. 만들면서 틀린 것 — 그대로 남겼다

| 케이스 | 첫 판 | 실제 | 조치 |
|---|---|---|---|
| `CN-03A` | `--illegal-access` 가 Unrecognized 로 기동 실패할 것 | **경고 후 무시, rc=0**(JEP 403 은 기능을 없앴지 옵션 파싱을 없앤 게 아니다) | 기대를 고치고 주석에 기록 |
| `CN-03A` | `ModuleLayer.boot()` 로 Graal 모듈 탐색 → "없음" | 부트 레이어에 **해석되지 않을 뿐** 실려 있다 | `ModuleFinder.ofSystem()` 으로 |
| `CN-04A` | CPU 2 + `MaxRAM=1g` → Serial | **G1**. `MaxRAM` 은 서버 클래스 판정에 안 들어간다 | 명제를 "MaxRAM 은 GC 선택을 바꾸지 않는다"로 다시 씀. 메모리 임계는 📄 로 |
| `CN-06A` | 디렉터리 클래스패스로 AOT 훈련 | rc=1 "Cannot have non-empty directory in paths" | `Jvm.jarOf()` 로 임시 JAR, 실패 자체를 증거로 |
| `CN-11A` | Counter 가 음수 증가를 무시할 것 | `SimpleMeterRegistry` 는 −2.0 을 기록 | 주장을 "계약이지 강제가 아니다"로 |

REFUTED 를 임계값 완화로 통과시킨 것은 없다 — 전부 명제나 측정을 고쳤고, 고친 이유가 케이스 주석에 있다(`docs/00` §8).

## 4. 안 한 것

- cgroup 한계 실물(권한 없음) — `MaxRAM`·`ActiveProcessorCount` 로 대신.
- AOT 캐시의 기동 시간 이득 — 잡음 속이라 수치를 적지 않는다.
- 구조적 동시성(미리보기, `--enable-preview` 필요) · Vector API(인큐베이터) · Valhalla(없음).
- K8s·Argo·Istio·Kafka 스트림(8·9·14장) — 클러스터 없음.

## 5. CI

`.github/workflows/ci.yml` 의 `cloudnative` 잡이 `actions/setup-java` 로 25 를 깔고 이 모듈만 돌린다(인프라 불필요, 수 분). 러너가 2코어라 `CN-09A` 의 commonPool 값은 1 이 되지만 케이스는 "CPU−1" 관계만 단정한다.
