# spring-tutorial — 실행되는 스프링 튜토리얼

`manuscripts/spring-면접/` 의 145문항 중 **인프라 없이 실행해서 확인할 수 있는 것들**만
골라 레슨으로 만든 모듈입니다. `java-tutorial` 과 같은 형식 — **레슨 하나가 JUnit 테스트 하나**입니다.

```bash
./gradlew :spring-tutorial:test
```

현재 **7개 레슨 / 52개 테스트, 전부 통과**합니다 (JDK 17.0.19 / Spring Boot 3.3.5).
**DB·브로커·Redis·서블릿 컨테이너를 전혀 쓰지 않으므로** compose 없이 바로 돕니다.

---

## 1. 무엇을 담고 무엇을 뺐나

선정 기준은 `java-tutorial` 과 같다 — **커버리지가 아니라 판정 가능성.**

- **여기 있는 것** — 컨테이너·프록시·트랜잭션 *경계*·MVC 처리 경로·설정 바인딩처럼
  **스프링 프레임워크 자신이 결정하는 동작.** 인프라 없이 결정적으로 판정된다.
- **여기 없는 것** — 격리 수준·더티 체킹·N+1·커넥션 풀처럼 **DB 가 있어야 성립하는 명제.**
  실물 PostgreSQL 로 검증하는 `verify-labs` 의 `SPRING-*`·`JPA-*`·`DB-*` 케이스 소관이다.
  성능 명제도 없다(`verify-labs-perfbook` 소관).

## 2. 트랜잭션 레슨에 DB 가 없는 이유 (레슨 4)

레슨 4 는 DataSource 대신 **BEGIN/COMMIT/ROLLBACK 을 기록만 하는 트랜잭션 매니저**를 꽂았다.

이게 흉내가 아닌 이유: 전파(propagation)·롤백 규칙·rollback-only 전염의 **판단 로직은
DB 가 아니라 스프링의 `AbstractPlatformTransactionManager` 안에 있다.** 기록용 매니저는
그 판단의 결과(begin 이 몇 번, 어느 트랜잭션이 롤백됐나)를 받아 적을 뿐이고,
판단 자체는 실물 스프링 코드가 한다.

**한계도 명확하다** — 이것이 보여주는 것은 *스프링이 내리는 경계 결정*까지다.
격리 수준·실제 데이터 롤백 같은 DB 의미론은 여기서 검증되지 않는다.

만들다 하나 배웠다: rollback-only 표식은 참여자들이 **공유하는 상태**(JDBC 의
ConnectionHolder 역할)에 있어야 하고, 트랜잭션 객체가 `SmartTransactionObject` 를
구현해야 바깥 커밋이 그 표식을 본다. 이것 없이 만들었더니 4-7 의
`UnexpectedRollbackException` 이 재현되지 않았다.

## 3. 레슨 목록

숫자는 `manuscripts/spring-면접/` 의 문항 번호. ★ 는 변별력이 크거나 실무 사고로 이어지는 항목.

### 레슨 1. DI 컨테이너 (8개) — `Lesson01_DiContainer`

| | 레슨 | |
|---|---|---|
| 1-1 | 생성자 주입이라야 필드를 final 로 잠글 수 있다 | Q3 |
| 1-2 | **순환 참조는 주입 방식에 따라 드러나는 시점이 다르다** | Q4 ★ |
| 1-3 | 같은 타입 빈이 둘이면 기동 실패 — @Primary / @Qualifier | Q5 |
| 1-4 | List 주입 + @Order | Q5 |
| 1-5 | **@Bean 메서드끼리 호출해도 싱글턴 유지 — @Configuration 프록시** | Q10 ★ |
| 1-6 | 빈 이름이 겹치면 Boot 는 기동을 멈춘다 (순수 컨텍스트는 조용히 덮는다) | Q28 |
| 1-7 | ObjectProvider 는 '없을 수도 있음'을 다룬다 | Q5 |
| 1-8 | **ApplicationContextRunner 는 순수 컨텍스트가 아니다** | ★만들다 발견 |

### 레슨 2. 스코프와 생명주기 (7개) — `Lesson02_ScopeAndLifecycle`

| | 레슨 | |
|---|---|---|
| 2-1 | 기본 스코프는 싱글턴 — 몇 번 꺼내도 하나 | Q6 |
| 2-2 | prototype 은 꺼낼 때마다 새로 | Q6 |
| 2-3 | **싱글턴이 prototype 을 주입받으면 갱신되지 않는다** | Q7 ★ |
| 2-4 | 매번 새로 받으려면 ObjectProvider 로 물어봐야 한다 | Q7 |
| 2-5 | 생성자 → @PostConstruct → afterPropertiesSet → @PreDestroy | Q8 |
| 2-6 | **prototype 은 소멸 콜백이 불리지 않는다** | Q6·Q8 ★ |
| 2-7 | @Lazy 는 첫 사용까지 생성을 미룬다 | Q13 |

