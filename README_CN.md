# OpenSSReader Android 1.0.3

独立、完整、精简的 Android 源码仓库；不包含 Linux AppImage、历史 Fix 补丁或旧版源码。包名保持 `org.openssreader.android`，版本号 `1.0.3`，`versionCode=10003`。

## 本版功能

- **GeckoView 原生多标签书城**：点击 SSLibrary 搜索结果中的书名/封面，可打开独立图书详情标签；原搜索结果保留，可切换、关闭标签。使用同一个可见 GeckoView，避免透明窗口拦截操作。`book://` 下载仍交由原 PDZ 下载器处理。此多标签修复已由用户在 Android 设备上验收通过。
- **手机和平板自适应书架**：窄窗口为全宽书架和分类抽屉，宽窗口仍采用左右分栏；自建标签长按重命名或删除，数量与内置分类对齐。
- **高清封面**：真实 PDZ 封面优先；没有独立封面时使用已解码 PDF 第一页。按屏幕像素密度渲染、无损缓存，不改动原文件。
- **书架功能**：封面大小滑块直接调整书卡宽度；添加日期、作者、书名、出版年份各支持升序和降序排序。
- **阅读器**：单页/双页轻点左侧和右侧翻页，中间轻点控制工具栏，保留双指缩放；紧凑“前言 / 目录 / 正文”分区下拉菜单；保留连续阅读模式。
- **应用设置**：GitHub Release 检查更新、下载校验并调用系统安装器；“关于”显示版本。

## 6800H 本地编译

需要 JDK 17、Android SDK Platform 37.0 和 Build Tools 36.0.0。无需 Node.js；源码的 Gradle 启动脚本使用 9.3.1。

```bash
bash ./BUILD_ANDROID_6800H.sh
```

调试 APK 路径：`android/app/build/outputs/apk/debug/app-debug.apk`。已安装 GitHub 正式签名版的手机/平板**不能直接使用调试签名 APK 覆盖**；不要为了测试卸载原应用，以免损失书架、笔记和登录数据。

## 从本地推送与发布 1.0.3

保留已关联 `yanjing-chen/openssreader-android` 的原 Git 工作树（具有 `.git` 目录）。从**新完整源码目录**运行以下两步：

```bash
# 1. 提交、推送新的 1.0.3 源码到 main（不创建 tag）
bash ./PUSH_FROM_6800H.sh /path/to/原有Git仓库

# 2. 等最新 commit 的 Android APK CI 变绿之后，推送 v1.0.3 标签
bash ./RELEASE_1_0_3_6800H.sh /path/to/原有Git仓库
```

第二步会检查 GitHub 身份、工作树、CI 成功记录以及四项正式签名 Secrets 的存在，再请求输入 `release` 确认。GitHub Actions 将自动发布正式签名的 APK 和 SHA-256 文件。

不重新生成 `.keystore`，不提交任何密码/密钥，不覆盖既有标签（包括 `v1.0.0`、`v1.0.1`、`v1.0.2`）。仓库保持整洁，只交付现行有效文件。

详见 [RELEASE_GUIDE_CN.md](RELEASE_GUIDE_CN.md)。
