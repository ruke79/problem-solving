# 9장 — JVM 의 코드 실행 (Code Execution on the JVM)

> *Optimizing Java* 1판(2018) 9장 요약. [`00-검토`](00-검토-2018년-책과-현재.md) §7 의 **착수 순서 2순위**(JIT — "가장 잘 버텼고 `PERF-04` 와 바로 이어진다").
> 책의 기준은 **Java 8 + Java 9/10 예고**. 표기 — ✅ 실행해 확인(JDK 17.0.19 / 21.0.10 / 25.0.4) · 📄 문서로만 아는 것.
>
> **2판(2024)에서는** 이 장과 10장을 합쳐 [6장 「JVM 에서의 코드 실행」](../optimizing-java-2nd/06-JVM-코드-실행.md) 이 됐다. 이 장의 내용은 거의 그대로 살아남았고,
> 「AOT 와 JIT 비교」 절만 2판에서 **AOT·Quarkus·GraalVM** 절로 크게 확장됐다.

---

## 1. 이 장의 핵심 주장

1. JVM 인터프리터는 **스택 머신**. 평가 스택(메서드 로컬)·지역 변수·힙(공유) 세 저장 영역. 바이트코드는 1바이트 opcode(Java 10 기준 ~200개), **빅엔디안**,
   타입이 있는 패밀리(`iadd`/`dadd`, `dstore`/`astore`), 단축형(`aload_0` = `this`). Java 1.0 이후 추가된 opcode 는 **`invokedynamic` 하나**, 폐기는 `jsr`/`ret`.
2. 호출은 네 가지 + 하나: `invokevirtual`(보통) / `invokeinterface`(정적 타입이 인터페이스) / `invokespecial`(private·super, 정확한 대상) / `invokestatic` / `invokedynamic`(Java 7 도입, 8 의 람다부터 핵심).
3. HotSpot 은 **템플릿 인터프리터**이고 **사설 바이트코드**를 쓴다 — `final` 메서드 호출이 `invokespecial` 이 아니라 `invokevirtual` 인 이유(JLS 13.4.17, 리스코프), `Object::<init>` 반환 표식(파이널라이즈 등록).
4. **AOT vs JIT**: AOT 는 최적화 기회가 한 번이고 CPU 를 보수적으로 가정한다. JIT 는 **PGO** — 프로파일을 저장하지 않는 이유는 NFP 날처럼 날마다 프로필이 다르기 때문.
   "자바는 AOT 가 안 된다"는 신화 — 상용 VM 은 오래전부터 했고 **Java 9 부터 HotSpot 도 코어 클래스에 한해 AOT 를 시작**(`jaotc`).
5. 컴파일 단위는 메서드, 핫 루프는 **OSR**. vtable 항목 갱신 = **포인터 스위즐링**. `-XX:+PrintCompilation`(시각·순번·레벨·플래그 `n s ! %`·`made not entrant`), `-XX:+LogCompilation`(진단, XML, JITWatch).
6. C1/C2, **nmethod**, SSA. **계층형 컴파일 5레벨**(0 인터프리터 / 1 C1 최적화 / 2 C1 카운터 / 3 C1 전체 프로파일 / 4 C2)과 경로(0-3-4, 0-2-3-4, 0-3-1 자명, 0-4).
7. **코드 캐시**: 고정 크기, 차면 JIT 정지. Java 8 Linux x86-64 기본 계층형 **240MB** / 비계층형 **48MB**. 단편화(Java 8 이하). 단순 JIT 튜닝 5단계.

## 2. 절별 상세 요약

