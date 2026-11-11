# OpenSSReader Android 1.0.3 · 本地推送及正式发布

仓库：`https://github.com/yanjing-chen/openssreader-android`。

## 前提

- 你已在设备上验收 GeckoView 多标签候选版；本次正式版本不修改已验收的浏览、PDZ 下载与阅读实现。
- 原有 Git 仓库工作树含 `.git`，`origin` 指向目标仓库，当前在 `main`，无未提交更改；GitHub CLI `gh auth status` 正常。
- GitHub Actions 已配置四个原有签名 Secrets：`OSR_ANDROID_KEYSTORE_BASE64`、`OSR_ANDROID_KEYSTORE_PASSWORD`、`OSR_ANDROID_KEY_ALIAS`、`OSR_ANDROID_KEY_PASSWORD`。
- **不要重新生成 release.keystore**，也不要删除或强制覆盖以前的任何正式发布标签。

## 先推送完整源码

解压新 ZIP 到独立目录，进入**新源码根目录**执行：

```bash
bash ./PUSH_FROM_6800H.sh /path/to/原有Git仓库
```

脚本检查仓库身份、远程与本地分支一致性，先展示将同步的变更。你确认后才更新原有 Git 工作树、提交并推送 `main`。旧补丁说明不会留在仓库里。脚本不会推送 tag。

## 再发布 v1.0.3

确保刚才新提交对应的 `Android APK CI` 已成功：

```bash
gh run list -R yanjing-chen/openssreader-android --workflow 'Android APK CI' --limit 5
```

然后在**新源码根目录**执行：

```bash
bash ./RELEASE_1_0_3_6800H.sh /path/to/原有Git仓库
```

脚本检查版本、远程 tag 不存在、CI 成功、原签名 Secrets 存在；确认后创建并推送 `v1.0.3`，GitHub Actions 自动生成正式签名 APK 和 `.sha256` 校验文件。

```bash
gh run list -R yanjing-chen/openssreader-android --workflow 'Android Signed Release' --limit 5
```

只有 Release 工作流成功，且 GitHub Releases 中出现匹配的 APK 与 `.sha256` 两个附件后，才可认为发布完成。

## 安装兼容性

包名始终为 `org.openssreader.android`，版本代码 `10003`。使用同一正式签名时可覆盖安装并保留应用数据。不要把 debug APK 作为正式 Release 上传，也不要把 keystore、签名密码、Token、个人图书和 PDZ 文件提交到公开仓库。
