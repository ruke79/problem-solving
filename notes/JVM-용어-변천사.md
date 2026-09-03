# JVM 용어 변천사 — 도입·변경·제거·현재 사용 여부

> 요청 — *"용어도 별도로 문서를 만들어서 정리해 줘. G1, C2, CMS, ZGC, Shenandoah 등 JVM 관련 용어들의 변천사(현재 사용여부 포함)"*.
> 이 저장소의 세 책(2014 *Java Performance* · 2018 *Optimizing Java* · 2024 *Optimizing Cloud Native Java*)과 면접 원고에 나오는 JVM 용어를 **한 표에서 연표로** 본다.
>
> **표기** — ✅ 이 환경(JDK 17.0.19 · 21.0.10 · 25.0.4)에서 실행해 확인한 것 · 📄 JEP·릴리스 노트 기준. **"현재"는 JDK 25(2025-09) 기준**이다.
> 상태 기호 — 🟢 현역(기본 또는 권장) · 🟡 현역이지만 옵션/특수 용도 · 🟠 폐기 예정·미리보기·인큐베이터 · 🔴 제거됨 · ⚪ 아직 없음.

---

## 1. 가비지 컬렉터

| 용어 | 무엇 | 도입 | 주요 변경 | 제거 | JDK 25 현재 | 근거 |
|---|---|---|---|---|---|---|
| **Serial GC** (`-XX:+UseSerialGC`) | 단일 스레드 STW 수집기. Young 복사 + Old 마크·스윕·압축 | JDK 1.2 이전부터 | 컨테이너 시대에 재조명 — **CPU 1개(또는 메모리 < 2 GB) 면 에르고노믹스가 자동 선택** | — | 🟢 1 CPU 컨테이너의 기본 | ✅ `ActiveProcessorCount=1` → Serial (`CN-04A`) |
| **Parallel GC** (`-XX:+UseParallelGC`, 옛 이름 Throughput Collector, ParallelOld) | 다중 스레드 STW. 처리량 최우선 | 1.4.1 (Young), 5/6 ParallelOld | JDK 8 까지 서버 기본 | — | 🟡 배치·처리량 워크로드 옵션 | 📄 |
| **CMS** (Concurrent Mark Sweep, `-XX:+UseConcMarkSweepGC`) | Old 를 동시 마킹·스윕(압축 없음). 저지연의 원조 | 1.4.1 | JDK 9 폐기(JEP 291) | **JDK 14 제거(JEP 363)** | 🔴 플래그는 Unrecognized — **기동 실패** | ✅ 17·25 (`PERF-A01`·`CN-03A`) |
| iCMS (Incremental CMS) | 단일 코어용 CMS 변형 | 1.4.2 | JDK 8 폐기 | JDK 9 제거 | 🔴 | 📄 |
| **G1** (Garbage-First, `-XX:+UseG1GC`) | 리전 기반, 예측 가능한 일시정지(`MaxGCPauseMillis`), 혼합 GC, 동시 마킹 | 6u14 실험, 7u4 정식 | **JDK 9 기본 수집기**(JEP 248) · 10 병렬 풀 GC(JEP 307) · 12 중단 가능 혼합 GC · 20 이후 리전 고정 크기 조정 개선 | — | 🟢 **기본 수집기** | ✅ `UseG1GC=true`, 리전 2 MB, `MaxGCPauseMillis=200` (25) |
| **ZGC** (`-XX:+UseZGC`) | 컬러 포인터·로드 배리어, 일시정지 < 1 ms(힙 크기 무관), TB 급 힙 | **11 실험**(JEP 333) | **15 정식**(JEP 377) · **21 세대형 옵션** `-XX:+ZGenerational`(JEP 439) · **23 세대형 기본**(JEP 474) · **24 비세대형 제거**(JEP 490) | (비세대형만) | 🟢 세대형만 남음. `-XX:-ZGenerational` 은 경고 후 무시 | ✅ 21 `ZGC Cycles/Pauses` vs 25 `ZGC Minor/Major …`; 25 "support was removed in 24.0" (`CN-05A`) |
| **Shenandoah** (`-XX:+UseShenandoahGC`) | Red Hat 의 동시 압축 수집기(브룩스 포인터 → 로드 참조 배리어) | **12**(JEP 189, 실험; Red Hat 빌드는 8 부터) | 15 정식(JEP 379) · **24 세대형 실험**(JEP 404) · **25 세대형 정식**(JEP 521) | — | 🟢 옵션. 기본 모드는 `satb`, 세대형은 `-XX:ShenandoahGCMode=generational` | ✅ 21 "Unknown ShenandoahGCMode option", 25 `Mode: Generational` (`CN-05B`) |
| Epsilon GC (`-XX:+UseEpsilonGC`) | 회수하지 않는 no-op 수집기(테스트·초단명 프로세스) | 11 (JEP 318, 실험) | — | — | 🟡 실험(`UnlockExperimentalVMOptions`) | 📄 |
| C4 (Continuously Concurrent Compacting Collector) | Azul Zing 의 무정지 수집기 | 2010년경 | Zing → Azul Platform Prime | — | 🟡 상용 JVM 전용. 2판에서 언급 삭제 | ✅ 키워드(2판 0회) |
| IBM J9 Balanced / gencon | OpenJ9 의 수집기 정책 | — | Eclipse OpenJ9 로 공개 | — | 🟡 OpenJ9 전용 | 📄 |
| **세대 가설 · Young/Old(Tenured) · Eden/Survivor** | "대부분의 객체는 젊어서 죽는다" | 초기부터 | ZGC 도 21 부터 세대형 → **모든 주류 수집기가 세대형** | — | 🟢 | 📄 |
| **PermGen** | 클래스 메타데이터 영역(힙 안) | 초기 | — | **JDK 8 제거(JEP 122) → Metaspace** | 🔴 `-XX:MaxPermSize` 는 무시 | 📄 |
| **Metaspace** (`-XX:MaxMetaspaceSize`) | 네이티브 메모리의 클래스 메타데이터 | 8 | 16 에서 할당기 개선(JEP 387) | — | 🟢 | 📄 |
| **TLAB** (Thread-Local Allocation Buffer) | 스레드별 Eden 조각 — 락 없는 O(1) 할당 | HotSpot 초기 | JFR `ObjectAllocationInNewTLAB/OutsideTLAB` 이벤트, 16 `ObjectAllocationSample` | — | 🟢 | 📄 (하드웨어 TLB 와 혼동 금지) |
| 카드 테이블 / RSet(Remembered Set) | 세대 간 참조 추적(512 바이트당 1 바이트 / G1 리전별) | 초기 / G1 | ZGC·Shenandoah 는 배리어 방식이라 카드 테이블 없음. G1 은 20 부터 RSet 대신 카드 세트 | — | 🟢 (Serial·Parallel·G1) | 📄 |
| **압축 oop** (`-XX:+UseCompressedOops`) | 32 GB 미만 힙에서 참조 4 바이트 | 6u23 기본 | — | — | 🟢 기본 true | ✅ 25 (`CN-03A` 대조군) |
| **컴팩트 객체 헤더** (`-XX:+UseCompactObjectHeaders`) | 헤더 12 → 8 바이트(마크 워드에 클래스 포인터 압축) | **24 실험**(JEP 450) | **25 정식**(JEP 519), 기본 꺼짐 | — | 🟡 옵션(기본 false) | ✅ 25 빈 객체 16.0 → 8.0 바이트 (`CN-05C`) |
| 문자열 중복 제거 (`-XX:+UseStringDeduplication`) | 같은 내용의 `byte[]` 공유 | 8u20 G1 (JEP 192) | 18 에서 Serial·Parallel·ZGC 로 확대 | — | 🟡 옵션 | 📄 |
| **GC 에르고노믹스 / 서버 클래스 머신** | CPU ≥ 2 이고 메모리 ≥ 2 GB 면 서버 설정(G1), 아니면 Serial | 5 | 10 컨테이너 인식(cgroup v1, 8u191 백포트) · **17 cgroup v2**(JDK-8230305) · `MaxRAMPercentage`(25%) | — | 🟢 | ✅ `MaxRAM=2g` → 힙 495 MB; `MaxRAM` 은 GC 선택에 무관 (`CN-04A/B`) |
| `-XX:+UseContainerSupport` | 컨테이너 한계 읽기 | 10 / 8u191 | — | — | 🟢 기본 true | 📄 |
| **통합 GC 로깅** (`-Xlog:gc*`) | 태그·레벨·데코레이터·출력 옵션이 있는 단일 로깅 | **9**(JEP 158·271) | — | 옛 플래그(`PrintGCDetails` 경고 / `PrintGCTimeStamps`·`PrintGCDateStamps`·`PrintTenuringDistribution`·`PrintGCApplicationStoppedTime` **Unrecognized**) | 🟢 유일한 방법 | ✅ 17 (`PERF-08A`), 25 (`CN-03A`) |
| GC 인터페이스 (JEP 304) | 수집기 코드 격리 | 10 | Epsilon·ZGC·Shenandoah 가 이 위에 붙었다 | — | 🟢 내부 | 📄 |

