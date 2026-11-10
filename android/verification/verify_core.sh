#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../.." && pwd)"
SRC="$PROJECT/android/app/src/main/java/org/openssreader/android/core"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$SRC"/*.kt "$BASE/CoreParity.kt" -include-runtime -d "$TMP/core-parity.jar"
java -jar "$TMP/core-parity.jar" "$BASE/fixtures/synthetic_direct_jpeg.pdz"
echo 'PASS Android Kotlin catalog/meta/toc against synthetic fixture'
