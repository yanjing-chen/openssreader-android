# OpenSSReader Android 1.0.3

OpenSSReader Android is an independent Android project with an embedded Mozilla GeckoView bookstore, native PDZ downloading and decoding, local PDF reading and a responsive bookshelf. Package ID: `org.openssreader.android`; minimum Android API 26.

## 1.0.3

- Verified on-device: GeckoView native multiple tabs support SSLibrary search-result book-title/cover links that open a new browsing context. The original search results tab remains available, and `book://` links still enter the existing downloader.
- Phone/tablet adaptive bookshelf, adjustable high-resolution covers, eight book sorting choices, compact reader section menu, and tap-to-turn-page navigation are included.
- App Settings provides GitHub Release update checks, verified APK download, and About.

## Build

Requirements: Android SDK Platform 37.0, Build Tools 36.0.0, JDK 17; Gradle 9.3.1 is bootstrapped by the project. Node.js is not required.

```bash
bash ./BUILD_ANDROID_6800H.sh
```

## GitHub publication — from your own PC

Do not move the signing keystore or secrets into the repository. Use the existing git checkout (with `.git`) as the target and run these scripts from this unpacked source directory:

```bash
bash ./PUSH_FROM_6800H.sh /path/to/existing/openssreader-android
# After the main-branch Android APK CI is green:
bash ./RELEASE_1_0_3_6800H.sh /path/to/existing/openssreader-android
```

The tag `v1.0.3` triggers `.github/workflows/release.yml`, which creates an APK signed using the existing production signing secrets and includes the SHA-256 checksum. Existing release tags are never rewritten. Do not replace the keystore. Consult [RELEASE_GUIDE_CN.md](RELEASE_GUIDE_CN.md) for details and installation-signature cautions.