## 2. 실행 엔진 — 인터프리터·JIT·AOT

| 용어 | 무엇 | 도입 | 주요 변경 | 제거 | JDK 25 현재 | 근거 |
|---|---|---|---|---|---|---|
| **HotSpot** | Sun 의 JVM(1999 Java 1.3 부터 기본). 인터프리터 + JIT + GC | 1.3 | Oracle → OpenJDK 참조 구현 | — | 🟢 사실상 표준 VM(Temurin·Corretto·Zulu·Oracle 전부 HotSpot) | 📄 |
| 템플릿 인터프리터 | 바이트코드별 어셈블리 템플릿을 기동 시 생성 | 1.3 | — | — | 🟢 계층 0 | 📄 |
| **C1** (클라이언트 컴파일러, `-client`) | 빠른 컴파일·가벼운 최적화 | 1.3 | 7 계층형 컴파일에서 계층 1~3 담당; `-client` 스위치는 64비트에서 무시 | — | 🟢 계층 1~3 | ✅ `TieredStopAtLevel=4` |
| **C2** (서버 컴파일러, `-server`, opto) | 프로파일 기반 공격적 최적화(인라이닝·탈출 분석·루프 최적화·인트린직) | 1.3 | 1판(2018)은 "수명이 끝나 Graal 로 대체된다"고 예측 → **빗나감**. 25 도 C2 가 최종 계층 | — | 🟢 계층 4. 현역 | ✅ 17·21·25 인라이닝 기본값 동일(`MaxInlineSize` 35 등) |
| **계층형 컴파일** (`-XX:+TieredCompilation`) | 0 인터프리터 → 1~3 C1(프로파일 수집) → 4 C2 | 7 | 8 기본 | — | 🟢 기본 | ✅ `Tier3/4InvocationThreshold` 200/5000 |
| OSR (On-Stack Replacement) | 실행 중인 루프를 컴파일 코드로 교체 | 초기 | — | — | 🟢 | 📄 |
| **역최적화** (deoptimization, "made not entrant") | 투기적 가정이 깨지면 인터프리터로 복귀 | 초기 | — | — | 🟢 | ✅ `PrintCompilation` 에서 관측 (`PERF-10D`) |
| **탈출 분석 / 스칼라 치환** | 탈출하지 않는 객체를 할당하지 않는다 | 6u14 (기본 6u23) | Graal 의 부분 탈출 분석은 JDK 밖 | — | 🟢 (`EliminateAllocationArraySizeLimit` 64) | ✅ `PERF-08A` 프로브의 할당이 사라진 사건 |
| **인트린직** (`@IntrinsicCandidate`) | 메서드를 손으로 짠 어셈블리/IR 로 치환 | 초기 | 9 `@HotSpotIntrinsicCandidate` → 16 `@IntrinsicCandidate`(`jdk.internal.vm.annotation`) | — | 🟢 (`UseAES`·`UseFMA`·`UseAVX=3`) | ✅ 25 |
| **코드 캐시 / 분할 코드 캐시** | 컴파일 코드 저장소, 9 부터 비메서드·프로파일·비프로파일 세 영역 | 초기 / **9**(JEP 197) | — | — | 🟢 `SegmentedCodeCache=true`, 240 MB | ✅ 17/21 251,658,240 · 25 251,662,336 (`CN-06B`) |
| **세이프포인트** | 모든 스레드가 멈출 수 있는 지점(GC·역최적화·바이어스 회수) | 초기 | **10 스레드 로컬 핸드셰이크**(JEP 312)로 전역 세이프포인트 없이 개별 스레드 정지 | `PrintSafepointStatistics` 제거 → `-Xlog:safepoint` | 🟢 | ✅ 옛 플래그 Unrecognized |
| **편향 락** (`-XX:+UseBiasedLocking`) | 경쟁 없는 락의 CAS 를 없애는 최적화 | 6 (기본 켜짐) | **15 기본 비활성·폐기**(JEP 374) | **18 제거**(JEP 429) | 🔴 17 경고, 21+ Unrecognized | ✅ 25 기동 실패 (`CN-03A`) |
| 경량 락(스택 락) / 무거운 락(모니터 팽창) | `synchronized` 의 두 단계 | 초기 | 21 새 경량 락 구현(`LockingMode=2`, 24 기본) · **24 가상 스레드가 모니터를 잡은 채 마운트 해제**(JEP 491) | — | 🟢 | ✅ 고정 실험 21 607 ms → 25 10 ms (`CN-13A`) |
| **JVMCI** (JVM Compiler Interface) | 자바로 쓴 JIT 를 꽂는 인터페이스 | **9**(JEP 243) | — | — | 🟡 인터페이스는 남았으나 **OpenJDK 안에 컴파일러가 없다** | ✅ `-XX:+UseJVMCICompiler` → "No JVMCI compiler found" |
| **Graal JIT** (`-XX:+UseJVMCICompiler`) | 자바로 쓴 C2 대체 후보 | **10 실험**(JEP 317) | 1판(2018)의 "C2 대체" 예측 | **17 제거**(JEP 410) | 🔴 JDK 안에서는. **GraalVM 제품**으로 존속 | ✅ `jdk.internal.vm.compiler`(17·21)·`jdk.graal.compiler`(25) 는 module-info 하나뿐 |
| **`jaotc`** (Java AOT 컴파일러) | Graal 로 클래스를 미리 네이티브 코드로 | **9**(JEP 295, Linux 만) | 1판 "지원 확대 예정" | **17 제거**(JEP 410) | 🔴 | ✅ `bin/jaotc` 없음 (`PERF-15A`) |
| **GraalVM Native Image** (SubstrateVM) | 폐쇄 세계 가정으로 정적 실행 파일 | 2018 | Spring Boot 3 AOT·Quarkus 의 네이티브 모드 | — | 🟢 JDK 밖 제품 | 📄 |
| **CDS / AppCDS / 동적 CDS** | 클래스 메타데이터 아카이브를 mmap 해 기동 단축 | 5 / **10 AppCDS**(JEP 310) / 12 기본 아카이브(JEP 341) / 13 동적(JEP 350) / 19 자동 생성(`-XX:+AutoCreateSharedArchive`) | — | — | 🟢 기본 아카이브 항상 사용 | ✅ 기본 CDS 에서 670 클래스, `-Xshare:off` 0 |
| **AOT 캐시 / Project Leyden** | 훈련 실행으로 클래스 로딩·링크·프로파일을 아카이브(`-XX:AOTCache`) | **24**(JEP 483) | **25** 한 단계 명령 `-XX:AOTCacheOutput`(JEP 514) · 메서드 프로파일(JEP 515) | — | 🟢 정식. **클래스패스는 JAR 이어야** 한다 | ✅ 25 761 클래스; 21 Unrecognized (`CN-06A`) |
| Project Metropolis | HotSpot 을 자바로 다시 쓰기 | 2017 예고 | 사실상 중단 | — | ⚪ | 📄 |
| **JITWatch** | `-XX:+LogCompilation` 로그 시각화 도구 | 2013 (Chris Newland) | 1판 30회 → 2판 축소 | — | 🟡 유지 중 | ✅ 키워드 |

