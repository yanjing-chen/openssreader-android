#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$BASE/../app/src/main/java/org/openssreader/android/core"
TEMP="$(mktemp -d)"; trap 'rm -rf "$TEMP"' EXIT
kotlinc "$SRC"/*.kt "$BASE/Hh00A1Parity.kt" -J-Xmx768m -include-runtime -d "$TEMP/hh00.jar"
java -cp "$TEMP/hh00.jar" Hh00A1ParityKt "$BASE/fixtures/synthetic_a1c_jpeg.jpg"
