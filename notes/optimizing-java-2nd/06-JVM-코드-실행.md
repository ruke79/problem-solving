# 6장 — JVM 에서의 코드 실행 (Code Execution on the JVM)

> *Optimizing Cloud Native Java* 2판(2024) 6장 요약. **착수 순서 2순위**(JIT). 책의 기준은 JDK 21(일부 "Java 23" 언급).
> 표기 — ✅ 실행해 확인(JDK 17.0.19 / 21.0.10 / 25.0.4) · 📄 문서로만 아는 것.
>
> **1판 9장 「JVM 의 코드 실행」과 10장 「JIT 컴파일 이해」를 합치고 줄인 장**이다. 1판 10장의 깊은 내용(인라이닝 임계값·
> 이스케이프 분석·단형/다형 인라인 캐시·루프 언롤링·JITWatch 30회)은 대부분 빠졌고, 그 자리에 **AOT·Quarkus·GraalVM** 이 들어왔다.
>
> **JDK 25 기준 이 장의 변화 한눈에**
>
> | 책의 서술 | 지금 (JDK 25) | 근거 |
> |---|---|---|
> | "HotSpot 은 프로파일을 저장하지 않고 매번 새로 만든다" | **JEP 483/514/515(JDK 24·25) AOT 캐시**가 클래스 로딩·링킹과 **메서드 프로파일**을 다음 실행으로 넘긴다. `-XX:AOTCacheOutput=app.aot` 한 줄로 만들고 `-XX:AOTCache=app.aot` 로 쓴다 | ✅ 25 에서 생성·사용, 캐시에서 로드된 클래스 761개 (기본 CDS 670개, `-Xshare:off` 0개) |
> | AOT = GraalVM 네이티브 이미지, Leyden 은 15장의 미래 | Leyden 의 첫 결과물이 **JDK 에 실렸다**(위) | ✅ |
> | 코드 캐시 기본 240MB(계층형) | 17·21: 251,658,240 ✅ / 25: **251,662,336**(240MB + 4KB, 에르고노믹) ✅ | ✅ |
> | 분할 코드 캐시(JEP 197) | `SegmentedCodeCache=true`, 메모리 풀 `CodeHeap 'non-nmethods'/'profiled nmethods'/'non-profiled nmethods'` | ✅ CN-06B |
> | 계층형 컴파일 기본 | `TieredCompilation=true`, `CICompilerCount=3`(4코어 에르고노믹) | ✅ |
> | GraalVM CE / EE 두 배포판 | 2023 년부터 **Oracle GraalVM(GFTC 라이선스) / GraalVM Community** 로 이름과 조건이 바뀌었다고 알고 있다 | 📄 미확인 |
> | OpenJDK 안의 Graal 모듈 | 21 의 `jdk.internal.vm.compiler` 는 **빈 껍데기**(`module-info` 만) ✅. 25 에서는 이름이 `jdk.graal.compiler` 로 바뀌었고 내용은 아래 §4 | ✅ |

---

## 1. 이 장의 핵심 주장

1. 자바 앱의 전통적 생애주기 — **기동 시 클래스 로딩·JIT·GC 급증 → 정상 상태**(변화가 0 은 아니다: 역최적화·재최적화·두 단계 클래스 로딩). 이것을 **dynamic VM 모드**라 부른다.
2. JVM 인터프리터는 **스택 머신**이고 바이트코드는 1바이트 opcode(Java 23 기준 ~200개 사용), 빅엔디안, 타입이 있는 명령 패밀리. **`invokedynamic` 은 Java 1.0 이후 유일하게 추가된 opcode**이고 람다·문자열 연결·비자바 언어가 쓴다.
3. HotSpot 은 **템플릿 인터프리터**이고 스펙에 없는 **사설 바이트코드**(final 메서드 디스패치, `Object::<init>` 반환 표식)를 쓴다. `final` 호출이 `invokespecial` 이 아니라 `invokevirtual` 인 이유는 JLS 13.4.17(바이너리 호환성)과 리스코프 치환 원칙.
4. JIT 는 **PGO(프로파일 유도 최적화)**. 프로파일을 저장하지 않는 이유 — NFP(비농업 고용지표) 발표일처럼 **날마다 프로필이 다르다.** (단, GraalVM·Leyden 으로 답이 복잡해졌다.)
5. 컴파일 단위는 메서드(핫 루프는 **OSR**), vtable 항목 갱신 = **포인터 스위즐링**. C1(빠른 컴파일)·C2(깊은 최적화), **계층형 5레벨**(0 인터프리터 / 1 C1 최적화·프로파일 없음 / 2 C1 카운터 / 3 C1 전체 프로파일 / 4 C2)과 경로(0-3-4, 0-2-3-4, 0-3-1 자명한 메서드, 0-4).
6. **코드 캐시**는 고정 크기라 차면 JIT 가 멈춘다. 계층형이 컴파일 양을 200~400% 늘려 기본 240MB. 단편화 → **분할 코드 캐시**(비메서드/프로파일된/프로파일 안 된).
7. 단순 JIT 튜닝: `PrintCompilation` → 코드 캐시 키워 다시 → 컴파일 집합이 의미 있게 커지나? 핫 패스 메서드가 다 컴파일되나?
8. 클라우드에서는 기동 비용의 상각이 안 맞을 수 있다 → **AOT**(단일 최적화 기회, 보수적 CPU 가정, 리플렉션 문제), **Quarkus**(빌드 단계로 "왼쪽 이동", JIT 모드도 빠르다), **GraalVM 네이티브 이미지**(Mandrel).