## 3. 언어·바이트코드·라이브러리

| 용어 | 무엇 | 도입 | 주요 변경 | 제거 | JDK 25 현재 | 근거 |
|---|---|---|---|---|---|---|
| **`invokedynamic`** | 런타임에 호출 지점을 링크하는 바이트코드 | 7 (JSR 292) | 8 람다 · **9 문자열 연결**(JEP 280, `makeConcatWithConstants`) | — | 🟢 | ✅ 상수 풀에서 확인 (`PERF-15A`) |
| **메서드 핸들 / VarHandle** | 직접 실행 가능한 메서드·필드 참조; VarHandle 은 CAS·메모리 순서 모드 | 7 / **9**(JEP 193) | Unsafe 의 공식 대체 경로 | — | 🟢 | ✅ `VarHandle` 존재 |
| **constant dynamic** (condy) | 첫 사용 시 계산되는 상수 풀 엔트리 | 11 (JEP 309) | 1판이 예고 | — | 🟢 | 📄 |
| **Compact Strings** (`-XX:+CompactStrings`) | `String.value` 가 `byte[]` + `coder`(Latin-1 = 1 바이트) | **9**(JEP 254) | — | — | 🟢 기본 true | ✅ ASCII 10자 10 바이트, 한글 10자 20 바이트 (`PERF-11E`) |
| **`sun.misc.Unsafe`** | 내부 저수준 API(CAS·메모리·객체 배치) | 1.4 | 9 `jdk.unsupported` 모듈로 격리 · **23 메모리 접근 메서드 제거 예정 폐기**(JEP 471) · **24 호출 시 경고**(JEP 498) | (단계적) | 🟠 25 경고, `--sun-misc-unsafe-memory-access=deny` 로 차단 가능 | ✅ 25 "terminally deprecated method" (`CN-15A`) |
| `jdk.internal.misc.Unsafe` | JDK 내부 전용 Unsafe | 9 | `AtomicInteger` 등이 여전히 사용 | — | 🟢 내부 | ✅ `AtomicInteger` 필드 `Unsafe U` (17·21·25) |
| **`--illegal-access`** | 모듈 캡슐화 완화 스위치 | 9 (JEP 261) | 16 기본 `deny`(JEP 396) | **17 기능 제거**(JEP 403) — 옵션은 경고 후 무시 | 🔴 "Ignoring option --illegal-access; support was removed in 17.0" | ✅ 17·21·25 rc=0 |
| 강한 캡슐화 / `--add-opens` / `--add-exports` | 내부 패키지 접근의 유일한 방법 | 9 / 16~17 | `@Contended`·`jdk.internal.vm.annotation` 도 이 대상 | — | 🟢 | ✅ perfbook 테스트 `--add-opens java.base/java.lang` |
| **모듈 시스템** (Jigsaw, JPMS) | `module-info`, 런타임 이미지 `lib/modules`, `jlink` | **9**(JEP 261) | 25: `jdk.random` 제거, `jdk.internal.md` 추가 | `rt.jar`·확장 로더 제거 | 🟢 | ✅ `ModuleFinder.ofSystem()` 대조 |
| 플랫폼 클래스로더 | 확장(Extension) 로더의 후신 | 9 | — | 확장 로더 | 🟢 | 📄 |
| **`finalize()`** | GC 전 콜백 | 초기 | 9 폐기 · **18 제거 예정 폐기**(JEP 421) | (예정) | 🟠 21·25 `forRemoval=true` | ✅ 리플렉션 |
| `Cleaner` / `PhantomReference` | `finalize` 의 대체 | 9 | — | — | 🟢 | 📄 |
| **`SecurityManager`** | 샌드박스 권한 모델 | 1.0 | **17 폐기**(JEP 411) | **24 영구 비활성**(JEP 486) | 🔴 25 `setSecurityManager` → `UnsupportedOperationException`, `-Djava.security.manager` 기동 실패 | ✅ (`CN-03A`) |
| Nashorn | JavaScript 엔진 | 8 | 11 폐기 | **15 제거**(JEP 372) | 🔴 | 📄 |
| `jdk.unsupported` | Unsafe 등 내부 API 를 노출하는 모듈 | 9 | — | — | 🟡 | 📄 |
| 컴팩트 소스 파일 / 인스턴스 `main` | `void main()` 만으로 실행 | 21 미리보기 | **25 정식**(JEP 512) · `import module`(JEP 511) | — | 🟢 | ✅ 25 컴파일·실행 |
| `var` | 지역 변수 타입 추론 | 10 (JEP 286) | — | — | 🟢 | 📄 |
| 레코드 · sealed · 패턴 매칭 | 데이터 지향 프로그래밍 | 16 · 17 · 21 | — | — | 🟢 | 📄 |