### 레슨 3. 프록시 (7개) — `Lesson03_Proxy`

| | 레슨 | |
|---|---|---|
| 3-1 | 어드바이스가 붙은 빈은 프록시로 바뀌어 있다 (new 한 객체는 아니다) | Q33·Q47 |
| 3-2 | **자기 호출은 프록시를 지나가지 않는다** | Q35 ★가장 중요 |
| 3-3 | 프록시를 통해 다시 들어가면 잡힌다 | Q35·Q47 |
| 3-4 | **JDK 프록시는 구현 타입으로 못 받는다** | Q34 ★ |
| 3-5 | **CGLIB 프록시에서 final 메서드는 조용히 잘못된 값을 준다** | Q34·Q47 ★ |
| 3-6 | 어드바이스 순서는 @Order 가 정한다 | Q52 |
| 3-7 | @Around 는 인자·반환값을 바꿀 수 있다 | Q53 |

### 레슨 4. 트랜잭션 경계 (8개) — `Lesson04_TransactionBoundary`

| | 레슨 | |
|---|---|---|
| 4-1 | 트랜잭션은 프록시 경계에서 열리고 닫힌다 | Q37 |
| 4-2 | **기본값: 언체크 예외만 롤백, 체크 예외는 커밋된다** | Q42 ★ |
| 4-3 | rollbackFor 가 그 기본값을 바꾼다 | Q42 |
| 4-4 | 메서드 안에서 catch 하면 롤백은 없다 | Q43 |
| 4-5 | REQUIRED 는 참여, REQUIRES_NEW 는 SUSPEND 후 새 BEGIN | Q38·Q39 |
| 4-6 | **자기 호출로는 REQUIRES_NEW 도 헛수고다** | Q35·Q47 ★ |
| 4-7 | **참여한 안쪽의 실패는 바깥의 커밋까지 뒤집는다** (UnexpectedRollbackException) | Q43 ★ |
| 4-8 | '커밋 후에 실행'은 동기화 콜백으로 — 롤백되면 안 불린다 | Q45·Q49 |

### 레슨 5. Web MVC (8개) — `Lesson05_WebMvc`

MockMvc standalone 모드 — 톰캣 없이 DispatcherServlet 의 처리 경로(바인딩·검증·예외 처리·인터셉터)를 실물로 통과시킨다.

| | 레슨 | |
|---|---|---|
| 5-1 | @RestController 는 본문, @Controller 는 뷰 이름 | Q57 |
| 5-2 | **@RequestParam 은 기본이 필수 — 빠지면 400** | Q58 ★ |
| 5-3 | @Valid 실패는 400 + 필드별 이유 | Q60·Q61 |
| 5-4 | 예외 처리는 @RestControllerAdvice 한 곳으로 | Q62·Q63 |
| 5-5 | 생성 응답은 201 + Location | Q64 |
| 5-6 | 지원하지 않는 메서드는 404 가 아니라 405 | Q64·Q65 |
| 5-7 | 인터셉터는 preHandle → 핸들러 → postHandle → afterCompletion | Q66 |
| 5-8 | 타입이 안 맞는 @PathVariable 도 400 | Q58 |

### 레슨 6. 설정 (7개) — `Lesson06_Configuration`

| | 레슨 | |
|---|---|---|
| 6-1 | @ConfigurationProperties 는 접두사 아래를 통째로 (5s→Duration 변환 포함) | Q19 |
| 6-2 | **완화된 바인딩 — kebab·camel·환경변수 표기가 같은 값** | Q18·Q19 ★ |
| 6-3 | 먼저 등록된 프로퍼티 소스가 이긴다 | Q18 |
| 6-4 | **@Value 오타는 기동 실패, 바인딩 누락은 조용한 기본값** | Q19 ★ |
| 6-5 | @Validated 를 붙이면 잘못된 설정이 기동을 막는다 | Q19·Q144 |
| 6-6 | 프로파일이 빈 구성을 바꾼다 | Q20 |
| 6-7 | 리스트·맵도 구조 그대로 바인딩된다 | Q19 |

### 레슨 7. 캐시·이벤트·비동기 (7개) — `Lesson07_CacheEventAsync`

