#!/usr/bin/env bash
# Offline checks only; NOT a substitute for an Android SDK build or real device tests.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="$ROOT/app/src/main/java/org/openssreader/android"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
ui=(root/'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
vm=(root/'app/src/main/java/org/openssreader/android/OsrViewModel.kt').read_text()
parser=(root/'app/src/main/java/org/openssreader/android/core/SafeXml.kt').read_text()
store=(root/'app/src/main/java/org/openssreader/android/PublicBookStorage.kt').read_text()
assert 'OutlinedTextField(link' not in ui and '添加下载' not in ui
assert 'Intent.ACTION_VIEW' in ui and 'model.acceptBookLink' in ui
assert 'fun download(url: String)' not in vm
assert 'OpenDocumentTree()' in ui and 'takePersistableUriPermission' in ui
assert 'OpenSSReader/pdz' in ui and 'bookshelf' in ui and 'OpenSSReader/${kind.subdir}' in store
assert 'Environment.isExternalStorageManager()' in store and 'publishToRoot' in store
assert 'MANAGE_EXTERNAL_STORAGE' in (root/'app/src/main/AndroidManifest.xml').read_text()
assert 'DocumentBuilderFactory' in parser and 'setEntityResolver' in parser
assert 'setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)' not in (root/'app/src/main/java/org/openssreader/android/core/BookMetadata.kt').read_text()
print('PASS UI only browser book://; SAF custom folders; root public storage permission and safe XML wiring')
PY
kotlinc "$SRC/core/"*.kt "$ROOT/verification/ImportXmlRegression.kt" -include-runtime -d "$WORK/xml-regression.jar"
java -jar "$WORK/xml-regression.jar" "$ROOT/verification/fixtures/synthetic_direct_jpeg.pdz"
bash "$ROOT/verification/verify_book_scheme.sh"
echo 'PASS offline import/storage/browser integration checks (Android SDK build still required)'