## 4. 동시성

| 용어 | 무엇 | 도입 | 주요 변경 | 제거 | JDK 25 현재 | 근거 |
|---|---|---|---|---|---|---|
| **JMM** (Java Memory Model, JSR 133) | happens-before, `volatile`·락의 가시성 규칙 | 5 | 9 VarHandle 의 메모리 순서 모드 추가 | — | 🟢 | 📄 |
| 그린 스레드 | 사용자 모드 스레드(초기 Solaris) | 1.1 | 1.3 부터 네이티브 스레드 | 🔴 | — | 📄 |
| **플랫폼 스레드** (= OS 스레드 1:1) | `Thread` 의 기본 | 1.3 | 21 부터 "플랫폼 스레드"라는 이름이 생김 | — | 🟢 | 📄 |
| **가상 스레드 / Project Loom** | JVM 이 스케줄하는 경량 스레드, 캐리어 = ForkJoinPool | 19·20 미리보기 | **21 정식**(JEP 444) · **24 `synchronized` 고정 해소**(JEP 491) | — | 🟢 | ✅ 데몬, 태스크마다 새 스레드, 고정 21 vs 25 (`CN-13A/B`) |
| 고정(pinning) | 가상 스레드가 캐리어에서 못 내려오는 상태 | 21 | 24 부터 `synchronized` 는 원인이 아님. 네이티브 프레임·`Object.wait` 일부는 남음 | — | 🟡 `jdk.VirtualThreadPinned` JFR 이벤트 | ✅ 21·25 같은 필드 |
| **구조적 동시성** (`StructuredTaskScope`) | 서브태스크를 한 단위로 fork/join | 21 미리보기(JEP 453) | 25 5번째 미리보기(JEP 505) — API `open()` 으로 변경 | — | 🟠 미리보기 | ✅ 책의 코드가 25 에서 컴파일 안 됨 |
| **`ScopedValue`** | 스코프 안에서 불변인 스레드 문맥(ThreadLocal 대체) | 21 미리보기(JEP 446) | **25 정식**(JEP 506) | — | 🟢 | ✅ (`CN-13C`) |
| `ThreadLocal` | 스레드별 변수 | 1.2 | 가상 스레드에서는 수백만 개가 될 수 있어 주의; ScopedValue 권장 | — | 🟢 | 📄 |
| Fork/Join · `ForkJoinPool` | 작업 훔치기 풀 | 7 | 8 `commonPool`(병렬도 = CPU−1) · 21 가상 스레드 스케줄러 | — | 🟢 | ✅ 4코어 → 3, `ActiveProcessorCount=2` → 1 (`CN-09A`) |
| `CompletableFuture` · 병렬 스트림 | 비동기·데이터 병렬 | 8 | — | — | 🟢 | 📄 |
| `@Contended` | 캐시 라인 패딩 주석 | 8 (`sun.misc`) → 9 `jdk.internal.vm.annotation` | 앱은 `--add-exports` 필요 | — | 🟡 내부 | ✅ 17 |
| 스핀 대기 힌트 `Thread.onSpinWait()` | PAUSE 명령 | 9 (JEP 285) | — | — | 🟢 | 📄 |