## 2. 절별 상세 요약

### 2-1. 전통적 생애주기

`java HelloWorld` → 셸이 바이너리를 찾아 프로세스 → 플래그 해석 → **머신 탐침**(코어 수·메모리·CPU 명령 집합 → GC 스레드 수·commonPool 크기 결정, **컨테이너에서 중요**, 8장) →
Xmx 만큼 C 힙에서 예약, Metaspace 초기화 → `JNI_CreateJavaVM` 으로 VM 스레드 생성, GC·JIT 스레드 시작 → 부트스트랩 클래스의 `<clinit>` 에서 첫 객체 → 엔트리포인트 전에도 GC·JIT 가 돈다.
정상 상태에서도 드문 경로가 새 클래스를 로드하거나 역최적화가 일어난다. Spring 류는 **두 단계 클래스 로딩**(프레임워크 → 객체 그래프 분석 → 앱 코드).

### 2-2. 바이트코드

- 세 저장 영역: 평가 스택(메서드 로컬) · 지역 변수 · 힙(공유). `x == 3 + 1` 의 평가 과정을 그림 5장으로.
- 패밀리별 표 — 로드/저장(`load`/`store`/`ldc`/`const`/`pop`/`dup`/`getfield`/`putfield`/`getstatic`/`putstatic`; `ldc` 는 상수 풀, `const` 는 `aconst_null`·`iconst_m1` 같은 진짜 상수),
  산술(`add`/`sub`/`div`/`mul`/cast/`neg`/`rem`, 프리미티브만, 인자 없음), 흐름 제어(`if` 패밀리·`goto`·`return`·`tableswitch`/`lookupswitch`; `jsr`/`ret` 는 Java 6 이후 javac 가 안 낸다),
  **메서드 호출**(`invokevirtual`/`invokespecial`/`invokeinterface`/`invokestatic`/`invokedynamic`), 플랫폼(`new`/`newarray`/`anewarray`/`arraylength`/`monitorenter`/`monitorexit`).
- 호출 지점(call site)·수신 객체·수신 타입 용어. 인스턴스 호출은 보통 `invokevirtual`, 정적 타입이 인터페이스면 `invokeinterface`, 정확한 대상이 컴파일 시점에 알려지면(private·super) `invokespecial`.
- `invokedynamic`: Java 7 에서는 javac 가 낼 방법이 없었고(JRuby 등 실험용), Java 8 람다부터 핵심. `Runnable r = () -> ...` 이 `invokedynamic #2, 0 // InvokeDynamic #0:run:()Ljava/lang/Runnable;` 로. Kotlin·JRuby·Scala·프레임워크가 널리 쓴다.
- **세이프포인트**: 인터프리터에서는 "바이트코드 사이"가 가장 단순한 세이프포인트(각주: 스레드 로컬 핸드셰이크 덕에 매 바이트코드마다 확인하지는 않을 수 있다). JIT 코드에는 컴파일러가 같은 장벽을 삽입.
- 단순 인터프리터 예(ocelotvm 0.1.1): `while(true){ switch(op) {...} }`.
- **HotSpot 사설 바이트코드**: `bytecodes.cpp` 의 "JVM bytecodes". final 메서드는 컴파일 시점 정적 바인딩으로 디스패치, 파이널라이즈 등록 시점 표식.

