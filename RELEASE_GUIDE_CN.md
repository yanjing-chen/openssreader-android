# OpenSSReader Android 1.0.8 正式版发布入口

当前正式版为 **v1.0.8**。从已验收的 `feature/scan-enhancement-e3-fix1` 开发分支发布，不推送、合并或修改 `main`。

请以随本次完整源码提供的 **`RELEASE_1_0_8_GUIDE_CN.md`** 为准。推荐流程：

```bash
# 第一步：在自己的 6800H 推送 1.0.8 版本准备提交，触发分支 Android APK CI
bash ./PUSH_ANDROID_1_0_8_BRANCH_6800H.sh

# 第二步：确认 Actions 构建通过后，由本地推送 v1.0.8 标签到 GitHub
bash ./RELEASE_ANDROID_1_0_8_6800H.sh
```

GitHub Actions 将从该 tag 构建正式签名 APK，与 v1.0.7 正式签名证书比较，创建 stable/latest Release 并附 SHA256。

**不要运行旧版推送 `main`、旧版 `v1.0.7` 或 `scan-e3-fix1-rc1` 的发布脚本。** 历史版本标签和旧 Release 均保留。
