#!/usr/bin/env bash
# Offline JVM-only check; requires Kotlin compiler and Java, but not Android SDK.
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/../app/src/main/java/org/openssreader/android/core"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$CORE/PdzCatalog.kt" "$CORE/PdzCompatibilityLimits.kt" \
  "$CORE/PdzMixedPagePlan.kt" "$BASE/AppendixPagePlanParity.kt" \
  -include-runtime -d "$TMP/appendix-test.jar"
java -jar "$TMP/appendix-test.jar"
