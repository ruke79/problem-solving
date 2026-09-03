# 2판(JDK 21 기준)을 최신 JDK 25 로 평가한다

> 요청 — *"java 17을 기준으로 했는데 최신버전을 기준으로 내용을 평가해 주고"*. 이 저장소의 앞선 검토(`notes/java-performance/00-JDK-17-차이.md`, `notes/optimizing-java/00-검토`)는 JDK 17 기준이었다. 이 문서는 **JDK 25.0.4** 를 기준으로 2판(JDK 21)의 문장을 평가하고, 비교를 위해 **17.0.19 · 21.0.10** 에서도 같은 명령을 돌렸다.
>
> **표기** — ✅ 이 환경에서 실행한 명령과 출력 · 📄 문서(JEP·릴리스 노트)로만 아는 것. ✅ 의 대부분은 `verify-labs-cloudnative` 의 케이스(`CN-*`)로 상시 실행된다.
>
> 환경: Linux 4코어(`ActiveProcessorCount` 조작으로 1·2코어 흉내), Temurin/OpenJDK 17.0.19 · 21.0.10 · 25.0.4, `JAVA_TOOL_OPTIONS` 는 자식 JVM 에서 제거하고 실행.

---

## 0. 결론 먼저

| 판정 | 건수 | 대표 |
|---|---|---|
| **2판이 맞고 25 에서도 그대로** | 다수 | 계층형 컴파일·인라이닝 기본값(17·21·25 동일), G1 기본·리전·`MaxGCPauseMillis`, 컨테이너 에르고노믹스, JFR, 가상 스레드 |
| **2판 이후 25 에서 결말이 난 것(책이 "예고"로 적음)** | 9 | 비세대형 ZGC 제거 · Shenandoah 세대형 정식 · 컴팩트 헤더 정식 · ScopedValue 정식 · AOT 캐시 정식 · 가상 스레드 고정 해소 · SecurityManager 영구 비활성 · Unsafe 경고 · 컴팩트 소스 파일 |
| **2판 문장이 25 에서 틀리거나 부정확** | 4 | `PrintGCApplicationStoppedTime`(12장, 17 부터 없음) · `-XX:-ZGenerational` 로 비세대형 선택(5장) · "21 의 FFM 은 `jdk.incubator.foreign`"(15장) · Graal 모듈 이름(`jdk.internal.vm.compiler` → 25 `jdk.graal.compiler`) |
| **여전히 미정** | 3 | 구조적 동시성(25 도 미리보기, API 변경) · Vector API(인큐베이터) · Valhalla |

## 1. GC — 4·5장

### 1-1. ZGC: 비세대형은 사라졌다 (`CN-05A` ✅)

```
$ java -XX:+UseZGC -XX:-ZGenerational -version          # JDK 25
OpenJDK 64-Bit Server VM warning: Ignoring option ZGenerational; support was removed in 24.0
```

| JDK | `-XX:+UseZGC` 의 GC MXBean 이름 | `-XX:+ZGenerational` |
|---|---|---|
| 21 | `ZGC Cycles`, `ZGC Pauses` (비세대형) | 받아들임 → `ZGC Minor Cycles`·`ZGC Minor Pauses`·`ZGC Major Cycles`·`ZGC Major Pauses` |
| 25 | `ZGC Minor/Major Cycles/Pauses` (세대형만) | 경고 후 무시 |

**평가**: 2판 5장은 "21 에서 `-XX:+ZGenerational` 로 켠다, 미래 기본"이라 썼다 — 맞았고, 24 에서 비세대형이 제거돼(JEP 490) **25 에서는 선택지 자체가 없다.** 책의 플래그를 넣어도 죽지는 않는다.

### 1-2. Shenandoah: 세대형 정식 (`CN-05B` ✅)

```
$ java -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational -Xlog:gc+init -version
JDK 21: Unknown -XX:ShenandoahGCMode option (기동 실패)
JDK 25: [gc,init] Mode: Generational   (기본은 satb)
```

