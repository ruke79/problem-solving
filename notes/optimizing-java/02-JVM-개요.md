# 2장 — JVM 개요 (Overview of the JVM)

> *Optimizing Java* 1판(2018) 2장 요약. [`00-검토-2018년-책과-현재.md`](00-검토-2018년-책과-현재.md) §7 의 **착수 순서 5순위**(3·2장 — 기반 지식, JDK 차이 작음). 표기 — ✅ 실행해 확인 · 📄 문서로만 아는 것.
>
> **2판(2024)에서는** [3장 「JVM 개요」](../optimizing-java-2nd/03-JVM-개요.md) 로 옮겨 갔다(2장 자리에 성능 테스트 방법론이 들어왔다). 확장 클래스로더 → 플랫폼 클래스로더, JVM 벤더 목록 갱신, VisualVM → JMC 가 주된 차이다(§3).

---

## 1. 이 장의 핵심 주장

1. **JVM 은 스택 기반 인터프리터 머신**("while 안의 switch" 가 첫 모델). `java HelloWorld` → `main()` 을 실행하려면 먼저 **클래스로딩**: **부트스트랩**(핵심 런타임, Java 8 까지 `rt.jar`) → **확장**(Extension, 부모에 위임; Nashorn 이 여기서 로드) → **애플리케이션**(클래스패스; "시스템 로더"라 부르지 말 것 — 시스템 클래스를 로드하는 건 부트스트랩). 못 찾으면 부모에 위임, 끝까지 없으면 `ClassNotFoundException`. **클래스의 정체성 = 로더 + 완전한 이름**(같은 클래스를 다른 로더가 두 번 로드할 수 있다).
2. **바이트코드 실행**: `javac` 는 최적화를 거의 안 한다 — 바이트코드는 읽기 쉽다(`javap -c`). 클래스 파일 구조 — **`0xCAFEBABE`**, 버전(낮은 런타임에서 `UnsupportedClassVersionError`), 상수 풀, 접근 플래그, this/super/interfaces, 필드, 메서드, 속성(`Code`). Java 9 모듈 파일은 `0xCAFEDADA`. `HelloWorld` 의 `main` 을 `iconst_0`·`istore_1`·`if_icmpge`·`getstatic`·`ldc`·`invokevirtual`·`iinc`·`goto` 로 한 줄씩 읽는다.
3. **HotSpot(1999)**: C++ 의 **제로 오버헤드 원칙**("안 쓰면 안 낸다, 쓰면 손으로 더 잘 못 짠다")을 자바는 **채택하지 않았다.** 대신 **런타임 동작을 분석해 이득이 큰 곳에 최적화를 적용**한다 — 관용적 자바를 쓰라는 것이 목표.
4. **JIT**: 인터프리터로 시작 → 자주 실행되는 **메서드와 루프**(컴파일 단위)를 네이티브로. 인터프리트 단계에서 모은 **프로파일 정보**로 AOT 가 못 하는 최적화(동적 인라이닝, 가상 호출 제거)를 한다. 기동 시 CPU 를 감지해 그 프로세서 전용 명령을 쓴다(**인트린직** — `synchronized` 의 내재적 락과 혼동 금지). **실행되는 코드는 소스와 전혀 다르게 생겼다** — 이것이 성능 조사의 출발점. 마이크로벤치마크는 전체 앱 분석보다 **더 어렵다**(5장).
5. **메모리 관리**: 수동 관리의 실패 경험 → **GC**. 비결정적, STW 일시정지. 6·7·8장.
6. **스레딩과 JMM**: 자바 스레드 = OS 스레드 하나(**그린 스레드는 성능이 안 나와 버려졌다**). 설계 원칙 — 모든 스레드가 하나의 힙을 공유, 참조만 있으면 어떤 스레드든 접근, 객체는 기본 가변(`final` 만 예외). **JMM** 은 "A 가 바꾼 값을 B 가 언제 보는가"의 공식 모델. 유일한 방어는 상호 배제 락. 12장.
7. **JVM 들**: OpenJDK(참조 구현, GPL) · Oracle(같은 소스, 사유 라이선스 — 도커 이미지 재배포 금지, 바이너리 패치 금지) · Zulu(Azul, GPL) · IcedTea(Red Hat) · Zing(Azul, 사유, 큰 힙) · J9(IBM → Eclipse OMR) · Avian(학습용) · Android(다른 것). **HotSpot 계열끼리는 같은 버전이면 성능 차이가 사실상 없다.** Twitter·Alibaba 는 자체 빌드를 유지한다.
8. **모니터링 기술 네 가지**: **JMX**(RMI 전송) · **Java 에이전트**(`-javaagent`, `Premain-Class`, `java.lang.instrument`) · **JVMTI**(네이티브, `-agentlib`/`-agentpath`, JVM 을 죽일 수 있어 가능하면 Java 에이전트를) · **Serviceability Agent**(대상 VM 에 코드를 안 넣고 프로세스 메모리·심볼을 읽는다, 코어 파일도).
9. **VisualVM**: `jconsole` 의 대체(`jvisualvm`). Overview·Monitor·Threads·Sampler·Profiler 탭, 원격은 `jstatd`(포트 1099), 플러그인(VisualGC). **Java 9 부터 JDK 에서 빠져 따로 받아야 한다.**