### 2-3. JIT 와 PGO

- JIT 는 앱과 VM 자원을 나눠 쓰므로 **아껴서** 컴파일한다. javac 는 의도적으로 "멍청한 바이트코드"를 만든다.
- NFP 날 예: 다른 날의 최적화를 쓰면 **덜 경쟁력 있다** → HotSpot 은 프로파일을 버린다. PGO 는 일방통행이 아니다(성능 저하 시 재조정). 그러나 GraalVM·Leyden 때문에 "AOT 냐 PGO 냐"는 더 복잡해졌다(15장).
- **Klass 워드·vtable·포인터 스위즐링**: 컴파일러 스레드가 백그라운드에서 컴파일 → 완료되면 klass 의 vtable 항목을 새 코드로 → 새 호출은 컴파일된 형태, 실행 중인 인터프리터 호출은 그대로 끝낸다. 단위는 메서드, 핫 루프는 **OSR**. 포팅: x86/x86-64/ARM 주력, SPARC·Power·MIPS·S390 부분.
- **C1/C2**: 호출 횟수 임계값 → 큐잉 → 내부 표현 → 프로파일 반영 최적화. 둘 다 **SSA**(단일 정적 대입, "전부 final 변수로 다시 쓴 것"). 컴파일 단위 = **nmethod**.
- **계층형**: Java 6 부터, 기본. `advancedThresholdPolicy.hpp` 의 5레벨과 표 6-6 의 4경로. "인터프리터 → C1 → C2" 라는 통설은 부정확하다. 자명한 메서드는 0-3-1 에서 끝난다.

### 2-4. 코드 캐시

- 힙 구조(미할당 영역 + 해제 블록 연결 리스트, **스위퍼**가 재활용). 제거 조건: 역최적화 / 계층형에서 교체 / 클래스 언로드. `-XX:ReservedCodeCacheSize=<n>`.
- Java 8 Linux x86-64 기본: 계층형 **240MB**(251,658,240), 비계층형 48MB.
- 단편화 → 컴파일 중단 = 또 다른 캐시 고갈. **JEP 197 분할 코드 캐시(Java 9)**: 비메서드(`NonMethodCodeHeapSize`, 앱 수명) / 프로파일된(`ProfiledCodeHeapSize`, 짧은 수명) / 프로파일 안 된(`NonProfiledCodeHeapSize`, 긴 수명). 지역성 이득. ⚠️ 크기는 고정이고 조정은 충분히 시험한 뒤에.

### 2-5. 로깅과 단순 튜닝

- `-XX:+PrintCompilation` 출력: 시각(ms) · 순번 · 레벨 · 메서드(바이트 수), 플래그 `n`(네이티브) `s`(동기화) `!`(예외 핸들러) `%`(OSR), `made not entrant`. Java 21 예시에 `String::hashCode`·`ArraysSupport::vectorizedHashCode` 등 JDK 메서드가 가득 — JRE 도 자바라 함께 컴파일된다. 실행마다 조금씩 다르다(PGO 의 부작용, 정상).
- `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation` → 수백 MB XML → **JITWatch**. J9 의 Testarossa 도 로그를 내지만 형식 표준은 없다.
- 단순 튜닝 원칙: *"컴파일되고 싶은 메서드는 그럴 자원을 받아야 한다."* 5단계 체크리스트. 캐시를 키워도 컴파일 집합이 안 늘면 JIT 는 자원 부족이 아니다 → 핫 패스 메서드가 다 있는지 → 없으면 원인 추적.

### 2-6. 진화하는 실행 — AOT·Quarkus·GraalVM

