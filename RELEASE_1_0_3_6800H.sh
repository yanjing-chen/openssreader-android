#!/usr/bin/env bash
# Publish a *new*, signed v1.0.3 release from the user's existing Git worktree.
# No password/key material is read, printed, or written by this helper.
set -Eeuo pipefail
REPO='yanjing-chen/openssreader-android'
TAG='v1.0.3'
TARGET="${1:-}"
[[ -n "$TARGET" ]] || { echo '用法：bash ./RELEASE_1_0_3_6800H.sh /path/to/已有Git仓库' >&2; exit 2; }
TARGET="$(cd "$TARGET" && pwd)"
[[ -d "$TARGET/.git" || -f "$TARGET/.git" ]] || { echo '请指定包含 .git 的原仓库工作树' >&2; exit 2; }
command -v git >/dev/null && command -v gh >/dev/null || { echo '需要 git 和 gh' >&2; exit 2; }
gh auth status >/dev/null
[[ "$(gh api user --jq .login)" == 'yanjing-chen' ]] || { echo '当前 gh 登录账号不是 yanjing-chen' >&2; exit 3; }
remote="$(git -C "$TARGET" remote get-url origin)"
case "$remote" in
  https://github.com/yanjing-chen/openssreader-android.git|https://github.com/yanjing-chen/openssreader-android|git@github.com:yanjing-chen/openssreader-android.git|git@github.com:yanjing-chen/openssreader-android) ;;
  *) echo "origin 不是指定仓库：$remote" >&2; exit 3;;
esac
[[ "$(git -C "$TARGET" branch --show-current)" == 'main' ]] || { echo '必须位于 main 分支' >&2; exit 3; }
[[ -z "$(git -C "$TARGET" status --porcelain)" ]] || { echo '工作树有未提交改动；拒绝发布' >&2; exit 3; }
git -C "$TARGET" fetch origin main --tags
head="$(git -C "$TARGET" rev-parse HEAD)"
[[ "$head" == "$(git -C "$TARGET" rev-parse origin/main)" ]] || { echo '本地 HEAD 与 origin/main 不一致' >&2; exit 3; }
grep -Fq 'versionName = "1.0.3"' "$TARGET/android/app/build.gradle.kts" || { echo '版本名不是1.0.3' >&2; exit 3; }
grep -Fq 'versionCode = 10003' "$TARGET/android/app/build.gradle.kts" || { echo 'versionCode不是10003' >&2; exit 3; }
[[ -f "$TARGET/.github/workflows/release.yml" ]] || { echo '找不到正式发布工作流' >&2; exit 3; }
if git -C "$TARGET" rev-parse -q --verify "refs/tags/$TAG" >/dev/null ||
   git -C "$TARGET" ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1 ||
   gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    echo 'v1.0.3 已存在 tag 或 Release；为安全起见拒绝覆盖与重复发布。' >&2
    exit 3
fi
# Only list secret *names*: never access or print secret values.
secret_names="$(gh secret list -R "$REPO" --json name --jq '.[].name')"
for required in OSR_ANDROID_KEYSTORE_BASE64 OSR_ANDROID_KEYSTORE_PASSWORD OSR_ANDROID_KEY_ALIAS OSR_ANDROID_KEY_PASSWORD; do
    if ! grep -Fxq "$required" <<< "$secret_names"; then
        echo "缺少签名 Secret: $required。请在原有本地密钥基础上恢复，不要重新生成新密钥。" >&2
        exit 3
    fi
done
CI_OK="$(gh run list -R "$REPO" --workflow 'Android APK CI' --commit "$head" \
     --limit 30 --json conclusion,headSha --jq '[.[] | select(.conclusion == "success")] | length')"
if [[ ! "$CI_OK" =~ ^[0-9]+$ ]] || (( CI_OK < 1 )); then
    echo "这个 commit ($head) 尚无成功的 Android APK CI；暂不创建 tag。" >&2
    echo "检查：gh run list -R $REPO --workflow 'Android APK CI' --limit 5" >&2
    exit 3
fi
printf '已核验仓库、1.0.3 版本、CI 成功、4 个签名 Secret。\n将新建 tag %s 指向 %s 并推送触发正式签名 Release。\n输入 release 确认：' "$TAG" "$head"
read -r confirm
[[ "$confirm" == release ]] || { echo '取消发布，无修改。'; exit 0; }
git -C "$TARGET" tag -a "$TAG" -m 'OpenSSReader Android 1.0.3 - GeckoView native multi-tab and bookshelf improvements'
git -C "$TARGET" push origin "refs/tags/$TAG"
echo '标签已推送，GitHub Actions 将自动构建正式签名 APK 并创建 Release。'
echo "检查：gh run list -R $REPO --workflow 'Android Signed Release' --limit 5"
echo "发布页：https://github.com/$REPO/releases/tag/$TAG"
echo '只有 Release 工作流成功并出现签名 APK 和 .sha256 文件后，才视为正式发布完成。'
