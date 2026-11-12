#!/usr/bin/env bash
# Create the E3 candidate tag from E3 branch tip locally; GitHub Actions builds and publishes signed APK.
# Never push a branch, never create a stable v* tag, never remove a prior tag/release.
set -Eeuo pipefail
REPO='yanjing-chen/openssreader-android'
BRANCH='feature/scan-enhancement-e3-fix1'
TAG="${1:-scan-e3-fix1-rc1}"
[[ "$TAG" =~ ^scan-e3-fix1-rc[1-9][0-9]*$ ]] || {
  echo '标签必须是 scan-e3-fix1-rc1（或 rc2、rc3 等），禁止 v* 正式标签。' >&2; exit 2;
}
for cmd in git gh python3; do command -v "$cmd" >/dev/null || { echo "缺少 $cmd" >&2; exit 2; }; done
gh auth status >/dev/null
[[ "$(gh api user --jq .login)" == 'yanjing-chen' ]] || { echo '不是目标 GitHub 账号，停止。' >&2; exit 2; }
DEFAULT="$(gh repo view "$REPO" --json defaultBranchRef --jq '.defaultBranchRef.name')"
[[ "$DEFAULT" == main ]] || { echo '默认分支不是 main，拒绝猜测。' >&2; exit 2; }
mkdir -p "$HOME/下载"
WORKDIR="$(mktemp -d "$HOME/下载/OSR-E3-RC-XXXXXX")"
TARGET="$WORKDIR/repository"
gh repo clone "$REPO" "$TARGET" -- --origin origin
[[ -n "$(git -C "$TARGET" ls-remote --heads origin "refs/heads/$BRANCH")" ]] || {
  echo '还没有 E3 feature 分支；先执行 PUSH_SCAN_E3_ACTIONS_6800H.sh。' >&2; exit 3;
}
[[ -z "$(git -C "$TARGET" ls-remote --tags origin "refs/tags/$TAG")" ]] || {
  echo "远程标签 $TAG 已存在，为防止覆盖停止。请使用新的 rc 编号。" >&2; exit 3;
}
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo '同名 Release 已存在，停止。' >&2; exit 3;
fi
git -C "$TARGET" switch --track -c "$BRANCH" "origin/$BRANCH"
SHA="$(git -C "$TARGET" rev-parse HEAD)"
test -s "$TARGET/.github/workflows/scan-enhancement-e3.yml"
grep -Fq 'import androidx.compose.foundation.horizontalScroll' "$TARGET/android/app/src/main/java/org/openssreader/android/MainActivity.kt"
# An Actions run for exactly this branch head must have passed before tagging.
RUNS="$(gh run list -R "$REPO" --branch "$BRANCH" --event push --limit 40 \
  --json databaseId,headSha,conclusion,status,workflowName)"
export RUNS SHA
RUN_ID="$(python3 - <<'PY'
import os,json
runs=json.loads(os.environ['RUNS']); sha=os.environ['SHA']
target=[r for r in runs if r.get('headSha') == sha and r.get('workflowName') == 'Scan Enhancement E3 - CI and Prerelease']
ok=[r for r in target if r.get('status') == 'completed' and r.get('conclusion') == 'success']
print(ok[0]['databaseId'] if ok else '')
PY
)"
if [[ -z "$RUN_ID" ]]; then
  echo "当前分支 HEAD ($SHA) 尚无成功的 E3 CI 运行；请先查看：https://github.com/$REPO/actions" >&2
  echo '不创建标签，避免失败版本自动发布。' >&2
  exit 4
fi
# Reuse official repository signing Secrets; names only, do not read secret contents.
SECRETS="$(gh secret list -R "$REPO" --json name --jq '.[].name')"
for name in OSR_ANDROID_KEYSTORE_BASE64 OSR_ANDROID_KEYSTORE_PASSWORD OSR_ANDROID_KEY_ALIAS OSR_ANDROID_KEY_PASSWORD; do
  grep -Fxq "$name" <<< "$SECRETS" || {
    echo "缺少既有签名 Secret：$name；停止。不要重新生成签名密钥。" >&2; exit 4;
  }
done
echo '===== 发布前检查 ====='
echo "仓库：$REPO"
echo "来源：$BRANCH"
echo "Commit：$SHA"
echo "已通过的 Actions run：$RUN_ID"
echo "即将创建并仅推送 tag：$TAG"
echo '后续工作流将使用已有正式签名密钥生成 Pre-release（不设 Latest）。'
echo '本操作不推送 main、不推送其它分支，不会生成新的密钥。'
printf '输入 yes 创建并推送标签：'
read -r CONFIRM
[[ "$CONFIRM" == yes ]] || { echo '已取消。'; exit 0; }
[[ -n "$(git -C "$TARGET" config user.name || :)" && -n "$(git -C "$TARGET" config user.email || :)" ]] || {
  echo '请先配置 Git 作者名和邮箱。' >&2; exit 4;
}
git -C "$TARGET" tag -a "$TAG" -m "OpenSSReader Android ScanEnhance E3 Fix1 candidate $TAG"
git -C "$TARGET" push origin "refs/tags/$TAG:refs/tags/$TAG"
echo "已推送预发布标签：$TAG"
echo "Actions: https://github.com/$REPO/actions"
echo "Release（仅工作流成功后出现）: https://github.com/$REPO/releases/tag/$TAG"
echo '如签名或 Gradle 构建失败，不会创建 Release；请提供 Actions 日志。'
