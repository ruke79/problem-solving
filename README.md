# interview-verify-lab

시니어 개발자 면접 질문의 **답변을 말이 아니라 실행 결과로 검증**하는 컴팩트한 Spring Boot 랩.

"@Transactional 은 self-invocation 에서 안 걸립니다" 같은 답변을 외우는 대신,
그 명제를 코드로 재현해 `CONFIRMED` / `REFUTED` 판정을 남긴다.

- Gradle **8.14.3** / Java **17** / Spring Boot **3.3.5**
- 모듈 4개: `verify-core`(이식 가능한 하네스) + `verify-labs`(**88건**) + `verify-labs-kafka`(실물 브로커 **7건**)
  + `verify-labs-perfbook`(*Java Performance* 책 명제 **15건**)
- **검증 케이스 110건 · 13개 분류** — db 25 / perfbook 15 / resilience 11 /
  spring·kafka·jvm·jpa·ai 각 7 / security 5 / msa·concurrency·api 각 5 / observability 4
- 인프라는 전부 실물이다(`compose.yaml`) — **PostgreSQL 16 + pgvector**, **스트리밍 레플리카**,
  **Kafka 3.9**, **Redis 7**. 흉내가 아니라 실제 옵티마이저·MVCC·복제 지연·파티션 재할당을 관측한다.
- **실행 검증 완료** — 110건 전부 실행해 **REFUTED 0 / INCONCLUSIVE 1**
  (Java 17.0.19 / Linux / 4코어, perfbook 초판 12건은 3연속 실행으로 확인).
  **남은 1건은 `PERF-10C`(원격 네트워크)이고, 로컬·CI 에서 INCONCLUSIVE 인 것이 설계다** —
  실제 광역 링크가 있어야 단정할 수 있어 EC2 배포본을 대상으로만 CONFIRMED 가 된다(`docs/11` §5).
  케이스마다 독립된 테스트라 어느 것이 통과했는지 목록에서 바로 보인다.
- 답변 원고 **Q1~Q200 전 범위**를 대조해 사실·표현 오류 **8건**을 확정했다 —
  적용 가능한 형태로 모은 것이 `docs/10-원고-수정-지시서.md`.
  원래 9건이었으나 Part 5 원문을 받아 대조하면서 **한 건을 스스로 철회**했다(`docs/05` §23).
- **원고 자체가 저장소 안에 있다** — `manuscripts/` 에 세 벌. `원본/`(받은 그대로) ·
  `수정본/`(지적 8건·보강 9건을 실제로 반영, **사실관계 기준**) ·
  `회화체/`(수정본을 말투만 바꾼 판, **Q1~Q200 전부 완료**). 세 벌 비교표는 `manuscripts/README.md`.
- 만드는 과정에서 나온 문제와 해결(23건)은 `docs/05-개발-중-문제와-해결.md`

### 문서

| 파일 | 내용 |
|---|---|
| `docs/00-인계-노트.md` | 이 랩이 만들어진 경위와 인계 사항 (원본 그대로) |
| `docs/01-질문-검증-매핑.md` | Q31~Q115 를 A/B/C 로 분류한 표 (해소된 항목은 취소선) |
| `docs/02-정직한-고지.md` | 이 랩이 증명하지 **못하는** 것 |
| `docs/03-새-케이스-추가-가이드.md` | 케이스 추가 방법 |
| `docs/04-답변-원고-검토-지적사항.md` | 답변 원고에서 발견한 사실·표현 오류 |
| `docs/05-개발-중-문제와-해결.md` | 빌드·이관·측정 하네스에서 나온 문제 23건과 해결 |
| `docs/06-원고-수정본-Part5.md` | Q61~Q75 원고 대조 결과와 확정된 수정문 (Q64 Base62 오기 포함) |
| `docs/07-원고-수정본-Part6.md` | Q76~Q90 원고 대조 결과와 확정된 수정문 (Q77·Q78·Q87) |
| `docs/08-PostgreSQL-로-늘어난-검증-범위.md` | PostgreSQL·Kafka 로 새로 검증 가능해진 질문과 아직 안 되는 것 |
| `docs/09-테스트-누락-점검-Q1-Q200.md` | 전 범위 원고 대조 — 만들 수 있는데 없는 케이스 17건 |
| `docs/10-원고-수정-지시서.md` | 확정된 원고 수정 8건을 적용 가능한 형태로 정리 (철회 1건 포함) |
| `docs/11-CI-CD-와-EC2-프리티어.md` | GitHub Actions CI · EC2 프리티어 배포 검증 절차와 §9-1 과의 관계 |
| `manuscripts/README.md` | 원고 세 벌(원본·수정본·회화체) 비교표와 PDF 추출 고지 |

---

## 1. 빠른 시작

필요한 것은 **JDK 17 이상**, **PostgreSQL 16**, 네트워크다. Gradle 은 설치하지 않아도 된다 —
`gradle-wrapper.jar` 가 저장소에 들어 있어 `./gradlew` 가 8.14.3 배포판을 직접 받아 쓴다.
JDK 17 이 없는 머신이면 `settings.gradle` 의 foojay 리졸버가 툴체인을 자동으로 내려받는다.

```bash
# 0. 인프라가 쓸 호스트 포트를 빈 것으로 골라 둔다 (.env 생성, 한 번만)
./scripts/random-ports.sh

# 1. 인프라 기동 (PostgreSQL 16 + 레플리카 + Kafka + Redis)
docker compose up -d

# 2. 전체 검증 실행 + 리포트 생성
./gradlew :verify-labs:test

# 3. 결과 확인
cat verify-labs/build/reports/verification.md
cat verify-labs-kafka/build/reports/verification-kafka.md
```

### 케이스 하나씩 확인하기

케이스마다 테스트가 하나씩 생긴다. Gradle·IDE 의 테스트 목록에
`[DB-14] db — 복합 인덱스 (A, B) 를 만들었는데 …` 처럼 뜨므로
**어느 케이스가 통과했고 어느 것이 깨졌는지 목록에서 바로 보이고**, 실패한 것만 다시 돌릴 수 있다.

하나만 돌리려면 `-Dverify.only` 를 준다. 이때는 **판정 근거(관측값·검증 항목·메모)가 콘솔에 그대로** 나온다.

```bash
./gradlew :verify-labs:test -Dverify.only=DB-14          # 케이스 하나
./gradlew :verify-labs:test -Dverify.only=SEC            # id 접두사 → SEC-01~05
./gradlew :verify-labs:test -Dverify.only=observability  # 분류 전체
./gradlew :verify-labs:test -Dverify.only=DB-14,SEC-03   # 여러 개
./gradlew :verify-labs-kafka:test -Dverify.only=KAFKA-05 # Kafka 모듈
./gradlew :verify-labs-perfbook:test -Dverify.only=PERF-12  # 책 검증 모듈, 12장만
```

