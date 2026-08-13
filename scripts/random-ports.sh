#!/usr/bin/env bash
#
# 인프라가 쓸 호스트 포트를 빈 것으로 골라 `.env` 에 적는다.
# `docker compose` 는 프로젝트 루트의 `.env` 를 자동으로 읽으므로, 이 스크립트를 한 번 돌린 뒤
# 평소대로 `docker compose up -d` 하면 된다.
#
#   ./scripts/random-ports.sh          # .env 가 없으면 만들고, 있으면 그대로 둔다
#   ./scripts/random-ports.sh --force  # 무조건 새 포트로 다시 고른다
#
# 왜 필요한가
#   5432(PostgreSQL) · 9092(Kafka) · 6379(Redis) 는 개발 장비에 이미 떠 있는 경우가 흔하다.
#   하나라도 충돌하면 `docker compose up` 이 실패해 인프라 전체가 안 뜬다.
#
#   PostgreSQL·Redis 는 포트를 0 으로 두면 도커가 알아서 빈 포트를 배정하므로 이 스크립트가 없어도 된다.
#   Kafka 는 다르다 — 클라이언트가 처음 접속한 뒤 브로커가 알려 주는 advertised listener 주소로
#   '다시' 접속하므로, 그 주소를 브로커 기동 시점에 알고 있어야 한다.
#   그래서 포트를 우리가 먼저 골라 넘겨야 한다. 이 스크립트가 하는 일이 그것이다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"

if [[ -f "$ENV_FILE" && "${1:-}" != "--force" ]]; then
    echo "이미 $ENV_FILE 가 있어 그대로 쓴다 (다시 고르려면 --force):"
    cat "$ENV_FILE"
    exit 0
fi

# 커널에게 빈 포트를 물어본다 — 직접 난수를 뽑고 충돌을 확인하는 것보다 정확하다.
# 바인딩을 닫은 직후라 이론상 경합이 있지만, 곧바로 compose 가 잡으므로 실질적인 문제는 없다.
pick_free_port() {
    python3 - <<'PY'
import socket
with socket.socket() as s:
    s.bind(("", 0))
    print(s.getsockname()[1])
PY
}

DB_PORT="$(pick_free_port)"
REPLICA_PORT="$(pick_free_port)"
KAFKA_PORT="$(pick_free_port)"
REDIS_PORT="$(pick_free_port)"

cat > "$ENV_FILE" <<EOF
# scripts/random-ports.sh 가 생성했다. docker compose 가 자동으로 읽는다.
# 특정 포트로 고정하고 싶으면 이 파일을 직접 고치면 된다.
DB_PORT=$DB_PORT
REPLICA_PORT=$REPLICA_PORT
KAFKA_PORT=$KAFKA_PORT
REDIS_PORT=$REDIS_PORT
EOF

echo "빈 포트를 골라 $ENV_FILE 에 적었다:"
cat "$ENV_FILE"
echo
echo "이제 그대로 진행하면 된다:  docker compose up -d && ./gradlew test"
