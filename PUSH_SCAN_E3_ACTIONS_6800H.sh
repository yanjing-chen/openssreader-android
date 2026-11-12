#!/usr/bin/env bash
# Push E3-specific Actions + release tooling ONLY to its feature branch.
# If branch does not exist yet: create from origin/main and import this full source snapshot.
# No force push, no main push, no release/tag creation.
set -Eeuo pipefail
REPO='yanjing-chen/openssreader-android'
BRANCH='feature/scan-enhancement-e3-fix1'
SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for cmd in git gh rsync python3; do command -v "$cmd" >/dev/null || { echo "缺少 $cmd" >&2; exit 2; }; done
[[ -s "$SOURCE/.github/workflows/scan-enhancement-e3.yml" && -s "$SOURCE/SCAN_E3_PRERELEASE_NOTES_CN.md" ]] || {
  echo '请从本次完整源码包中运行此脚本，不能直接运行旧版。' >&2; exit 2;
}
grep -Fq 'import androidx.compose.foundation.horizontalScroll' "$SOURCE/android/app/src/main/java/org/openssreader/android/MainActivity.kt" || {
  echo '当前源码不是 E3-Fix1：缺少编译修复，停止。' >&2; exit 2;
}
gh auth status >/dev/null
LOGIN="$(gh api user --jq .login)"
[[ "$LOGIN" == 'yanjing-chen' ]] || { echo "GitHub 当前登录的是 $LOGIN，不是 yanjing-chen" >&2; exit 2; }
INFO="$(gh repo view "$REPO" --json nameWithOwner,defaultBranchRef,visibility --jq '[.nameWithOwner,.defaultBranchRef.name,.visibility] | @tsv')"
IFS=$'\t' read -r FULL DEFAULT VISIBILITY <<< "$INFO"
[[ "$FULL" == "$REPO" && "$DEFAULT" == main ]] || {
  echo "仓库或默认分支不符合预期：$FULL / $DEFAULT。停止。" >&2; exit 2;
}
echo "GitHub: $LOGIN / $FULL ($VISIBILITY)"
echo "仅操作分支：$BRANCH；绝不推送 main。"
WORKDIR="$(mktemp -d "$HOME/下载/OSR-E3-Actions-XXXXXX")"
TARGET="$WORKDIR/repository"
echo "独立 Git 工作目录：$TARGET"
gh repo clone "$REPO" "$TARGET" -- --origin origin
if [[ -n "$(git -C "$TARGET" ls-remote --heads origin "refs/heads/$BRANCH")" ]]; then
  echo '远端 E3 分支已存在：只更新 Actions / 预发布工具，不覆盖 Android 源码。'
  git -C "$TARGET" switch --track -c "$BRANCH" "origin/$BRANCH"
  [[ -f "$TARGET/android/app/src/main/java/org/openssreader/android/ReaderImageEnhancer.kt" ]] || {
    echo '远程分支缺少 E3 图像增强文件，拒绝修改。' >&2; exit 3;
  }
  grep -Fq 'import androidx.compose.foundation.horizontalScroll' "$TARGET/android/app/src/main/java/org/openssreader/android/MainActivity.kt" || {
    echo '远程分支缺少 E3-Fix1 编译修复；请先检查既有分支。' >&2; exit 3;
  }
  git -C "$TARGET" diff --quiet && git -C "$TARGET" diff --cached --quiet || {
    echo '远端克隆目录不是干净状态，停止。' >&2; exit 3;
  }
  mkdir -p "$TARGET/.github/workflows"
  cp "$SOURCE/.github/workflows/scan-enhancement-e3.yml" "$TARGET/.github/workflows/scan-enhancement-e3.yml"
  for name in SCAN_E3_PRERELEASE_NOTES_CN.md SCAN_E3_ACTIONS_RELEASE_CN.md PUSH_SCAN_E3_ACTIONS_6800H.sh RELEASE_SCAN_E3_RC1_6800H.sh; do
    cp "$SOURCE/$name" "$TARGET/$name"
  done
  chmod +x "$TARGET/PUSH_SCAN_E3_ACTIONS_6800H.sh" "$TARGET/RELEASE_SCAN_E3_RC1_6800H.sh"
else
  echo '远端 E3 分支尚不存在：从 origin/main 创建，导入完整 E3-Fix1 源码（不修改 main）。'
  git -C "$TARGET" switch -c "$BRANCH" origin/main
  rsync -a --delete \
    --exclude='.git/' --exclude='.gradle/' --exclude='**/build/' \
    --exclude='local.properties' --exclude='*.jks' --exclude='*.keystore' \
    --exclude='*.p12' --exclude='*.apk' --exclude='*.aab' \
    --exclude='*.log' --exclude='.env' --exclude='.env.*' \
    --exclude='.idea/' --exclude='__pycache__/' \
    "$SOURCE/" "$TARGET/"
fi
# Refuse any attempt to update files that should be unaffected on existing branch.
git -C "$TARGET" add -A
echo '===== 将提交到独立 E3 分支的变更 ====='
git -C "$TARGET" status --short
git -C "$TARGET" diff --cached --stat
if git -C "$TARGET" diff --cached --quiet; then
  echo "没有新内容。$BRANCH 保持不变。"
  exit 0
fi
[[ -n "$(git -C "$TARGET" config user.name || :)" && -n "$(git -C "$TARGET" config user.email || :)" ]] || {
  echo '请先配置 Git 作者名和邮箱（git config --global user.name / user.email）。' >&2; exit 3;
}
[[ "$(git -C "$TARGET" branch --show-current)" == "$BRANCH" ]] || exit 3
printf '\n仅向 %s 推送新提交。输入 yes 确认：' "$BRANCH"
read -r CONFIRM
[[ "$CONFIRM" == yes ]] || { echo '已取消。'; exit 0; }
git -C "$TARGET" commit -m 'ci(android): E3 feature-branch Actions and signed prerelease workflow'
# Explicit refspec to branch; never uses push.default or --force.
git -C "$TARGET" push origin "HEAD:refs/heads/$BRANCH"
echo "完成：https://github.com/$REPO/tree/$BRANCH"
echo "Actions：https://github.com/$REPO/actions"
echo "下一步：等待该分支 Actions 构建成功，然后运行 bash ./RELEASE_SCAN_E3_RC1_6800H.sh"
