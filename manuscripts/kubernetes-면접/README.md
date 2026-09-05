# Kubernetes 면접 답변 스크립트 — Q1~Q50

[`java-면접/`](../java-면접/README.md)·[`spring-면접/`](../spring-면접/README.md) 과 같은 형식의 별도 세트다.
**일본어 답변 + 한국어 답변** 양쪽, 한 문항 **30~40초 분량**.

- **50문항 이내로 압축했다.** `kubectl` 옵션을 외우는 문항이 아니라 **왜 그렇게 동작하는가**를 묻는 형태로 골랐다.
- 문체는 다른 세트와 같은 **です・ます체**.

> ## ⏱ 시간이 부족하면 → [`필수-키노트.md`](필수-키노트.md)
>
> 50문항을 **S급 10 / A급 15 / B급 8** 세 등급으로 나누고,
> S급에는 **그대로 외울 수 있는 일본어 한 문장**을 붙였습니다.
> **20분밖에 없으면 S급 10문항의 일본어 문장만** 보면 됩니다.
> 끝에 「면접 30분 전 최종 점검」으로 **함정 질문의 "아니오" 목록**을 정리해 뒀습니다.
>
> 그 S급만 뽑은 **[플래시카드](../플래시카드/README.md)** 도 있습니다(`kubernetes.tsv`, 10장 · Anki 로 바로 가져올 수 있음).

## 구성

| 파일 | 범위 | 문항 | 주제 |
|---|---|---|---|
| [`Part1.md`](Part1.md) | Q1~Q25 | 25 | 기본 구조와 워크로드 — 컨트롤 플레인, Pod/Deployment/StatefulSet, Service/Ingress, 프로브, requests·limits, QoS, 스케줄링, ConfigMap/Secret, 롤링 업데이트, 종료 처리, HPA, 볼륨, 네임스페이스 |
| [`Part2.md`](Part2.md) | Q26~Q50 | 25 | 운영·보안·트러블슈팅 — Pod 기동 실패, OOMKilled, 연결 불가, NetworkPolicy, RBAC, 이미지 태그, 노드 교체, 로그·모니터링, GitOps, Helm/Kustomize, Operator, 서비스 메시, 업그레이드, 비용, 장애 대응 |
| | | **50** | |

## 시간이 없다면 이 아홉 개

| | 문항 |
|---|---|
| 거의 반드시 | Q11 liveness vs readiness · Q13 requests 와 limits · Q7 Service · Q19 롤링 업데이트 |
| 시니어면 반드시 | Q21 안전한 종료(preStop) · Q26 Pod 기동 실패 조사 · Q28 연결 불가 조사 순서 · Q50 장애 대응 |
| 준비된 사람이 적음 | Q14 limits 와 JVM 의 관계 · Q15 QoS 클래스 |

**Q11 은 함정에 가깝다.** liveness 에 DB 확인을 넣으면 **의존 서비스가 잠깐 느린 것만으로 재시작 루프**가 된다.
"둘 다 헬스체크"로 답하지 말고 **역할이 다르다**는 것부터 말해야 한다.

**Q14 는 자바 면접에서 특히 잘 나온다.** CPU limit 1 이면 에르고노믹스가 SerialGC 를 고르고,
메모리 limit 을 힙과 같게 잡으면 힙 밖(메타스페이스·스레드 스택·네이티브) 몫에서 OOMKilled 가 난다.

## 이 세트의 성격 — 답변의 근거

> ### ⚠️ **이 저장소는 Kubernetes 를 검증하지 못했다.**
>
> `docs/02-정직한-고지.md` §1-3 과 `docs/05-개발-중-문제와-해결.md` §18 에 적힌 그대로다 —
> k3s 컨트롤 플레인까지는 띄웠지만 이 환경에서 파드가 뜨지 않았다. **API 레벨만 보는 케이스를 올리면
> "검증한 척"이 되므로 만들지 않았다.** 그 판단을 이 세트에도 그대로 적용한다.
>
> **따라서 이 50문항에는 실행 근거가 붙는 문항이 하나도 없다.** 공식 문서와 실무 경험에 근거한 서술이다.

다만 **JVM 쪽 귀결은 실행으로 확인했다.** 컨테이너의 CPU·메모리 제한이 JVM 을 어떻게 구성하는지는
`verify-labs-cloudnative` 에서 JDK 25 로 확인한 것이 있다.

| 문항 | 실행 근거 |
|---|---|
| Q14 limits 와 JVM | `CN-04A` — `ActiveProcessorCount=1` 이면 **SerialGC**, 2 면 G1 (MaxRAM 을 8g 로 줘도 1 CPU 면 Serial) |
| Q14 힙 비율 | `CN-04B` — `MaxRAM=2g` → 힙 495MB(25%), `MaxRAMPercentage=50` → 1024MB |
| Q22 HPA 와 병렬성 | `CN-09A` — CPU 제한이 `commonPool` 병렬도를 정한다(4코어 → 3, `ActiveProcessorCount=2` → 1) |
| Q27 OOMKilled 와 힙 밖 | `JVM-07` — **힙 밖에서 쓰는 메모리가 0 이 아니다**, 컨테이너 한계는 힙보다 커야 한다 |
| Q12 startupProbe 와 워밍업 | `PERF-04` — JIT 워밍업 전후의 차이 |
| Q37 지표를 백분위수로 | `CN-10A` — 인스턴스별 p99 는 평균 낼 수 없다 |
| Q36 로그 상관 | `OBS-01`·`OBS-02` — `traceparent` 전파, 스레드가 바뀌면 MDC 가 끊긴다 |

**면접에서 말할 때의 요령**: 쿠버네티스 자체의 동작은 "문서 기준으로는" 이라고 하고,
**JVM 과 컨테이너의 관계는 "직접 재 봤을 때는"** 으로 나눠 말하면 근거의 두께가 정확히 전달된다.

## 검증하지 못한 것

- 클러스터 동작 전부 — 스케줄링, 프로브, 롤링 업데이트, 축출, NetworkPolicy, RBAC.
- 이 세트에는 **수치를 쓰지 않았다.** 재지 않은 것을 인용하지 않기 위해서다.
- 클러스터를 띄울 수 있는 환경이 생기면 `verify-labs-kafka` 처럼 별도 모듈로 붙일 수 있다 —
  막힌 것은 설계가 아니라 실행 환경이다(`docs/00` 「이어서 할 일」 2번).

## 일본어 품질에 대한 고지

`docs/12-일본어-동사-전수-조사.md` 의 방침에 따라 **です・ます체로 통일**했지만 **원어민 검수를 받지 않았다.**

## 관련 문서

- [`../../notes/optimizing-java-2nd/08-클라우드-스택-구성요소.md`](../../notes/optimizing-java-2nd/08-클라우드-스택-구성요소.md) · [`09-클라우드-배포.md`](../../notes/optimizing-java-2nd/09-클라우드-배포.md) — *Optimizing Cloud Native Java* 2판의 컨테이너·K8s 장 요약
- [`../db-면접/README.md`](../db-면접/README.md) · [`../kafka-면접/README.md`](../kafka-면접/README.md) · [`../python-면접/README.md`](../python-면접/README.md) · [`../javascript-면접/README.md`](../javascript-면접/README.md)
- [`../../docs/02-정직한-고지.md`](../../docs/02-정직한-고지.md) §1-3 — 검증하지 못한 것
