# OpenSSReader Android 1.0.0

独立 Android 项目，内置 Mozilla GeckoView 浏览器书城及本地 PDZ 阅读器。保留原有 `book://` 下载队列、PDF 解码与书架标签。

- 当前版本：`1.0.0`，versionCode `10000`，包名 `org.openssreader.android`。
- 本地编译：`bash ./BUILD_ANDROID_6800H.sh`（Android SDK 37.0 + Build Tools 36.0.0、JDK 17）。
- 自动构建：`.github/workflows/android.yml`。
- 正式发布：`.github/workflows/release.yml`，通过私密签名密钥构建发布版 APK 和 SHA256 校验文件。
- 应用设置新增：检查 GitHub Release 更新、下载并校验 APK、使用 Android 系统安装器安装；以及“关于”版本信息。
- 界面移除预览版/引擎说明、下载页说明、扫描书架说明和功能范围说明；保留有效功能。

**发布之前务必阅读 [RELEASE_GUIDE_CN.md](RELEASE_GUIDE_CN.md) 的签名迁移提示。** 新正式签名与旧 debug 安装签名不同，不能直接覆盖安装，切勿在没有备份时卸载旧版。
