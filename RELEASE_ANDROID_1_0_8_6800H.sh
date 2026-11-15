#!/usr/bin/env bash
# After feature-branch Android CI passes, push tag v1.0.8 from that exact commit; Actions publishes a signed official Release.
set -Eeuo pipefail
REPO='yanjing-chen/openssreader-android'
BRANCH='feature/scan-enhancement-e3-fix1'
TAG='v1.0.8'
for cmd in git gh python3; do command -v "$cmd" >/dev/null || { echo "缺少 $cmd" >&2; exit 2; }; done
gh auth status >/dev/null
[[ "$(gh api user --jq .login)" == 'yanjing-chen' ]] || { echo 'GitHub 登录账号错误。' >&2; exit 2; }
DEFAULT="$(gh repo view "$REPO" --json defaultBranchRef --jq '.defaultBranchRef.name')"
[[ "$DEFAULT" == 'main' ]] || { echo "默认分支不是 main：$DEFAULT，停止。" >&2; exit 2; }
mkdir -p "$HOME/下载"
WORKDIR="$(mktemp -d "$HOME/下载/OSR-108-Tag-XXXXXX")"
TARGET="$WORKDIR/repository"
gh repo clone "$REPO" "$TARGET" -- --origin origin
[[ -n "$(git -C "$TARGET" ls-remote --heads origin "refs/heads/$BRANCH")" ]] || {
  echo '缺少目标 E3 分支，请先执行 PUSH_ANDROID_1_0_8_BRANCH_6800H.sh。' >&2; exit 3;
}
[[ -z "$(git -C "$TARGET" ls-remote --tags origin "refs/tags/$TAG")" ]] || {
  echo "标签 $TAG 已存在，拒绝修改。" >&2; exit 3;
}
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo "Release $TAG 已存在，不重复发布。" >&2; exit 3
fi
git -C "$TARGET" switch --track -c "$BRANCH" "origin/$BRANCH"
SHA="$(git -C "$TARGET" rev-parse HEAD)"
APP="$TARGET/android/app/build.gradle.kts"
grep -Fq 'versionCode = 10008' "$APP" && grep -Fq 'versionName = "1.0.8"' "$APP" || {
  echo '远程分支还没有更新到版本 1.0.8；先推送分支。' >&2; exit 3;
}
test -s "$TARGET/.github/workflows/release.yml"
grep -Fq "tags: ['v1.0.8']" "$TARGET/.github/workflows/release.yml"
grep -Fq 'import androidx.compose.foundation.horizontalScroll' "$TARGET/android/app/src/main/java/org/openssreader/android/MainActivity.kt"
# CI must have succeeded on the EXACT remote branch HEAD, not an older commit.
RUNS="$(gh run list --workflow android.yml -R "$REPO" --branch "$BRANCH" --event push --limit 80 --json databaseId,headSha,conclusion,status)"
export RUNS SHA
RUN_ID="$(python3 - <<'PY'
import os,json
runs=json.loads(os.environ['RUNS']); sha=os.environ['SHA']
ok=[r for r in runs if r.get('headSha')==sha and r.get('status')=='completed' and r.get('conclusion')=='success']
print(ok[0]['databaseId'] if ok else '')
PY
)"
if [[ -z "$RUN_ID" ]]; then
  echo "此分支当前提交 $SHA 的 Android APK CI 还没有成功；拒绝创建正式标签。" >&2
  echo "请先检查 https://github.com/$REPO/actions/workflows/android.yml" >&2
  exit 4
fi
# Existing keystore only; no new signing identity or credentials.
SECRETS="$(gh secret list -R "$REPO" --json name --jq '.[].name')"
for name in OSR_ANDROID_KEYSTORE_BASE64 OSR_ANDROID_KEYSTORE_PASSWORD OSR_ANDROID_KEY_ALIAS OSR_ANDROID_KEY_PASSWORD; do
  grep -Fxq "$name" <<< "$SECRETS" || { echo "缺少既有发布签名 Secret：$name" >&2; exit 4; }
done
[[ -n "$(git -C "$TARGET" config user.name || :)" && -n "$(git -C "$TARGET" config user.email || :)" ]] || {
  echo '请先配置 Git 提交作者名和邮箱。' >&2; exit 4;
}
echo '======== 正式发布确认 ========'
echo "GitHub: $REPO"
echo "来源: $BRANCH"
echo "Commit: $SHA"
echo "通过的 Actions Run: $RUN_ID"
echo "标签: $TAG"
echo '发布流程：tag -> GitHub Actions -> 正式签名 APK -> 对照 v1.0.7 证书 -> SHA256 -> Stable Release / Latest。'
echo '本地脚本只推送 tag，绝不推送或合并 main，不覆盖旧 tag/release。'
printf '输入 release 才创建正式 v1.0.8 标签：'
read -r CONFIRM
[[ "$CONFIRM" == 'release' ]] || { echo '已取消，未发布。'; exit 0; }
git -C "$TARGET" tag -a "$TAG" -m 'OpenSSReader Android 1.0.8 — Scan Enhancement E3 Fix1 stable'
git -C "$TARGET" push origin "refs/tags/$TAG:refs/tags/$TAG"
echo "已推送 $TAG，自动发布工作流将由 GitHub 执行。"
echo "Actions: https://github.com/$REPO/actions/workflows/release.yml"
echo "Release（工作流成功后）: https://github.com/$REPO/releases/tag/$TAG"
echo '如签名一致性检查或构建失败，工作流不会创建正式 Release，请保存日志再修复。'
