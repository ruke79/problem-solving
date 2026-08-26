#!/usr/bin/env bash
#
# EC2 인스턴스 위에서 검증 스위트를 돌린다. deploy-ec2 워크플로가 rsync 후 호출하고,
# 인스턴스에 직접 들어가 손으로 돌려도 된다.
#
#   ./scripts/ec2-run-verify.sh            # perfbook 14건 (PostgreSQL 만 필요 — 1GB 인스턴스용)
#   ./scripts/ec2-run-verify.sh full       # 전 모듈 109건 (Kafka·Redis·레플리카까지 — RAM 2GB 이상)
#
# 프리티어 1GB 에서 full 을 돌리면 Kafka 만으로 메모리가 바닥난다. 그래서 기본은 perfbook 이고,
# full 은 t3.small(2GB) 이상에서만 의미가 있다 — 워크플로가 그대로 안내한다.
set -euo pipefail

SUITE="${1:-perfbook}"
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# 1GB 인스턴스 배려: 데몬을 띄우지 않고, Gradle 자체 힙도 줄인다.
# 테스트 JVM 힙은 건드리지 않는다 — 케이스가 실제로 쓰는 메모리는 검증 조건의 일부다.
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx384m"

./scripts/random-ports.sh

if [ "$SUITE" = "full" ]; then
    docker compose up -d --wait
    ./gradlew test
else
    docker compose up -d --wait postgres
    ./gradlew :verify-labs-perfbook:test
fi

echo
echo "== 리포트 =="
for report in verify-labs-perfbook/build/reports/verification-perfbook.md \
              verify-labs/build/reports/verification.md \
              verify-labs-kafka/build/reports/verification-kafka.md; do
    [ -f "$report" ] && grep -m1 "합계" "$report" | sed "s|^|$report: |"
done
