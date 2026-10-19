#!/bin/bash
# 사용법: install.sh [저장소 루트]
# 저장소 루트의 기본값은 이 스크립트 위치에서 계산한다(워크트리 안이면 그 위의 원래 체크아웃).
set -eu

LABEL="com.real-estate.data-probe"
# launchd는 처음 띄운 실행 파일을 기준으로 폴더 접근 권한을 본다. /usr/bin/python3는 껍데기라
# 실제로 도는 실행 파일(Python.app 안의 Python)의 경로를 알아내 그것을 등록한다.
PYTHON="$(/usr/bin/python3 - <<'PY'
import ctypes, ctypes.util
libc = ctypes.CDLL(ctypes.util.find_library("c"))
buf = ctypes.create_string_buffer(4096)
size = ctypes.c_uint32(4096)
libc._NSGetExecutablePath(buf, ctypes.byref(size))
print(buf.value.decode())
PY
)"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_REPO="$(cd "$HERE/../../.." && pwd)"
if [ "$(basename "$(dirname "$DEFAULT_REPO")")" = ".worktrees" ]; then
  DEFAULT_REPO="$(cd "$DEFAULT_REPO/../.." && pwd)"
fi
REPO="${1:-$DEFAULT_REPO}"
TEMPLATE="$HERE/$LABEL.plist.template"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

mkdir -p "$HOME/Library/LaunchAgents" "$REPO/.private/data/logs"
sed -e "s|__PYTHON__|$PYTHON|g" -e "s|__REPO__|$REPO|g" "$TEMPLATE" > "$PLIST"

# 이미 올라가 있으면 내린 뒤 다시 올린다.
if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
    launchctl bootout "$DOMAIN/$LABEL"
fi
launchctl bootstrap "$DOMAIN" "$PLIST"
echo "등록했다: $PLIST"
echo "실행 파일: $PYTHON"
