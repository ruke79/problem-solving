# interview-verify-lab

시니어 개발자 면접 질문의 **답변을 말이 아니라 실행 결과로 검증**하는 컴팩트한 Spring Boot 랩.

"@Transactional 은 self-invocation 에서 안 걸립니다" 같은 답변을 외우는 대신,
그 명제를 코드로 재현해 `CONFIRMED` / `REFUTED` 판정을 남긴다.

- Gradle **8.14.3** / Java **17** / Spring Boot **3.3.5**
- 모듈 3개: `verify-core`(이식 가능한 하네스) + `verify-labs`(**81개**) + `verify-labs-kafka`(실물 브로커 **7개**)
- 답변 스크립트 Part 3~7(Q31~Q115)을 A/B/C 로 분류해 반영 — `docs/01-질문-검증-매핑.md`
- 검증 케이스 **95건** (verify-labs 88 + verify-labs-kafka 7), 12개 분류.
- 인프라는 전부 실물이다(`compose.yaml`) — **PostgreSQL 16 + pgvector**, **스트리밍 레플리카**, **Kafka 3.9**, **Redis 7**. 흉내가 아니라 실제 옵티마이저·MVCC·복제 지연·파티션 재할당을 관측한다.
- **실행 검증 완료** — **88건** 전부 실행해 REFUTED 0 (CONFIRMED 84 / INCONCLUSIVE 4).
  그 과정에서 나온 문제와 해결은 `docs/05-개발-중-문제와-해결.md`

### 문서

| 파일 | 내용 |
|---|---|
| `docs/00-인계-노트.md` | 이 랩이 만들어진 경위와 인계 사항 (원본 그대로) |
| `docs/01-질문-검증-매핑.md` | Q31~Q115 를 A/B/C 로 분류한 표 |
| `docs/02-정직한-고지.md` | 이 랩이 증명하지 **못하는** 것 |
| `docs/03-새-케이스-추가-가이드.md` | 케이스 추가 방법 |
| `docs/04-답변-원고-검토-지적사항.md` | 답변 원고에서 발견한 사실·표현 오류 |
| `docs/05-개발-중-문제와-해결.md` | 빌드·실행·PostgreSQL 이관에서 발생한 문제 11건과 해결 |
| `docs/06-원고-수정본-Part5.md` | Q61~Q75 원고 대조 결과와 확정된 수정문 (Q64 Base62 오기 포함) |
| `docs/07-원고-수정본-Part6.md` | Q76~Q90 원고 대조 결과와 확정된 수정문 (Q77·Q78·Q87) |
| `docs/08-PostgreSQL-로-늘어난-검증-범위.md` | PostgreSQL·Kafka 로 새로 검증 가능해진 질문과 아직 안 되는 것 |
| `docs/09-테스트-누락-점검-Q1-Q200.md` | 전 범위 원고 대조 — 만들 수 있는데 없는 케이스 17건 |

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
`verify-labs-kafka` 는 브로커가 없으면 5건을 INCONCLUSIVE 로 남기고 테스트를 건너뛴다(실패가 아니다).

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

## 2. 검증 케이스 (88개)

**spring** — 프록시와 트랜잭션 경계

| ID | 질문 | 검증 방법 |
|---|---|---|
| SPRING-01 | Q98 self-invocation 에서 @Transactional | 트랜잭션 활성 여부 직접 조회 |
| SPRING-02 | Q92 JDK 프록시 vs CGLIB | ProxyFactory 로 양쪽 생성해 타입 확인 |
| SPRING-03 | REQUIRED vs REQUIRES_NEW | 롤백 후 실제 행 수 |
| SPRING-04 | checked 예외 롤백 | 예외 종류별 잔존 행 수 |
| SPRING-05 | 싱글턴 안의 프로토타입 빈 | 인스턴스 serial 비교 |
| SPRING-06 | @Async 컨텍스트 전파 | 비동기 스레드 스냅샷 |
| SPRING-07 | Q96 Strategy 선택 로직 | Map<빈이름, Strategy> 자동 주입 |

**jpa** — 영속성 컨텍스트