**평가**: 2판은 Shenandoah 를 비세대형으로만 설명했다. 25 에서 세대형이 정식(JEP 521)이지만 **기본은 여전히 `satb`** — 켜야 한다.

### 1-3. 컴팩트 객체 헤더 (`CN-05C` ✅)

```
$ java -XX:+PrintFlagsFinal -version | grep UseCompactObjectHeaders     # JDK 25
     bool UseCompactObjectHeaders   = false   {product}
```

빈 객체(`new Object()`) 크기: 기본 **16.0 바이트 → `-XX:+UseCompactObjectHeaders` 로 8.0 바이트**(할당 바이트 차이로 측정). 17·21 은 플래그 없음(21 에는 experimental 도 없다 — 24 JEP 450 실험, 25 JEP 519 정식).

**평가**: 2판 4장의 객체 헤더 그림(마크 워드 8 + 클래스 포인터 4 = 12, 정렬 16)은 **기본값에서는 그대로 맞고**, 켜면 헤더 8 이다. 컨테이너 힙 예산(8·9장) 이야기에 붙여 읽어야 한다.

### 1-4. 에르고노믹스 — 컨테이너 (`CN-04A`·`CN-04B` ✅)

| 실행 | 선택된 GC / 힙 |
|---|---|
| `-XX:ActiveProcessorCount=1` | **SerialGC** (MaxRAM=8g 를 줘도 Serial) |
| `-XX:ActiveProcessorCount=2` | G1 |
| `-XX:ActiveProcessorCount=2 -XX:MaxRAM=1g` | **G1** — `MaxRAM` 은 서버 클래스 판정에 안 쓰인다(첫 판의 REFUTED 에서 얻은 관측) |
| `-XX:MaxRAM=2g` | `Runtime.maxMemory()` 495 MB (≈25%) |
| `-XX:MaxRAM=2g -XX:MaxRAMPercentage=50` | 1024 MB |
| `ForkJoinPool.commonPool()` 병렬도 | 4코어 3, `ActiveProcessorCount=2` 면 1 |

**평가**: 2판 8·9장의 "CPU 1개 컨테이너 → Serial, 1 GB → 256 MB 힙"은 25 에서 그대로다. 책이 구분하지 않은 것 하나 — **`MaxRAM` 은 힙 상한만 바꾸고 GC 선택(서버 클래스 = CPU 2+ 이면서 실제 물리 메모리 2 GB+)은 안 바꾼다.** cgroup 한계는 이 환경에서 못 바꿔 📄.

### 1-5. G1 기본값 (JDK 25 `PrintFlagsFinal`, 3.4 GB 기본 힙에서 ✅)

`G1HeapRegionSize` 2 MB · `MaxGCPauseMillis` 200 · `GCTimeRatio` 12 · `NewRatio` 2 · `SurvivorRatio` 8 · `MaxTenuringThreshold` 15 · `ConcGCThreads` 1 · `ParallelGCThreads` 4. **2판 5장의 설명과 일치.** (1판 8장의 "테뉴어링 임계 기본 4"는 틀렸다 — 15.)

### 1-6. 사라진 GC 스위치 (`CN-03A` ✅, 25)

`-XX:+UseConcMarkSweepGC` · `-XX:+UseBiasedLocking` · `-XX:+PrintGCApplicationStoppedTime` · `-XX:+AggressiveOpts` → **Unrecognized VM option, 기동 실패.** 편향 락은 21 부터 Unrecognized(17 은 경고). 2판 12장은 `PrintGCApplicationStoppedTime` 을 아직 세이프포인트 진단 플래그로 적고 있다 — **17 부터 없고 `-Xlog:safepoint` 다.**

### 1-7. GC 로깅 형식 (`PERF-08A` ✅, 17)

```
$ java -XX:+PrintGCDetails -version
OpenJDK 64-Bit Server VM warning: Option PrintGCDetails was deprecated ... Use -Xlog:gc* instead.
$ java -XX:+PrintGCTimeStamps -version
Unrecognized VM option 'PrintGCTimeStamps'
$ java -Xlog:gc -Xmx32m ...
[0.190s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 13M->1M(32M) 1.013ms
```

