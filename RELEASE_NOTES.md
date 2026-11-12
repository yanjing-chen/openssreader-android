# 1.0.7 — 下载功能恢复候选版（未发布）

- 用户要求停止图像阅读页下载修复，GeckoView 下载协议接管恢复至最初上传的 `openssreader-android-main.zip`（1.0.3）原始实现。
- `InstitutionPdzTransfer.kt` 完全恢复为 1.0.3 网络下载、授权回退与目录验证实现。
- 保留已实机验收的单标签显示、网页真实标题及 Bookshelf 同名 JSON 持久化。
- 保留其余 PDZ 解析兼容性；**下载阶段外层容器宽松校验（Compat12 #3）恢复为原版严格目录校验**。
- 图像阅读页下载按钮不再特殊桥接，仍按原网站行为；本版重点保证书目简介下载功能。
- 本包不是已经在 Android 实机通过下载验收的正式版。

详见 `DOWNLOAD_RESTORE_FROM_ORIGINAL_1_0_3_CN.md`。
