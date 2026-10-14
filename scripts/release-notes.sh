#!/usr/bin/env bash
# 从 CHANGELOG.md 取出某个版本的小节，生成 GitHub Release 正文。
# 用法：scripts/release-notes.sh <版本号或 tag，如 1.2.0 / v1.2.0> [CHANGELOG 路径]
# 小节不存在或为空时以非 0 退出，发版任务随之失败。
set -euo pipefail

version="${1:?usage: $0 <version> [changelog]}"
version="${version#v}"
changelog="${2:-CHANGELOG.md}"
repo="https://github.com/winterszhangdong/cleanphotos"
# 与 README「Verify the APK」一致
cert_sha256="78:A6:BD:4F:43:2A:F2:7A:D7:57:3B:95:1F:89:8D:7D:33:BC:7D:74:7C:2A:42:BF:EE:B2:93:04:D5:8F:49:80"

[[ -f "$changelog" ]] || { echo "error: $changelog not found" >&2; exit 1; }

# 「## [x.y.z]」到下一个「## [」之间（不含标题行）；顺带记下紧随其后的版本作为上一版
section="$(awk -v v="$version" '
  /^## \[/ {
    if (found) { if (prev == "") { p = $0; sub(/^## \[/, "", p); sub(/\].*/, "", p); prev = p }; exit }
    h = $0; sub(/^## \[/, "", h); sub(/\].*/, "", h)
    if (h == v) { found = 1; next }
  }
  /^\[[^]]+\]: / { if (found) exit }
  found { print }
  END { if (!found) exit 2; print "\x1ePREV=" prev }
' "$changelog")" || { echo "error: section [$version] not found in $changelog" >&2; exit 1; }

prev="${section##*$'\x1e'PREV=}"
body="${section%$'\x1e'PREV=*}"
# 去掉首尾空行
body="$(printf '%s\n' "$body" | sed -e '/./,$!d' | awk '{ a[NR] = $0 } END { n = NR; while (n > 0 && a[n] ~ /^[[:space:]]*$/) n--; for (i = 1; i <= n; i++) print a[i] }')"

if [[ -z "${body//[[:space:]]/}" ]]; then
  echo "error: section [$version] in $changelog is empty" >&2
  exit 1
fi

printf '%s\n\n---\n\n' "$body"
printf '**APK signing certificate SHA-256 / 签名证书 SHA-256**\n\n```text\n%s\n```\n\n' "$cert_sha256"
if [[ -n "$prev" ]]; then
  printf '**Full changelog / 完整变更**: %s/compare/v%s...v%s\n' "$repo" "$prev" "$version"
else
  printf '**Full changelog / 完整变更**: %s/commits/v%s\n' "$repo" "$version"
fi
