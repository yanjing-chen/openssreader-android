#!/usr/bin/env bash
# Test lightweight storefront navigation and bookshelf integrity without Android SDK.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT/android"
python3 verification/check_bookstore_tabs_and_handoff.py
python3 verification/check_gecko_new_window.py
python3 verification/check_geckoview_bookstore.py
python3 verification/check_bookshelf_persistence.py
python3 verification/check_bookshelf_integrity.py
python3 verification/check_release_current.py
bash verification/verify_gecko_navigation.sh
printf '\nPASS: storefront + bookshelf offline checks. APK build and real device tests are separate.\n'
