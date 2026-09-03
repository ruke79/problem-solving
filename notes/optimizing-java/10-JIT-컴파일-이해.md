# 10장 — JIT 컴파일 이해 (Understanding JIT Compilation)

> *Optimizing Java* 1판(2018) 10장 요약. [`00-검토`](00-검토-2018년-책과-현재.md) §7 의 **착수 순서 2순위**. 이 장은 **2판에서 대부분 사라졌다**
> (2판 6장에 JITWatch 1회·인라이닝 1회·이스케이프 분석 0회 ✅ 키워드) — **깊은 JIT 내용이 필요하면 지금도 이 장을 봐야 한다.**
> 표기 — ✅ 실행해 확인(JDK 17.0.19 / 21.0.10 / 25.0.4) · 📄 문서로만 아는 것.
>
> **JDK 25 기준 이 장의 변화 한눈에**
>
> | 책의 서술 (JDK 8 Linux x86_64) | 지금 (JDK 17 · 21 · 25 모두 같은 값) | 근거 |
> |---|---|---|
> | `MaxInlineSize` 35 / `FreqInlineSize` 325 | **그대로** 35 / 325 | ✅ |
> | `InlineSmallCode` 1,000(비계층) / 2,000(계층) | **2,500** — 17 에서 이미 바뀌어 있었다 | ✅ |
> | `MaxInlineLevel` 9 | **15** — 17 에서 이미 바뀌어 있었다 | ✅ |
> | `EliminateAllocationArraySizeLimit` 64 | **그대로 64** | ✅ |
> | `-XX:+PrintGCApplicationStoppedTime`, `-XX:+PrintSafepointStatistics` | **둘 다 없다** — `Unrecognized VM option`. `-Xlog:safepoint` 로 | ✅ |
> | 세이프포인트가 필요한 일에 "편향 락 취소" | 편향 락 자체가 21 에서 제거 | ✅ |
> | `@HotSpotIntrinsicCandidate`(Java 9) | JDK 16 부터 `@IntrinsicCandidate` 로 이름 변경 | 📄 |
> | Nashorn 을 JITWatch 샌드박스에서 | Nashorn 은 JDK 15 에서 제거 | 📄 |

---

## 1. 이 장의 핵심 주장

1. **JITWatch**(저자 Chris Newland)로 HotSpot 이 바이트코드에 실제로 무엇을 했는지 본다 — `-XX:+UnlockDiagnosticVMOptions -XX:+TraceClassLoading -XX:+LogCompilation`.
   샌드박스·TriView(소스/바이트코드/어셈블리)·코드 캐시 레이아웃. 어셈블리를 보려면 디버그 JVM 또는 **hsdis** + `-XX:+PrintAssembly`.
2. 프로파일은 **MDO(method data object)** 에 — 호출 메서드·분기·호출 지점의 타입. 카운터는 **감쇠**한다(큐 앞에 왔을 때 여전히 뜨거운 것만 컴파일).
3. **C1 은 추측 최적화를 하지 않고 C2 는 한다.** 추측에는 **가드**가 붙고, 가드가 깨지면 즉시 **역최적화**해 인터프리터로 강등.
4. 최적화 목록 — **인라이닝**(관문 최적화), **루프 언롤링**(int 카운터만, 세이프포인트 폴 제거, 경계 검사 제거를 위한 pre/main/post 분할), **이스케이프 분석**(NoEscape/ArgEscape/GlobalEscape,
   스칼라 치환, 락 제거·병합·중첩 제거, 배열 64개 한계, 부분 이스케이프 분석 없음), **단형 디스패치**(klass 워드 비교 가드, 양형, 메가모픽 "벗겨내기"), **인트린식**(`.ad` 파일, `log10D`), **OSR**.
5. 세이프포인트가 필요한 일: GC STW 외에 역최적화·힙 덤프·편향 락 취소·클래스 재정의. JIT 는 **루프 백브랜치와 메서드 반환**에 폴을 넣는다 → TTSP(time to safepoint).
6. 코어 라이브러리 메서드 크기 — `String.toUpperCase()` 439 바이트(로케일 처리) > `FreqInlineSize` 325 → ASCII 전용 69 바이트 버전이 2.4배. **HugeMethodLimit 8,000 바이트** 이상은 컴파일되지 않는다(절반 속도). JarScan 으로 찾는다.

## 2. 절별 상세 요약