## 5. 네이티브·메모리·미래 프로젝트

| 용어 | 무엇 | 도입 | 주요 변경 | 제거 | JDK 25 현재 | 근거 |
|---|---|---|---|---|---|---|
| **JNI** | C 로 쓰는 네이티브 메서드 | 1.1 | 24 부터 JNI 사용에도 `--enable-native-access` 경고(JEP 472) | — | 🟢 (레거시 경로) | 📄 |
| **Project Panama / FFM API** | `Linker`·`MemorySegment`·`Arena` 로 JNI 없이 네이티브 호출·메모리 | 14~18 인큐베이터(`jdk.incubator.foreign`) · 19~21 미리보기(`java.lang.foreign`) | **22 정식**(JEP 454) · 24~25 제한 메서드 경고(JEP 472) | — | 🟢 | ✅ 25 `strlen` 다운콜 + "restricted method" 경고; 21 미리보기 (`CN-15B`) |
| **Vector API** | SIMD 명시 API | 16 인큐베이터(JEP 338) | 25 **10번째 인큐베이터**(JEP 508) — Valhalla 대기 | — | 🟠 인큐베이터 | 📄 |
| **Project Valhalla / 값 클래스** | 정체성 없는 클래스, 평탄 배열 | 2014 시작 | 1판(2018) "2019년 출시" 예측 → 빗나감. 설계가 R/Q/U 타입 → `value` 키워드로 바뀜 | — | ⚪ 25 에 없음 | 📄 |
| **Project Leyden** | 기동·워밍업 단축(컨덴서·premain) | 2022 | 24~25 AOT 캐시로 첫 결과물 | — | 🟢 (AOT 캐시) | ✅ 위 2절 |
| `ByteBuffer.allocateDirect` | 힙 밖 버퍼 | 1.4 | FFM `MemorySegment` 가 상위 호환(2 GB 한계 없음) | — | 🟢 | 📄 |
| **NMT** (Native Memory Tracking) | JVM 자체의 네이티브 메모리 계정 | 7u40 | `jcmd VM.native_memory` | — | 🟢 | 📄 |
| 라지 페이지 (`-XX:+UseLargePages`, THP) | TLB 미스 감소 | 5 | — | — | 🟡 | 📄 |

