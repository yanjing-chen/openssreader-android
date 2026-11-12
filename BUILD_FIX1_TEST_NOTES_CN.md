# OpenSSReader Android 1.0.7 Fix1 — 单元测试断言修复

## 症状

用户在 Code01-Ver2-0 上运行 1.0.7 的 `BUILD_ANDROID_6800H.sh`，Gradle 9.3.1 + JDK17 完成 Kotlin 编译和 APK 打包任务，但 66 个单元测试中有 1 个失败：

- `PdzMixedPagePlanTest.appendixAfterBodyIsPreserved`
- `PdzMixedPagePlanTest.kt:59`

## 原因

原测试只有 `000001.pdg`（1 页）、`2_2.pdg`（2 页）、`att018.pdg`（1 页），实际共 4 页、3 个 Part，却错误地断言共 5 页、4 个 Part。该错误是测试样例自身数据与断言不匹配，不是 Android 解码器遗漏书页，也不是 SDK 编译错误。

## 修复

- 在原测试输入中增加 `000004.pdg`，保留原来的 5 页、4 个 Part 断言，同时检查正文页数、每个 Part 的页数及完整资源顺序。
- 另加单独测试，确认原来的三资源输入应为 4 页、3 个 Part。
- 保留对附页编号缺口的拒绝测试。
- 增加 `android/verification/verify_appendix_page_order.sh` 无 Android SDK 的 JVM 验证脚本。

**未修改** `PdzMixedPagePlan.kt`、PDZ 解码器、书架 JSON 逻辑、GeckoView 下载流程、包名、签名配置及 `versionName=1.0.7` / `versionCode=10007`。本次是同版本构建修复版，不创建新正式版本。

## 在 6800H 重新执行

```bash
cd "$HOME/下载/OpenSSReader-Android-1.0.7-Fix1-Source/openssreader-android-main"
bash android/verification/verify_appendix_page_order.sh
bash ./TEST_BOOKSTORE_6800H.sh
bash ./BUILD_ANDROID_6800H.sh
```

构建通过后，调试 APK 为 `android/app/build/outputs/apk/debug/app-debug.apk`。正式 GitHub 签名版不可用调试签名版覆盖，避免卸载原应用损失数据。

## 验证边界

离线 JVM 结构测试能够证明本次对页数的预期正确，不能替代 6800H 完整 Android SDK 构建和真实平板回归。
