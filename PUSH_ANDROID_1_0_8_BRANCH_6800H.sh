#!/usr/bin/env bash
# Locally push official 1.0.8 promotion to TESTED feature branch only; never push main, force, or tag.
set -Eeuo pipefail
REPO='yanjing-chen/openssreader-android'
BRANCH='feature/scan-enhancement-e3-fix1'
SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for cmd in git gh diff python3; do command -v "$cmd" >/dev/null || { echo "缺少 $cmd" >&2; exit 2; }; done
[[ -f "$SOURCE/android/app/build.gradle.kts" && -f "$SOURCE/RELEASE_1_0_8_GUIDE_CN.md" && -f "$SOURCE/.github/workflows/release.yml" ]] || {
  echo '请在 1.0.8 完整源码包根目录运行脚本。' >&2; exit 2;
}
grep -Fq 'versionCode = 10008' "$SOURCE/android/app/build.gradle.kts"
grep -Fq 'versionName = "1.0.8"' "$SOURCE/android/app/build.gradle.kts"
python3 "$SOURCE/android/verification/check_release_current.py"
python3 "$SOURCE/android/verification/check_reader_enhancement_ui.py"
gh auth status >/dev/null
[[ "$(gh api user --jq .login)" == 'yanjing-chen' ]] || { echo 'GitHub 登录账号不是 yanjing-chen。' >&2; exit 2; }
INFO="$(gh repo view "$REPO" --json nameWithOwner,defaultBranchRef,visibility --jq '[.nameWithOwner,.defaultBranchRef.name,.visibility] | @tsv')"
IFS=$'\t' read -r FULL DEFAULT VISIBILITY <<< "$INFO"
[[ "$FULL" == "$REPO" && "$DEFAULT" == 'main' ]] || { echo "仓库/默认分支异常：$FULL $DEFAULT" >&2; exit 2; }
echo "账号/仓库：$FULL ($VISIBILITY)"
echo "唯一允许推送的分支：$BRANCH（绝不推送 main）"
WORKDIR="$(mktemp -d "$HOME/下载/OSR-108-Push-XXXXXX")"
TARGET="$WORKDIR/repository"
echo "独立工作目录：$TARGET"
gh repo clone "$REPO" "$TARGET" -- --origin origin
[[ -n "$(git -C "$TARGET" ls-remote --heads origin "refs/heads/$BRANCH")" ]] || {
  echo '尚无 E3 已测试分支；不从 main 猜测创建，请先检查仓库。' >&2; exit 3;
}
if [[ -n "$(git -C "$TARGET" ls-remote --tags origin "refs/tags/v1.0.8")" ]] || gh release view v1.0.8 -R "$REPO" >/dev/null 2>&1; then
  echo 'v1.0.8 已有 tag/Release，拒绝覆盖。' >&2; exit 3;
fi
git -C "$TARGET" switch --track -c "$BRANCH" "origin/$BRANCH"
[[ -z "$(git -C "$TARGET" status --porcelain)" ]] || { echo '工作目录不干净。' >&2; exit 3; }
APP="$TARGET/android/app/build.gradle.kts"
grep -Fq 'versionCode = 10007' "$APP" || { echo '远程分支已不是测试时的 10007 基线，停止。' >&2; exit 3; }
grep -Fq 'versionName = "1.0.7"' "$APP" || { echo '远程分支版本不同于 E3 基线，停止。' >&2; exit 3; }
# Prevent an old ZIP from overwriting changes made since the user's device-tested RC1.
if ! diff -qr "$SOURCE/android/app/src" "$TARGET/android/app/src" >/dev/null; then
  echo '远程分支中的 Android 源码与此次已测试 E3-Fix1 源码不一致。停止，不覆盖任何功能。' >&2
  echo "可运行 diff -qr '$SOURCE/android/app/src' '$TARGET/android/app/src' 查看差异。" >&2
  exit 3
