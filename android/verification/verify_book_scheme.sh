#!/usr/bin/env bash
# Offline cross-language parity; the fixtures use fake a/ua tokens. No network request.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/app/src/main/java/org/openssreader/android"
kotlinc "$SRC/core/SafeXml.kt" "$SRC/InstitutionBookProtocol.kt" "$SRC/InstitutionDeviceBinding.kt" \
    "$ROOT/verification/BookSchemeParity.kt" -include-runtime -d "$TMP/protocol.jar"
java -jar "$TMP/protocol.jar" \
    "$ROOT/verification/fixtures/book_scheme_request_synthetic.txt" \
    "$ROOT/verification/fixtures/book_grant_mode1_synthetic.hex"
kotlinc "$SRC/core/SafeXml.kt" "$SRC/InstitutionBookProtocol.kt" "$SRC/InstitutionDeviceBinding.kt" \
    "$SRC/InstitutionPdzTransfer.kt" "$SRC/DownloadPolicy.kt" \
    "$SRC/core/PdzCatalog.kt" -d "$TMP/transfer.jar"
python3 "$ROOT/verification/check_book_manifest.py" "$ROOT"
echo 'BOOK_SCHEME_OFFLINE_PASS (NOT an Android build or live institution download)'
