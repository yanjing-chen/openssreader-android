# OpenSSReader Android 1.0 — 在 6800H 本地推送、构建与发布

仓库地址（执行脚本后才会创建）：`https://github.com/yanjing-chen/openssreader-android`。仓库须为 **Public**，应用才能无账号访问 GitHub 的 `releases/latest` 接口。

## 发布流程

1. 解压完整源码到独立目录，确保是你准备发布的完整 Android 1.0 源码。
2. 如果未登录 GitHub CLI，执行 `gh auth login`，以账号 `yanjing-chen` 登录。脚本不会要求或记录 GitHub 密码。
3. 首次推送源码：`bash ./PUSH_FROM_6800H.sh`。它创建公开仓库、提交、推送 `main`，检测签名密钥。若没有密钥则只推送源码，不触发 Release。
4. 首次配置发布签名：`bash ./SETUP_RELEASE_SIGNING_6800H.sh`。在 `~/.config/openssreader-android/release.keystore` 生成个人发布密钥，安全上传到 **GitHub Actions Secrets**，不会存入仓库。**请离线备份密钥文件和密码。**
5. 再运行 `bash ./PUSH_FROM_6800H.sh`，输入 `yes` 创建并推送 `v1.0.0` 标签，触发 `.github/workflows/release.yml`。
6. 查看 GitHub 仓库 Actions。工作流编译、运行测试、签名 APK、校验签名、生成 `.apk.sha256`、发布 GitHub Release。正式下载资产应为 `OpenSSReader-Android-v1.0.0.apk` 和 `OpenSSReader-Android-v1.0.0.apk.sha256`。

## 签名兼容性（非常重要）

**你目前两台平板安装的 Fix4 调试 APK 与新正式版的签名密钥通常不同。** 即使包名相同，Android 也会拒绝直接覆盖安装不同签名的 APK。这不是更新程序错误。不可通过改变 `versionCode` 解决，也**不要贸然卸载**导致私有书架/标签记录丢失。

发布前先确认你的当前图书、书架索引、标签等均已保存到可访问的 `OpenSSReader` 公共存储目录，额外复制备份整个目录；应用内部私有数据和 GeckoView 登录 Cookies 并不一定随公共目录备份。需要保留私有数据时，应在旧版内完成迁移或另行设计安全迁移，再卸载旧调试版。应用内更新从 **首次安装正式签名版之后** 才能对今后用同一签名的 Release 直接覆盖升级。

**不要使用 debug.keystore 签署公开正式版来规避迁移**：调试签名不适合长期公开发布。

## 应用内检查更新

设置 → 检查更新：查询公共 GitHub 最新正式 Release。若版本高于当前版本，可下载发布版 APK。应用下载后校验 GitHub 提供的 SHA-256 或附带 `.sha256` 文件，只有校验通过才显示“安装”；安装由 Android 系统安装器执行，可能需要用户首次授权“允许安装未知应用”。不会静默安装。

## 后续版本

更新 `android/app/build.gradle.kts` 中 `versionName`、递增 `versionCode`，提交并推送，然后推送匹配的 `vX.Y.Z` 标签；使用**同一发布密钥**签名所有正式版。

## 日志与安全

GitHub 密码、token、签名密钥、机构账户、Cookies 不可提交到 Git；`*.keystore`、`*.jks`、`local.properties` 均被忽略。Release 构建在 GitHub Actions 内签名。源码仓库不发布 PDZ 图书样本以外的用户私人图书、授权令牌或账号数据。
