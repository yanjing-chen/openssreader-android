#!/usr/bin/env bash
# 从本地完整源码 ZIP 创建 GitHub 新分支，不推送/修改 main，也不创建 tag。
set -Eeuo pipefail

REPO='yanjing-chen/openssreader-android'
BRANCH='feature/scan-enhancement-e3-fix1'
ZIP_DEFAULT='OpenSSReader-Android-1.0.7-ScanEnhance-E3-Fix1-FullSource.zip'
ZIP_SAFE='OpenSSReader-Android-1.0.7-ScanEnhance-E3-Fix1-GitBranch-FullSource.zip'
DOWNLOADS="$HOME/下载"
SOURCE_ZIP="${1:-}"
if [[ -z "$SOURCE_ZIP" ]]; then
  if [[ -f "./$ZIP_SAFE" ]]; then SOURCE_ZIP="./$ZIP_SAFE"
  elif [[ -f "$DOWNLOADS/$ZIP_SAFE" ]]; then SOURCE_ZIP="$DOWNLOADS/$ZIP_SAFE"
  elif [[ -f "./$ZIP_DEFAULT" ]]; then SOURCE_ZIP="./$ZIP_DEFAULT"
  else SOURCE_ZIP="$DOWNLOADS/$ZIP_DEFAULT"
  fi
fi

for command_name in gh git rsync unzip python3; do
  command -v "$command_name" >/dev/null || { echo "错误：缺少 $command_name" >&2; exit 2; }
done
[[ -f "$SOURCE_ZIP" ]] || { echo "找不到源码 ZIP：$SOURCE_ZIP" >&2; exit 2; }
unzip -tqq "$SOURCE_ZIP" || { echo '源码 ZIP 完整性检查失败' >&2; exit 2; }

gh auth status >/dev/null
USER_LOGIN="$(gh api user --jq '.login')"
[[ "$USER_LOGIN" == 'yanjing-chen' ]] || { echo "当前 gh 登录的是 $USER_LOGIN，不是 yanjing-chen，停止" >&2; exit 2; }
REPO_INFO="$(gh repo view "$REPO" --json nameWithOwner,visibility,defaultBranchRef --jq '[.nameWithOwner,.visibility,.defaultBranchRef.name] | @tsv')"
IFS=$'\t' read -r FULL_REPO VISIBILITY DEFAULT_BRANCH <<< "$REPO_INFO"
[[ "$FULL_REPO" == "$REPO" ]] || { echo "仓库不匹配：$FULL_REPO" >&2; exit 2; }
echo "已登录：$USER_LOGIN"
echo "目标仓库：$FULL_REPO（$VISIBILITY）"
echo "仓库默认分支：$DEFAULT_BRANCH（仅作为分支基线，不修改）"
echo "准备创建：$BRANCH"

mkdir -p "$DOWNLOADS"
WORKDIR="$(mktemp -d "$DOWNLOADS/OSR-E3-GitBranch-XXXXXX")"
mkdir -p "$WORKDIR/source"
unzip -q "$SOURCE_ZIP" -d "$WORKDIR/source"
SOURCE="$WORKDIR/source/OpenSSReader-Android-ScanEnhance-E3"
[[ -f "$SOURCE/android/app/src/main/java/org/openssreader/android/MainActivity.kt" ]] || { echo 'ZIP 内容不符合预期，停止' >&2; exit 2; }
grep -Fq 'import androidx.compose.foundation.horizontalScroll' "$SOURCE/android/app/src/main/java/org/openssreader/android/MainActivity.kt" || {
  echo '源码未包含 E3-Fix1 horizontalScroll 修复，拒绝推送' >&2; exit 2;
}

TARGET="$WORKDIR/repository"
gh repo clone "$REPO" "$TARGET" -- --origin origin
# 不在现有本地 Git 工作树操作；全程使用新的独立 clone。
[[ -n "$(git -C "$TARGET" rev-parse --verify refs/remotes/origin/main 2>/dev/null)" ]] || { echo '仓库没有 origin/main，拒绝猜测基线' >&2; exit 2; }
REMOTE_BRANCHES="$(git -C "$TARGET" ls-remote --heads origin "refs/heads/$BRANCH")" || { echo '无法确认远程分支状态' >&2; exit 2; }
[[ -z "$REMOTE_BRANCHES" ]] || { echo "远程分支 $BRANCH 已存在，为防止覆盖，停止" >&2; exit 2; }

git -C "$TARGET" switch --create "$BRANCH" origin/main

# 仅将 ZIP 所有源码同步至新克隆：不包含 .git、编译输出或私密签名文件。
RSYNC_EXCLUDES=(
  --exclude='.git/' --exclude='.gradle/' --exclude='**/build/'
  --exclude='local.properties' --exclude='*.jks' --exclude='*.keystore'
  --exclude='*.p12' --exclude='*.apk' --exclude='*.aab'
  --exclude='*.log' --exclude='.env' --exclude='.env.*'
  --exclude='.idea/' --exclude='__pycache__/'
)
rsync -a --delete "${RSYNC_EXCLUDES[@]}" "$SOURCE/" "$TARGET/"
# 总是覆盖掉 ZIP 中遗留的旧脚本（原脚本会直接推送 main）。
SCRIPT_SELF="$(readlink -f "$0")"
cp "$SCRIPT_SELF" "$TARGET/PUSH_FROM_6800H.sh"
chmod +x "$TARGET/PUSH_FROM_6800H.sh"

# 校验且展示将提交的完整变更。
git -C "$TARGET" add -A
echo '===== 本次新分支源码变更 ====='
git -C "$TARGET" status --short
git -C "$TARGET" diff --cached --stat
if git -C "$TARGET" diff --cached --quiet; then
  echo '没有发现与 origin/main 不同的源码，停止推送。'
  exit 0
fi
[[ -n "$(git -C "$TARGET" config user.name || true)" && -n "$(git -C "$TARGET" config user.email || true)" ]] || {
  echo '缺少 Git 作者名或邮箱。请先配置 git config --global user.name / user.email，再重试。' >&2
  exit 2
}
echo
echo '安全确认：本脚本将仅创建并推送以下新分支：'
echo "  $REPO :: $BRANCH"
echo '不会向 main 推送，不会覆盖远程分支，不会创建 tag / Release。'
printf '输入 yes 执行提交和推送：'
read -r CONFIRM
[[ "$CONFIRM" == 'yes' ]] || { echo "已取消推送。检视目录：$TARGET"; exit 0; }

git -C "$TARGET" commit -m 'feat(android): scan enhancement E3 Fix1 and reference-page cropping'
git -C "$TARGET" push --set-upstream origin "$BRANCH"
echo
echo '===== 已完成，仅新分支 ====='
echo "https://github.com/$REPO/tree/$BRANCH"
echo "本地新工作树：$TARGET"
