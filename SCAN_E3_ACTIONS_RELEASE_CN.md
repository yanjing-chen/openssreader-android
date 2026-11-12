# E3-Fix1：只针对 feature 分支的 GitHub Actions 与 Pre-release

仓库：`yanjing-chen/openssreader-android`  
分支：`feature/scan-enhancement-e3-fix1`  
独立首个标签：`scan-e3-fix1-rc1`

**本方案严格不修改 `main`，不产生正式 `v*` 标签，不发布 Latest，不重新生成签名密钥。**

## 第一步：从 Ryzen 6800H 本地推送配置

将完整源码 ZIP 解压到一个新目录并进入根目录：

```bash
cd ~/下载
unzip -q OpenSSReader-Android-1.0.7-ScanEnhance-E3-Fix1-Actions-Release-FullSource.zip -d OSR-E3-Actions-Release
cd OSR-E3-Actions-Release/OpenSSReader-Android-ScanEnhance-E3
bash ./PUSH_SCAN_E3_ACTIONS_6800H.sh
```

该脚本验证 `gh` 登录为 `yanjing-chen` 且仓库默认分支为 `main`。如 E3 分支已存在，只添加/更新分支专属工作流、预发布说明及操作脚本，不覆盖现有 Android 源码；如分支不存在，则从 `origin/main` **创建新分支**并同步完整 E3-Fix1 源码。两种情况均使用独立 clone，不修改你原先的本地工作目录，也不向 `main` 推送。推送前会展示变更并要求输入 `yes`。

推送成功后自动触发分支 Actions：`Scan Enhancement E3 - CI and Prerelease`。它会运行已有 Gradle 调试构建和单元测试、release lint，并提供 debug APK 作为 Actions artifact。这个 debug APK 不会作为 GitHub Release 发布。

查看：`https://github.com/yanjing-chen/openssreader-android/actions`

## 第二步：确认 CI 成功后从本地推送独立的预发布标签

继续在解压后的源码根目录执行：

```bash
bash ./RELEASE_SCAN_E3_RC1_6800H.sh
```

脚本要求当前分支 HEAD 有**对应 SHA 的成功 E3 CI 运行**，并确认仓库里存在原来的四个 Android 正式签名 Secrets；它不会查看或回显密钥内容。检查成功后，提示输入 `yes`，从远端 E3 分支的 HEAD 新建并推送注释标签 `scan-e3-fix1-rc1`。

该 tag push 自动触发**同一个** E3 专用工作流：再次完成调试构建、测试、lint，成功后使用原正式版签名构建 `assembleRelease`，通过 `apksigner verify` 后产生：

- `OpenSSReader-Android-scan-e3-fix1-rc1.apk`
- `OpenSSReader-Android-scan-e3-fix1-rc1.apk.sha256`

两个文件作为 `Pre-release` 附件上传；该 Release 显式 `--latest=false`。GitHub Release 源码归档指向该**标签的源码**，而不是 main。

查看：`https://github.com/yanjing-chen/openssreader-android/releases/tag/scan-e3-fix1-rc1`

如要发布后续修订，先向同一 E3 分支推送源码更新并使 Actions 通过，再以新的标签名运行，例如：

```bash
bash ./RELEASE_SCAN_E3_RC1_6800H.sh scan-e3-fix1-rc2
```

**不要重复推送或移动已有标签，千万不要 `git push --force`。**

## 签名与数据安全

- 使用仓库既有 `OSR_ANDROID_KEYSTORE_BASE64`、`OSR_ANDROID_KEYSTORE_PASSWORD`、`OSR_ANDROID_KEY_ALIAS`、`OSR_ANDROID_KEY_PASSWORD` 四个 Secrets。没有这些 Secrets 就不会发布带假签名的 APK。
- APK 包名仍是 `org.openssreader.android`，`versionName = 1.0.7`，`versionCode = 10007`。预发布身份只在 Release 名称和标签中，防止为一轮 CI 误改原有稳定版本号。
- 同包覆盖更新要求设备上已安装 APK 的签名证书一致，并受系统版本策略限制。更新前请备份重要数据；`adb install -r` 失败绝对不要为了继续测试执行 `adb uninstall` 或 `pm clear`。
- `ReleaseUpdates.kt` 使用 `/releases/latest` 和正式的 `vX.Y.Z` 版本识别，预发布不属于正式自动更新通道。
- GitHub Actions 工作流使用 `push` 触发，不使用 `workflow_dispatch`，因为仅存在于 feature 分支中的新工作流无法可靠地由默认分支 UI 手动触发。可直接通过分支 push 与标签 push 运行。

## 工作流故障排查

检查分支与标签：

```bash
gh run list -R yanjing-chen/openssreader-android --branch feature/scan-enhancement-e3-fix1 --limit 10
gh release view scan-e3-fix1-rc1 -R yanjing-chen/openssreader-android
```

如果第 1 步 CI 失败，第 2 步脚本不会创建标签。如果第 2 步的 release 构建失败，GitHub 不会产生正式的 Release，保留日志检查签名或构建错误即可。
