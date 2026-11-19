#!/usr/bin/env bash
# 로컬 Garage(compose.yaml의 garage 서비스)를 처음 쓸 수 있게 준비한다. 여러 번 실행해도 안전하다.
# 레이아웃 지정, 버킷 둘(원본 응답, 사진) 생성, collector 키 생성과 원본 응답 버킷 읽기/쓰기 권한 부여를 한다.
set -euo pipefail

RAW_BUCKET="${ESTATE_RAW_S3_BUCKET:-estate-raw}"
PHOTO_BUCKET="${ESTATE_PHOTO_S3_BUCKET:-estate-photos}"
KEY_NAME="collector"

g() { docker compose exec -T garage /garage "$@" 2>/dev/null; }

docker compose up -d garage

# 컨테이너가 명령을 받을 때까지 기다린다
for _ in $(seq 1 30); do
    g status >/dev/null && break
    sleep 1
done

# 노드에 역할이 없을 때만 레이아웃을 지정하고 적용한다
if g status | grep -q "NO ROLE ASSIGNED"; then
    node_id="$(g status | awk '/^[0-9a-f]{16} /{print $1; exit}')"
    g layout assign -z local -c 1GB "$node_id" >/dev/null
    g layout apply --version 1 >/dev/null
fi

for bucket in "$RAW_BUCKET" "$PHOTO_BUCKET"; do
    g bucket info "$bucket" >/dev/null || g bucket create "$bucket" >/dev/null
done

g key info "$KEY_NAME" >/dev/null || g key create "$KEY_NAME" >/dev/null
# 원본 응답 버킷에만 읽기와 쓰기를 준다. 사진 버킷에는 권한을 주지 않는다
g bucket allow "$RAW_BUCKET" --key "$KEY_NAME" --read --write >/dev/null

echo "준비가 끝났습니다. 아래 두 줄을 .env에 넣으세요."
echo "ESTATE_RAW_S3_ACCESS_KEY=$(g key info "$KEY_NAME" --key-id)"
echo "ESTATE_RAW_S3_SECRET_KEY=$(g key info "$KEY_NAME" --secret-key)"
