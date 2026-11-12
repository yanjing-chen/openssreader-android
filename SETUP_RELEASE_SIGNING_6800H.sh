#!/usr/bin/env bash
# One-time setup, run only on the user's own 6800H machine. Do not commit keys.
set -Eeuo pipefail
REPO='yanjing-chen/openssreader-android'
KEY="${OSR_KEYSTORE_PATH:-$HOME/.config/openssreader-android/release.keystore}"
command -v gh >/dev/null && command -v keytool >/dev/null || {
  echo '请先安装 gh 与 JDK 17（keytool）' >&2; exit 2;
}
gh auth status >/dev/null
[[ "$(gh api user --jq .login)" == 'yanjing-chen' ]] || { echo 'GitHub 账号不匹配' >&2; exit 3; }
gh repo view "$REPO" >/dev/null
umask 077
mkdir -p "$(dirname "$KEY")"
if [[ ! -e "$KEY" ]]; then
  echo "创建正式签名密钥：$KEY；keytool 会在终端私密提示输入密码。"
  keytool -genkeypair -v -keystore "$KEY" -alias openssreader -keyalg RSA \
    -keysize 3072 -validity 10000 -dname 'CN=OpenSSReader Android, O=OpenSSReader'
fi
chmod 600 "$KEY"
echo "务必把 $KEY 离线备份。丢失密钥会使后续发布无法覆盖升级。"
read -r -s -p '请输入密钥库密码：' storepass; echo
read -r -s -p '请输入 openssreader 密钥密码（可能与密钥库密码相同）：' keypass; echo
[[ -n "$storepass" && -n "$keypass" ]] || { echo '密码不能为空' >&2; exit 4; }
# GitHub CLI encrypts each secret before transmitting; no secrets enter Git history.
base64 -w0 "$KEY" | gh secret set -R "$REPO" OSR_ANDROID_KEYSTORE_BASE64
printf '%s' "$storepass" | gh secret set -R "$REPO" OSR_ANDROID_KEYSTORE_PASSWORD
printf 'openssreader' | gh secret set -R "$REPO" OSR_ANDROID_KEY_ALIAS
printf '%s' "$keypass" | gh secret set -R "$REPO" OSR_ANDROID_KEY_PASSWORD
unset storepass keypass
echo '正式发布密钥已保存在 GitHub Actions Secrets；密钥文件仍仅位于你的电脑。'
echo '再执行：bash ./PUSH_FROM_6800H.sh 发布 v1.0.0。'