## 6. 관측·도구

| 용어 | 무엇 | 도입 | 주요 변경 | 제거 | JDK 25 현재 | 근거 |
|---|---|---|---|---|---|---|
| **JFR** (Java Flight Recorder) | 저오버헤드 이벤트 기록기 | JRockit → 7u40 Oracle 상용 | **11 오픈소스**(JEP 328) · 14 이벤트 스트리밍(JEP 349) · **25 `jdk.CPUTimeSample`**(JEP 509)·`jdk.MethodTrace`/`MethodTiming`(JEP 520) | `-XX:+UnlockCommercialFeatures` 제거 | 🟢 | ✅ 스트리밍 17·21·25, 새 이벤트 25 만 (`CN-12A`) |
| **JMC** (JDK Mission Control) | JFR 뷰어·JMX 콘솔 | JRockit → 7u40 | 7 (2018) 오픈소스, JDK 와 별도 배포(Adoptium·Azul·Oracle) | JDK 동봉 아님 | 🟢 | 📄 |
| **JMX** / RMI | 관리 빈·원격 모니터링 | 5 | 컨테이너에서는 JFR·OTel 이 우선 | — | 🟡 | 📄 |
| Java 에이전트 (`-javaagent`) / 동적 부착 | 바이트코드 계측 | 5 / 6 | **21 동적 부착 시 경고**(JEP 451) | — | 🟢 / 🟠 | 📄 |
| JVMTI | 네이티브 도구 인터페이스 | 5 | — | — | 🟢 | 📄 |
| **hprof** (에이전트) | JVMTI 참조 구현 프로파일러 | 5 | — | **9 제거**(JEP 240) | 🔴 (덤프 **형식**으로서의 `.hprof` 는 남음) | ✅ 도구 목록 |
| `jhat` | hprof 덤프 뷰어 | 6 | — | 9 제거(JEP 241) | 🔴 | ✅ 없음 |
| **VisualVM** (`jvisualvm`) | GUI 모니터·프로파일러 | 6u7 | — | **9 부터 JDK 에서 분리**(별도 다운로드) | 🟡 | 📄 |
| `jconsole` | JMX 콘솔 | 5 | — | — | 🟡 JDK 에 있음(배포판에 따라 패키지 분리) | ✅ 이 환경은 21 패키지에만 |
| **Serviceability Agent** / `jhsdb` | 프로세스·코어 파일을 밖에서 읽는 디버거 | 6 / 9 통합 | — | — | 🟢 | ✅ 17·21·25 |
| `jcmd` · `jmap` · `jstack` · `jstat` | 진단 CLI | 7 / 5 | `jcmd` 가 통합 진입점(`GC.heap_dump`·`Thread.dump_to_file`·`Compiler.perfmap`) | — | 🟢 | ✅ |
| **Async Profiler** | `AsyncGetCallTrace` + perf 기반, 세이프포인트 편향 없음 | 2016 | 2판의 대표 프로파일러 | — | 🟢 JDK 밖 | ✅ 키워드(2판 18회) |
| Honest Profiler | 같은 원리의 선구자 | 2014 | 개발 중단 | — | 🔴 사실상 | 📄 |
| `-XX:+PreserveFramePointer` | perf 스택 추적용 | 8u60 | 17 `-XX:+DumpPerfMapAtExit` 로 perf-map-agent 불필요 | — | 🟡 기본 false | ✅ 25 |
| `-XX:+DebugNonSafepoints` | 인라인 프레임 정확도 | 8 | — | — | 🟡 진단, 기본 false | ✅ 25 |
| **Censum** / Illuminate (jClarity) | GC 로그 분석 상용 도구 | 2013 | Microsoft 인수(2019) | 단종 | 🔴 | ✅ 키워드(2판 0회) |
| GCViewer · GCeasy | GC 로그 뷰어 | — | — | — | 🟢 | 📄 |
| jHiccup | JVM "딸꾹질" 히스토그램 | 2012 (Gil Tene) | — | — | 🟡 | 📄 |
| Red Hat Thermostat | 오픈소스 모니터링 | 2012 | 종료 | 🔴 | — | 📄 |
| **OpenTelemetry** / Micrometer | 관측가능성 표준 / 메트릭 파사드 | 2019 / 2017 | 2판 10·11장의 중심 | — | 🟢 JDK 밖 | ✅ Micrometer 1.13.6 (`CN-10A/11A`) |
| Cryostat | 컨테이너용 JFR 관리 | 2020 | 2판 8회 | — | 🟢 | ✅ 키워드 |