## 2. 절별 상세 요약

- **Interpreting and Classloading** — §1-1. 부트스트랩 로더가 `Object`·`Class`·`ClassLoader` 를 먼저 올려야 하는 순환성 노트. "운영과 같은 클래스패스로 빌드하라".
- **Executing Bytecode** — 표 2-1 클래스 파일 해부, 그림 2-2 기억법, `HelloWorld` 디스어셈블 전문과 한 줄씩 해설(기본 생성자의 `aload_0`·`invokespecial`).
- **Introducing HotSpot / JIT** — Stroustrup 인용, PGO, 인트린직, "코드가 소스와 다르다" 팁.
- **JVM Memory Management** — C/C++/Objective-C 의 수동 관리와 스마트 포인터, GC 의 STW.
- **Threading and the JMM** — 람다로 스레드 만들기, 세 원칙, 스케줄러가 스레드를 쫓아낸다.
- **Meet the JVMs / A Note on Licenses** — 위 목록, OCA 이중 라이선스, Oracle 업데이트가 OpenJDK 에서 가지를 쳐 차이가 안 벌어지는 이유, 15장 예고.
- **Monitoring and Tooling / VisualVM** — 네 기술, 에이전트 설치 플래그, VisualVM 탭.

## 3. 2판(2024) 3장과 달라진 점

| 항목 | 1판 2장 | 2판 3장 | 근거 |
|---|---|---|---|
| 위치 | 2장 | 3장(성능 테스트 방법론 뒤) | ✅ 목차 |
| 클래스로더 | 부트스트랩 · **확장** · 애플리케이션, `rt.jar` | 부트스트랩 · **플랫폼** · 애플리케이션, 모듈 이미지 | 📄 통독([2판 3장 요약](../optimizing-java-2nd/03-JVM-개요.md)) |
| JVM 목록 | Oracle·Zulu·IcedTea·Zing·J9·Avian·Android | Temurin·Corretto·Zulu·Microsoft·GraalVM·OpenJ9 등 — IcedTea·Avian 사라짐 | ✅ 키워드 |
| 라이선스 | Oracle 사유, 도커 재배포 금지 | JDK 17 NFTC 이후 구도 | 📄 |
| 모니터링 도구 | VisualVM 중심, `jvisualvm` | **JFR·JMC** 중심, VisualVM 은 별도 배포 | ✅ 키워드 |
| Nashorn 언급 | 확장 로더 예시 | 없음(JDK 15 제거) | ✅ 키워드 |

## 4. JDK 17 / 25 기준 평가

