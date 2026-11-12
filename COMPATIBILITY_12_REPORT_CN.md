# OpenSSReader Android 1.0.5 — PDZ 兼容性 12 项修复核对报告

> **当前下载回退说明（2026-11-12）：** 本文件记录的是此前 1.0.5 Compat12 的开发历史。用户要求恢复原始下载逻辑后，第 3 项（下载阶段仅做容器外层校验）已撤销并恢复为 1.0.3 的严格目录检查。其他 PDZ 核心兼容实现未整体回退。详见 `DOWNLOAD_RESTORE_FROM_ORIGINAL_1_0_3_CN.md`。


**开发基线：** 用户提供的 `openssreader-android-main.zip`（Android 1.0.3，versionCode 10003）。
**对照来源：** 同轮提供的 `OpenSSReader-main.zip` Linux PDZ 解析与转换实现。
**交付版本：** 完整独立 Android 1.0.5 候选源码（versionCode 10005），**不是**已构建的 APK，也不是已验收的正式发布版。

| # | 修复主题 | 修改内容 | 主要位置 | 状态 |
|---|---|---|---|---|
| 1 | `att###.pdg` 附页 | 附页可从 `att018` 等任意正编号开始，按连续编号排在正文之后；普通与混合 PDF 页序同步纳入附页，缺页继续拒绝 | `core/PdzToc.kt`、`core/PdzMixedPagePlan.kt`、`BookEngine.kt` | 已编码；合成测试通过 |
| 2 | `meta.xml` 非关键故障 | 书目 XML 损坏或超限时使用文件名等后备名称，不因辅助信息错误清理已下载 PDZ；仍使用安全 XML 解析 | `core/BookMetadata.kt`、`BookEngine.kt` | 已编码；合成测试通过 |
| 3 | 下载阶段校验过严 | 仅在下载完成时验证 PDZ 外层格式与结束标记，不将不支持的内层目录等同于下载失败；目录不可识别时仍保留原文件并采用后备入库名称 | `core/PdzDownloadEnvelope.kt`（新增）、`InstitutionPdzTransfer.kt`、`BookEngine.kt` | 已编码；合成测试及 book 协议回归通过 |
| 4 | CMYK JPEG | 支持 4 通道 JPEG 与 `/DeviceCMYK`，原始 JPEG 字节不重新压缩 | `core/LosslessJpegPdf.kt` | 已编码；字节一致性/PyMuPDF PDF 校验通过 |
| 5 | 1AH PDF 备用恢复 | 精确恢复失败后进行有时间上限的搜索；仍须通过 zlib/Adler32 和 PDF 基本检查；不采用 Linux 未验证的预览模式 | `core/OneAhPdfRecovery.kt` | 已编码；合成参数和 PDF 测试通过 |
| 6 | 前导零的 PDF 分段名 | `01_02.pdg` 一类名称可识别，各分段规划逻辑一致 | `core/PdzSegmentReader.kt`、`core/PdzMixedPagePlan.kt` | 已编码；合成测试通过 |
| 7 | 目录空白行 | 略过空格、制表符组成的空行；真实章节记录仍严格解析 | `core/PdzToc.kt` | 已编码；合成测试通过 |
| 8 | 1AH 64/128 MiB 固定上限 | 以 JVM 可用最大堆为依据，保留低内存设备原有限制，高内存设备有界提高压缩与解压容量上限 | `core/PdzCompatibilityLimits.kt`（新增）、1AH 读取及 PDF 组装调用处 | 已编码；静态与合成测试通过；大书需实机验证 |
| 9 | 扫描图容量/像素上限 | 按堆容量调整 JPEG 最大字节与图像总像素上限；低内存设备保留保护 | `core/PdzCompatibilityLimits.kt`、`core/Hh04JpegDecoder.kt`、`core/BwPdgDecoder.kt` 等 | 已编码；容量策略检查通过；超大真书需实机验证 |
| 10 | 100000 页结构上限 | 统一为 Linux 侧相容的 1000000 页结构规划上限，不取消连续页与完整性检查 | `core/PdzCompatibilityLimits.kt`、页序规划与转换入口 | 已编码；200005 页结构测试通过（未实际生成如此大的 PDF） |
| 11 | 复合照片部分越界 | 两处严格矩形检查均修复；部分越过右、下边界可保留原始 JPEG 嵌入，由 PDF 页面裁切，原始扫描页尺寸不扩大；完全越界与损坏仍拒绝 | `core/CompositePhotoPage.kt`、`core/LosslessJpegPdf.kt` | 已编码；合成复合照片 PDF 测试通过；SSID 10994312 未解密实测 |
| 12 | 双解析器误拒绝 | PDFBox 负责 PDF **分段**的格式及页数校验；最终组装完成的 PDF 仍用 Android PdfRenderer 验证可读性及页数，再发布至书架 | `PdzSegmentPdfAssembler.kt`、`PdzMixedPdfAssembler.kt`、`BookEngine.kt` | 已编码；编译桩与代码校验通过；需 Android 实机验证 |