출력은 이런 모양이다.

```
DB-14  [db]  CONFIRMED  (793 ms)
질문: 복합 인덱스 (A, B) 를 만들었는데 B 만 조건에 넣으면 어떻게 됩니까?
── 관측값
   · 선행 컬럼 조회의 계획 비용 = 8.41
   · 후행 컬럼만 조회의 계획 비용 = 3706.93
   · 비용 배수(후행 ÷ 선행) = 441배
── 검증 항목
   [O] 선행 컬럼 조건은 인덱스를 탄다
   [O] 후행 컬럼만으로는 탐색 범위를 좁히지 못해 비용이 10배 이상으로 뛴다
   ...
```

골라 돌린 결과는 전체 리포트를 덮지 않고 `verification-selected.md` 에 따로 남는다.
잘못된 id 를 주면 쓸 수 있는 id 목록을 콘솔에 보여 준다.

Kafka 없이 DB 케이스만 돌리려면 `./gradlew :verify-labs:test` 를 쓰면 된다.
`verify-labs-kafka` 는 브로커가 없으면 7건을 INCONCLUSIVE 로 남기고 **케이스마다 건너뜀으로 표시**한다(실패가 아니다).

### 포트는 고정하지 않는다

5432(PostgreSQL) · 9092(Kafka) · 6379(Redis) 는 개발 장비에 이미 떠 있는 경우가 흔하다.
하나만 충돌해도 `docker compose up` 이 실패해 **인프라 전체가 안 뜬다.** 그래서 전부 비워 두었다.

- `scripts/random-ports.sh` 가 빈 포트를 골라 `.env` 에 적고, `docker compose` 가 이를 자동으로 읽는다.
- `./gradlew test` 는 실행 직전에 **지금 실제로 열려 있는 포트**를 도커에게 물어
  `DB_PORT` / `REPLICA_PORT` / `KAFKA_PORT` / `REDIS_PORT` 로 넘긴다. 위 세 줄은 그대로 쓰면 된다.

`.env` 없이 `docker compose up -d` 만 해도 된다. PostgreSQL · 레플리카 · Redis 는 호스트 포트가
`0` 이라 도커가 알아서 빈 포트를 배정한다. **Kafka 만 예외로 9092 를 쓴다** — 클라이언트가 처음 접속한 뒤
브로커가 알려 주는 advertised listener 주소로 *다시* 접속하므로, 그 주소를 브로커 기동 시점에
알고 있어야 하기 때문이다. 9092 가 이미 쓰이고 있다면 위 스크립트를 돌리거나 `KAFKA_PORT` 를 직접 주면 된다.

```bash
docker compose port postgres 5432   # 실제 배정 포트 확인 (예: 0.0.0.0:52939)
docker compose port kafka 19092     # Kafka 는 호스트용 리스너가 19092 다
KAFKA_PORT=19099 docker compose up -d kafka   # 특정 포트로 고정하고 싶을 때
```

앱을 직접 띄우거나(`bootRun`) IDE 에서 돌릴 때는 위에서 확인한 포트를 환경변수로 지정한다.

`postgres:16` 을 못 받는 환경(Docker Hub 차단, 익명 pull 레이트 리밋)이면 레지스트리 미러를 걸면 된다.

```bash
echo '{ "registry-mirrors": ["https://mirror.gcr.io"] }' > /etc/docker/daemon.json
systemctl restart docker    # 데몬이 아예 안 떠 있으면: dockerd &
```

Docker 를 쓰지 않고 이미 깔린 PostgreSQL 16 을 쓸 수도 있다. 계정과 DB 만 만들어 두면 된다.

```bash
psql -U postgres -c "CREATE ROLE verifylab LOGIN PASSWORD 'verifylab';" \
                 -c "CREATE DATABASE verifylab OWNER verifylab;"
```

`DB-10`(CDC)은 논리 복제 슬롯을 쓰므로 서버가 `wal_level=logical` 이어야 한다
(`compose.yaml` 은 그렇게 띄운다). 기본값 `replica` 인 서버에서는 그 케이스만 `INCONCLUSIVE` 로 남고
나머지는 정상 동작한다.

접속 정보는 환경변수로 덮어쓴다 — 기본값은 `jdbc:postgresql://localhost:5432/verifylab` / `verifylab` / `verifylab` 이다.

```bash
DB_URL=jdbc:postgresql://db.example.com:5432/verifylab \
DB_USERNAME=someone DB_PASSWORD=secret \
  ./gradlew :verify-labs:test
```

스키마는 `ddl-auto: create-drop` 이라 실행할 때마다 새로 만들고 끝나면 지운다.
원시 SQL 로 만드는 테이블(`scan_demo`, `deadlock_demo` 등)도 각 케이스가 직접 지운다 —
**검증 전용 DB 를 쓰는 것을 전제로 한다. 운영 DB 를 가리키게 하면 안 된다.**

기동해서 HTTP 로 돌리는 방법:

```bash
./gradlew :verify-labs:bootRun

curl localhost:8080/verify/cases                     # 등록된 질문 목록
curl -X POST localhost:8080/verify/run               # 전체 실행 (JSON)
curl -X POST localhost:8080/verify/run?category=jpa  # 분류별 실행
curl -X POST localhost:8080/verify/run/SPRING-01     # 케이스 1개
curl localhost:8080/verify/report.md                 # 마크다운 리포트
```

기동 즉시 전부 돌리고 리포트를 남기려면:

```bash
./gradlew :verify-labs:bootRun --args='--verify.run-on-startup=true'
```

---

## 2. 검증 케이스 (110개)

각 케이스는 **질문 → 답변의 명제 → 실행으로 확인한 것** 순으로 되어 있다.
아래 '확인하는 것'은 리포트의 검증 항목에서 앞의 둘을 옮긴 것이다.