| ID | 질문 | 검증 방법 |
|---|---|---|
| JPA-01 | Q99 N+1 | Hibernate Statistics 로 SQL 수 계수 |
| JPA-02 | 변경 감지 | save() 없이 UPDATE 발생 |
| JPA-03 | LazyInitializationException / OSIV | 트랜잭션 밖 프록시 접근 |
| JPA-04 | 낙관적 락 | stale 버전 저장 시 예외 타입 |
| JPA-05 | @BatchSize | 적용 전후 SQL 수 |
| JPA-06 | Q99 fetch join + 페이징 | 발행 SQL 에 limit 절이 없음을 StatementInspector 로 포착 |
| JPA-07 | Q97 비관적 락 | FOR UPDATE 의 실제 대기 시간 |

**concurrency / jvm** — 자바 기본기

| ID | 질문 | 검증 방법 |
|---|---|---|
| CON-01 | HashMap 동시 수정 | 8스레드 16만 증가 후 손실량 |
| CON-02 | synchronized / Atomic / LongAdder | 정확성 + 처리 시간 |
| CON-03 | volatile 가시성 | 스핀 루프 (환경 의존) |
| CON-04 | 스레드 풀의 ThreadLocal | 재사용 스레드에서 이전 값 관측 |
| CON-05 | Q48 데드락 탐지 | ThreadMXBean + 락 순서 통일 |
| JVM-01 | Integer == 비교 | 캐시 범위 안팎 동일성 |
| JVM-02 | String Pool / intern | 리터럴·런타임·new 비교 |
| JVM-03 | equals 만 재정의 | HashSet 중복 저장·조회 실패 |
| JVM-04 | Q66 Strong/Soft/Weak | GC 후 회수 여부 (환경 의존) |
| JVM-05 | Q93 방어적 복사 | record 도 가변 컬렉션이면 불변 아님 |
| JVM-06 | Q67 CPU 100% | 정규식 파국적 백트래킹의 지수 증가 |

**db** — RDBMS

| ID | 질문 | 검증 방법 |
|---|---|---|
| DB-01 | READ COMMITTED vs REPEATABLE READ | 커넥션 2개로 재읽기 차이 |
| DB-02 | 풀 고갈 시 동작 | Hikari 풀(size=2) 고갈 → 예외·대기시간 |
| DB-03 | Q46 인덱스와 실행계획 | EXPLAIN + 선택도별 비교 |
| DB-04 | Q104 프리페어드 스테이트먼트 | `' OR '1'='1` 로 전건 노출 vs 0건 |
| DB-05 | Q106·Q107 UUID PK | UUIDv7 정렬 가능성 + 삽입 시간 |
| DB-06 | **Q18·Q44·Q68 풀 고갈 원인 판별** | 누수 / 장시간 점유 / 용량 부족 3시나리오의 지표 모양 |
| DB-07 | Q48 DB 데드락 | 엇갈린 순서 → 데드락(40P01), 통일 → 무사고 |
| DB-08 | **Q47 파티셔닝** | 파티션 프루닝(1개만 읽음) / DROP PARTITION vs DELETE 의 dead tuple |
| DB-09 | Q97 write skew | REPEATABLE READ 는 통과시키고 SERIALIZABLE 이 40001 로 차단 |
| DB-10 | **Q51 CDC** | 논리 복제 슬롯 — DELETE 는 기본 PK 만, FULL 이면 전체 값 / 미소비 슬롯이 WAL 점유 |
| DB-11 | **Q40·Q73 DDL 락** | ADD COLUMN 1ms vs 타입 변경 재작성 / 락 큐가 뒤의 SELECT 까지 정지 |
| DB-12 | Q61·Q100 멱등성 | 커넥션 20개 동시 진입 → 유니크 제약이 정확히 1건(23505) |

**msa** — 분산 아키텍처

