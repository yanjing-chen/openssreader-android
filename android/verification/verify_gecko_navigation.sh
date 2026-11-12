#!/usr/bin/env bash
set -Eeuo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
command -v kotlinc >/dev/null || { echo '缺少 kotlinc，跳过 JVM 导航规则独立编译（CI Android JUnit 仍会编译）。'; exit 0; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc \
  "$ROOT/app/src/main/java/org/openssreader/android/BookstoreNavigationPolicy.kt" \
  "$HERE/BookstoreNavigationParity.kt" \
  -include-runtime -d "$TMP/navigation.jar"
java -jar "$TMP/navigation.jar"