## 2. 실행 엔진 — 6장

### 2-1. JIT 기본값은 17·21·25 가 같다 (✅ `PrintFlagsFinal` 3종 대조)

| 플래그 | 값 | 2판 6장 |
|---|---|---|
| `TieredStopAtLevel` | 4 | 계층형 4단계 ✓ |
| `CompileThreshold` | 10000 | (비계층형에서만 의미) ✓ |
| `Tier3InvocationThreshold` / `Tier4InvocationThreshold` | 200 / 5000 | ✓ |
| `MaxInlineSize` / `FreqInlineSize` | 35 / 325 | ✓ |
| `InlineSmallCode` | 2500 | ✓ |
| `MaxInlineLevel` | 15 | 1판 10장 "9" 는 옛 값 — 17 부터 15 |
| `EliminateAllocationArraySizeLimit` | 64 | 탈출 분석 배열 한도 ✓ |
| `LoopUnrollLimit` | 60 | ✓ |
| `DontCompileHugeMethods` | true (8000 바이트) | ✓ |
| `CICompilerCount` | 3 (4코어) | ✓ |
| `UseAVX` | 3 · `UseAES`·`UseFMA` true | 인트린직 ✓ |
| `ReservedCodeCacheSize` | 17/21 251,658,240 · **25 251,662,336** · 비계층형 50,331,648 | 240 MB ✓ (25 는 4 KB 더 크다 — 세그먼트 정렬) |
| `SegmentedCodeCache` | true | ✓ (`CN-06B`) |
| `PreserveFramePointer` / `DebugNonSafepoints` | false / false(diagnostic) | 프로파일링 12장 ✓ |
| 총 플래그 수 | 17: 559 · 21: 533 · 25: 513 | 줄어드는 추세 |

### 2-2. 역최적화 (`PERF-10D` ✅, 17)

`-Xbatch -XX:+PrintCompilation` 에서 단형 호출 지점에 두 번째 타입을 흘리면 `made not entrant` 가 찍힌다 — 2판 6장의 설명 그대로.

### 2-3. AOT 캐시 — Leyden (`CN-06A` ✅, 25)

```
# 한 단계 (JEP 514)
$ java -XX:AOTCacheOutput=app.aot -cp app.jar Main          # 훈련 + 생성
$ java -XX:AOTCache=app.aot -cp app.jar Main                # 사용
# 두 단계 (JEP 483)
$ java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -cp app.jar Main
$ java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -cp app.jar
```

| 조건 | `-Xlog:class+load` 의 "shared objects file" 출처 클래스 수 |
|---|---|
| `-Xshare:off` | 0 |
| 기본(기본 CDS 아카이브) | 670 |
| `-XX:AOTCache=app.aot` | **761** (다른 실행에서 851) |

**함정** ✅: 클래스패스가 **디렉터리**면 훈련이 실패한다 — `Error occurred during initialization of VM: Cannot have non-empty directory in paths`. **JAR 이어야 한다**(케이스가 임시 JAR 을 만든다). 21 은 `AOTCache` 옵션을 모른다(Unrecognized). 기동 **시간** 차이는 이 환경에서 유의미하게 재지 못했다 — 적지 않는다.

**평가**: 2판 15장 "Leyden 은 조기 단계" → 25 에서는 위 명령이 그대로 돈다. 2판 6장의 CDS/AppCDS 설명은 그 하위 호환으로 유효.

### 2-4. Graal / JVMCI (`CN-03A`·`PERF-15A` ✅)

| JDK | 모듈 | `.class` 수 | `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+UseJVMCICompiler -Xcomp` |
|---|---|---|---|
| 17·21 | `jdk.internal.vm.compiler` | 1 (module-info 뿐) | — |
| 25 | `jdk.graal.compiler` (이름 변경; 부트 레이어엔 해석 안 됨 → `ModuleFinder.ofSystem()` 으로 찾아야) | 1 | 기동은 되나 첫 컴파일에 **"Cannot use JVMCI compiler: No JVMCI compiler found"** |