**spring** — 프록시와 트랜잭션 경계 — 7건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| SPRING-01 | 같은 클래스 안에서 @Transactional 메서드를 호출하면 트랜잭션이 걸립니까? | 자기 호출에서는 트랜잭션이 없다 / 외부 호출에서는 트랜잭션이 열린다 |
| SPRING-02 | JDK 동적 프록시와 CGLIB 프록시의 차이는 무엇이고 Spring Boot 는 무엇을 씁니까? | 인터페이스만 지정하면 JDK 동적 프록시가 생성된다 / proxyTargetClass=true 면 CGLIB 프록시가 생성된다 |
| SPRING-03 | REQUIRED 와 REQUIRES_NEW 의 차이를 롤백 관점에서 설명해 주세요. | 바깥 트랜잭션 데이터는 롤백된다 / REQUIRES_NEW 내부 데이터는 살아남는다 |
| SPRING-04 | @Transactional 은 어떤 예외에서 롤백합니까? | checked 예외는 롤백되지 않고 커밋된다 / rollbackFor 지정 시 checked 예외도 롤백된다 |
| SPRING-05 | 싱글턴 빈에 프로토타입 빈을 주입하면 매번 새 인스턴스가 생깁니까? | 주입 방식은 항상 같은 인스턴스다 / ObjectProvider 는 호출마다 새 인스턴스를 만든다 |
| SPRING-06 | @Async 메서드는 호출한 쪽의 트랜잭션과 ThreadLocal 컨텍스트를 이어받습니까? | 비동기 작업은 다른 스레드에서 실행된다 / 트랜잭션이 전파되지 않는다 |
| SPRING-07 | Strategy 패턴을 쓸 때 전략 선택 로직은 어디에 둡니까? | 등록된 모든 전략이 자동 주입된다 / 빈 이름을 키로 런타임 해결된다 |

**jpa** — 영속성 컨텍스트 — 7건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| JPA-01 | N+1 문제가 무엇이고 실제로 쿼리가 몇 번 나가는지 설명해 주세요. | 지연 로딩은 1 + N 번의 SQL 을 발생시킨다 / fetch join 은 1번으로 줄인다 |
| JPA-02 | JPA 에서 save() 를 호출하지 않아도 UPDATE 가 나가는 이유는 무엇입니까? | save() 없이도 값이 반영된다 / Hibernate 통계상 UPDATE 가 1건 이상 발생했다 |
| JPA-03 | LazyInitializationException 은 언제 발생하고 OSIV 와 어떤 관계입니까? | 준영속 상태에서 지연 로딩은 실패한다 / fetch join 으로 로딩해 두면 트랜잭션 밖에서도 안전하다 |
| JPA-04 | 낙관적 락(@Version)은 언제 어떤 예외로 충돌을 알려줍니까? | 다른 트랜잭션 갱신으로 version 이 증가한다 / stale 버전으로 저장하면 낙관적 락 예외가 난다 |
| JPA-05 | 페이징이 필요해서 fetch join 을 못 쓸 때 N+1 을 어떻게 줄입니까? | 배치 페치는 N+1 보다 SQL 수가 훨씬 적다 / SQL 수가 이론값과 일치한다 |
| JPA-06 | 1대다 컬렉션에 fetch join 을 쓰면서 페이징하면 어떤 일이 일어납니까? | 일반 조회는 DB 에서 limit 으로 자른다 / 컬렉션 fetch join 은 SQL 에 limit 이 붙지 않는다(메모리 페이징) |
| JPA-07 | 비관적 락과 낙관적 락은 실제 동작이 어떻게 다릅니까? | 락 없는 조회는 블로킹되지 않는다 / FOR UPDATE 는 락 해제까지 대기한다 |

**concurrency** — 자바 동시성 기본기 — 5건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| CON-01 | HashMap 을 여러 스레드에서 동시에 수정하면 어떤 일이 일어납니까? | ConcurrentHashMap.merge 는 원자적이라 정확하다 / ConcurrentHashMap 은 예외 없이 완주한다 |
| CON-02 | 카운터를 synchronized, AtomicLong, LongAdder 로 구현할 때의 차이는 무엇입니까? | synchronized 는 정확하다 / AtomicLong 은 정확하다 |
| CON-03 | volatile 은 무엇을 보장하고 무엇을 보장하지 않습니까? | volatile 필드 변경은 다른 스레드에 즉시 보인다 / 일반 필드는 변경이 보이지 않아 루프가 타임아웃까지 돈다 |
| CON-04 | 스레드 풀 환경에서 ThreadLocal 을 쓸 때 주의할 점은 무엇입니까? | 동일 스레드가 재사용된다 / 이전 작업의 값이 그대로 보인다(누수) |
| CON-05 | 데드락은 어떻게 발생하고 운영 중에 어떻게 탐지·예방합니까? | ThreadMXBean 이 데드락 스레드 2개를 탐지한다 / lockInterruptibly 를 쓰면 인터럽트로 회복할 수 있다 |

**jvm** — 언어·런타임 — 7건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| JVM-01 | Integer 를 == 로 비교하면 왜 값이 같은데 false 가 나올 수 있습니까? | 캐시 범위 안에서는 == 가 true / 캐시 범위 밖에서는 == 가 false |
| JVM-02 | String 리터럴과 new String() 의 차이, 그리고 intern() 은 무엇을 합니까? | 컴파일 타임 상수 결합은 리터럴로 접힌다 / 런타임 결합은 새 인스턴스다 |
| JVM-03 | equals 만 재정의하고 hashCode 를 재정의하지 않으면 어떤 문제가 생깁니까? | hashCode 미재정의 시 논리적으로 같은 객체가 중복 저장된다 / hashCode 미재정의 시 contains 가 실패한다 |
| JVM-04 | Strong / Soft / Weak 참조의 차이는 무엇이고 캐시 구현에 어떻게 쓰입니까? | 강한 참조가 있으면 Weak 대상도 회수되지 않는다 / GC 가 돌면 Weak 대상은 회수된다 |
| JVM-05 | record 를 쓰면 자동으로 불변 객체가 됩니까? | 방어적 복사 없는 record 는 외부에서 내용이 바뀐다 / List.copyOf 로 복사하면 영향받지 않는다 |
| JVM-06 | CPU 사용률이 100%로 붙어 있을 때 어떤 원인을 의심합니까? | 취약 패턴은 입력 6자 증가에 시간이 배 이상 늘어난다 / 원자적 그룹으로 백트래킹을 막으면 같은 입력이 즉시 끝난다 |
| JVM-07 | 컨테이너 환경에서 JVM 메모리 설정 시 주의할 점은 무엇입니까? | 힙 밖에서 쓰는 메모리가 0 이 아니다 — 컨테이너 한계는 힙보다 커야 한다 / UseContainerSupport 는 기본으로 켜져 있다(Java 10+) |

