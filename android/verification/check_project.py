#!/usr/bin/env python3
"""Offline structural checks; NOT an Android build or emulator test."""
from pathlib import Path
import hashlib, re, sys
root=Path(__file__).resolve().parents[2]
android=root/'android'
files=list((android/'app/src/main/java').rglob('*.kt'))
assert len(files) >= 8
all_code='\n'.join(p.read_text(encoding='utf-8') for p in files)
for required in ('PdzCatalogReader','PdzContentsReader','BookMetadataReader',
                 'PdzDirectJpegConverter','CompositePhotoPage','addCompositePage','PdfRenderer','LosslessJpegPdf','Hh04JpegDecoder',
                 'BookNames.pdzName','BwPdgDecoder','OuterPageRecovery','PdzAuthorization','Hh00EmbeddedImage','LinearProgressIndicator','takeIf','OpenDocument',
                 'CreateDocument','UnsupportedPdzPages','GridCells.Fixed'):
    assert required in all_code, required
for forbidden in ('Gtk.PrintOperation','print_worker','com.google.firebase','android.permission.MANAGE_EXTERNAL_STORAGE',
                  'READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE'):
    assert forbidden not in all_code, forbidden
manifest=(android/'app/src/main/AndroidManifest.xml').read_text()
assert 'usesCleartextTraffic="false"' in manifest
assert 'INTERNET' in manifest
core=list((android/'app/src/main/java/org/openssreader/android/core').glob('*.kt'))
assert len(core) >= 15, 'Android PDZ core files must be included'
for file in (android/'app/src/test/resources').glob('*.pdz'):
    assert file.stat().st_size < 100_000, file
# Device acceptance lives in androidTest and is NOT equivalent to a JVM stub.
device_test = android/'app/src/androidTest/java/org/openssreader/android/PdzDeviceAcceptanceTest.kt'
assert device_test.is_file()
assert 'PdfRenderer' in device_test.read_text() and 'PDDocument.load' in device_test.read_text()
assert all((android/'app/src/androidTest/assets'/name).is_file() for name in (
    'synthetic_oneah_segmented_type0.pdz', 'synthetic_hh04_mixed.pdz', 'synthetic_composite_both.pdz'))
assert 'assembleDebugAndroidTest' in (root/'BUILD_ANDROID_6800H.sh').read_text()
assert (android/'gradlew').stat().st_mode & 0o111
assert 'distributionSha256Sum=' in (android/'gradle/wrapper/gradle-wrapper.properties').read_text()
print(f'PASS Android skeleton: {len(files)} Kotlin files, privacy/permissions, limited synthetic fixtures, source presence')
print(f'PASS Android PDZ core retained: {len(core)} Kotlin modules')