**평가**: 2판은 Graal 을 JDK 안의 JIT 로 다루지 않는다(맞다). OpenJDK 빌드에 컴파일러 본체는 없다.

### 2-5. 언어·컴파일러 (✅ 25)

컴팩트 소스 파일(`void main()`, JEP 512)과 `import module java.base;`(JEP 511) 가 25 에서 `--enable-preview` 없이 컴파일·실행된다. 2판(21)에는 없던 것.

## 3. 동시성 — 13·15장

### 3-1. 가상 스레드 고정 해소 (`CN-13A` ✅)

`-Djdk.virtualThreadScheduler.parallelism=1` 로 캐리어를 하나로 두고, 가상 스레드 하나가 `synchronized` 안에서 블로킹하는 동안 다른 가상 스레드가 시작되기까지:

| JDK | 두 번째 가상 스레드 시작까지 |
|---|---|
| 21 | **607 ms** (고정 — 캐리어가 잡혀 있다) |
| 25 | **10 ms** (JEP 491 — 모니터를 잡은 채 마운트 해제) |

**평가**: 2판 13장의 "`synchronized` 안에서 블로킹하면 고정된다 — `ReentrantLock` 으로 바꿔라"는 **21 에서 맞고 25 에서는 이유가 사라졌다.** `jdk.VirtualThreadPinned` JFR 이벤트는 21·25 같은 필드로 존재.

### 3-2. 스레드별 스레드 (`CN-13B` ✅)

`Executors.newVirtualThreadPerTaskExecutor()` 는 태스크마다 새 가상 스레드(스레드 ID 전부 다름), `Thread.ofVirtual()` 의 스레드는 **데몬** — 21·25 같다.

### 3-3. ScopedValue 정식 (`CN-13C` ✅, 25)

`where(SV, v).run(...)` 안에서 `get()`, 중첩 `where` 로 재바인딩 후 바깥 값 복원, **스코프 밖 `get()` → `NoSuchElementException`**, `Thread.ofVirtual().start()` 로 만든 자식에는 **상속되지 않음**(구조적 동시성 스코프 안에서만 상속). 2판 15장 "미리보기" → 25 정식(JEP 506).

### 3-4. 구조적 동시성 — 아직 미리보기 (✅ 컴파일 시도, 25)

책의 `new StructuredTaskScope.ShutdownOnFailure()` 는 25 에서 컴파일되지 않는다 — API 가 `StructuredTaskScope.open(Joiner...)` 로 바뀌었고(JEP 505) `--enable-preview` 가 필요하다. 케이스로 만들지 않았다.

### 3-5. Unsafe (`CN-15A` ✅)

```
JDK 25: sun.misc.Unsafe.allocateMemory 호출 →
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::allocateMemory has been called by ...
JDK 25 + --sun-misc-unsafe-memory-access=deny → UnsupportedOperationException
JDK 21: 옵션 없음(Unrecognized), 경고 없음
```

`AtomicInteger` 는 17·21·25 모두 `jdk.internal.misc.Unsafe U; long VALUE` 필드 위에 있다(내부 Unsafe 는 이 정책의 대상이 아니다).

### 3-6. FFM (`CN-15B` ✅)

25: `Linker.nativeLinker().defaultLookup().find("strlen")` → 다운콜 동작. `--enable-native-access=ALL-UNNAMED` 없으면 `WARNING: A restricted method in java.lang.foreign.Linker has been called`. 21: `java.lang.foreign.Linker` 는 **미리보기** API(책의 "`jdk.incubator.foreign`" 은 17~18 시절 이름).

## 4. 관측 — 10·11·12장

### 4-1. JFR 이벤트 (`CN-12A` ✅)

스트리밍(`RecordingStream`) 은 17·21·25 모두 동작. 25 에만 있는 이벤트 — **`jdk.CPUTimeSample`**(JEP 509) · **`jdk.MethodTrace`** · **`jdk.MethodTiming`**(JEP 520). `jdk.VirtualThreadPinned` 는 21·25 같은 필드. 2판 12장이 말한 "JFR 실행 샘플러는 세이프포인트 편향이 적다"에 더해 25 는 CPU 시간 기반 샘플러를 갖는다.

