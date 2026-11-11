#!/usr/bin/env bash
# Safe local-only sync into the user's existing Android repository; no tag rewriting.
set -Eeuo pipefail
SOURCE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-}"
if [[ -z "$TARGET" ]]; then
    echo '用法：bash ./PUSH_FROM_6800H.sh /path/to/原有Git工作树（带.git）' >&2
    exit 2
fi
TARGET="$(cd "$TARGET" && pwd)"
[[ "$SOURCE" != "$TARGET" ]] || { echo '请解压源码到新目录，再指定原有 Git 仓库。' >&2; exit 2; }
[[ -d "$TARGET/.git" || -f "$TARGET/.git" ]] || { echo '目标必须是原有 Git 工作树，不能是新建目录。' >&2; exit 2; }
command -v gh >/dev/null && command -v git >/dev/null && command -v rsync >/dev/null || { echo '需要 gh、git、rsync' >&2; exit 2; }
gh auth status >/dev/null
ACCOUNT="$(gh api user --jq .login)"
[[ "$ACCOUNT" == 'yanjing-chen' ]] || { echo "GitHub 账号不符：$ACCOUNT" >&2; exit 3; }
REMOTE="$(git -C "$TARGET" remote get-url origin)"
case "$REMOTE" in
  'https://github.com/yanjing-chen/openssreader-android.git'|'https://github.com/yanjing-chen/openssreader-android'|'git@github.com:yanjing-chen/openssreader-android.git'|'git@github.com:yanjing-chen/openssreader-android') : ;;
  *) echo "仓库 origin 不符：$REMOTE" >&2; exit 3 ;;
esac
[[ "$(git -C "$TARGET" branch --show-current)" == 'main' ]] || { echo '只允许 main 分支' >&2; exit 3; }
[[ -z "$(git -C "$TARGET" status --porcelain)" ]] || { echo '原有 Git 仓库有未提交改动，请先处理。' >&2; exit 3; }
git -C "$TARGET" fetch origin main --tags
[[ "$(git -C "$TARGET" rev-parse HEAD)" == "$(git -C "$TARGET" rev-parse origin/main)" ]] || { echo '本地 main 与远程不同步，拒绝覆盖。' >&2; exit 3; }
if git -C "$TARGET" ls-remote --exit-code --tags origin refs/tags/v1.0.3 >/dev/null 2>&1; then
    echo '远程 v1.0.3 标签已存在，本脚本不会覆盖已有发布。' >&2
    exit 3
fi
# Keep the remote working tree as lean as the current full source bundle.
# rsync exclusions protect .git, local SDK configuration, credentials and caches.
echo '===== 目标工作树将同步的文件（含过时文件删除） ====='
RSYNC_EXCLUDES=(
    --exclude='.git/' --exclude='.gradle/' --exclude='build/'
    --exclude='local.properties' --exclude='*.jks' --exclude='*.keystore'
    --exclude='*.log' --exclude='.env' --exclude='.env.*'
    --exclude='.idea/' --exclude='__pycache__/'
)
rsync -ain --delete "${RSYNC_EXCLUDES[@]}" "$SOURCE/" "$TARGET/" | sed -n '1,160p' 
printf '\n本次会清理原仓库中不再属于源码包的文件，继续请输入 yes：'
read -r SYNC_CONFIRM
[[ "$SYNC_CONFIRM" == 'yes' ]] || { echo '未修改原仓库。'; exit 0; }
rsync -a --delete "${RSYNC_EXCLUDES[@]}" "$SOURCE/" "$TARGET/"
# One obsolete first-release-only script must not remain in the Android repo.
if [[ -f "$TARGET/RETRY_FAILED_RELEASE_6800H.sh" ]]; then
    rm "$TARGET/RETRY_FAILED_RELEASE_6800H.sh"
fi
git -C "$TARGET" add -A
echo '===== 即将提交的改动 ====='
git -C "$TARGET" diff --cached --stat
if git -C "$TARGET" diff --cached --quiet; then
    echo '没有新的源码改动。'
    exit 0
fi
printf '\n只推送源码至 main，不创建 tag；输入 yes 确认：'
read -r CONFIRM
[[ "$CONFIRM" == 'yes' ]] || { echo '未提交、未推送；已复制文件，请自行检查工作树。'; exit 0; }
git -C "$TARGET" commit -m 'Prepare Android 1.0.3: GeckoView native multi-tab and bookshelf improvements'
git -C "$TARGET" push origin main
echo '已推送 main。当前多标签版已实机验收；请等待这个 commit 的 CI 通过，再运行正式发布脚本创建 v1.0.3 标签。'