**db** — PostgreSQL 16 실물 — 25건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| DB-01 | READ COMMITTED 와 REPEATABLE READ 의 차이를 실제 현상으로 설명해 주세요. | READ COMMITTED 에서는 재읽기 값이 달라진다(non-repeatable read) / REPEATABLE READ 에서는 스냅샷이 유지된다 |
| DB-02 | 커넥션 풀이 고갈되면 애플리케이션은 어떻게 동작합니까? | 풀이 고갈되면 타임아웃 예외로 실패한다 / connectionTimeout 근처에서 실패한다 (무한 대기가 아니다) |
| DB-03 | 인덱스가 있으면 항상 빨라집니까? 실행계획으로 어떻게 확인합니까? | 인덱스 생성 전후로 실행계획이 달라진다 / 높은 선택도 조건에서 인덱스가 선택된다 |
| DB-04 | 프리페어드 스테이트먼트는 왜 안전합니까? 이스케이프 처리와 무엇이 다릅니까? | 문자열 결합은 조건이 무력화되어 전건이 노출된다 / 바인딩하면 페이로드는 '문자열 값'일 뿐이라 0건이다 |
| DB-05 | UUID 를 RDB 의 PK 로 쓰면 어떤 단점이 있고, 대안은 무엇입니까? | UUIDv7 은 생성 순서대로 정렬된다 / UUID v4 는 생성 순서와 정렬 순서가 무관하다 |
| DB-06 | 커넥션 풀이 고갈됐습니다. 누수인지, 장시간 점유인지, 단순 용량 부족인지 어떻게 구분합니까? | 누수: 부하가 끝나도 active 가 풀 크기 그대로 남는다 / 누수: 반납이 없으므로 usage 표본이 0이다 |
| DB-07 | 데드락은 왜 발생하고 어떻게 회피합니까? 0으로 만들 수 있습니까? | 엇갈린 락 순서에서는 한쪽이 실패한다 / 엇갈린 순서의 실패는 락 타임아웃이 아니라 데드락(40P01)이다 |
| DB-08 | 대용량 테이블에 파티셔닝을 어떻게 적용합니까? 효과는 어디서 나옵니까? | 파티션 키가 조건에 있으면 한 파티션만 읽는다 / 파티션 키가 빠지면 전 파티션을 읽는다 |
| DB-09 | '당직은 최소 1명' 같은 불변식을 동시성 아래에서 어떻게 지킵니까? | REPEATABLE READ 에서는 둘 다 커밋되어 불변식이 깨진다(당직 0명) / SERIALIZABLE 에서는 한쪽이 취소되어 당직 1명이 남는다 |
| DB-10 | CDC 로 DB 변경을 흘려보낼 때 주의할 점은 무엇입니까? | INSERT/UPDATE/DELETE 가 모두 스트림에 나타난다 / 기본 identity 의 DELETE 에는 PK 만 남고 다른 컬럼은 없다 |
| DB-11 | 무중단 스키마 변경이라고 했는데, 실제로 무엇이 서비스를 멈추게 합니까? | 상수 기본값의 컬럼 추가는 행 수와 무관하게 즉시 끝난다 / 타입 변경은 재작성이라 컬럼 추가보다 오래 걸린다 |
| DB-12 | 재시도로 같은 요청이 여러 번 들어와도 결제가 한 번만 되게 하려면 어떻게 합니까? | 유니크 제약이 있으면 부수효과는 정확히 1회다 / 나머지 재시도는 전부 중복으로 거절된다 |
| DB-13 | 멀티테넌트 SaaS 에서 테넌트 간 데이터 유출을 어떻게 막습니까? | RLS 가 없으면 조건 누락 시 전 테넌트가 노출된다 / RLS 를 켜면 조건을 빼먹어도 자기 테넌트 행만 보인다 |
| DB-14 | 복합 인덱스 (A, B) 를 만들었는데 B 만 조건에 넣으면 어떻게 됩니까? | 선행 컬럼 조건은 인덱스를 탄다 / 두 컬럼 모두 주면 당연히 인덱스를 탄다 |
| DB-15 | RDBMS 의 텍스트 검색은 어디서 한계가 옵니까? Elasticsearch 는 언제 넣습니까? | 전방 일치는 B+Tree 인덱스를 탄다 / 중간 일치는 인덱스를 못 타고 전건 스캔이 된다 |
| DB-16 | 대규모 트래픽에서 분산 락을 구현할 때, 락만으로 충분합니까? | 락만 쓰면 뒤늦게 깨어난 A 가 B 의 결과를 덮어쓴다 / 펜싱 토큰이 있으면 늦은 토큰의 쓰기가 거절되어 B 의 결과가 살아남는다 |
| DB-17 | HikariCP 의 maxLifetime 은 DB 설정과 어떻게 연동해야 합니까? | 서버가 먼저 끊은 커넥션을 검증 없이 쓰면 요청이 통신 오류로 죽는다 / 풀은 대여 시 검증으로 그 오류를 애플리케이션에 노출하지 않는다 |
| DB-18 | 커넥션 누수가 의심될 때 Spring Boot 에서 어떻게 탐지·조사합니까? | 임계치를 넘긴 커넥션에 대해 경고가 남는다 / 경고에는 '어디서 빌렸는지' 스택 트레이스가 함께 실린다 |
| DB-19 | 프리페어드 스테이트먼트를 쓰면 SQL 인젝션은 끝난 겁니까? | 정렬 컬럼을 바인딩하면 예외는 안 나지만 '상수로 정렬'이라 아무 효과가 없다 / 문자열 결합한 정렬식은 그대로 실행되어 정보가 새어 나간다 |
| DB-20 | 대규모 EC 구매 데이터를 분석하려면 데이터 모델을 어떻게 설계합니까? | Type 1 은 과거 매출이 현재 가격으로 다시 계산된다 / Type 2 는 구매 시점 가격으로 집계된다 |
| DB-21 | 배치 처리 중 에러가 났을 때의 리커버리 전략을 설명해 주세요. | 체크포인트가 없으면 재실행이 처음부터 전건을 다시 돈다 / 체크포인트가 있으면 실패한 청크부터만 다시 돈다 |
| DB-22 | 이벤트 소싱과 CQRS 를 도입할 때 주의할 점은 무엇입니까? | 두 방식의 복원 결과는 같아야 한다 / 스냅샷을 쓰면 스냅샷 이후 이벤트만 읽는다 |
| DB-23 | 여러 노드에 같은 배치가 배포돼 있을 때 중복 실행을 어떻게 막습니까? | 락이 없으면 노드 수만큼 중복 실행된다 / 락을 선점하면 정확히 한 노드만 실행한다 |
| DB-24 | 레플리카를 붙였더니 사용자가 방금 쓴 데이터를 못 읽는 문제가 생겼습니다. | 레플리카는 읽기 전용이다 / 레플리카에는 쓸 수 없다 |
| DB-25 | 서버리스(Lambda)를 도입할 때의 함정은 무엇입니까? | 상한만큼만 접속에 성공한다 / 나머지는 애플리케이션이 아니라 서버가 거절한다 |

