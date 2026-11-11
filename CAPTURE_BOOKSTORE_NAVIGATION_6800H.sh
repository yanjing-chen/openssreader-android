#!/usr/bin/env bash
# Capture only OpenSSReader's privacy-reduced GeckoView navigation diagnostics.
set -Eeuo pipefail
if ! command -v adb >/dev/null 2>&1; then
    echo '缺少 adb，请先安装 Android platform-tools。' >&2
    exit 2
fi
adb devices -l
printf '\n确保已连接且授权一台测试设备。\n'
if [[ -n "${ADB_SERIAL:-}" ]]; then
    ADB=(adb -s "$ADB_SERIAL")
else
    ADB=(adb)
fi
"${ADB[@]}" logcat -c
OUT="${1:-bookstore-navigation-$(date +%Y%m%d-%H%M%S).log}"
printf '现在在平板上打开书城→搜索→点击书名或封面，完成后按 Ctrl+C。\n日志: %s\n' "$OUT"
"${ADB[@]}" logcat -v time -s 'OSR_GECKO_BOOKSTORE:I' '*:S' | tee "$OUT"
