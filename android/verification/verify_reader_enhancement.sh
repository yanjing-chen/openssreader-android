#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/org/openssreader/android/ReaderImageEnhancer.kt"
TEST="$ROOT/verification/enhancement/ReaderEnhancementParity.kt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$SRC" "$TEST" -include-runtime -d "$TMP/enhancement.jar"
java -jar "$TMP/enhancement.jar"
python3 "$ROOT/verification/check_reader_enhancement_ui.py"
