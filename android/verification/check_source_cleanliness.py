#!/usr/bin/env python3
"""Android-only release repository does not ship historical experiments."""
from pathlib import Path
r=Path(__file__).resolve().parents[2]
a=r/'android'
assert (r/'BUILD_ANDROID_6800H.sh').is_file()
assert (a/'app/src/main/java/org/openssreader/android/GeckoBookstore.kt').is_file()
assert not list((a/'app/src/main/assets').glob('bookstore_*.js'))
assert not any(r.glob('BUILD_GECKOVIEW_RC1*.sh'))
assert not (r/'openssreader').exists(), 'Android repo must exclude Linux application'
print('ANDROID_SOURCE_CLEANLINESS=PASS')
