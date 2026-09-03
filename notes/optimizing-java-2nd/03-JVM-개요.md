# 3장 — JVM 개요 (Overview of the JVM)

> *Optimizing Cloud Native Java* 2판(2024) 3장 요약. **착수 순서 5순위**(기반 지식). 표기 — ✅ 실행해 확인(JDK 17.0.19 / 21.0.10 / 25.0.4) · 📄 문서로만 아는 것.
>
> **1판 2장 「JVM 개요」의 개정판.** 1판이 Java 8 의 단일 런타임(`rt.jar`·확장 클래스 로더)을 설명했다면 2판은 **모듈 시스템(JPMS)** 을 전제로 다시 썼고,
> 「자바 구현·배포판·릴리스」 절을 2024년 지형(Adoptium·Corretto·Microsoft·Zulu·GraalVM·OpenJ9·Semeru)으로 통째로 갱신했다.
>
> **JDK 25 기준 이 장의 변화 한눈에**
>
> | 책의 서술 (2024) | 지금 (JDK 25) | 근거 |
> |---|---|---|
> | LTS 는 8·11·17·21 | **25 가 다음 LTS**(2025-09). 이 저장소는 17 기본 + 25 모듈 | 📄 / ✅ 25.0.4 설치 |
> | VisualVM 은 별도 배포, `jconsole` 은 구식 | 그대로. `jconsole` 이 이 머신 17·25 패키지에 없는 것은 데비안 분할 탓(21 패키지에는 있다) | ✅ |
> | 자바 에이전트 `-javaagent`, JVMTI `-agentlib`/`-agentpath` | 그대로. **JDK 21 부터 동적 attach 에이전트는 기본 경고, 향후 `-XX:+EnableDynamicAgentLoading` 필요**(JEP 451) | 📄 |
> | Serviceability Agent | 그대로 `jhsdb` 존재 | ✅ 17·21·25 |
> | Oracle 은 Shenandoah 를 싣지 않는다 | 그대로. 이 머신의 Ubuntu 빌드에는 있다 | ✅ |
> | 클래스 파일 버전 검사 → `UnsupportedClassVersionError` | 그대로 — 25 로 컴파일한 클래스(69.0)를 21 에서 실행하면 "class file version 69.0 … up to 65.0" | ✅ 이 세션에서 관측 |
> | SecurityManager(언급 없음) | 24 에서 영구 비활성(JEP 486) — 에이전트·JMX 문맥에서 알아 둘 것 | ✅ CN-03A |

---

## 1. 이 장의 핵심 주장

1. JVM 은 **스택 기반 인터프리터**("while 안의 switch")로 시작한다. `java HelloWorld` → 부트스트랩 → 플랫폼 → 애플리케이션 클래스 로더 체인. **Java 9 부터 모든 JVM 은 모듈형**(호환 모드 없음) — 기동 시 항상 모듈 그래프(DAG)를 만들고, 비모듈 앱은 UNNAMED 모듈. 부트스트랩은 `java.base` 등 최소 모듈만(검증 생략, 전권 부여), 나머지 JDK 는 **플랫폼 로더**(확장 로더는 제거), 사용자 클래스는 **애플리케이션 로더**("시스템 로더"라 부르지 마라).
   클래스는 **로더 + FQCN** 으로 식별 — 앱 서버의 다중 테넌트, 에이전트의 재변환.
