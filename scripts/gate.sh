#!/usr/bin/env bash
# hc-bridge 门禁（与 docs/TESTING.md 一一对应）。
#
#   ./tools/gate.sh             # 全量：JVM 单测 + lint + debug/release 构建 + 模拟器仪器测试
#   ./tools/gate.sh --fast      # 只跑 JVM 单测 + lint（改纯逻辑时够用）
#   ./tools/gate.sh --no-device # 跳过仪器测试（没有可用设备时）
#   ANDROID_SERIAL=emulator-5556 ./tools/gate.sh   # 指定设备
#
# 退出码非 0 = 门禁不过，不允许装/发。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

FAST=0
DEVICE=1
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1; DEVICE=0 ;;
    --no-device) DEVICE=0 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg"; exit 2 ;;
  esac
done

GRADLE=${GRADLE:-./gradlew}
FAILED=()
STEP=0

step() {
  STEP=$((STEP + 1))
  echo
  echo "== [$STEP] $* =="
}

run() {
  local name="$1"; shift
  step "$name"
  if "$@"; then
    echo "-- OK: $name"
  else
    echo "-- FAIL: $name"
    FAILED+=("$name")
  fi
}

run "JVM 单测（logic/ 纯函数）" $GRADLE :app:testDebugUnitTest
run "lint（abortOnError=true）" $GRADLE :app:lintDebug

if [ "$FAST" -eq 0 ]; then
  run "assembleDebug" $GRADLE :app:assembleDebug
  run "assembleRelease" $GRADLE :app:assembleRelease
fi

if [ "$DEVICE" -eq 1 ]; then
  SERIAL=${ANDROID_SERIAL:-}
  if [ -z "$SERIAL" ]; then
    # 默认用模拟器（真机留给视觉验收；见 docs/TESTING.md）
    SERIAL=$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}')
  fi
  if [ -z "$SERIAL" ]; then
    echo
    echo "-- SKIP: 仪器测试（没有在线模拟器；用 ANDROID_SERIAL=<serial> 指定，或 --no-device 跳过）"
    FAILED+=("connectedDebugAndroidTest(无设备)")
  else
    echo
    echo "== [$((STEP + 1))] 仪器测试 @ $SERIAL =="
    STEP=$((STEP + 1))
    if ANDROID_SERIAL="$SERIAL" $GRADLE :app:connectedDebugAndroidTest; then
      echo "-- OK: 仪器测试 @ $SERIAL"
    else
      echo "-- FAIL: 仪器测试 @ $SERIAL"
      FAILED+=("connectedDebugAndroidTest@$SERIAL")
    fi
  fi
fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "GATE: PASS"
  exit 0
fi
echo "GATE: FAIL -> ${FAILED[*]}"
exit 1
