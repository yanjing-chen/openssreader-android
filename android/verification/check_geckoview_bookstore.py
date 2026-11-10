#!/usr/bin/env python3
"""Static guard for GeckoView storefront; not an APK, web login or book:// acceptance test."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
source=(r/'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
g=(r/'app/src/main/java/org/openssreader/android/GeckoBookstore.kt').read_text()
d=(r/'app/build.gradle.kts').read_text()
settings=(r/'settings.gradle.kts').read_text()
assert 'versionCode = 10000' in d
assert 'org.mozilla.geckoview:geckoview:159.0.20261022150525' in d
assert 'https://maven.mozilla.org/maven2/' in settings
assert 'androidx.webkit:webkit:' not in d
assert 'WebView' not in source and 'WebView(' not in g
assert 'GeckoBookstorePage(state = bookstoreSession' in source
for hook in ('setNavigationDelegate', 'onLoadRequest', 'onSubframeLoadRequest',
             'onNewSession', 'onLoadError', 'onBookLink(url)',
             'setProgressDelegate', 'setContentDelegate', 'setPromptDelegate',
             'VIEWPORT_MODE_DESKTOP', 'VIEWPORT_MODE_MOBILE',
             'USER_AGENT_MODE_DESKTOP', 'USER_AGENT_MODE_MOBILE'):
    assert hook in g,hook
for anti in ('evaluateJavascript', 'document.querySelector', 'MutationObserver',
             'setInitialScale', 'setUserAgentMetadata', 'scrollIntoView',
             'bookstore_reveal_login.js'):
    assert anti not in source+g,anti
assert not list((r/'app/src/main/assets').glob('bookstore_*.js'))
# Tags occupy full sidebar width, with the same component for built-in and custom labels.
assert 'SidebarCategoryRow(' in source
assert source.count('SidebarCategoryRow(') >= 3
s=source.split('private fun LibrarySidebar(')[1].split('private fun SidebarCategoryRow(')[0]
assert 'onLongClick = { actionsVisible = true }' in s
assert 'Text("重命名")' in s and 'Text("删除标签")' in s
assert 'books.count { it.id in label.bookIds }' in s
assert 'IconButton(modifier = Modifier.size(38.dp)' not in s
assert 'model.deleteLabel(id)' in source and 'model.renameLabel(editId, labelDialogText)' in source
print('GECKOVIEW_BOOKSTORE_AND_LABELS_CONTRACT=PASS (static; device verification required)')
