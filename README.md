# OpenSSReader Android 1.0.6

OpenSSReader Android is an independent Android project with an embedded Mozilla GeckoView bookstore, native PDZ downloading and decoding, local PDF reading and a responsive bookshelf. Package ID: `org.openssreader.android`; minimum Android API 26.

## 1.0.6 Candidate — durable per-book bookshelf JSON

Fixes the download→public PDZ→bookshelf JSON→UI→PDF conversion flow. The per-book
JSON now uses the exact public PDZ/PDF stem (e.g. `Title_Author_Year_SSID.json`),
is read back before a book enters the shelf, and receives the converted PDF's
reference before a transfer is marked complete. Existing UUID.json manifests
are read and migrated without scanning arbitrary PDZ/PDF files. Failure to write
JSON does not pretend a book was safely added, and downloaded PDZ bytes are kept.

The 12 PDZ compatibility changes from the 1.0.5 baseline remain intact.

## 1.0.5 baseline — PDZ parity with Linux

All 12 compatibility items are implemented or handled with bounded protections, including appendix-page ordering, optional metadata, transport envelope admission, CMYK JPEG, checksum-verified 1AH fallback, zero-padded segments, whitespace TOC lines, memory-adaptive resource ceilings, one-million-page structural plans, partly clipped composite photos, and final-only Android PDF renderer checks. **Android SDK build and device acceptance still required.** See [COMPATIBILITY_12_REPORT_CN.md](COMPATIBILITY_12_REPORT_CN.md).

## 1.0.6

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
bash ./RELEASE_1_0_6_6800H.sh /path/to/existing/openssreader-android
```

The tag `v1.0.6` triggers `.github/workflows/release.yml`, which creates an APK signed using the existing production signing secrets and includes the SHA-256 checksum. Existing release tags are never rewritten. Do not replace the keystore. Consult [RELEASE_GUIDE_CN.md](RELEASE_GUIDE_CN.md) for details and installation-signature cautions.

### 1.0.7 GeckoView storefront navigation

The home tab is always shown and tab captions follow the site's real document title. The GeckoView book:// handoff and institution PDZ transfer implementation are restored to the original user-supplied 1.0.3 baseline, without image-reader iframe bridging. See `DOWNLOAD_RESTORE_FROM_ORIGINAL_1_0_3_CN.md`.