### 4-2. Micrometer (`CN-10A`·`CN-11A` ✅, 1.13.6)

- 인스턴스별 p99 를 평균 낸 값과 전체 p99 가 다르다(케이스가 만든 분포에서 확인) — 2판 10장 ✓.
- `Counter.increment(-5)` 를 `SimpleMeterRegistry` 가 **거부하지 않는다**(count −2.0). 단조성은 계약이지 런타임 강제가 아니다 — 2판 11장 문장을 이렇게 읽어야 한다. `MeterFilter` 의 deny/rename 은 설명대로.

### 4-3. 도구 존재 (✅ `$JAVA_HOME/bin`)

| 도구 | 17 | 21 | 25 |
|---|---|---|---|
| `jfr` `jcmd` `jmap` `jhsdb` `jstack` | Y | Y | Y |
| `jconsole` | n | Y | n (Debian 패키지 구성 차이 — JDK 자체 문제 아님) |
| `jaotc` `jhat` | n | n | n |

## 5. 플랫폼 — 3장·부록 B

### 5-1. 캡슐화·보안 (`CN-03A`·`PERF-15A` ✅)

| 스위치 | 17 | 21 | 25 |
|---|---|---|---|
| `--illegal-access=permit` | 경고 "Ignoring option --illegal-access=permit; support was removed in 17.0", rc=0 | 같음 | 같음 |
| `System.setSecurityManager(null)` | 허용(폐기 경고) | 허용 | **`UnsupportedOperationException`** |
| `-Djava.security.manager` | 동작 | 동작 | **기동 실패** |

### 5-2. 모듈 (✅ `ModuleFinder.ofSystem()`)

21 → 25: `jdk.random` 제거, `jdk.internal.md` 추가, `jdk.internal.vm.compiler` → `jdk.graal.compiler`. 2판 3장의 모듈 그림에는 영향 없다.

### 5-3. 폐기 표시 (✅ 리플렉션)

`Object.finalize()` 의 `@Deprecated(since="9", forRemoval=true)` — 17 은 `forRemoval=false`, **21·25 는 true**. 1판 11장의 「파이널라이제이션을 피하라」는 JDK 가 스스로 결론을 냈다.

### 5-4. 문자열 (`PERF-11E` ✅)

`String` 필드 `byte[] value, byte coder, int hash, boolean hashIsZero`(17·21·25). ASCII 10자 = 10 바이트, 한글 5자 = 10 바이트, 한글 10자 = 20 바이트, 혼합 = 20 바이트 — Compact Strings 의 Latin-1/UTF-16 전환 그대로.

## 6. 이 문서가 실행하지 않은 것 — 정직한 고지

- cgroup 한계를 바꾸는 실험(진짜 컨테이너 메모리 제한) — 권한 없음. `MaxRAM`·`ActiveProcessorCount` 로 대신했다.
- AOT 캐시의 **기동 시간** 이득 — 잡음 속이라 수치를 적지 않았다.
- Vector API·Valhalla — 실행할 대상이 없다.
- 로거 벤치마크(1판 14장)·할당률 상한(1장) — 재측정하지 않았다.
- 클러스터(K8s·Argo·Istio)·Kafka 스트림 — 환경 없음.

## 7. 관련 문서

- [`00-1판-대비-변경내역.md`](00-1판-대비-변경내역.md)
- [`../JVM-용어-변천사.md`](../JVM-용어-변천사.md) — 여기 나온 이름들의 연표
- [`../../verify-labs-cloudnative/README.md`](../../verify-labs-cloudnative/README.md) — 위 ✅ 를 다시 돌리는 방법
- [`../java-performance/00-JDK-17-차이.md`](../java-performance/00-JDK-17-차이.md) · [`../optimizing-java/00-검토-2018년-책과-현재.md`](../optimizing-java/00-검토-2018년-책과-현재.md) — JDK 17 기준의 앞선 두 검토
