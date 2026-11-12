# OpenSSReader Android 1.0.7 · ScanEnhance E3-Fix1 预发布

**来源分支**：`feature/scan-enhancement-e3-fix1`  
**发行定位**：扫描阅读增强的测试候选版本；非稳定版、非 Latest Release。

## 新功能

- 阅读器顶部「单页 / 双页 / 连续」使用清晰的单选圆点。
- 「原始图像 / 扫描增强」快速切换，扫描增强设置有独立入口。
- 保真去灰底、灰度文字增强、强黑白净化、彩色保真增强。
- 「不裁边 / 逐页自动 / 基准页固定」三选一。
- 基准页可用当前页或指定页码；自动镜像左右页裁边比例；明显差异时提示补选第二基准页。
- 原始 PDZ、PDF 不修改；原有下载、GeckoView 书城、书架继续保留。
- E3-Fix1 解决 Compose `horizontalScroll` 缺失导入导致的编译错误。

## 测试与安装

- GitHub Actions 执行 Android 调试包构建、Gradle 单元测试及 `lintVitalRelease` 后，使用**仓库原有正式签名 Secrets**构建并校验预发布 APK。
- APK 的包名是 `org.openssreader.android`，版本名 `1.0.7`，内部版本号 `10007`，与既有源码一致；预发布身份由 Release 标签标识，而不是应用内版本号。与同码正式版覆盖安装的可行性，取决于**签名相同**及系统安装策略。
- 不要为了测试而卸载原有应用、清除应用数据或重新生成签名密钥。安装前建议备份书架与阅读数据。
- 请重点测试单页、双页、连续模式；去灰底对文字细节的影响；逐页裁边；左右页镜像/双基准提示与保存。
- 正式版「检查更新」使用 `/releases/latest`，这个 prerelease 不应作为正式自动更新发布。

**APK 与 SHA256 均以本次 Release 的 Assets 为准。**