| ID | 질문 | 검증 방법 |
|---|---|---|
| MSA-01 | Q31 Outbox 패턴 | 커밋 후 발행 실패 시 유실 vs 릴레이 복구 |
| MSA-02 | Q37 결과적 일관성 | 버전 가드가 중복·역전 이벤트를 제거 |
| MSA-03 | Q42 큐 부하 평준화 | 지속 초과 시 랙 무한 증가, 레이트 리밋 시 0 |
| MSA-04 | Q43 헤드 오브 라인 블로킹 | 파티션 내 재시도 vs 재시도 토픽(순서 붕괴) |
| MSA-05 | Q40·Q73 Expand/Contract | 단계별 구 버전 코드 동작 여부 |

**resilience** — 회복 탄력성

| ID | 질문 | 검증 방법 |
|---|---|---|
| RES-01 | Q61·Q100 멱등성 | 동시 재시도 20건의 부수효과 횟수 |
| RES-02 | Q52·Q69 캐시 스탬피드 | sync 유무별 원본 호출 횟수 |
| RES-03 | Q34 지수 백오프 + 지터 | 시도 횟수·대기 시간 실측 |
| RES-04 | Q34 서킷브레이커 | resilience4j 로 3상태 + 최소 시행 횟수 |
| RES-05 | Q62 Rate Limit | 고정 윈도 경계 2배 통과 + 확인/소비 경쟁 |
| RES-06 | Q101 리프레시 토큰 회전 | 재사용 탐지 → 계보 전체 무효화 |
| RES-07 | Q103 OAuth PKCE | 코드만 탈취한 공격자 차단 / plain 방식은 무력 |
| RES-08 | **Q34 재시도 × 서킷브레이커** | 겹치는 순서가 결과를 바꾼다 — 원격 호출 6회 vs 12회, 서킷은 한쪽만 열림 |

**kafka** — 실물 브로커 (`verify-labs-kafka` 모듈, 브로커 없으면 INCONCLUSIVE 후 건너뜀)

| ID | 질문 | 검증 방법 |
|---|---|---|
| KAFKA-01 | Q41 순서 보증 범위 | 파티션 3개에 키별 발행 — 키 안에서는 순서 유지, 토픽 전체는 역전 / 오프셋 되감기 재소비 |
| KAFKA-02 | Q42·Q45 컨슈머 랙 | AdminClient 로 실측 — 미소비 500 → 부분 처리 400 → 전량 처리 0 |
| KAFKA-03 | Q43 헤드 오브 라인 블로킹 | 파티션 내 재시도는 뒤를 세우고, 재시도 토픽은 순서를 잃는다 |
| KAFKA-04 | Q100 Exactly-Once | enable.idempotence 기본 true / abort 된 메시지는 read_committed 에만 안 보인다 |
| KAFKA-05 | Q45·Q110 리밸런스 | 파티션 2개 + 컨슈머 3대 → 1:1 재할당 후 3번째는 논다 |

**api / ai**

| ID | 질문 | 검증 방법 |
|---|---|---|
| API-01 | Q108 API 버저닝 | 가산적 변경 vs 파괴적 변경의 파싱 결과 |
| API-02 | Q64 Base62 용량 | **답변 원고의 '350억' 오기 검출** |
| AI-01 | Q76 Feature Store | 같은 정의를 따로 구현하면 1750 vs 1500 |
| AI-02 | Q77 모델 드리프트 | PSI 계산과 임계치 판정 |
| AI-03 | Q78 ANN | nprobe 별 재현율·계산량 트레이드오프 |
| AI-04 | Q113 임베딩 차원 | 양자화 1/4 메모리 / 단순 절단의 재현율 붕괴 |
| AI-05 | Q54 하이브리드 검색 | Recall@3: 키워드 0.5, 벡터 0.5, RRF 1.0 |
| AI-06 | Q59·Q79 RAG 권한 | 전필터 vs 후필터 vs 무필터 |

