#!/usr/bin/env bash
# Android 1.0.7 — user's original 1.0.3 download flow, with validated tabs/JSON.
# Never uninstall apps or clear data. Requires local Android SDK for build.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
echo "===== 1. 检查原始下载代码与现有标签/书架 ====="
python3 android/verification/check_bookstore_tabs_and_handoff.py
bash ./TEST_BOOKSTORE_6800H.sh

echo "===== 2. Gradle 构建与 Android 单元测试 ====="
bash ./BUILD_ANDROID_6800H.sh
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "找不到 APK：$APK" >&2; exit 1; }
ls -lh "$APK"
sha256sum "$APK"

echo "===== 3. ADB 已授权设备检测 ====="
if ! command -v adb >/dev/null; then
  echo '尚未找到 adb。APK 已成功构建；可稍后安装 adb 后运行安装命令。' >&2
  exit 2
fi
adb start-server
adb devices -l
COUNT="$(adb devices | awk '$2=="device"{n++} END{print n+0}')"
if [[ "$COUNT" != 1 ]]; then
  echo '当前没有恰好一台已授权设备；APK 已构建，不会卸载应用或清除数据。' >&2
  exit 3
fi

echo "===== 4. ADB push 传送构建 APK ====="
REMOTE='/data/local/tmp/openssreader-download-original-1.0.7-debug.apk'
adb push "$APK" "$REMOTE"

echo "===== 5. adb install -r 覆盖更新（保留应用数据） ====="
if ! adb install -r "$APK"; then
  echo '安装失败。可能是签名不一致；不得卸载原应用或清除数据。APK 已保存在构建目录。' >&2
  exit 4
fi

echo "===== 6. 启动 OpenSSReader ====="
adb shell monkey -p org.openssreader.android -c android.intent.category.LAUNCHER 1
adb shell dumpsys package org.openssreader.android | grep -m1 'versionName=' || true

echo '===== 完成：请只验收书目简介下载 → PDZ/PDF → 同名 JSON → 重启恢复 ====='
echo "APK：$APK"