| 책의 문장 | 지금 | 상태 |
|---|---|---|
| 확장 클래스로더, `rt.jar` | JDK 9 부터 **플랫폼 클래스로더**(`ClassLoader.getPlatformClassLoader()`), 런타임은 `lib/modules` 이미지. 원리(위임·정체성 = 로더+이름)는 그대로 | 📄 |
| Nashorn 은 확장 로더가 로드 | Nashorn 은 JDK 15 에서 제거(JEP 372) | 📄 |
| `0xCAFEBABE`, 버전 검사 | 그대로. 클래스 파일 메이저 버전 17→61, 21→65, 25→69 | 📄 |
| Java 9 모듈 파일 `0xCAFEDADA` | 그대로(jmod) | 📄 |
| 그린 스레드는 버려졌다 | **JDK 21 가상 스레드로 "M:N" 이 돌아왔다** — 단 OS 스레드(캐리어) 위의 사용자 모드 스레드이고 플랫폼 스레드는 여전히 1:1. `Thread.ofVirtual()` 이 만드는 스레드는 데몬 | ✅ CN-13B(21·25) |
| JVM 목록 | Zing → Azul Platform Prime, J9 → Eclipse OpenJ9(IBM Semeru), IcedTea·Avian 은 사실상 종료, **Temurin·Corretto·Zulu·Microsoft Build** 가 실무 표준 | 📄 |
| Oracle 사유 라이선스, 재배포 금지 | JDK 17 부터 NFTC(무료, 재배포 가능) — 단 LTS 다음 LTS 1년 뒤까지 | 📄 |
| JMX 는 RMI 위 | 그대로. 다만 컨테이너 환경에서는 포트 문제로 JFR 스트리밍·OpenTelemetry 가 우선(2판 11·12장) | 📄 |
| VisualVM 은 Java 9 부터 별도 | 그대로. `jconsole` 은 JDK 에 남아 있다 — 이 환경에서는 Debian 패키지 구성상 21 에만 들어 있었다 | ✅ 도구 목록(17/21/25) |
| SA 로 코어 파일 디버깅 | `jhsdb` 로 통합(JDK 9), 17·21·25 에 있음 | ✅ 도구 목록 |
| `-javaagent` 동적 부착 | JDK 21 부터 **실행 중 동적 에이전트 로드 시 경고**(JEP 451), 미래에 기본 금지 예고 | 📄 |

## 5. 이 장을 우리 랩에 비춰 보면

| 책의 명제 | 이 저장소의 근거 | 상태 |
|---|---|---|
| 스레드 = OS 스레드(1:1) vs 가상 스레드 | `verify-labs-cloudnative` **CN-13A·13B·13C** | 2판 랩 케이스 |
| JIT 는 실행 중 프로파일로 컴파일한다 | `verify-labs-perfbook` **PERF-04**, **PERF-10D**(단형 호출 지점 역최적화) | 기존·신규 |
| 도구 존재 여부(jfr·jcmd·jmap·jhsdb·jconsole) | `01-최신-JDK-기준-평가` §8 도구 표 | ✅ |
| 클래스로더 위임·플랫폼 로더 | 실행 케이스 없음 | 미검증 |

## 6. 면접에서 쓸 수 있는 문장

- "JVM 이 실제로 실행하는 코드는 소스와 전혀 다르게 생겼습니다. `javac` 는 거의 최적화를 안 하고, HotSpot 이 실행 중 프로파일을 보고 인라이닝하고 가상 호출을 없앱니다. 그래서 '작은 메서드를 피하라' 같은 옛 조언은 지금 역효과입니다."
- "클래스의 정체성은 이름만이 아니라 로더입니다. 같은 이름의 클래스를 두 로더가 올리면 `ClassCastException` 이 납니다. 웹 컨테이너·플러그인 시스템에서 나는 그 문제가 이겁니다."
- "1판이 '그린 스레드는 버려졌다'고 했는데, JDK 21 가상 스레드로 사용자 모드 스레드가 돌아왔습니다. 차이는 캐리어 스레드가 OS 스레드고 블로킹 시 마운트 해제된다는 점입니다."

## 7. 관련 문서

- [`../optimizing-java-2nd/03-JVM-개요.md`](../optimizing-java-2nd/03-JVM-개요.md) — 2판
- [`09-JVM-코드-실행.md`](09-JVM-코드-실행.md) — 바이트코드 심화
- [`12-동시성-성능-기법.md`](12-동시성-성능-기법.md) — JMM
- [`../JVM-용어-변천사.md`](../JVM-용어-변천사.md) — HotSpot·C1·C2·JVMCI 등 용어
