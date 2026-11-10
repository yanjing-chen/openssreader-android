#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/../app/src/main/java/org/openssreader/android/core"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
kotlinc "$CORE/PdzCatalog.kt" "$CORE/PdzMixedPagePlan.kt" "$CORE/PdzToc.kt" \
  "$CORE/BookMetadata.kt" "$CORE/SafeXml.kt" \
  "$BASE/MixedPagePlanParity.kt" -include-runtime -d "$TMP/hybrid.jar"
java -jar "$TMP/hybrid.jar" \
  "$BASE/fixtures/synthetic_hybrid_cover_and_segments.pdz" \
  "$BASE/fixtures/synthetic_hybrid_interleaved_body.pdz"
