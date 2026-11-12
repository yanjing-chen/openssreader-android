#!/usr/bin/env bash
# OpenSSReader Android scanning enhancement E3 (debug): Ryzen 6800H Ubuntu.
# Keeps app data; NEVER uninstalls or clears an existing installation.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
echo '===== E3 1/5: 本地增强算法及稳定版源代码检查 ====='
if command -v kotlinc >/dev/null 2>&1; then
  bash android/verification/verify_reader_enhancement.sh
else
  echo 'kotlinc 未安装：跳过可选独立 JVM 合成测试，Gradle 仍会编译并运行对应单元测试。'
  python3 android/verification/check_reader_enhancement_ui.py
fi

echo '===== E3 2/5: Android 37.0 / Gradle debug APK 构建与单元测试 ====='
bash ./BUILD_ANDROID_6800H.sh
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
ls -lh "$APK"
sha256sum "$APK"
if [[ "${BUILD_ONLY:-0}" == 1 ]]; then
  echo "BUILD_ONLY=1：已停止在构建阶段，APK: $APK"
  exit 0
fi

echo '===== E3 3/5: ADB 设备检查 ====='
command -v adb >/dev/null 2>&1 || { echo 'adb 未安装：请先安装 android-tools-adb；APK 已构建。'; exit 2; }
adb start-server
adb devices -l
SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#DEVICES[@]} -ne 1 ]]; then
    echo '不是恰好一台已授权 Android 设备。若连接多台，请设置 ADB_SERIAL=设备序列号 后重试。' >&2
    exit 3
  fi
  SERIAL="${DEVICES[0]}"
fi
if ! adb -s "$SERIAL" get-state | grep -qx device; then
  echo "目标设备 $SERIAL 未授权或不可用；不会执行安装" >&2
  exit 3
fi

echo '===== E3 4/5: 安全传输并覆盖安装（保留应用数据） ====='
REMOTE='/data/local/tmp/openssreader-enhance-e3-debug.apk'
adb -s "$SERIAL" push "$APK" "$REMOTE"
# install -r intentionally uses local APK, preserving app data and package identity.
if ! adb -s "$SERIAL" install -r "$APK"; then
  echo '安装失败：可能由于已安装的正式签名版与 debug 签名不一致。' >&2
  echo '不要 adb uninstall / pm clear / 卸载重装！请改用兼容签名 APK 或独立测试设备。' >&2
  exit 4
fi

echo '===== E3 5/5: 启动并确认版本 ====='
adb -s "$SERIAL" shell monkey -p org.openssreader.android -c android.intent.category.LAUNCHER 1
adb -s "$SERIAL" shell dumpsys package org.openssreader.android | grep -m1 'versionName=' || true
printf '增强 E3 调试包：%s\n设备：%s\n' "$APK" "$SERIAL"
echo '请检查顶部单页/双页/连续点选、原图/增强切换、左右镜像及差异提示补选第二基准页。'