**msa** — 분산 아키텍처 (인메모리 모델 — 실물 판은 kafka 분류) — 5건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| MSA-01 | 모놀리스에서 MSA 로 갈 때 가장 큰 과제는 무엇입니까? | Outbox 없이도 주문 자체는 커밋된다 / Outbox 없으면 발행 실패 시 복구 근거가 남지 않는다 |
| MSA-02 | 결과적 일관성을 구현할 때 반드시 넣어야 하는 것은 무엇입니까? | 무조건 덮어쓰면 마지막에 도착한 오래된 이벤트가 이긴다 / 버전 가드는 최신 상태를 유지한다 |
| MSA-03 | 트래픽이 급증할 때 Kafka 로 시스템을 어떻게 지킵니까? | 순간 피크는 큐가 흡수해 결국 전부 처리된다 / 지속적으로 초과하면 랙이 선형으로 늘어난다 |
| MSA-04 | Kafka 컨슈머에서 처리에 실패한 메시지는 어떻게 재처리합니까? | 불량 메시지 하나가 뒤의 모든 메시지를 막는다 / 막히기 전까지 처리된 것은 앞의 2건뿐이다 |
| MSA-05 | 무중단으로 DB 마이그레이션을 하는 절차를 설명해 주세요. | 1~4단계 내내 구 버전 코드가 계속 동작한다 / 백필 후 미처리 행이 없다 |

**resilience** — 회복 탄력성 — 11건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| RES-01 | 재시도를 넣으면 왜 멱등성이 필요합니까? 어떻게 보장합니까? | 보호 없이 재시도하면 그 수만큼 중복 실행된다 / 멱등성 키가 있으면 정확히 1회만 실행된다 |
| RES-02 | 캐시 스탬피드(thundering herd)가 무엇이고 어떻게 막습니까? | sync=true 는 로딩을 1회로 묶는다 / sync=false 는 동시 미스만큼 원본을 호출한다 |
| RES-03 | 재시도에 지수 백오프와 지터를 왜 넣습니까? | 2회 실패 후 3번째 시도에서 성공한다 / 총 호출은 3회다 |
| RES-04 | 서킷브레이커의 동작 원리를 3상태로 설명해 주세요. | 최소 시행 횟수 전에는 CLOSED 를 유지한다 / 임계치를 넘으면 OPEN 이 된다 |
| RES-05 | 분산 환경에서 API Rate Limit 을 어떻게 구현합니까? 고정 윈도의 문제는 무엇입니까? | 고정 윈도는 경계를 넘는 순간 제한의 2배가 통과한다 / 토큰 버킷은 같은 구간에서 훨씬 적게 통과시킨다 |
| RES-06 | JWT 가 탈취됐을 때의 피해를 어떻게 줄입니까? | 회전할 때마다 새 토큰이 발급된다 / 이미 쓴 토큰을 다시 제시하면 재사용으로 탐지된다 |
| RES-07 | OAuth 2.0 인가 코드 플로우의 장점은 무엇입니까? 모바일/SPA 에서도 그대로 안전합니까? | 정상 클라이언트는 토큰을 받는다 / verifier 를 모르면 코드가 있어도 교환할 수 없다 |
| RES-08 | 재시도와 서킷브레이커를 함께 쓸 때 주의할 점은 무엇입니까? | 재시도를 바깥에 두면 서킷이 더 일찍 열린다 / 서킷을 바깥에 두면 같은 요청 수 안에서는 최소 시행 횟수를 못 채워 아예 열리지 않는다 |
| RES-09 | 장애가 다른 기능으로 번지는 것을 어떻게 막습니까? | 공유 풀에서는 정상 기능이 거절되거나 크게 지연된다 / 분리 풀에서는 정상 기능이 전부 성공한다 |
| RES-10 | 트래픽이 100배가 되어도 견딥니까? 정말 스테이트리스입니까? | 인메모리 카운터는 인스턴스 수만큼 제한이 샌다 / 중앙 카운터는 인스턴스가 늘어도 제한을 지킨다 |
| RES-11 | WebSocket 서버를 스케일 아웃할 때의 과제와 해결책은 무엇입니까? | 백플레인이 없으면 발행한 인스턴스의 사용자에게만 간다 / 다른 인스턴스에 붙은 사용자는 아예 못 받는다 |

**kafka** — 실물 브로커 (`verify-labs-kafka` 모듈, 브로커 없으면 INCONCLUSIVE 후 건너뜀) — 7건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| KAFKA-01 | Kafka 와 RabbitMQ 를 어떤 기준으로 고릅니까? 순서 보증은 어디까지 됩니까? | 같은 키는 항상 한 파티션에만 들어간다 / 키(파티션) 안에서는 발행 순서가 그대로 유지된다 |
| KAFKA-02 | 트래픽 급증을 Kafka 로 흡수한다고 했는데, 무엇을 보고 대응합니까? | 아무도 소비하지 않으면 랙은 발행 건수 그대로다 / 처리한 만큼만 랙이 줄어든다 |
| KAFKA-03 | Kafka 컨슈머에서 처리에 실패한 메시지는 어떻게 재처리합니까? | 두 전략 모두 결국 전건을 처리한다 / 파티션 내 재시도에서는 뒤 메시지가 독이 든 메시지의 재시도가 끝날 때까지 밀린다 |
| KAFKA-04 | Kafka 의 Exactly-Once 는 무엇을 보장합니까? 애플리케이션은 무엇을 더 해야 합니까? | Kafka 3.0+ 의 프로듀서 멱등성은 기본 활성이다 / read_committed 컨슈머는 커밋된 메시지만 본다 |
| KAFKA-05 | 컨슈머를 늘리면 처리량이 그만큼 늘어납니까? 오토스케일은 어디까지 유효합니까? | 컨슈머가 1대면 모든 파티션을 혼자 받는다 / 컨슈머가 늘면 파티션이 나눠진다 |
| KAFKA-06 | 파티션 키를 고객 ID 로 잡았더니 특정 고객 때문에 한 파티션만 밀립니다. 어떻게 합니까? | 키가 편중되면 한 파티션이 압도적으로 많이 받는다 / 나머지 파티션은 놀거나 거의 비어 있다 |
| KAFKA-07 | 스키마 진화에 어떻게 대응합니까? 전방 호환과 후방 호환의 차이는 무엇입니까? | 기본값이 있는 필드 추가는 후방 호환된다 — 컨슈머를 먼저 배포해도 안전 / 기본값이 있는 필드 추가는 전방 호환도 된다 — 옛 컨슈머는 새 필드를 무시한다 |

**perfbook** — *Java Performance: The Definitive Guide* 의 명제
(`verify-labs-perfbook` 모듈, `notes/java-performance/` 요약과 짝, PERF-11* 은 PostgreSQL 필요) — 15건