2. 바이트코드 실행: javac 는 최적화를 거의 안 한다. 클래스 파일 구조(`0xCAFEBABE`·버전·상수 풀·접근 플래그·this/super/interfaces·필드·메서드·속성). `javap -c HelloWorld` 해부(`aload_0`/`invokespecial`/`iconst_0`/`istore_1`/`if_icmpge`/`getstatic`/`ldc`/`invokevirtual`/`iinc`/`goto`).
3. **HotSpot(1999)**: 제로 오버헤드 원칙(Stroustrup)을 거부하고 **런타임 행동을 분석해 최적화**. JIT 는 메서드·루프 단위, 인터프리터에서 모은 트레이스 정보로 PGO, 재JIT 가능, "재컴파일 없이 새 HotSpot 의 최적화를 받는다". 실행되는 코드는 소스와 전혀 다를 수 있다 — 상식적 추론을 경계하라. **인트린식**(CPU 기능 탐지) ≠ intrinsic 락.
4. **메모리 관리**: GC 는 비결정적, 전통적으로 STW 였지만 2024년의 자바 GC 는 최고 수준이고 STW 는 훨씬 덜 필요하다(4·5장).
5. **스레딩과 JMM**: 모든 자바 프로그램은 본질적으로 멀티스레드(VM 스레드). 초기 M:N/그린 스레드 → 1:1 플랫폼 스레드 → **스레드 병목 → Loom → Java 21 가상 스레드**(명시적으로 만들어야 하고 기존 의미론은 보존). JMM 의 세 전제(공유 힙·참조로 접근·기본 가변). 방어 수단은 상호 배제 락뿐(13장).
6. **모니터링·도구**: **JMX**(+RMI), **자바 에이전트**(`-javaagent`, `Premain-Class`, `premain()`, `ClassFileTransformer`, 별도 스레드로 데이터 반출), **JVMTI**(네이티브, `-agentlib`/`-agentpath`, 위험), **SA**(대상 VM 에 코드 없이 프로세스 메모리 읽기, 코어 파일 디버깅). **VisualVM**(NetBeans 기반, JDK 6~8·GraalVM 19~23.0 에 동봉되다 분리, 첫 실행 시 보정, attach/jstatd/JMX 1099, Overview/Monitor/Threads/Sampler·Profiler 탭, VisualGC 플러그인).
7. **구현·배포판·릴리스**: OpenJDK 는 **소스**(GPLv2+CE), Oracle 이 주도. 배포판은 리눅스 배포판 비유. 선택 기준 셋(운영 비용? 버그 수정? 보안 패치?). Oracle JDK / **Eclipse Adoptium**(빌드·테스트 엔지니어 중심) / Red Hat(2위 기여자) / Amazon Corretto / Microsoft(2021-05, 11.0.11 부터) / Azul Zulu·**Platform Prime(구 Zing)** / GraalVM / OpenJ9·IBM Semeru / Android(ART 는 JVM 이 아니다). **"같은 소스에서 빌드하므로 배포판 간 성능 차이는 없다"** — 예외는 Oracle 이 Shenandoah 를 싣지 않는 것뿐. 6개월 릴리스(2017-09 부터), Oracle 은 다음 릴리스가 나오면 손을 떼고 **8u·11u·17u·21u** 만 커뮤니티가 잇는다 = LTS. 생태계는 "6개월마다 올려라"를 거부했다. "Java Is Still Free" 문서.

## 2. 절별 상세 요약

- **해석과 클래스 로딩**: 클래스 로더 체인의 위임(부모 먼저), `ClassNotFoundException`, "운영과 같은 클래스패스로 빌드하라". 부트스트랩이 `java.security.sasl`·`java.datatransfer` 같은 의외의 모듈도 로드.
- **바이트코드 실행**: 표 3-1 클래스 파일 구조, 그림 3-2 니모닉. 매직 넘버 "0xCAFEBABE 는 부끄럽고 성차별적이지만 바꾸기 어렵다". 상수 풀은 런타임 메모리 레이아웃에 의존하지 않기 위한 것.
- **HotSpot 소개**: C++ 의 "안 쓰면 안 낸다, 쓰면 손으로 더 잘 못 짠다" vs 자바의 관용적 코드 + 지능적 최적화. C++/Rust 는 예측 가능하지만 "예측 가능 ≠ 더 좋음". 마이크로벤치마크는 전문가의 일(부록 A).
- **JVM 과 OS 스레드**: "start() 가 불리면 고유한 OS 스레드가 생긴다고 가정해도 안전하다(플랫폼 스레드)". 가상 스레드는 고루틴에 비유.
- **도구**: 에이전트의 `premain` 은 main 스레드에서 main 전에 돌고 반드시 반환해야 한다. JVMTI 는 C/C++ — 오류가 JVM 을 죽일 수 있어 가능하면 자바 에이전트. SA 는 심벌 조회와 프로세스 메모리 읽기.
- **배포판**: X(구 Twitter)·Alibaba 는 자체 빌드. 보안 패치는 공개 전 비공개 수정 → 공개 후 각 저장소로 흐른다 → 그래서 LTS 에 머문다. Adoptium 회원사(Red Hat·Google·Microsoft·Azul)는 상류 기여를 회사 이름으로 한다. "소셜 미디어의 배포판 성능 차이 보고는 통계적으로 검증되기 전에는 회의적으로".

## 3. 1판(2018) 2장과 달라진 점