- 워밍업은 수십 초. 장수 프로세스에는 무의미하지만 클라우드의 단명 프로세스에는 세 질문 — 상각이 맞나? 더 빨리 뜰 수 없나? 필요 없는 동적 능력에 메모리를 쓰나?
- **AOT**: 최적화 기회가 한 번, 대상 CPU 를 모르면 **보수적** 선택 → CPU 능력을 남긴다. HotSpot JIT 는 기동 시 CPU 를 탐침해 **인트린식**을 켠다 — JVM 만 올려도 빨라지는 메커니즘. 자바 AOT 의 난제는 **리플렉션**(어디에나 있다). AOT 는 수단이고 관심사는 결과다.
- **Quarkus**(Red Hat): "Kubernetes Native Java stack for HotSpot and GraalVM". **빌드 단계** 신설 — ArC(CDI Lite) DI, Jandex 인덱싱, Gizmo 바이트코드 생성으로 애너테이션 스캔·리플렉션을 빌드 시점으로. 컨테이너는 불변이라 런타임에 새 의존이 안 나타나므로 타당하고, 덜 동적인 코드는 **C2 에도 이롭다.**
  두 모드: dynamic VM(HotSpot) / native(GraalVM). quarkus.io 수치 📄: 전통 스택 4.3s / Quarkus+JIT 0.943s / 네이티브 0.016s. **많은 팀이 JIT 모드로 충분**하다고 느낀다. `./mvnw quarkus:dev` 라이브 리로드, 명령형·리액티브 둘 다.
- **GraalVM**: Oracle 연구소 출신, CE(오픈소스)/EE(유료) 📄(§4). Truffle 언어 프레임워크. **네이티브 이미지** = 자바로 쓴 Graal 컴파일러를 AOT 로 사용. 리플렉션 대상은 빌드 시 알아야 하고 정적 분석이 실패하면 수동 설정. Quarkus 를 통하는 편이 쉽고, Red Hat 은 **Mandrel** 배포판을 권한다.

## 3. 1판(2018) 9·10장과 달라진 점

| 항목 | 1판 | 2판 6장 | 근거 |
|---|---|---|---|
| 바이트코드·인터프리터·사설 바이트코드(1판 9장) | 있음 | 거의 그대로 | 📄 통독 |
| `invokedynamic` | 9장 12회 | 10회, 문자열 연결 언급 없이 람다 중심 | ✅ 키워드 |
| **JIT 상세(1판 10장)**: 인라이닝 임계값(`MaxInlineSize` 35·`FreqInlineSize` 325), 이스케이프 분석, 단형/양형/다형 인라인 캐시, 루프 언롤링, 안전점 예, 인트린식 표 | 10장 통째(JITWatch 30회, inlining 25회, escape analysis 14회) | **대부분 삭제** — JITWatch 1회, inlining 1회, escape analysis 0회 | ✅ 키워드 |
| 계층형 5레벨 표·코드 캐시·분할 코드 캐시 | 9장 | 유지 | 📄 |
| **AOT·Quarkus·GraalVM 네이티브 이미지** | 15장 미래 절에 Graal 13회·`jaotc` 3회 | 6장 본문, Quarkus 29회·GraalVM 19회, `jaotc` 0회 | ✅ 키워드 |
| "프로파일을 저장하지 않는다"(NFP 예) | 있음 | 유지 + "Leyden 으로 복잡해졌다" 단서 | 📄 |

**깊은 JIT 내용이 필요하면 1판 10장을 봐야 한다** — [`../optimizing-java/10-JIT-컴파일-이해.md`](../optimizing-java/10-JIT-컴파일-이해.md).

## 4. JDK 25 기준 평가

배너 표에 더해 실행으로 확인한 것:

- **AOT 캐시(Project Leyden)** — 책이 "15장의 미래"로 미룬 것이 JDK 24(JEP 483, 2단계 `-XX:AOTMode=record` → `create`), JDK 25(JEP 514 `-XX:AOTCacheOutput` 한 단계, JEP 515 메서드 프로파일 저장)로 실렸다.
  ✅ 25 에서 두 방식 모두 9.6MB 캐시를 만들었고 `-Xlog:class+load` 기준 **캐시에서 761개 클래스**를 읽었다(기본 CDS 670개, `-Xshare:off` 0개). 21 은 `AOTMode`·`AOTCache` 모두 `Unrecognized VM option`.
  → **CN-06A**. 책의 "프로파일은 매번 새로" 서술은 이제 "기본은 그렇지만 AOT 캐시로 워밍업 프로파일을 넘길 수 있다(JEP 515)"로 보충해야 한다. 메서드 프로파일이 실제로 워밍업을 얼마나 줄이는지는 **재지 않았다**.
