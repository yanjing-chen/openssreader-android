#!/usr/bin/env bash
# Creates / updates the GitHub repository using the user's *local* gh login.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
REPO="yanjing-chen/openssreader-android"
command -v gh >/dev/null || { echo '请先安装 GitHub CLI (gh)' >&2; exit 2; }
command -v git >/dev/null || { echo '需要 git' >&2; exit 2; }
gh auth status >/dev/null || { echo '先运行 gh auth login 完成你自己的本地登录' >&2; exit 2; }
ACCOUNT="$(gh api user --jq '.login')"
[[ "$ACCOUNT" == "yanjing-chen" ]] || { echo "GitHub 当前账号是 $ACCOUNT，不是 yanjing-chen；已停止。" >&2; exit 3; }
if ! gh repo view "$REPO" >/dev/null 2>&1; then
    echo "创建公开仓库 $REPO（供应用无凭据检查 Release 更新）"
    gh repo create "$REPO" --public --description 'OpenSSReader Android — GeckoView PDZ reader'
fi
if [[ ! -d .git ]]; then git init -b main; fi
if ! git config user.name >/dev/null || ! git config user.email >/dev/null; then
    echo '请先配置 Git 作者信息：git config --global user.name 和 user.email' >&2
    exit 4
fi
if git remote get-url origin >/dev/null 2>&1; then
    REMOTE="$(git remote get-url origin)"
    case "$REMOTE" in
      "https://github.com/$REPO.git"|"git@github.com:$REPO.git") : ;;
      *) echo "现有 origin 指向 $REMOTE，拒绝误推送" >&2; exit 4;;
    esac
else
    git remote add origin "https://github.com/$REPO.git"
fi
git add -A
if ! git diff --cached --quiet; then git commit -m 'Release OpenSSReader Android 1.0.0 with GeckoView and updater'; fi
git branch -M main
git push -u origin main
echo "代码已推送：https://github.com/$REPO"
required=(OSR_ANDROID_KEYSTORE_BASE64 OSR_ANDROID_KEYSTORE_PASSWORD OSR_ANDROID_KEY_ALIAS OSR_ANDROID_KEY_PASSWORD)
SECRETS="$(gh secret list -R "$REPO" | awk '{print $1}')"
missing=()
for s in "${required[@]}"; do
    grep -Fxq "$s" <<< "$SECRETS" || missing+=("$s")
done
if (( ${#missing[@]} > 0 )); then
    echo 'GitHub Actions 正式签名尚未设置完整；本次只推送源码，暂不创建正式版 tag。'
    printf '缺少密钥：%s\n' "${missing[*]}"
    echo '请执行：bash ./SETUP_RELEASE_SIGNING_6800H.sh ，然后重新运行本脚本。'
    exit 0
fi
if git rev-parse 'refs/tags/v1.0.0' >/dev/null 2>&1; then
    echo '检测到 v1.0.0 标签已经存在；不会直接重推旧标签（否则会使用旧代码）。'
    echo '如果上一次 Release 因 lint 失败且尚未正式发布，请使用：'
    echo '  bash ./RETRY_FAILED_RELEASE_6800H.sh /path/to/existing/git/checkout'
    exit 0
fi
printf '\n即将推送 v1.0.0 触发正式签名构建与公开 Release。\n'
read -r -p '你已核实正式签名、数据备份，并同意发布吗？输入 yes：' answer
[[ "$answer" == 'yes' ]] || { echo '未创建发布标签。'; exit 0; }
echo '发布前再次执行本地 Android 编译及单元测试。'
bash ./BUILD_ANDROID_6800H.sh
git tag -a v1.0.0 -m 'OpenSSReader Android 1.0.0'
git push origin v1.0.0
echo "已触发：https://github.com/$REPO/actions"