## 验证结果和适用范围

已在当前 Linux JVM/Python 环境运行并通过：

- `android/verification/verify_compatibility_12.sh`（12 项针对性合成检查）
- `android/verification/verify_composite_photo.sh`（原始照片字节、双图层、部分越界、破损拒绝）
- `android/verification/verify_oneah_pdf.sh`（1AH 精确恢复、后备恢复、Type-0 与 PDF 结构）
- `android/verification/verify_mixed_page_plan.sh`（混合页规划）
- `android/verification/verify_hh04_port.sh`（HH/04H 彩色页无损输出）
- `android/verification/verify_bw_a1c.sh`（02H/11H 黑白页恢复）
- `android/verification/verify_authorization_a1.sh`（Type-0/Type-2 授权的正反测试）
- `android/verification/verify_book_scheme.sh`（book 协议与下载逻辑）
- `android/verification/verify_import_storage_fix.sh`（导入、存储、XML 安全）
- `android/verification/verify_fix2_sidebar_root_storage.sh`、`verify_a2a3.sh`、`verify_core.sh`（原有 UI/核心静态与合成回归）
- `android/verification/check_*.py` 的 14 个检查脚本
- 新增的 `app/src/test/java/org/openssreader/android/core/PdzCompatibility12Test.kt` 8 个测试方法在 JVM JUnit 编译桩下执行通过；正式 Android/Gradle JUnit 流水线尚未执行。

**未验证事项：** 当前工作环境没有 Android SDK/设备，未真正执行 Android Gradle `assemble` / GitHub Actions、没有安装/启动 APK，也没有《阅读与写作训练教程》SSID 10994312 的已验证解密授权上下文和逐页读写实测。图像内存上限在手机、平板上是否足够，需要各设备分别确认。

## 建议在用户自己的 6800H 上的验收顺序

```bash
cd openssreader-android-main
bash android/verification/verify_compatibility_12.sh
bash android/verification/verify_composite_photo.sh
bash android/verification/verify_oneah_pdf.sh
bash ./BUILD_ANDROID_6800H.sh
```

取得 APK 后，优先在 TB138FC 等设备复验：普通黑白 02H/11H、彩色 HH/04H、含附页或 1AH 分段的书、图片部分越界的复合照片页、SSID 10994312 实书、书城 `book://` 下载、导入以及导出/阅读。确认完整页数、章节目录和书架持久化；**不要在实机通过前创建 `v1.0.5` Release tag**。

### 安全与兼容边界

- 不修改原 PDZ、不跳页充数、不跳过授权或 zlib/Adler 校验、不把未验证预览输出作为正式 PDF。
- 图片保持原始 JPEG 字节，不能因“修复兼容性”而重采样或改变扫描底图大小。
- 完全越出纸张的照片、重叠资源、损坏 JPEG 等仍会被拒绝；资源大小采用设备内存有界策略。
- 保留包名 `org.openssreader.android`、原有签名 Secret 引用方式以及自动构建和更新机制；这次没有生成或替换签名私钥。
