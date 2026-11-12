# OpenSSReader Android 1.0.7 — 下载功能恢复至用户原始基线

## 恢复范围
- **下载协议处理**：`GeckoBookstore.kt` 的全部 `book://` 顶层导航、新标签、加载错误、子框架原逻辑，来自用户上传的 `openssreader-android-main.zip`（1.0.3），仅保留 1.0.7 已验收的三处标签标题/显示改动。
- **下载网络与容器判定**：`InstitutionPdzTransfer.kt` 整文件恢复为 1.0.3 原始版本，使用原始 `PdzCatalogReader.read(partial)`，不再由下载阶段使用后续兼容性版本的 `PdzDownloadEnvelope.inspect(partial)`。
- **移除后续尝试**：本包从 1.0.7 Fix1 版本构建，不包含 Fix2–Fix6 的 WebExtension 诊断器、隐藏 iframe 下载桥接、JavaScript 点击代发或自动跳回书目详情。

## 保留范围
- 单标签栏始终显示；所有标签显示站点真实标题；子标签窗口正常。
- 下载完 PDZ 先持久化 `bookshelf/书名.json`，成功入库后显示；PDF 关联写回同一 JSON。保留收藏、标签和重启书架恢复。
- Fix1 中的其他 1.0.5 PDZ 解析、复合照片、CMYK、附页等兼容性改动，不因恢复下载入口而整体回退。

## 特别说明
- 因下载网络流程严格恢复为 1.0.3 原版，**先前 Compat12 的第 3 项「下载阶段仅检查外层容器」也随之撤销**：原版在下载阶段调用完整 PDZ 目录解析。这是用户要求恢复原始下载行为的直接结果，不应再声称 12 项兼容性变动全部生效。
- 图像阅读页的下载按钮不再尝试修复，仍按原网站方式运行，可能显示安装官方阅读器的提示。书目简介下载入口才是本次恢复和验收的重点。
- 本包只完成静态与离线回归；Android 设备真实下载需要实机确认，不声称已实机通过。
- 正式签名与 Debug 签名不匹配时绝不能卸载原应用来强装，以免丢失应用私有数据。

## 核心还原校验
`android/verification/check_bookstore_tabs_and_handoff.py` 对原始 GeckoBookstore（扣除三处标签 UI 差异）和 `InstitutionPdzTransfer.kt` 进行 SHA-256 指纹对照。修改两处原始下载代码会导致检查失败。
