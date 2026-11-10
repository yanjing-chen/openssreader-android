#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT/android"
python3 verification/check_source_cleanliness.py
python3 verification/check_geckoview_toolchain.py
python3 verification/check_geckoview_bookstore.py
python3 verification/check_bookshelf_integrity.py
python3 verification/check_release_1_0.py
python3 verification/check_book_manifest.py .
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then
  SDK="$(sed -n 's/^sdk.dir=//p' local.properties | head -n1)"
fi
SDK="${SDK:-$HOME/Android/Sdk}"
[[ -f "$SDK/platforms/android-37.0/android.jar" ]] || { echo "Missing Android 37.0 platform: $SDK" >&2; exit 3; }
export ANDROID_HOME="$SDK"
LOG="$ROOT/android/build-android-1.0.log"
set +e
bash ./gradlew --version 2>&1 | tee "$LOG"
check_status=${PIPESTATUS[0]}
set -e
[[ $check_status -eq 0 ]] && grep -q '^Gradle 9\.3\.1$' "$LOG" || { echo 'Wrong Gradle version' >&2; exit 4; }
set +e
bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest 2>&1 | tee -a "$LOG"
status=${PIPESTATUS[0]}
set -e
if (( status != 0 )); then echo "Build failed: $LOG" >&2; exit "$status"; fi
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
echo "BUILT: $APK"
