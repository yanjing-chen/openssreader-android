> **扫描阅读增强 E3 候选**：见 [`READER_ENHANCEMENT_E3_CN.md`](READER_ENHANCEMENT_E3_CN.md)。支持顶部 radio 点选、原图/增强切换、逐页自动/单基准镜像、相邻页差异提示补选第二基准。Android 编译及真机验收仍需在本地完成。

# OpenSSReader Android 1.0.6

> **当前版本（1.0.7 下载回退候选）：** 下载协议和网络下载过程已恢复为最初上传的 Android 1.0.3 源码；保留已通过验收的书城标签栏和书架同名 JSON。图像阅读工具栏不做特殊接管。详见 `DOWNLOAD_RESTORE_FROM_ORIGINAL_1_0_3_CN.md`。


独立、完整、精简的 Android 源码仓库；不包含 Linux AppImage、历史 Fix 补丁或旧版源码。包名保持 `org.openssreader.android`，版本号 `1.0.6`，`versionCode=10006`。

## 1.0.6 候选版 — 书架 JSON 持久化修复

本版直接基于上一轮 **1.0.5 Compat12 完整源码**修复书架持久化：

- `book://` 下载后先将 PDZ 保存到用户已授权的公共目录，再**同步创建并读回** `bookshelf/图书文件名.json`，然后才显示在书架；禁止内存中出现缺少 JSON 的虚假入库。
- 转换后的 PDF 必须写入同一个 JSON 的 `publicPdfName`，持久化成功之后下载任务才显示“已完成”。
- JSON 文件名与实际 PDZ（或单独导入的 PDF）去掉扩展名后**完全一致**，例如 `荷兰史_作者_出版社_2024_12345678.pdz` 对应 `荷兰史_作者_出版社_2024_12345678.json`。
- 启动/重新扫描时仅从 `bookshelf` JSON 恢复图书；不扫描 `pdz`、`pdf` 文件夹擅自新增图书；下载和手动导入都必须先将 JSON 持久化。
- 迁移旧版 UUID.json 和旧总索引，保留书签进度、收藏、标签；不覆盖重名 JSON，文件暂不可访问时保留其旧索引记录。
- 写入 JSON 或公共文件失败时保留已经取得的原始 PDZ，提示保存失败，不冒充已成功入库。

新回归：`android/app/src/androidTest/java/org/openssreader/android/BookshelfPersistenceInstrumentedTest.kt`，设备上运行 `connectedDebugAndroidTest`。**离线源码检查通过不等于实机回归通过。**

## 1.0.5 基线保留 — 12 项 PDZ 兼容性更新

1.0.5 基线此前以用户上传的 **Android 1.0.3 完整源码**为基线，参考 Linux 源码实现 12 项兼容性调整；本版全部保留。**源码及离线 JVM/PyMuPDF 测试不代表 Android 设备已验收。** 包含：

1. `att###.pdg` 附页及混合页面编排、完整页数、附页导航；
2. `meta.xml` 异常时的书架导入/下载重命名回退；
3. 下载仅检验 Linux 同等外层封装，目录解析失败仍保留原始 PDZ；
4. CMYK JPEG `/DeviceCMYK` 原始字节无损嵌入；
5. 1AH 精确恢复失败后的有界 C100 搜索，必须通过 zlib/Adler32 和 PDF 结构验证；
6. 带前导零的 PDF 分段文件名；
7. `contents.dat` 仅空白行的容错；
8. 1AH 大分段的按 JVM 堆容量动态上限；
9. 大图像像素数量和 JPEG 页面资源上限按堆容量动态调整；
10. 与 Linux 一致的最多 1,000,000 页结构规划上限；
11. 复合照片允许部分越过页面边界，由 PDF 页面裁切，完全越界/数据损坏仍拒绝；
12. 片段使用 PDFBox 校验，最终完整 PDF 必须通过 Android PdfRenderer 校验。

测试：`bash android/verification/verify_compatibility_12.sh`，`verify_oneah_pdf.sh`，`verify_composite_photo.sh` 等；详见 `COMPATIBILITY_12_REPORT_CN.md`。

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

## 从本地推送与发布 1.0.6（须先完成实机验收）

保留已关联 `yanjing-chen/openssreader-android` 的原 Git 工作树（具有 `.git` 目录）。从**新完整源码目录**运行以下两步：

```bash
# 1. 完成实机回归后，提交、推送 1.0.6 源码到 main（不创建 tag）
bash ./PUSH_FROM_6800H.sh /path/to/原有Git仓库

# 2. 等最新 commit 的 Android APK CI 变绿之后，推送 v1.0.6 标签
bash ./RELEASE_1_0_6_6800H.sh /path/to/原有Git仓库
```

第二步会检查 GitHub 身份、工作树、CI 成功记录以及四项正式签名 Secrets 的存在，再请求输入 `release` 确认。GitHub Actions 将自动发布正式签名的 APK 和 SHA-256 文件。

不重新生成 `.keystore`，不提交任何密码/密钥，不覆盖既有标签（包括 `v1.0.0`、`v1.0.1`、`v1.0.2`）。仓库保持整洁，只交付现行有效文件。

详见 [RELEASE_GUIDE_CN.md](RELEASE_GUIDE_CN.md)。

## 最新版：1.0.7 书城标签页和下载入口兼容修复

详情参见 `BOOKSTORE_TABS_DOWNLOAD_FIX_REPORT_CN.md`。此前的书架索引/JSON 修复详见 `BOOKSHELF_PERSISTENCE_FIX_REPORT_CN.md`，PDZ 兼容问题详见 `COMPATIBILITY_12_REPORT_CN.md`。