### 2-1. JITWatch
- AdoptOpenJDK 산하(런던 자바 커뮤니티). 로그 파싱 → JavaFX GUI. 측정 없이 스위치를 만지면 「큰 그림 놓치기」.
- 샌드박스: 컴파일 → JIT 로깅으로 실행 → 로드. Java·Scala·Kotlin·Groovy·JavaScript(Nashorn). 설정으로 디스어셈블리 출력·계층형 끄기·압축 oop 끄기·OSR 끄기·인라이닝 한계 변경. ⚠️ 샌드박스 코드는 실제 앱과 다르게 동작한다 — 실제 앱에서는 인라이닝이 더 넓은 범위를 열어 준다.
- **TriView**: 불필요한 할당 제거의 증거를 보여 준다. 코드 캐시 레이아웃 뷰(Java 8 은 단일 영역, Java 9 분할 → 15장).
- 디버그 JVM(OpenJDK 소스 빌드, 저자 사이트에 Linux 바이너리), hsdis 빌드(JITWatch 위키), `-XX:+PrintAssembly`(비싸다).

### 2-2. JIT 컴파일 소개
- MDO. 내부 표현은 C1/C2 가 다르다. 최적화 7종. C1 은 비추측, C2 는 추측 + 가드 + 역최적화.

### 2-3. 인라이닝
- 호출 오버헤드(인자 준비·메서드 조회·프레임 생성·제어 이전·반환). `add(a, b)` → `a + b`. **관문 최적화** — 이스케이프 분석·죽은 코드 제거·언롤링·락 제거의 시야를 넓힌다.
- 한계 요인: 바이트코드 크기, 호출 체인 깊이, 이미 컴파일된 버전의 코드 캐시 점유. JITWatch 그림 10-6.
- **표 10-1** (JDK 8 Linux x86_64): `-XX:MaxInlineSize=35`(바이트코드), `-XX:FreqInlineSize=325`(핫 메서드), `-XX:InlineSmallCode=1000/2000`(네이티브 바이트, 최종 계층 컴파일이 이보다 크면 인라인 안 함), `-XX:MaxInlineLevel=9`.
  "조금 큰" 메서드가 인라인 안 되면 조정해 볼 수 있으나 **관측 데이터로만** — 아니면 「스위치 만지작거리기」.

### 2-4. 루프 언롤링
- 인라이닝 뒤에 루프 크기를 알 수 있다. 백브랜치는 파이프라인을 버린다 → 짧은 루프일수록 상대 비용 ↑. 기준: 카운터 타입(int/long/객체), 스트라이드, 종료점 수.
- `[base, index, offset]` 주소 지정 `add rbx, QWORD PTR [base + index*size + offset]`. **경계 검사 제거**: pre(검사) / main(검사 없음) / post(검사).
- JMH `LoopUnrollingCounter`: int 카운터 2,423 ops/s vs long 카운터 1,470 — **int 가 64% 더**. long 루프는 언롤되지 않고 **세이프포인트 폴**이 들어간다. 가변 스트라이드도 언롤 안 됨.
- 요약: int/short/char 카운터의 카운티드 루프 최적화, 언롤 + 세이프포인트 폴 제거. "아키텍처·HotSpot 버전마다 다르니 직접 확인하라."

### 2-5. 이스케이프 분석
- 인라이닝 **뒤에** 수행(인자로만 넘긴 객체가 이스케이프로 표시되지 않게). `escape.hpp`: `NoEscape=1`(스칼라 치환 가능) / `ArgEscape=2` / `GlobalEscape=3`.
- **힙 할당 제거**: 스칼라 치환 → 레지스터 할당기(부족하면 스택 스필). 예 `noEscape()`(`MyObj foo = new MyObj(i); sum += foo.bar()`) vs `argEscape()`(`extBar(foo)`, 인라인되면 NoEscape 로).
- **락과 이스케이프 분석**(intrinsic 락만, j.u.c 락 제외): 락 제거(elision)·**락 병합**(coarsening, `-XX:-EliminateLocks`)·**중첩 락 제거**(`-XX:-EliminateNestedLocks`, Java 8 에서는 `static final` 락과 `this` 락에 동작). JITWatch 그림 10-7/10-8.
- **한계**: 기본 **배열 64개 초과는 제외**(`-XX:EliminateAllocationArraySizeLimit=<n>`). JMH `EscapeTestArraySize`: 63·64 는 4,982만 ops/s, **65 는 2,112만** — 한계를 65 로 올리면 회복. **부분(흐름 민감) 이스케이프 분석 없음**(JRockit 에는 있었다) — 한 분기에서라도 이스케이프하면 힙 할당; 할당을 분기 안으로 옮기면 그 경로는 이득.

