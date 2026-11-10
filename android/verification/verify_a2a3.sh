#!/usr/bin/env bash
# Offline policy + naming parity. Does NOT substitute for Android SDK or device tests.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_ROOT="$ROOT/app/src/main/java/org/openssreader/android"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$KOTLIN_ROOT/DownloadPolicy.kt" "$ROOT/verification/DownloadPolicyCheck.kt" -include-runtime -d "$TMP/policy.jar"
java -jar "$TMP/policy.jar"
kotlinc "$KOTLIN_ROOT/core/"*.kt "$ROOT/verification/A2A3NamingCheck.kt" -include-runtime -d "$TMP/names.jar"
java -jar "$TMP/names.jar"
python3 "$ROOT/verification/check_project.py"