| 항목 | 1판 2장 | 2판 3장 | 근거 |
|---|---|---|---|
| 클래스 로더 | 부트스트랩(`rt.jar`) → **확장** → 애플리케이션 | 부트스트랩(`java.base` 모듈) → **플랫폼** → 애플리케이션, JPMS 전제 | ✅ 통독(1판 2장 재독 예정) |
| 스레딩 절 | 1:1 플랫폼 스레드 | + 그린 스레드 역사, **가상 스레드(21)** 4회 | ✅ 키워드 |
| 도구 절 | VisualVM 중심("9 부터 별도 배포" 예고) | + JMX·자바 에이전트·JVMTI·SA 를 정면으로(관측 가능성 장의 기반) | 📄 |
| 배포판 절 | Zulu·Zing·J9·Avian 등 "JVM 들을 만나다" | 2024 배포판 지형 + 릴리스 주기·LTS·"성능 차이 없음" | 📄 |
| Docker 언급 | 1회 | 없음(8장으로) | ✅ 키워드 |
| GraalVM | 없음(15장에 Graal) | 6회 — 배포판의 하나로 | ✅ |

## 4. JDK 25 기준 평가

- 클래스 로더 구조·클래스 파일 구조·`javap` 출력은 25 에서도 같다 📄(`javap` 존재 ✅).
- **동적 에이전트 로딩(JEP 451, JDK 21)**: `-javaagent` 로 기동 시 붙이는 것은 그대로지만, 실행 중 attach 로 에이전트를 올리면 21 부터 경고가 나고 장차 기본 차단된다 📄 — 관측 도구 운영자가 `-XX:+EnableDynamicAgentLoading` 을 알아야 한다. 이 저장소에서 실행하지 않았다.
- **SecurityManager 영구 비활성(JEP 486, 24)** ✅ — JMX·RMI 문맥의 옛 보안 설정 예제는 더 이상 돌지 않는다.
- **클래스 파일 버전**: 25 = 69.0 ✅(21 에서 실행 시 `UnsupportedClassVersionError`). 이 저장소의 `verify-labs-cloudnative` 가 Spring 없이 만들어진 이유이기도 하다(Spring Framework 6.1 의 ASM 이 69 를 읽는지 시험하지 않았다 📄).
- 릴리스: 25 가 LTS 📄. Ubuntu 패키지 `25.0.4+7-1-24.04-Ubuntu` 로 설치했다 ✅.
- 배포판 지형은 시점 서술 📄.

## 5. 이 장을 우리 랩에 비춰 보면

| 책의 명제 | 이 저장소의 근거 | 상태 |
|---|---|---|
| "배포판 간 성능 차이 없음, 같은 소스" | 이 저장소는 Ubuntu 빌드 17·21·25 만 쓴다 — 비교하지 않았다 | 미검증 |
| 클래스 파일 버전 검사 | `UnsupportedClassVersionError` 관측(25 클래스를 21 에서) | ✅ 세션 관측(케이스 아님) |
| 가상 스레드는 명시적으로 만든다 | `verify-labs-cloudnative` **CN-13A/B** | 2판 랩 케이스 |
| 옛 옵션·API 의 운명 | **CN-03A**, **PERF-A01**, **PERF-15A** | 기존 + 신규 |
| 자바 에이전트·JMX | `verify-labs` 의 `observability` 분류(액추에이터·JMX 관측) | 기존 |

## 6. 면접에서 쓸 수 있는 문장

- "Java 9 부터 확장 클래스 로더는 없고 플랫폼 로더가 그 자리를 잇습니다. 클래스는 이름만이 아니라 로더와 함께 식별되므로 앱 서버에서 같은 이름의 클래스가 둘 있을 수 있습니다."
- "OpenJDK 배포판들은 같은 소스에서 빌드하니 같은 버전이면 성능 차이가 없습니다. 예외는 Oracle 빌드에 Shenandoah 가 없다는 것 정도입니다."
- "LTS 는 8·11·17·21 이고 25 가 다음입니다. 6개월 릴리스를 그대로 따르는 팀은 드물고, LTS 에서 LTS 로 옮깁니다."

## 7. 관련 문서

- [`06-JVM-코드-실행.md`](06-JVM-코드-실행.md) — 바이트코드·JIT 상세
- [`../optimizing-java/02-JVM-개요.md`](../optimizing-java/02-JVM-개요.md) — 1판(Java 8 런타임 기준)
- [`../JVM-용어-변천사.md`](../JVM-용어-변천사.md) — 클래스 로더·JPMS·에이전트·SA 항목