- **코드 캐시 기본값**: 25 는 `ReservedCodeCacheSize=251662336` 으로 17·21 의 251658240 보다 4,096 바이트 크다({ergonomic}) ✅ — 원인은 확인하지 않았다 📄. 비계층형 값은 [`01-최신-JDK-기준-평가.md`](01-최신-JDK-기준-평가.md) 에 실행 결과로.
- **Graal 모듈**: 21 `jdk.internal.vm.compiler` → 25 `jdk.graal.compiler`(+`.management`) 로 **이름이 바뀌었다** ✅(`--list-modules` diff). 내용물이 비어 있는지는 [`01-최신-JDK-기준-평가.md`](01-최신-JDK-기준-평가.md) 의 실행 결과를 따른다.
  `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+UseJVMCICompiler -version` 은 17·21·25 모두 rc=0 — **기동만으로는 실제 컴파일러 유무를 알 수 없다** ✅.
- **사라진 것**: `-XX:+UseBiasedLocking` 은 21 부터 기동 실패 ✅ — 책의 이 장은 편향 락을 언급하지 않아 영향 없음.
- Java 23 기준 "~200 opcodes", "언어 수준 `invokedynamic` 접근 없음" — 25 도 같다 📄(JVMS 25 를 세지는 않았다).
- Quarkus/GraalVM 수치와 배포판 이름은 책 시점 📄.

## 5. 이 장을 우리 랩에 비춰 보면

| 책의 명제 | 이 저장소의 근거 | 상태 |
|---|---|---|
| 워밍업 — 첫 호출들은 인터프리터, 이후 컴파일 | `verify-labs-perfbook` **PERF-04** | 기존 |
| 역최적화(`made not entrant`)가 정상 상태에서도 일어난다 | **PERF-10D** — 단형 호출 지점에 새 타입을 넣어 `-Xbatch -XX:+PrintCompilation` 로 관측 | 1판 제안 케이스 |
| 계층형·분할 코드 캐시·240MB 기본값 | `verify-labs-cloudnative` **CN-06B** — `HotSpotDiagnosticMXBean` + `MemoryPoolMXBean` 이름 | 2판 랩 케이스 |
| AOT 캐시가 클래스 로딩을 미리 한다 | **CN-06A** — 자식 JVM 으로 생성·사용, `class+load` 출처 집계 | 2판 랩 케이스 |
| 문자열 연결이 `invokedynamic` 을 쓴다 | 1판 검토 §4(JDK 17 `makeConcatWithConstants` 확인), **PERF-15A** | 기존/1판 제안 |
| 죽은 코드 제거 | **CN-A01** | 2판 랩 케이스 |
| Quarkus 빌드 단계 / GraalVM 네이티브 이미지 | 도구 설치가 필요하다 — **실행하지 않았다** | 미검증 |

## 6. 면접에서 쓸 수 있는 문장

- "HotSpot 은 프로파일을 저장하지 않습니다. 날마다 트래픽 프로필이 다르기 때문인데, JDK 24·25 의 AOT 캐시는 클래스 로딩과 메서드 프로파일을 다음 기동으로 넘겨 이 전제를 일부 바꿨습니다."
- "계층형 컴파일은 '인터프리터 → C1 → C2' 가 아니라 5레벨입니다. 자명한 메서드는 C1 레벨 1 에서 끝나고 C2 로 가지 않습니다."
- "코드 캐시가 차면 JIT 가 조용히 멈추고 나머지는 인터프리터로 돕니다. `PrintCompilation` 으로 컴파일 집합을 보고 캐시를 키워 다시 비교하는 것이 가장 싼 JIT 튜닝입니다."

## 7. 관련 문서

- [`../optimizing-java/09-JVM-코드-실행.md`](../optimizing-java/09-JVM-코드-실행.md) · [`../optimizing-java/10-JIT-컴파일-이해.md`](../optimizing-java/10-JIT-컴파일-이해.md) — 1판의 깊은 JIT 내용
- [`../java-performance/04-JIT-컴파일러.md`](../java-performance/04-JIT-컴파일러.md) — 2014년 책
- [`15-현대-성능과-미래.md`](15-현대-성능과-미래.md) — Leyden·GraalVM 의 예측 대조
- [`../JVM-용어-변천사.md`](../JVM-용어-변천사.md) — C1/C2·JVMCI·Graal·AOT·CDS 항목