| ID | 책의 명제 (장) | 확인하는 것 |
|---|---|---|
| PERF-04 | 핫 메서드는 컴파일된 뒤에야 빨라진다 (4장) | 같은 메서드가 워밍업 전후로 수십 배 차이 — 워밍업 없는 측정은 인터프리터를 잰 것 |
| PERF-08 | 다이렉트 버퍼는 할당이 비싸니 재사용하라 (8장) | 재사용이 반복 할당보다 빠르다 / 다이렉트 할당이 힙 할당보다 느리다 |
| PERF-09A | 거짓 공유 — 같은 캐시 라인이면 서로를 느리게 한다 (9장) | 인접 슬롯 vs 간격 8 슬롯, 4스레드 증가 경쟁 — 라인 분리가 1.6~2.0배 빠르다 |
| PERF-09B | 암달의 법칙 — 직렬 구간이 상한을 정한다 (9장) | F=0.5 작업의 4스레드 실측 1.5배 — 이론 예측 1.6, 상한 2.0 을 넘지 않는다 |
| PERF-10A | 직렬화를 쪼개면 참조 동일성이 깨진다 (10장) | 기본 직렬화는 == 유지, 쪼개 쓰면 복제본 둘 — equals 로는 안 보인다 |
| PERF-10B | 압축 후 지연 해제가 가장 빠르다 (10장) | 압축이 크기를 절반 이하로 / 접근하지 않으면 해제 비용이 아예 없다 |
| PERF-10C | 출력을 줄이면 원격 클라이언트가 빨라진다 (10장) | 같은 정보의 두 표현 — 전송량 20.2배 감소는 어디서든 결정적으로, **시간은 원격 링크에서만 단정**(로컬은 INCONCLUSIVE 가 정상) |
| PERF-11A | 쓰기는 배치로 묶어라 (11장) | 같은 2,000행 — 건별 autocommit 대비 배치+단일 커밋이 36배 |
| PERF-11B | prepared statement 는 재사용부터 이득이다 (11장) | pg_prepared_statements 로 관측 — 5회째에 서버측 prepare 가 생기고, 커넥션에 묶인다 |
| PERF-11C | L2 캐시는 쿼리 결과를 담지 않는다 (11장) | 실제 Hibernate+Ehcache — find 는 L2 를 타지만 JPQL 은 SQL 실행, 쿼리 캐시를 켜야 담긴다 |
| PERF-11D | fetch size 는 메모리와 왕복의 트레이드오프 (11장) | 스레드 할당 바이트 — 기본값 4.3MB vs 커서 22KB, autocommit 켜면 조용히 무시 |
| PERF-12A | 버퍼 없는 I/O 는 바이트마다 시스템 콜 (12장) | 하부 read 호출 524,288회 → 65회 — 횟수는 결정적으로 센다 |
| PERF-12B | 예외 비용의 실체는 스택 수집이고 깊이에 비례 (12장) | 깊은 스택 생성이 3배 이상 비싸다 / writableStackTrace=false 면 그 비용이 사라진다 |
| PERF-12C | 스트림은 지연 순회한다 (12장) | findFirst 까지 filter 10회·map 1회 — 시간 대신 호출 횟수를 센다 |
| PERF-A01 | 책의 플래그 권고 일부는 전제가 사라졌다 (부록 A) | 실행 중 JVM 에 직접 질의 — BiasedLocking 기본 꺼짐, AggressiveOpts 제거, StringTableSize 1009→65536 |

**security** — 웹 보안 (Spring Security·Thymeleaf·PgJDBC 실물) — 5건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| SEC-01 | CSRF 공격의 원인과 방어를 아키텍처 관점에서 설명해 주세요. | GET 은 CSRF 검사 대상이 아니다 / 토큰 없는 POST 는 403 으로 차단된다 |
| SEC-02 | XSS 공격을 아키텍처 관점에서 어떻게 막습니까? | 자동 이스케이프는 태그를 문자로 바꿔 실행되지 않게 한다 / th:utext 는 입력을 그대로 내보내 XSS 통로가 된다 |
| SEC-03 | CORS 는 어떤 문제를 푸는 장치이고, 프리플라이트는 언제 발생합니까? | 프리플라이트는 본 요청 전에 OPTIONS 로 먼저 물어보고 200 으로 답한다 / 허용 출처에는 Access-Control-Allow-Origin 이 내려간다 |
| SEC-04 | 비밀번호를 안전하게 저장하려면 어떤 해시를 어떻게 써야 합니까? | 같은 비밀번호라도 해시가 매번 다르다(솔트 자동 포함) / 그래도 둘 다 원래 비밀번호로 검증된다 |
| SEC-05 | PreparedStatement 는 SQL 인젝션을 어떻게 막습니까? 이스케이프와 무엇이 다릅니까? | 문자열 결합은 세미콜론 뒤를 진짜 명령으로 실행해 표를 날린다 / 같은 문자열을 바인딩하면 아무 일도 일어나지 않는다 |

**observability** — 관측성 (OpenTelemetry SDK 실물, 콜렉터 없이 인메모리 익스포터) — 4건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| OBS-01 | 마이크로서비스에서 하나의 요청이 여러 서비스를 거칠 때 어떻게 추적합니까? | 전파 헤더는 W3C 표준 이름(traceparent)으로 실린다 / traceparent 값에 게이트웨이의 traceId 가 들어 있다 |
| OBS-02 | 비동기 처리나 스레드 풀을 쓰면 트레이스와 로그 상관관계가 왜 끊깁니까? | 같은 스레드에서는 traceId 가 그대로 보인다 / 같은 스레드에서는 MDC 도 그대로다 |
| OBS-03 | 트래픽이 많을 때 트레이스를 전부 수집합니까? 샘플링은 어떻게 정합니까? | 전량 수집이면 모든 요청이 저장된다 / 전량 수집이면 희귀 오류도 전부 남는다 |
| OBS-04 | SLI·SLO·SLA 를 구분해 설명하고, 에러 버짓을 어떻게 운영에 씁니까? | SLO 99.9% 의 30일 에러 버짓은 43분 12초다 / 한 자리 올리면(99.99%) 버짓은 10분의 1로 줄어든다 |

**api** — API 설계 — 5건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| API-01 | API 버저닝 전략은 어떻게 가져갑니까? | 필드 추가는 구 클라이언트를 깨지 않는다 / 구 페이로드는 신 서버에서 선택 필드가 null 로 들어온다 |
| API-02 | URL 단축 서비스를 설계해 주세요. 단축 코드는 몇 자리로 잡습니까? | 62^7 = 3,521,614,606,208 / 62^7 은 350억의 100배 이상이다 |
| API-03 | RESTful API 설계에서 가장 중요한 베스트 프랙티스는 무엇입니까? | PUT 은 몇 번을 보내도 자원이 하나다 / POST 는 보낼 때마다 자원이 늘어난다 |
| API-04 | 외부 API 연동 부분은 테스트를 어떻게 하셨습니까? | 계약이 맞을 때는 정상 파싱된다 / 제공 측이 필드를 바꾸면 HTTP 레벨 검증은 즉시 깨진다 |
| API-05 | GraphQL 과 REST 의 차이와 장단점을 설명해 주세요. | 두 방식 모두 같은 결과를 돌려준다 / 순진한 구현은 작가 수만큼 추가 쿼리가 나간다(1+N) |