## 7. JVM 배포판·벤더

| 이름 | 무엇 | 상태(2025) | 근거 |
|---|---|---|---|
| **OpenJDK** | GPL+CPE 참조 구현. 모든 아래 배포판의 소스 | 🟢 | 📄 |
| **Oracle JDK** | 17 부터 NFTC(무료) → LTS 다음 LTS 1년 뒤부터 유료 지원 | 🟢 | 📄 |
| **Eclipse Temurin** (Adoptium) | AdoptOpenJDK 의 후신, 가장 널리 쓰이는 무료 빌드 | 🟢 | 📄 |
| **Amazon Corretto** · **Azul Zulu** · **Microsoft Build of OpenJDK** · Red Hat build · BellSoft Liberica | HotSpot 계열 무료 빌드 | 🟢 | 📄 |
| **GraalVM** (Oracle) | Graal JIT + Native Image. JDK 안의 Graal 은 17 에서 빠졌고 제품으로만 | 🟢 | ✅ 모듈 공동화 |
| **Azul Platform Prime** (옛 Zing) | C4 수집기·Falcon JIT 의 상용 JVM | 🟢 상용 | 📄 |
| **Eclipse OpenJ9** (IBM Semeru) | 옛 IBM J9 | 🟢 | 📄 |
| IcedTea · Avian · Apache Harmony | 1판 2장의 목록 | 🔴 종료 | 📄 |