| | 레슨 | |
|---|---|---|
| 7-1 | @Cacheable — 같은 인자는 한 번만 실행된다 | Q104 |
| 7-2 | **캐시도 자기 호출에는 무력하다** (3-2 의 세 번째 재현) | Q104 ★ |
| 7-3 | @CacheEvict 가 갱신 시점의 정합성을 만든다 | Q104·Q105 |
| 7-4 | **ApplicationEvent 는 기본이 동기 — 리스너 예외가 발행자를 깬다** | Q23 ★ |
| 7-5 | **@TransactionalEventListener 는 트랜잭션 밖에서는 버려진다** | Q49 ★ |
| 7-6 | @Async 는 다른 스레드 — ThreadLocal 이 끊긴다 | Q48·Q79·Q80 |
| 7-7 | **void @Async 의 예외는 호출자에게 절대 닿지 않는다** | Q79 ★ |

## 4. 만들다 밟은 함정들 — 실패가 그대로 레슨이 됐다

이 모듈을 만드는 동안 **여섯 번 틀렸고**, 그중 넷은 코드 주석이나 레슨으로 남겼다.

1. **ApplicationContextRunner 를 순수 컨텍스트로 착각했다.** 필드 주입 순환과 빈 이름 중복이
   "순수 컨텍스트에서는 통과"라고 썼는데 runner 에서 실패했다. runner 는 이름과 달리
   **Boot 의 기본값(순환 차단·덮어쓰기 금지)을 적용**한다. → 레슨 1-8 로 승격.
2. **공유 설정이 카운터를 오염시켰다.** 레슨 2 에서 설정 하나에 빈을 다 넣었더니, holder 의
   주입이 기동 시점에 prototype 을 하나 만들어 "2개" 단정이 3개로 깨졌다. → 설정을 레슨별로 분리.
3. **rollback-only 는 공유 상태여야 한다.** 레슨 4 의 기록용 트랜잭션 매니저가
   `SmartTransactionObject` 없이는 4-7(UnexpectedRollbackException)을 재현하지 못했다.
   JDBC 에서 ConnectionHolder 가 왜 있는지를 거꾸로 배웠다. → 클래스 주석에 기록.
4. **레슨 3-5 의 함정을 레슨 7 에서 내가 밟았다.** CGLIB 프록시 빈의 필드를 직접 읽어
   NPE — 프록시 인스턴스의 필드는 생성자를 거치지 않아 비어 있다. 자기가 만든 교재의
   함정에 걸린 것이다. → 접근을 전부 메서드 경유로 바꾸고 주석에 기록.
5. **@TransactionalEventListener 의 조용한 강등.** `@EnableTransactionManagement` 가 없으면
   이 리스너는 **일반 리스너로 강등되어 즉시 실행**된다(메타 어노테이션이 @EventListener 라서).
   "버려진다"를 검증하려던 7-5 가 반대로 "즉시 실행"을 관측했다. → 설정 주석에 기록.
6. **Spring 6.1 의 -parameters 요구.** Boot 플러그인이 없는 모듈이라 컴파일 플래그가 빠졌고,
   레슨 5 의 @RequestParam 바인딩이 전멸했다. 평소 Boot 가 무엇을 대신해 주고 있었는지가
   실패로 드러났다. → `build.gradle` 주석에 기록. (한글 응답 인코딩도 같은 종류 — Boot 의
   UTF-8 기본값이 없으면 StringHttpMessageConverter 는 ISO-8859-1 이다.)

## 5. 정직한 고지

- **145문항 중 52개만 레슨이 됐다.** DB·브로커가 필요한 문항(Part 4 대부분), 운영·설계 논의
  문항(Part 5 다수)은 실행 판정 대상이 아니거나 이 모듈 밖이다.
- **레슨 4 는 트랜잭션 '경계'만 검증한다.** DB 의미론은 `verify-labs` 로 갈 것.
- **레슨 5 는 standalone MockMvc 다** — DispatcherServlet 경로는 실물이지만 Boot 자동 설정
  (컨버터 인코딩, 기본 예외 응답 포맷 등)은 빠져 있고, 그 차이가 실제로 두 번 드러났다(§4-6).
- **Spring Boot 3.3.5 / Spring Framework 6.1 기준이다.** 순환 참조 기본 차단(2.6+),
  -parameters 요구(6.1+)처럼 버전에 묶인 동작이 여럿이다. 다른 버전에서 결과가 다르면
  그것 자체가 관찰 대상이다.
- 이 모듈의 `observe` 도구는 이번에 쓸 일이 없었다 — 모든 레슨이 결정적으로 단정 가능했다.
  (동시성 타이밍 같은 비결정 요소를 다루는 레슨이 없기 때문이지, 원칙을 깬 것이 아니다.)

## 6. 관련 문서

- `manuscripts/spring-면접/` — 원본 145문항 (일본어·한국어) + `필수-키노트.md`
- `java-tutorial/` — 같은 형식의 자바 레슨 54건
- `verify-labs/` — DB 가 필요한 스프링 명제의 실행 검증 (`SPRING-*`·`JPA-*`·`SEC-*`)
- `docs/00-인계-노트.md` §8 — 이 저장소의 정직성 원칙