**ai** — AI/검색 (임베딩 모델·LLM 은 쓰지 않는다 — `docs/02` §6) — 7건

| ID | 질문 | 확인하는 것 |
|---|---|---|
| AI-01 | Feature Store 의 역할은 무엇입니까? | 따로 구현하면 같은 정의인데도 값이 달라진다 / 공통 정의를 쓰면 두 경로의 값이 정확히 일치한다 |
| AI-02 | 모델 드리프트는 무엇이고 운영에서 어떻게 대응합니까? | 분포가 같으면 PSI 는 0 이다 / 소폭 변화는 관찰 구간에 머문다 |
| AI-03 | 벡터 검색에서 ANN(근사 최근접 탐색)이 필수인 이유는 무엇입니까? | 근사 탐색은 후보를 좁히므로 계산량이 줄어든다 / 탐색 폭이 좁으면 진짜 근방을 놓친다 |
| AI-04 | 임베딩의 차원 수는 성능에 어떤 영향을 줍니까? | 절단하면 메모리가 차원 비율만큼 준다 / int8 양자화는 메모리를 1/4 로 줄인다 |
| AI-05 | RAG 의 검색 정확도를 올리려면 무엇부터 합니까? | 키워드 검색은 제품 코드 문서만 잡는다 / 벡터 검색은 의미 문서만 잡는다 |
| AI-06 | 사내 LLM 시스템에서 기밀 정보 유출을 어떻게 막습니까? | 필터가 없으면 인사·임원 문서가 일반 직원 답변에 섞인다 / 후필터는 컨텍스트가 비어버린다 |
| AI-07 | RAG 에서 벡터 DB 는 무엇을 쓰셨습니까? | 확장을 켜면 PostgreSQL 안에서 벡터 컬럼과 거리 연산이 동작한다 / 인덱스가 없으면 전건 비교(Seq Scan)다 |

질문 전체(Q31~Q115)의 A/B/C 분류는 `docs/01-질문-검증-매핑.md`,
답변 원고에서 발견한 수정 사항은 `docs/04-답변-원고-검토-지적사항.md` 와 `docs/10-원고-수정-지시서.md` 참고.

## 3. 판정 규칙

| 판정 | 의미 | 대응 |
|---|---|---|
| `CONFIRMED` | 답변대로 재현됨 | 그대로 면접에서 말해도 된다 |
| `REFUTED` | 실행 결과가 답변과 다름 | **답변을 고쳐야 한다** |
| `INCONCLUSIVE` | 이번 실행에서 결론 못 냄 | 환경 의존 항목(`nondeterministic`)이면 정상 |
| `ERROR` | 검증 코드 자체가 예외 | 랩 버그 |

케이스 안에서는 증거를 세 종류로 나눠 기록한다.

- `fact(...)` — 판정에 영향 없는 관측값. 리포트에 그대로 실린다.
- `expect(...)` — 반드시 성립해야 하는 명제. 깨지면 `REFUTED`.
- `expectFlaky(...)` — 타이밍/JIT/GC 의존. 깨지면 `REFUTED` 가 아니라 `INCONCLUSIVE`.

`expectFlaky` 는 현재 **40곳**이고, 성격별로 나누면 이렇다(`docs/02` §2).

| 성격 | 개수 |
|---|---|
| 인프라·조건 게이트 — "검증 못 했다"를 남기는 정상 용법 | 8 |
| 하네스 결함이라 고쳐서 더는 흔들리지 않음 | 5 |
| 여유가 수십 배라 그대로 둠 | 7 |
| 본질적 비결정(옵티마이저·근사 알고리즘·JIT/GC·락 타이밍 등) | 20 |

> **성능 비교를 쓸 때 주의.** `after <= before` 처럼 쓰면 **둘 다 0일 때 조용히 통과한다.**
> 이 랩도 세 케이스가 그 상태였다(밀리초로 잘라 비교). 마이크로초로 재고 최소 여유를 요구하도록 고쳤다 —
> 해상도와 여유를 함께 정해야 한다(`docs/05` §22).

---

## 4. 다른 프로젝트에 이식하기

`verify-core` 는 Spring Boot 자동 설정 라이브러리다. 의존성만 추가하면 끝난다.

```groovy
dependencies {
    implementation project(':verify-core')   // 또는 publishToMavenLocal 후 'io.webboy:verify-core:0.1.0'
}
```

케이스는 `VerificationCase` 를 상속한 `@Component` 하나로 끝난다.

```java
@Component
public class MyCase extends VerificationCase {
    public String id()       { return "MYAPP-01"; }
    public String category() { return "myapp"; }
    public String question() { return "면접에서 받은 질문"; }
    public String claim()    { return "내가 주장한 명제"; }

    protected void verify(Evidence evidence) {
        evidence.fact("관측값", something);
        evidence.expect("명제가 성립한다", condition);
    }
}
```

`verify-core` 를 추가하면 자동으로 붙는 것:

- `VerificationRegistry` / `VerificationRunner` 빈
- `/verify/**` REST 엔드포인트 (웹 앱일 때만, `verify.web.enabled=false` 로 끌 수 있다)
- `verify.run-on-startup=true` 일 때 기동 시 전체 실행 + 리포트 저장

운영 환경에 그대로 섞이는 게 걱정되면 프로파일로 분리한다.

```yaml
# application-prod.yml
verify:
  enabled: false
```

설정 항목: `verify.enabled`, `verify.run-on-startup`, `verify.report-path`, `verify.base-path`, `verify.web.enabled`

---

## 5. 프로젝트 구조

```
interview-verify-lab/
├── compose.yaml                      # PostgreSQL 16 + 레플리카 + Kafka + Redis
├── scripts/random-ports.sh           # 빈 호스트 포트를 골라 .env 에 적는다
├── verify-core/                      # 이식용 하네스 (외부 의존: spring-boot-starter 뿐)
│   └── io/webboy/verify/core/
│       ├── VerificationCase.java     # 상속해서 케이스 작성
│       ├── Evidence.java             # fact / expect / expectFlaky
│       ├── VerificationResult.java   # 판정 + 증거
│       ├── VerificationRegistry.java # 빈 자동 수집, id 중복 검사
│       ├── VerificationRunner.java   # 전체/분류/개별 실행
│       ├── CaseFilter.java           # -Dverify.only 로 케이스 선택
│       ├── VerificationReport.java   # 콘솔 표 + 케이스 상세 + 마크다운 리포트
│       ├── web/                      # REST 엔드포인트
│       └── autoconfigure/            # Spring Boot 자동 설정
├── verify-labs/                      # 검증 케이스 88건
│   └── io/webboy/verify/labs/
│       ├── spring/  jpa/  concurrency/  jvm/  db/  msa/
│       ├── resilience/  security/  observability/  api/  ai/
│       └── LabApplication.java
├── verify-labs-kafka/                # 실물 브로커 케이스 7건 (별도 모듈)
│   └── io/webboy/verify/labs/kafka/
├── verify-labs-perfbook/             # Java Performance 책 명제 12건 (notes/java-performance 와 짝)
│   └── io/webboy/verify/labs/perfbook/
│       ├── ch04/ ch08/ ch09/ ch10/ ch11/ ch12/ appendixa/
│       └── PerfBookLabApplication.java
└── manuscripts/                      # 대조 대상인 답변 원고 자체 (Q1~Q200)
    ├── 원본/                          # 받은 그대로 (Part 3~6 은 PDF 추출 .txt)
    ├── 수정본/                        # 지적 8건·보강 9건 반영 — 사실관계 기준
    └── 회화체/                        # 수정본을 말투만 바꾼 판 (Part 1~11 완료)
```