### 2-1. 바이트코드 해석 개요
- `x == 3 + 1` 평가 그림 5장(9-1~9-5). 상수는 클래스 파일 상수 영역에서.
- 카테고리 표: 로드/저장(`load`·`store`·`ldc`·`const`·`pop`·`dup`·`getfield`·`putfield`·`getstatic`·`putstatic`) / 산술(`add`·`sub`·`div`·`mul`·cast·`neg`·`rem`) / 흐름 제어(`if` 패밀리·`goto`·`tableswitch`·`lookupswitch` "범위 밖") / 호출 / 플랫폼(`new`·`newarray`·`anewarray`·`arraylength`·`monitorenter`·`monitorexit`).
- 호출 지점(call site)·수신 객체·수신 타입. 람다 예제의 `invokedynamic #2, 0 // InvokeDynamic #0:run:()Ljava/lang/Runnable;`. 비자바 언어(JRuby·**Nashorn**)와 프레임워크가 쓴다 — "대체로 호기심거리".
- 굵은 바이트코드(상수 풀 조회·디스패치, VM 으로 콜백) vs 가는 바이트코드(산술, 어셈블리). **세이프포인트**: "바이트코드 사이"가 가장 단순한 세이프포인트, JIT 코드에는 컴파일러가 장벽을 넣는다.
- **Ocelot** 0.1.1 의 `execMethod()` — `while(true) { switch(op) … }`. 0.2 에 정적 호출.
- HotSpot 사설 바이트코드: `bytecodes.cpp` 의 "JVM bytecodes".

### 2-2. AOT 와 JIT
- AOT: 대상 CPU 를 알면 최고지만 **확장되지 않는다**(아키텍처마다 바이너리). gcc 의 LTO·PGO 는 초기 단계. HotSpot 은 새 CPU 기능을 릴리스마다 더하고 앱은 재컴파일 없이 이득 — "HotSpot 릴리스 사이에 성능이 눈에 띄게 오르는 것이 드물지 않다".
- JIT: 자원을 앱과 나누므로 아껴서. javac 는 "멍청한 바이트코드". NFP 날 예 → 프로파일 폐기.
- "Java 9 부터 HotSpot 이 AOT 를 옵션으로 제공(코어 JDK 클래스)" → ⚠️ **`jaotc` 는 JDK 17 에서 제거됐다**(JEP 410) ✅ `jaotc` 바이너리 없음(17·21·25). 2판 6장은 `jaotc` 를 0회 언급한다 ✅. 대신 JDK 24·25 의 **AOT 캐시**(JEP 483/514/515)가 다른 형태로 같은 질문에 답한다 ✅ (→ [2판 6장 §4](../optimizing-java-2nd/06-JVM-코드-실행.md)).

### 2-3. HotSpot JIT 기초
- 컴파일 단위 메서드 / OSR. klass 워드 → vtable → **포인터 스위즐링**(새 호출은 컴파일 코드, 실행 중 인터프리터 호출은 그대로 끝남). 포팅: x86/x86-64/ARM 주력.
- **`PrintCompilation`** Java 8 예시(`String::hashCode (55 bytes)`, `System::arraycopy (native) (static)` 에 `n`, OSR `%` 와 `@ 2`, `made not entrant`). 실행마다 조금씩 다르다(PGO 의 부작용). `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation` → 수백 MB XML → JITWatch. J9 Testarossa 는 형식이 다르다.
- **C1/C2**: 호출 횟수 임계값 → 큐 → 내부 표현 → 프로파일 반영. SSA. 계층형 표 9-6.
- **코드 캐치**: 힙 구조(미할당 + 해제 목록, 스위퍼). 제거 조건 셋. `-XX:ReservedCodeCacheSize=<n>`. Java 8 이하의 단편화 → (10장·15장에서 Java 9 분할 코드 캐시 예고).
- **단순 JIT 튜닝**: (1) `PrintCompilation` 켜고 실행 → (2) 컴파일 목록 수집 → (3) `ReservedCodeCacheSize` 증가 → (4) 재실행 → (5) 컴파일 집합 비교. 늘지 않으면 자원 부족이 아니다 → 핫 패스 메서드가 다 있는지 → 없으면 원인 추적. "JIT 가 절대 꺼지지 않게" 하는 전략.

## 3. 2판(2024)에서 어떻게 바뀌었나

