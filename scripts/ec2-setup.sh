#!/usr/bin/env bash
#
# EC2 프리티어 인스턴스 1회 초기 설정 (Amazon Linux 2023 기준).
# 인스턴스에 SSH 로 들어가 이 스크립트를 한 번 실행하면, 이후 배포는
# GitHub Actions 의 deploy-ec2 워크플로가 rsync + ssh 로 처리한다.
#
#   scp -i <키.pem> scripts/ec2-setup.sh ec2-user@<호스트>:~
#   ssh -i <키.pem> ec2-user@<호스트> 'bash ec2-setup.sh'
#
# 왜 스왑을 만드나
#   프리티어(t2.micro/t3.micro)는 RAM 1GB 다. Gradle 컴파일 + 테스트 JVM + PostgreSQL 을
#   같이 올리면 1GB 로는 OOM 킬이 난다. 스왑 2GB 를 붙이면 느려질지언정 완주한다 —
#   느린 것은 이 랩에서 문제가 아니다. 성능 수치는 어차피 장비 기준으로만 읽는다(docs/02 §1-4).
set -euo pipefail

echo "== 패키지 설치 (Java 17 / Docker / git / rsync)"
sudo dnf install -y java-17-amazon-corretto-headless docker git rsync

echo "== Docker 기동 + ec2-user 권한"
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user

echo "== Docker Compose v2 플러그인"
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-$(uname -m)" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

if ! swapon --show | grep -q /swapfile; then
    echo "== 스왑 2GB 생성 (1GB RAM 에서 Gradle + JVM + PostgreSQL 완주용)"
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "== 완료. 그룹 반영을 위해 재접속한 뒤 docker 명령이 sudo 없이 되는지 확인:"
echo "   docker info >/dev/null && echo OK"