fi
for f in \
  android/app/build.gradle.kts \
  android/verification/check_release_current.py \
  android/verification/check_geckoview_toolchain.py \
  android/verification/check_geckoview_bookstore.py \
  BUILD_ANDROID_6800H.sh \
  .github/workflows/android.yml \
  .github/workflows/release.yml \
  RELEASE_NOTES.md RELEASE_GUIDE_CN.md README.md README_CN.md \
  RELEASE_1_0_8_GUIDE_CN.md \
  PUSH_ANDROID_1_0_8_BRANCH_6800H.sh RELEASE_ANDROID_1_0_8_6800H.sh; do
  mkdir -p "$(dirname "$TARGET/$f")"
  cp "$SOURCE/$f" "$TARGET/$f"
done
chmod +x "$TARGET/PUSH_ANDROID_1_0_8_BRANCH_6800H.sh" "$TARGET/RELEASE_ANDROID_1_0_8_6800H.sh"
# Remove only obsolete RC/old-version publication entrypoints; old tags and releases remain unchanged.
for f in .github/workflows/scan-enhancement-e3.yml \
  RELEASE_SCAN_E3_RC1_6800H.sh PUSH_SCAN_E3_ACTIONS_6800H.sh \
  PUSH_E3_FIX1_NEW_BRANCH_6800H.sh PUSH_FROM_6800H.sh RELEASE_1_0_7_6800H.sh; do
  rm -f "$TARGET/$f"
done
git -C "$TARGET" add --all
[[ "$(git -C "$TARGET" branch --show-current)" == "$BRANCH" ]] || exit 3
# Only expected files can change; source code/PDZ core is read-only in this promotion.
ALLOWED="$WORKDIR/allowed.txt"
cat > "$ALLOWED" <<'EOF'
.github/workflows/android.yml
.github/workflows/release.yml
.github/workflows/scan-enhancement-e3.yml
BUILD_ANDROID_6800H.sh
PUSH_ANDROID_1_0_8_BRANCH_6800H.sh
PUSH_E3_FIX1_NEW_BRANCH_6800H.sh
PUSH_FROM_6800H.sh
PUSH_SCAN_E3_ACTIONS_6800H.sh
README.md
README_CN.md
RELEASE_1_0_7_6800H.sh
RELEASE_1_0_8_GUIDE_CN.md
RELEASE_ANDROID_1_0_8_6800H.sh
RELEASE_NOTES.md
RELEASE_GUIDE_CN.md
RELEASE_SCAN_E3_RC1_6800H.sh
android/app/build.gradle.kts
android/verification/check_geckoview_bookstore.py
android/verification/check_geckoview_toolchain.py
android/verification/check_release_current.py
EOF
while IFS= read -r f; do
  grep -Fxq "$f" "$ALLOWED" || { echo "意外的待提交路径：$f，停止。" >&2; exit 3; }
done < <(git -C "$TARGET" diff --cached --name-only)
echo '===== 仅在 E3 分支发生的提交变更 ====='
git -C "$TARGET" status --short
git -C "$TARGET" diff --cached --stat
if git -C "$TARGET" diff --cached --quiet; then
  echo '远程分支已经包含相同变更，无需重新提交。'; exit 0
fi
[[ -n "$(git -C "$TARGET" config user.name || :)" && -n "$(git -C "$TARGET" config user.email || :)" ]] || {
  echo '先配置 git config --global user.name / user.email。' >&2; exit 3;
}
printf '\n输入 yes 才会提交和推送到 %s（不会触碰 main）：' "$BRANCH"
read -r CONFIRM
[[ "$CONFIRM" == 'yes' ]] || { echo '已取消，未推送。'; exit 0; }
git -C "$TARGET" commit -m 'release(android): prepare signed stable 1.0.8 from verified E3 branch'
git -C "$TARGET" push origin "HEAD:refs/heads/$BRANCH"
echo "已推送至 https://github.com/$REPO/tree/$BRANCH"
echo "Actions：https://github.com/$REPO/actions/workflows/android.yml"
echo 'Actions 成功后再运行 ./RELEASE_ANDROID_1_0_8_6800H.sh 创建正式标签。'
