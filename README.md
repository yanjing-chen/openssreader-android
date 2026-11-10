# OpenSSReader Android

Native Android PDZ bookshelf and Firefox GeckoView integrated bookstore. This repository is the standalone Android application, not the Linux AppImage project.

**Version:** 1.0.0 · **Package:** `org.openssreader.android` · **Minimum Android:** API 26.

## Build (Ubuntu 26.04, JDK 17)

Install Android SDK Platform 37.0 and Build Tools 36.0.0:

```bash
android sdk install platforms/android-37.0 build-tools/36.0.0
bash ./BUILD_ANDROID_6800H.sh
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (developer debug build, **not** the published release signing certificate).

## CI and release

A push or pull request builds and tests the debug APK using GitHub Actions. Pushing tag `v1.0.0` builds a **release-signed** APK and publishes it in GitHub Releases. The release signing keystore is required and kept only in GitHub Actions secrets; never commit it.

Read [RELEASE_GUIDE_CN.md](RELEASE_GUIDE_CN.md) before publishing and migrating from an older debug APK. The installed-app update checker uses the public GitHub Releases API and checks SHA-256 before handing the APK to Android's system package installer.

The OpenSSReader app only downloads books through legitimate institution web authorization and preserves the existing `book://` download queue. No institution credentials are recorded by the app.
