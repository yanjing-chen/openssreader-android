# OpenSSReader Android 扫描增强 E3-Fix1：构建修复说明

## 用户 6800H 构建日志诊断

`OpenSSReader-E3-build.log` 显示编译停止在 `:app:compileDebugKotlin`，四处报错均为 `Modifier.horizontalScroll` 无法解析（原 E3 MainActivity.kt 第 1196、1585、1611、1651 行）。

根因：源码已经使用 Compose 的 `horizontalScroll` 扩展函数，但遗漏了 `import androidx.compose.foundation.horizontalScroll`。该 API 由项目已依赖的 Compose Foundation 提供，**不需要额外依赖、更不需要更改 Gradle 版本**。

## 修复范围

- 在 MainActivity.kt 增加 `androidx.compose.foundation.horizontalScroll` 导入。
- `android/verification/check_reader_enhancement_ui.py` 加入回归断言，避免遗漏此导入。
- 原有扫描图像增强、原图/增强单选、单页/双页/连续单选、按页裁边、单/双基准镜像检测、下载/解码/书架功能不变。

本环境可验证纯 Kotlin 图像算法的 JVM 合成测试、静态 UI 接线检查；尚不能代替 6800H 的 Android Gradle 实际构建及真机验收。请使用本包附带 `BUILD_AND_ADB_ENHANCEMENT_6800H.sh` 完整构建，查看下一处报错（如果还有）。

## 签名与应用数据安全

安装命令使用 `adb install -r`；不会卸载应用或清除数据。正式签名和 debug 签名不一致时覆盖安装将失败，此时不要卸载原版。
