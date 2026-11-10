#!/usr/bin/env python3
from pathlib import Path
r=Path(__file__).resolve().parents[1]
a=(r/'app/build.gradle.kts').read_text()
m=(r/'app/src/main/AndroidManifest.xml').read_text()
u=(r/'app/src/main/java/org/openssreader/android/ReleaseUpdates.kt').read_text()
ui=(r/'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
g=(r/'app/src/main/java/org/openssreader/android/GeckoBookstore.kt').read_text()
assert 'versionCode = 10001' in a and 'versionName = "1.0.1"' in a
assert 'org.openssreader.android' in a
assert 'OSR_SIGNING_STORE_FILE' in a and 'signingConfigs' in a
assert 'REQUEST_INSTALL_PACKAGES' in m and 'FileProvider' in m
assert 'applicationId}.updates' in m
assert 'releases/latest' in u and 'newer(tag, version(context))' in u
assert 'SHA-256' in u and 'getUriForFile' in u
assert 'ReleaseUpdateSettings()' in ui and 'AboutAppSettings()' in ui
for obsolete in ('超星 PDZ 图书阅读器 · Android 预览版', '超星书城 · Firefox 引擎', '功能范围',
    'bookshelf 文件夹为每本图书保存一个独立 JSON 小文件',
    '在『书城』中登录并点击下载图书'):
    assert obsolete not in ui+g, obsolete
print('ANDROID_RELEASE_1_0_UPDATER_CONTRACT=PASS')