---

## 6. 알려진 한계

`docs/02-정직한-고지.md` 에 정리해 두었다. 요약하면:

1. **DB 케이스는 PostgreSQL 16 기준**이다. 격리 수준·옵티마이저·락 동작은 제품마다 다르므로,
   MySQL InnoDB 의 gap lock 이나 클러스터 인덱스 특성은 여기서 재현되지 않는다.
   비교가 필요하면 `compose.yaml` 에 MySQL 을 추가하고 `DB_URL` 만 바꿔 같은 케이스를 돌리면 된다.
2. **시간을 재는 케이스는 JMH 가 아니다.** 벽시계 측정이라 **자릿수만** 신뢰해야 한다.
   다만 "벤치마크가 아니니 어쩔 수 없다"로 넘기지는 않았다 — `CON-02` 는 오토박싱 제거·워밍업·
   회차 인터리빙까지 고쳐서 안정화했다(`docs/05` §20).
3. **`INCONCLUSIVE` 는 정상 결과일 수 있다.** 지금 이 장비에서는 0 이지만
   더 느리거나 코어가 적은 장비에서는 날 수 있다. 결과를 정하는 것이 옵티마이저·JIT·GC·락 타이밍이라
   손댈 수 없는 항목이 20곳 있다(§3 표).
4. **AI 케이스는 실제 임베딩 모델을 쓰지 않는다.** 랜덤 벡터와 가짜 검색기로 *알고리즘의 성질*만 검증한다.
   `AI-07` 만 pgvector 실물 인덱스를 쓴다.
5. **MSA 분류는 절반이 인메모리 모델**이다. 같은 명제의 실물 판이 `kafka` 분류에 따로 있다 —
   `KAFKA-01`·`02`·`03` 이 각각 `MSA-02`·`03`·`04` 에 대응한다.
6. **Kubernetes 는 검증하지 못했다.** k3s 컨트롤 플레인까지는 띄웠지만 이 환경에서 파드가 뜨지 않았다.
   API 레벨만 보는 케이스를 올리면 "검증한 척"이 되므로 만들지 않았다(`docs/05` §18).
7. **리포트의 소요 시간은 장비 값이다.** 실행 환경(Java 17.0.19 / Linux / 4코어) 기준이라
   그대로 인용하면 안 된다.
8. **`manuscripts/` 의 Part 3~6 은 PDF 에서 추출한 것이다.** PDF 는 줄바꿈 지점에 띄어쓰기가
   있었는지를 복원할 수 없어서, 단어 중간에 어색한 공백이 남아 있을 수 있다. 정규식 교정을
   시도했다가 멀쩡한 공백까지 지우는 사고가 나서 되돌렸고, 대신 파일마다 고지를 넣었다.
   Part 1·2·7~11 은 markdown 원본이라 해당 없다.

---

## 7. 실행 결과 (2026-08-13)

Java 17.0.19 / Linux 4코어 기준.

```
$ ./scripts/random-ports.sh && docker compose up -d && ./gradlew test

- 합계 88건 | CONFIRMED 88 | REFUTED 0 | INCONCLUSIVE 0 | ERROR 0   (verify-labs)
- 합계  7건 | CONFIRMED  7 | REFUTED 0 | INCONCLUSIVE 0 | ERROR 0   (verify-labs-kafka)
```

케이스가 늘어온 경과는 이렇다.

| 단계 | 건수 | CONFIRMED | REFUTED | INCONCLUSIVE |
|---|---|---|---|---|
| 첫 실행 (H2) | 52 | 46 | **1** (DB-07) | 5 |
| PostgreSQL 16 이관 후 | 52 | 51 | 0 | 1 |
| 케이스 확장 + Kafka·Redis·레플리카 | 88 | 84 | 0 | 4 |
| security·observability 신설 | 95 | 93~95 | 0 | 0~2 |
| 측정 하네스 정리 후 | **95** | **95** | **0** | **0** |

**REFUTED 는 지금까지 두 번 나왔고 둘 다 답변이 아니라 랩의 문제였다.**

- `DB-07`(데드락) — 검증 코드의 동기화 버그. 두 시나리오가 배리어를 공유해 데드락 대신 락 타임아웃이 났다.
- `KAFKA-05`(리밸런스) — 컨슈머 그룹 이름이 실행 간에 공유돼, 앞 실행의 컨슈머가 파티션을 가져갔다.
  인위적으로 재현해 증상을 일치시킨 뒤 그룹 이름에 실행 단위 접미사를 붙여 해결했다.

INCONCLUSIVE 를 줄이는 과정에서 **"환경 의존이라 어쩔 수 없다"고 적었던 것이 여섯 번 틀렸다.**
전부 측정 하네스 쪽 결함이었다 — 오토박싱, 워밍업 부재, 몰아서 재기, `acquire` 를 `usage` 에 섞어 재기,
밀리초로 잘라 `<=` 로 비교하기. 과정은 `docs/05` §13·§20·§21·§22 에 있다.

그 과정에서 나온 것들이 원래 검증하려던 명제보다 쓸모 있었다.

```
· Index Only Scan 은 ANALYZE 가 아니라 VACUUM(visibility map)이 있어야 나온다
· 후행 컬럼만 줘도 EXPLAIN 은 'Index Cond' 라고 찍는다 — 비용은 441배 차이인데도
· 교과서 ReDoS 예제 ^(a+)+$ 는 Java 17 에서 폭발하지 않는다
· PgJDBC 는 ? 유무와 무관하게 다중 구문을 실행한다 — PreparedStatement 가 막는 게 아니다
· 타임스탬프만 쓰는 UUIDv7 은 고빈도 생성에서 v4 에 가까워진다 (RFC 9562 카운터 필요)
```