## 8. 읽는 법 — 면접에서 자주 섞이는 것

- **"CMS 튜닝 경험"** 은 JDK 8 이야기다. 14 부터 플래그가 기동을 막는다 ✅. 같은 자리에 지금은 **G1(기본) · 세대형 ZGC(< 1 ms) · Shenandoah** 가 있다.
- **"C2 를 Graal 이 대체한다"** 는 2018년 예측이고 빗나갔다. 25 도 C2 가 최종 계층이며 OpenJDK 안에 Graal 컴파일러는 없다 ✅.
- **ZGC 를 "세대형/비세대형" 으로 나눠 말하는 것**은 21~23 이야기다. 24 부터 세대형뿐 ✅.
- **`-XX:+UseBiasedLocking` · `PrintGCDetails` · `--illegal-access` · `PermSize`** 는 전부 사라졌거나 무시된다 ✅. 답변에 나오면 "지금은 …" 을 덧붙여야 한다.
- **가상 스레드의 `synchronized` 고정**은 21 의 제약이고 24 부터 아니다 ✅ — "`ReentrantLock` 으로 바꿔라"는 조언은 21 에 머무는 팀에게만 유효하다.
- **Unsafe 를 쓰는 라이브러리**(Agrona·Netty 옛 버전·일부 직렬화기)는 25 에서 경고가 뜨고 `deny` 면 예외다 ✅. 대체는 FFM·VarHandle.

## 9. 관련 문서

- [`optimizing-java-2nd/01-최신-JDK-기준-평가.md`](optimizing-java-2nd/01-최신-JDK-기준-평가.md) — 위 ✅ 의 명령과 출력
- [`optimizing-java/00-검토-2018년-책과-현재.md`](optimizing-java/00-검토-2018년-책과-현재.md) §5 — 1판 15장 예측 성적표
- [`java-performance/00-JDK-17-차이.md`](java-performance/00-JDK-17-차이.md) — 2014년 책 기준의 변화
- [`../verify-labs-cloudnative/README.md`](../verify-labs-cloudnative/README.md) · [`../verify-labs-perfbook/`](../verify-labs-perfbook/) — 상시 실행 케이스