| 항목 | 1판 9장 | 2판 6장 |
|---|---|---|
| 앱 생애주기(기동 급증 → 정상 상태, 두 단계 클래스 로딩, dynamic VM 모드) | 없음 | **신설**(장 첫머리) |
| opcode 수 기준 | Java 10 | Java 23 |
| `invokedynamic` 사용자 | JRuby·**Nashorn** | Kotlin·JRuby·Scala(Nashorn 은 JDK 15 에서 제거 📄) |
| `tableswitch`/`lookupswitch` | "범위 밖" | 한 줄 설명 추가 |
| AOT 절 | AOT vs JIT 비교 + `jaotc` 예고 | **AOT·Quarkus·GraalVM 네이티브 이미지**로 확장, `jaotc` 삭제, "Leyden 으로 복잡해졌다" |
| `PrintCompilation` 예시 | Java 8 | Java 21(`vectorizedHashCode` 등) |
| 코드 캐시 단편화 | "Java 8 이하" + 15장 예고 | JEP 197 분할 코드 캐시 본문 편입 |
| 인터프리터 예제 | Ocelot 프로젝트 | ocelotvm 0.1.1 (같은 것) |

## 4. JDK 17 / 25 기준 — 어긋나는 것 (실행 확인)

| 책의 서술 | 지금 | 근거 |
|---|---|---|
| Java 9 부터 코어 클래스 AOT(`jaotc`) | **JDK 17 에서 제거**(JEP 410). 17·21·25 에 `jaotc` 없음 | ✅ |
| 코드 캐시 기본 240MB / 48MB | 17·21: 251,658,240 / 50,331,648 그대로. **25: 251,662,336**(+4KB, 에르고노믹) / 50,331,648 | ✅ |
| 단편화(Java 8 이하) | 9+ 는 분할 코드 캐시 — `SegmentedCodeCache=true` | ✅ 17·21·25 |
| `PrintCompilation` 형식 | 그대로 | ✅ **PERF-10D** 가 파싱 |
| `-XX:+LogCompilation` 진단 플래그 | 그대로 | 📄 |
| 계층형 5레벨 | 그대로(`TieredCompilation=true`, `TieredStopAtLevel=4`) | ✅ 25 |
| Nashorn 이 `invokedynamic` 사용자 | Nashorn 은 JDK 15 에서 제거(JEP 372) | 📄 |
| "HotSpot 은 프로파일을 저장하지 않는다" | 기본은 그대로지만 **JDK 25 AOT 캐시가 메서드 프로파일을 저장**(JEP 515) | ✅ 25 에서 캐시 생성·사용 확인, 프로파일 효과는 미측정 |

## 5. 이 장을 우리 랩에 비춰 보면

| 책의 명제 | 이 저장소의 근거 | 상태 |
|---|---|---|
| 첫 호출들은 인터프리터, 임계값 뒤 컴파일 | `verify-labs-perfbook` **PERF-04** | 기존 |
| `made not entrant` — 역최적화 | **PERF-10D** (§7 제안) | 신규 |
| 계층형·분할 코드 캐시·기본 크기 | `verify-labs-cloudnative` **CN-06B** | 신규(JDK 25) |
| 문자열 연결의 `invokedynamic` | **PERF-15A** (§7 제안, 15장 예측 검증) | 신규 |
| `jaotc` 부재 / AOT 캐시 | **PERF-15A**(부재) / **CN-06A**(AOT 캐시) | 신규 |

## 6. 면접에서 쓸 수 있는 문장

- "`final` 메서드 호출도 `invokevirtual` 로 컴파일됩니다. `final` 을 떼도 바이너리 호환이 깨지면 안 되기 때문이고, HotSpot 은 대신 사설 바이트코드로 정적 디스패치합니다."
- "코드 캐시는 고정 크기라 차면 JIT 가 조용히 멈춥니다. `PrintCompilation` 으로 컴파일 집합을 보고 캐시를 키워 비교하는 것이 첫 번째 JIT 튜닝입니다."
- "JDK 9 의 `jaotc` 는 17 에서 사라졌지만, 같은 질문에 대한 답은 JDK 24·25 의 AOT 캐시로 돌아왔습니다."

## 7. 관련 문서

- [`10-JIT-컴파일-이해.md`](10-JIT-컴파일-이해.md) — 이 장의 심화(인라이닝·이스케이프 분석·단형 디스패치)
- [`../optimizing-java-2nd/06-JVM-코드-실행.md`](../optimizing-java-2nd/06-JVM-코드-실행.md)
- [`../java-performance/04-JIT-컴파일러.md`](../java-performance/04-JIT-컴파일러.md) — 2014년 책
- [`../JVM-용어-변천사.md`](../JVM-용어-변천사.md) — C1/C2·jaotc·AOT 캐시 항목
