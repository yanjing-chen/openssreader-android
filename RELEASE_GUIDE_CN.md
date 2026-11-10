# OpenSSReader Android 1.0.1 · 本地构建与发布

仓库：`https://github.com/yanjing-chen/openssreader-android`。源码仓库只包含 Android 的现行代码与测试。

## 先实机验收

1. 在新的完整源码目录运行 `bash ./BUILD_ANDROID_6800H.sh`。
2. 通过 ADB 安装 **debug APK** 之前，必须确认设备上当前应用的签名。debug APK 不能覆盖发布签名的 APK，切勿为测试随意卸载、清除书架和 GeckoView 登录数据。
3. 手机和平板检查：封面清晰度、真实 PDZ 原生封面优先、无封面时 PDF 第 1 页回退、缩放/旋转、书架标签、`book://` 下载和阅读进度。
4. 本项目保留安卓仪器测试 `HighResolutionShelfCoverTest`，需要连接设备并运行 `cd android && bash ./gradlew :app:connectedDebugAndroidTest`。

## 上传到现有 GitHub 仓库

首次 v1.0.0 已使用正式签名的仓库，不要重新生成发布密钥，不要覆盖旧 v1.0.0 标签。

1. 保留原来含 `.git` 的本地仓库工作树，确保是干净的 `main` 分支并已与远程同步。
2. 在**本完整源码目录**运行：`bash ./PUSH_FROM_6800H.sh /path/to/原有Git仓库`。脚本会检查身份、仓库、工作树，先复制修改并展示 diff；只有确认后才提交并推送 `main`。它不会自动创建 tag。
3. 等主分支 `Android APK CI` 绿色通过。新版同步脚本在复制前会展示删除清单；再次确认前不会同步和提交。
4. 在**新完整源码目录**运行 `bash ./RELEASE_1_0_1_6800H.sh /path/to/原有Git仓库`。脚本在推送新 `v1.0.1` 标签前检查远程分支、发布状态、CI 成功记录以及 4 个已有签名 Secret 名称。已有 tag/Release 时会直接拒绝重复发布。
5. GitHub Actions 将用**与 1.0.0 完全一致的正式签名密钥**发布 `v1.0.1`，提供 APK、SHA-256 和应用内更新。仅在正式工作流成功且 Release 资产齐全后，才视为发布完成。

```bash
# 第二步：CI 成功后创建并推送新标签（不会修改 v1.0.0）
bash ./RELEASE_1_0_1_6800H.sh /path/to/原有Git仓库

# 检查正式签名构建结果
gh run list -R yanjing-chen/openssreader-android --workflow 'Android Signed Release' --limit 5
```

## 签名与用户数据

应用的 `applicationId` 不变，versionCode 为 `10001`。保持同一正式发布签名即可从 1.0.0 覆盖更新，并保留书架、标签、阅读进度和登录数据。不要提交 keystore、密码、令牌、私有 PDZ/个人图书。

## 高清封面处理说明

仅从本地已有 PDZ / 已成功解码 PDF 提取图片。低分辨率来源不能凭空恢复丢失的细节；真实封面一律优先；原图若只有约 149×224 像素，无法凭空创造丢失的细节，不能误用内页冒充封面。若原 PDZ 还未完成授权和转换，个别加密首页暂时不可预览，解码成功后书架自动刷新。缓存有 96 MiB 的磁盘上限，且不写入、修改原始图书文件。