### 2-6. 단형 디스패치
- 경험적 사실: 호출 지점마다 수신 타입이 **하나**인 경우가 대부분(사람이 짜는 OO 코드의 반영). vtable 간접 참조 제거 → klass 워드 동등 비교 가드 + 직접 분기. `java.util.Date` → `java.sql.Date` 가 나타나면 가상 디스패치로 복귀.
- **양형(bimorphic)** 도 있다. 그 이상은 **메가모픽**. JMH `PeelMegamorphicCallsite`: 양형 7,584만 / 메가모픽 5,465만 / `instanceof` 로 하나 벗겨낸 것 6,202만 ops/s(각각 38%, 13% 차이; 코드가 달라 공정 비교는 아님). Shipilëv "The Black Magic of (Java) Method Dispatch".
- 결론: **인터페이스로 설계하되 구현이 하나면 단형 디스패치가 유지된다** — 테스트 대역도 되고 성능도 된다.

### 2-7. 인트린식
- JVM 이 미리 아는 고도 튜닝 네이티브 구현. 플랫폼 의존. 기동 시 CPU 탐침. 인터프리터·C1·C2 모두에 있을 수 있다.
- 표 10-3: `System.arraycopy()`(벡터), `currentTimeMillis()`, `Math.min()`(분기 없이), 다른 `Math`, AES. `.ad` 파일(`x86_64.ad`)의 `log10D_reg`: `fldlg2` + `fyl2x` 두 FPU 명령. Java 9 `@HotSpotIntrinsicCandidate`.
- 새 인트린식은 **실제 코드에서 자주 보이는 연산**에만 값어치가 있다(첫 n 개 합 O(1) 공식은 아니다).

### 2-8. OSR 과 세이프포인트
- `main()` 안의 핫 루프: 백브랜치 카운트 → 임계값 → 루프를 컴파일하고 실행을 옮긴다(지역 변수·락 상태 전달). 바이트코드 `goto 13`. C1·C2 모두 OSR 가능.
- 세이프포인트 필요 작업: 역최적화·힙 덤프·**편향 락 취소**·클래스 재정의. JIT 폴 위치: **루프 백브랜치, 메서드 반환**. 언롤된 루프는 세이프포인트까지 오래 걸릴 수 있다. TTSP 균형. 디버거가 이 동작에 의존.
- ⚠️ 책이 안내한 `-XX:+PrintGCApplicationStoppedTime` + `-XX:+PrintSafepointStatistics` 는 **둘 다 JDK 17 에 없다**(§4).

### 2-9. 코어 라이브러리 메서드
- **JarScan**(JITWatch 배포판): `--mode=maxMethodSize --limit=325 --packages=java.*` → Java 8u152 `rt.jar` 에 325 바이트 초과 **490개**. `String.toUpperCase()`/`toLowerCase()` 각 **439 바이트**(로케일별 길이 변화 처리).
  ASCII 전용 69 바이트 구현은 JMH 로 **2.4배**(2,014만 vs 835만 ops/s). 작은 메서드의 또 다른 이득 — 인라이닝 순열이 늘어 더 많은 핫 패스가 최적화된다.
- **HugeMethodLimit 8,000 바이트**(운영 JVM 에서는 못 바꾼다, 디버그 JVM 의 `-XX:HugeMethodLimit=<n>`): `--limit=8000` 결과는 Swing L&F 초기화·로케일/통화 리소스·XPath 파서 — 핫 코드에 있을 리 없는 것들.
  JMH `HugeMethod`: 8,000 미만 89,551 vs 초과 **44,429 ops/s**(컴파일 안 됨, 절반). 자동 생성 쿼리 코드가 실제로 걸린다.
- ⚠️ JIT 설정을 바꾸면 코드 캐시·컴파일 큐 길이·GC 압력에 부작용 — 전후 벤치마크 필수.

## 3. 2판(2024)에서 어떻게 바뀌었나

**2판 6장은 이 장의 내용을 거의 다 뺐다** ✅(JITWatch 30→1회, inlining 25→1회, escape analysis 14→0회, intrinsic 14→3회). 남은 것: JITWatch 이름 한 번, "인트린식" 개념(AOT 절에서 CPU 탐침 설명용),
세이프포인트 폴 개념. 인라이닝 표·언롤링·이스케이프 분석·락 최적화·단형 디스패치·JarScan·HugeMethodLimit 은 **2판에 없다.** 2판이 그 자리에 넣은 것은 클라우드 생애주기·AOT·Quarkus·GraalVM 이다.
따라서 이 저장소에서는 **1판 10장 = JIT 심화의 표준 참고**로 둔다.

## 4. JDK 17 / 25 기준 — 어긋나는 것 (실행 확인)

이 머신의 `-XX:+PrintFlagsFinal` 결과 — **17.0.19 · 21.0.10 · 25.0.4 세 버전이 아래 값 전부 동일**하다:

| 플래그 | 책(JDK 8) | 17 · 21 · 25 | 비고 |
|---|---|---|---|
| `MaxInlineSize` | 35 | **35** | 변함없음 ✅ |
| `FreqInlineSize` | 325 | **325** | 변함없음 ✅ |
| `InlineSmallCode` | 1,000 / 2,000 | **2,500** | 커졌다 ✅ |
| `MaxInlineLevel` | 9 | **15** | 커졌다 ✅ (JDK 14 에서 15 로 📄) |
| `EliminateAllocationArraySizeLimit` | 64 | **64** | 변함없음 ✅ — 책의 "65개부터 힙 할당" 은 지금도 유효한 전제다 |
| `EliminateLocks` / `EliminateNestedLocks` / `DoEscapeAnalysis` / `EliminateAllocations` | 기본 켜짐 | 기본 켜짐 | ✅ |
| `LoopUnrollLimit` | (미언급) | 60 | ✅ |
| `Tier3InvocationThreshold` / `Tier4InvocationThreshold` | (미언급) | 200 / 5,000 | ✅ |
| `CompileThreshold` | (미언급) | 10,000(비계층형용) | ✅ |
| `DontCompileHugeMethods` | true, 8,000 | true(한계값은 develop 플래그라 운영 빌드에 안 보인다) | ✅ / 📄 |
| `PrintGCApplicationStoppedTime`, `PrintSafepointStatistics` | 있음 | **`Unrecognized VM option`** | ✅ 17·21·25 |
| 편향 락 취소(세이프포인트 사유) | 있음 | 편향 락은 15 폐기, 21 에서 플래그도 제거 | ✅ |
| `@HotSpotIntrinsicCandidate` | Java 9 | JDK 16 부터 `jdk.internal.vm.annotation.IntrinsicCandidate` | 📄 |
| 인트린식 예 AES 등 | | `UseAESIntrinsics` 등 CPU 기능 플래그가 이 머신에서 켜져 있는지는 위 평가 문서에 | ✅ |
| JITWatch·JarScan·hsdis | AdoptOpenJDK | 프로젝트는 계속 유지된다고 알고 있으나 **이 저장소에서 실행하지 않았다** | 📄 |

**책의 JMH 수치(64% · 2.4배 · 절반)는 JDK 8 의 것이다.** 이 저장소의 `verify-labs-jmh` 는 이 장의 벤치마크를 그대로 옮기지 않았고, `docs/02` §9-4 의 두 항목(`final`·람다)만 다뤘다 — 이 장의 언롤링·이스케이프 분석·메가모픽 벤치마크는 **후속 후보**로 남긴다.

## 5. 이 장을 우리 랩에 비춰 보면

| 책의 명제 | 이 저장소의 근거 | 상태 |
|---|---|---|
| 가드가 깨지면 역최적화(단형 → 새 타입) | `verify-labs-perfbook` **PERF-10D** — 단형 호출 지점에 두 번째 구현을 넣어 `made not entrant` 관측 | **§7 제안 → 신규** |
| 죽은 코드 제거 | `verify-labs-cloudnative` **CN-A01** | 신규 |
| 인라이닝 한계 플래그 값 | 위 §4 표 + `01-최신-JDK-기준-평가.md` | 실행 확인 |
| 언롤링 int vs long, 배열 65개, 메가모픽 벗겨내기, HugeMethodLimit | JMH 가 필요하다 — **이번에는 만들지 않았다**(후속 후보) | 미검증 |
| 락 병합·제거 | 관측 수단(JITWatch/디버그 JVM)이 필요 — 미검증 | 미검증 |

## 6. 면접에서 쓸 수 있는 문장

- "C2 는 '이 호출 지점의 타입은 늘 하나'처럼 추측하고 가드를 답니다. 가드가 깨지면 역최적화해서 인터프리터로 돌아갔다가 다시 컴파일합니다. 그래서 정상 상태에서도 컴파일 로그가 완전히 조용하지 않습니다."
- "인터페이스로 설계해도 구현이 하나면 단형 디스패치가 유지되니 성능을 잃지 않습니다. 구현이 셋 이상 섞이는 핫 경로만 조심하면 됩니다."
- "이스케이프 분석은 인라이닝 뒤에 돕니다. 객체를 다른 메서드에 넘겨도 그 메서드가 인라인되면 스칼라 치환 대상이 됩니다."

## 7. 관련 문서

- [`09-JVM-코드-실행.md`](09-JVM-코드-실행.md) — 기초
- [`../optimizing-java-2nd/06-JVM-코드-실행.md`](../optimizing-java-2nd/06-JVM-코드-실행.md) — 2판이 남긴 것과 뺀 것
- [`../optimizing-java-2nd/01-최신-JDK-기준-평가.md`](../optimizing-java-2nd/01-최신-JDK-기준-평가.md) — 플래그 값 실행 결과 전체
- [`../../verify-labs-jmh/README.md`](../../verify-labs-jmh/README.md)
