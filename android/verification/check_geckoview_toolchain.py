#!/usr/bin/env python3
"""Check GeckoView 159 SDK/toolchain coordination, not a substitute for build."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
g=(r/'build.gradle.kts').read_text()
a=(r/'app/build.gradle.kts').read_text()
w=(r/'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'version "9.1.1"' in g
assert 'version "2.4.20"' in g and 'org.jetbrains.kotlin.plugin.compose' in g
assert 'id("org.jetbrains.kotlin.android") version "2.4.20" apply false' in g
assert 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20' in a
assert 'id("org.jetbrains.kotlin.android")' not in a
assert 'android.builtInKotlin=false' not in (r/'gradle.properties').read_text()
assert 'version = release(37)' in a and 'minorApiLevel = 0' in a and 'targetSdk = 35' in a and 'minSdk = 26' in a
assert 'versionCode = 10003' in a
assert 'geckoview:159.0.20261022150525' in a
assert 'gradle-9.3.1-bin.zip' in w
assert 'distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06' in w
assert 'kotlinOptions' not in a
print('GECKOVIEW_SDK37_AGP91_KOTLIN2420_CONTRACT=PASS (static)')

# The custom gradlew bootstrap must derive the version and checksum from properties,
# not hardcode an independent (potentially older) distribution.
bootstrap=(r/'gradlew').read_text()
assert 'RAW_URL=' in bootstrap and 'distributionUrl=' in bootstrap
assert 'distributionSha256Sum=' in bootstrap and 'BASH_REMATCH[1]' in bootstrap
assert 'VERSION=8.11.1' not in bootstrap
print('GRADLE_BOOTSTRAP_TOOLCHAIN_CONTRACT=PASS')