질문 전체(Q31~Q115)의 A/B/C 분류는 `docs/01-질문-검증-매핑.md`,
답변 원고에서 발견한 수정 사항은 `docs/04-답변-원고-검토-지적사항.md` 참고.

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
├── verify-core/                      # 이식용 하네스 (외부 의존: spring-boot-starter 뿐)
│   └── io/webboy/verify/core/
│       ├── VerificationCase.java     # 상속해서 케이스 작성
│       ├── Evidence.java             # fact / expect / expectFlaky
│       ├── VerificationResult.java   # 판정 + 증거
│       ├── VerificationRegistry.java # 빈 자동 수집, id 중복 검사
│       ├── VerificationRunner.java   # 전체/분류/개별 실행
│       ├── VerificationReport.java   # 콘솔 표 + 마크다운 리포트
│       ├── web/                      # REST 엔드포인트
│       └── autoconfigure/            # Spring Boot 자동 설정
└── verify-labs/                      # 실제 검증 케이스
    └── io/webboy/verify/labs/
        ├── spring/  jpa/  concurrency/  jvm/  db/  resilience/
        └── LabApplication.java
```

---

## 6. 알려진 한계

`docs/02-정직한-고지.md` 에 정리해 두었다. 요약하면:

1. **DB 케이스는 PostgreSQL 16 기준**이다. 격리 수준·옵티마이저·락 동작은 제품마다 다르므로, MySQL InnoDB 의 gap lock 이나 클러스터 인덱스 특성은 여기서 재현되지 않는다. 비교가 필요하면 `compose.yaml` 에 MySQL 을 추가하고 `DB_URL` 만 바꿔 같은 케이스를 돌리면 된다.
2. **CON-02 는 JMH 가 아니다.** 단발 벽시계 측정이라 절대값이 아니라 자릿수만 신뢰한다.
3. **환경 의존 케이스**(CON-01~03, CON-05, JVM-04, JVM-06, DB-03, DB-05, DB-06, DB-07, DB-08, DB-10, DB-11, JPA-07, RES-05, AI-03, AI-04)는 `INCONCLUSIVE` 가 나올 수 있고, 그것이 정상 동작이다.
4. **AI 케이스는 실제 임베딩 모델을 쓰지 않는다.** 랜덤 벡터와 가짜 검색기로 *알고리즘의 성질*만 검증한다.
5. **리포트의 소요 시간은 장비 값이다.** 실행 환경(Java 17.0.19 / Linux / 4코어) 기준이라 그대로 인용하면 안 된다.

---

## 7. 실행 결과 (2026-08-11)

Java 17.0.19 / Linux 4코어 기준. DB 는 컨테이너 `postgres:16`(16.14)와 네이티브 설치본(16.13)
양쪽에서 각각 돌렸고 결과가 같았다 — `compose.yaml` 은 실제로 기동해서 검증했다.

| 판정 | 건수 |
|---|---|
| CONFIRMED | 55 |
| REFUTED | 0 |
| INCONCLUSIVE | 2~4 (JVM-06, CON-02, DB-05, RES-05 — 전부 `nondeterministic()` 케이스, 실행마다 흔들린다) |
| ERROR | 0 |

PostgreSQL 로 바꾸면서 **검증 케이스가 52 → 57개**로 늘었다. Q47(파티셔닝)·Q51(CDC)이
"인프라 없어서 못 함"에서 실행 검증으로 넘어왔고, Q40·Q73·Q61·Q97 의 검증 깊이가 올라갔다 —
`docs/08-PostgreSQL-로-늘어난-검증-범위.md`.

H2 인메모리에서 PostgreSQL 16 으로 옮기면서 `DB-01`(격리 수준)과 `DB-03`(실행계획)의 판정이
`INCONCLUSIVE` → `CONFIRMED` 로 올라갔다. H2 옵티마이저로는 못 하던 관측이 실물에서는 그대로 잡힌다.

```
인덱스 전: Seq Scan on scan_demo (cost=0.00..1791.00 rows=1) Filter: (k = 42)
인덱스 후: Index Only Scan using idx_scan_demo_k on scan_demo (cost=0.29..8.31 rows=1)
데드락   : PSQLException (SQLState=40P01)   ← 락 타임아웃(55P03)이 아니라 진짜 데드락
```

처음 실행에서 `DB-07` 이 `REFUTED` 로 떨어졌고, 원인은 답변이 아니라 **검증 코드의 동기화 버그**였다.
그 밖에 빌드가 서지 않던 문제, PostgreSQL 이관에서 깨진 H2 전용 SQL 까지
`docs/05-개발-중-문제와-해결.md` 에 문제와 해결을 정리해 두었다.
