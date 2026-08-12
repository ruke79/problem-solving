#!/bin/bash
# 스트리밍 레플리카(DB-24)가 붙을 수 있도록 복제 접속을 허용한다.
# 공식 이미지는 replication 전용 pg_hba 항목을 만들어 주지 않는다.
set -e
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
