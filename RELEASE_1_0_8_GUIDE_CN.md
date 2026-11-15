# OpenSSReader Android v1.0.8 正式发布（本地推送 → Actions → Release）

## 项目及来源
- GitHub 仓库：`yanjing-chen/openssreader-android`
- 已在平板实机验收的基线：E3-Fix1 (`scan-e3-fix1-rc1`)
- 唯一推送分支：`feature/scan-enhancement-e3-fix1`（不推送、不合并 `main`）
- 正式 tag：`v1.0.8`（推送时触发现有 `release.yml`）
- Android 包名：`org.openssreader.android`；`versionName=1.0.8`；`versionCode=10008`

## 第一步：在 6800H 推送版本修改和 CI

下载本次完整源码 ZIP 至 `~/下载`，然后：

```bash
cd ~/下载
WORKDIR="$(mktemp -d "$HOME/下载/OSR-108-XXXXXX")"
unzip -q OpenSSReader-Android-1.0.8-FullSource.zip -d "$WORKDIR"
cd "$WORKDIR/OpenSSReader-Android-1.0.8"
gh auth status
bash ./PUSH_ANDROID_1_0_8_BRANCH_6800H.sh
```

此脚本安全地重新克隆远程仓库，在独立目录检查远程 E3 分支的 Android 应用源码是否与已验收 E3-Fix1 完全一致，再只同步版本、构建检查和工作流；会展示提交文件，必须手动输入 `yes` 才推送。**绝不操作 main。**

推送后会自动触发 `.github/workflows/android.yml` 对 E3 feature 分支的 CI。请检查：https://github.com/yanjing-chen/openssreader-android/actions/workflows/android.yml 。需要等待构建、单元测试、lint 全部成功。

## 第二步：在 6800H 创建正式 v1.0.8 标签

仍在同一个源码目录，执行：

```bash
bash ./RELEASE_ANDROID_1_0_8_6800H.sh
```

脚本将检查：目标仓库/分支、`v1.0.8` 不存在、当前分支最新提交与通过的 CI `headSha` 完全一致、签名 Secrets 名称完整，然后等待你输入 `release`。只创建并推送 annotated tag `v1.0.8`，不修改 `main`。

GitHub 的 `release.yml` 会在 tag 对应源码上再次运行 Android 编译、单元测试、release lint，再使用原有正式签名 Secrets 构建 APK，检查签名证书与 `v1.0.7` 正式 APK 完全一致，通过后创建 Stable/Latest Release，并上传：

- `OpenSSReader-Android-v1.0.8.apk`
- `OpenSSReader-Android-v1.0.8.apk.sha256`

Release：https://github.com/yanjing-chen/openssreader-android/releases/tag/v1.0.8

## 第三步：下载 APK、校验并安全升级平板

```bash
cd ~/下载
mkdir -p OSR-Android-1.0.8

gh release download v1.0.8 -R yanjing-chen/openssreader-android \
  -D OSR-Android-1.0.8 -p '*.apk' -p '*.sha256'

cd OSR-Android-1.0.8
sha256sum -c OpenSSReader-Android-v1.0.8.apk.sha256
adb devices -l

# 只有一台授权设备连接时：
adb install -r OpenSSReader-Android-v1.0.8.apk
adb shell monkey -p org.openssreader.android -c android.intent.category.LAUNCHER 1
adb shell dumpsys package org.openssreader.android | grep -m1 versionName
```

如果连接了多台设备：在 `adb devices -l` 里找到设备序列号，执行 `adb -s "序列号" install -r OpenSSReader-Android-v1.0.8.apk`；`adb shell` 相关命令同样加 `-s "序列号"`。

遇到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 时不要卸载旧版或清除应用数据；先检查原安装包是否 debug 签名。Release APK 由现有正式签名证书构建并与 v1.0.7 校验一致。

## 安全与版本注意

- 此包只调整发布版本、CI、文档和必要的版本号静态检查。Android 核心 `app/src` 与已测试 E3-Fix1 保持逐字节一致。
- 1.0.8 是正式版（非预发布版），APK 版本号必须大于 1.0.7，方可正常升级。
- 只有 Release Actions 成功才产生正式下载附件。任何失败都需要日志；不可强制重打现有 tag。
- 不提交私钥、签名口令或 `local.properties`；签名继续存放在原 GitHub Secrets。
