#!/usr/bin/env bash
# Source-level contracts only. Run real Gradle compilation on Ubuntu 6800H.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
r=Path(sys.argv[1])
src=r/'app/src/main/java/org/openssreader/android'
ui=(src/'MainActivity.kt').read_text()
vm=(src/'OsrViewModel.kt').read_text()
storage=(src/'PublicBookStorage.kt').read_text()
labels=(src/'ShelfLabels.kt').read_text()
manifest=(r/'app/src/main/AndroidManifest.xml').read_text()
build=(r/'app/build.gradle.kts').read_text()
assert 'val availableToc = toc' in ui and 'items(availableToc)' in ui
assert 'val availableChapters = chapters' in ui and 'items(availableChapters)' in ui
assert 'items(toc)' not in ui and 'items(chapters)' not in ui
assert 'SidebarCategoryRow(' in ui and 'LibrarySidebar(' in ui
for category in ['本地书架', '最近阅读', '我的最爱', '正在下载', '我的标签']:
    assert category in ui,category
assert all(k in vm for k in ['fun createLabel(', 'fun renameLabel(', 'fun deleteLabel(', 'fun toggleFavorite(', 'fun toggleLabelBook('])
assert 'labelsStore.saveLabels' in vm and 'getSharedPreferences("shelf-labels-v1"' in labels
assert 'rootStorageGranted' in vm and 'Environment.isExternalStorageManager()' in storage
assert 'rootDirectory(kind)' in storage and 'publishToRoot(kind' in storage
assert 'root/Downloads' not in storage
assert 'android.permission.MANAGE_EXTERNAL_STORAGE' in manifest
assert 'android:icon="@mipmap/ic_launcher"' in manifest
assert int(__import__('re').search(r'versionCode\s*=\s*(\d+)',build).group(1)) >= 11
assert (r/'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png').is_file()
assert 'model::acceptBookLink' in ui and 'OutlinedTextField(link' not in ui
print('PASS Fix2 source contracts: delegated list, sidebar, custom labels, root storage, launcher icon, browser download')
PY
