#!/usr/bin/env bash
# Playwright e2e: 테스트 키 생성, postgres와 가짜 구글 서버 컨테이너, e2e 프로필 앱 jar를 띄우고 테스트 뒤 정리한다.
# 키와 구글 값은 파일에 쓰지 않고 이 프로세스의 환경 변수로만 앱에 넘긴다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEB="$ROOT/web"
PORT=8081
MOCK_PORT=9090
LOG_DIR="$WEB/test-results"
APP_LOG="$LOG_DIR/app.log"
dc() { docker compose -f "$ROOT/compose.yaml" "$@"; }

APP_PID=""
POSTGRES_STARTED=0
STARTED=0

cleanup() {
  local code=$?
  trap - EXIT INT TERM
  [ "$STARTED" = 1 ] || exit "$code"
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  dc --profile e2e rm -sf mock-oauth2 >/dev/null || echo "mock-oauth2 정리 실패" >&2
  if [ "$POSTGRES_STARTED" = 1 ]; then dc stop postgres >/dev/null || echo "postgres 정리 실패" >&2; fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

fail() { echo "e2e: $*" >&2; exit 1; }

# 아무것도 띄우기 전에 준비물부터 확인한다
JAR="$(ls "$ROOT"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n 1 || true)"
[ -n "$JAR" ] || fail "앱 jar가 없습니다. 저장소 루트에서 ./gradlew bootJar 를 먼저 실행하세요."
for p in "$PORT" "$MOCK_PORT"; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then fail "포트 $p 를 이미 쓰고 있어 아무것도 띄우지 않았습니다."; fi
done
(cd "$WEB" && node -e "import('@playwright/test').then(m => process.exit(require('fs').existsSync(m.chromium.executablePath()) ? 0 : 1))") \
  || fail "Playwright 브라우저가 없습니다. cd web && npx playwright install chromium 을 먼저 실행하세요."

mkdir -p "$LOG_DIR"
STARTED=1

if dc ps --status running --services | grep -qx postgres; then
  echo "postgres는 이미 떠 있어 그대로 둡니다."
else
  dc up -d --wait postgres
  POSTGRES_STARTED=1
fi

# 실행마다 깨끗한 데이터베이스. 로컬 개발용 estate는 건드리지 않는다
psql_estate() { dc exec -T postgres psql -v ON_ERROR_STOP=1 -U estate -d estate -c "$1" >/dev/null; }
psql_estate 'DROP DATABASE IF EXISTS estate_e2e WITH (FORCE)'
psql_estate 'CREATE DATABASE estate_e2e'

dc --profile e2e up -d mock-oauth2
for _ in $(seq 60); do
  curl -fs -o /dev/null "http://localhost:$MOCK_PORT/google/.well-known/openid-configuration" && break
  sleep 1
done
curl -fs -o /dev/null "http://localhost:$MOCK_PORT/google/.well-known/openid-configuration" || fail "가짜 구글 서버가 60초 안에 뜨지 않았습니다."

ESTATE_COLUMN_KEYS="1:$(openssl rand -base64 32)" \
ESTATE_COLUMN_KEY_ACTIVE=1 \
ESTATE_LOGIN_COOKIE_KEY="$(openssl rand -base64 32)" \
GOOGLE_CLIENT_ID=e2e-client-id \
GOOGLE_CLIENT_SECRET=e2e-client-secret \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/estate_e2e \
SPRING_DATASOURCE_USERNAME=estate \
SPRING_DATASOURCE_PASSWORD=estate \
  java -jar "$JAR" --spring.profiles.active=e2e --server.port="$PORT" >"$APP_LOG" 2>&1 &
APP_PID=$!

ready=0
for _ in $(seq 60); do
  kill -0 "$APP_PID" 2>/dev/null || break
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/v1/me" || true)" = 401 ]; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "앱이 60초 안에 뜨지 않았습니다. 로그 끝부분($APP_LOG):" >&2
  tail -n 40 "$APP_LOG" >&2
  exit 1
fi

cd "$WEB"
E2E_BASE_URL="http://localhost:$PORT" npx playwright test
