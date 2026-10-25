#!/usr/bin/env bash
# 把 GitHub 上最近几个正式版的 APK 拉进官网（site/release/），生成 site/release/releases.json。
# 官网的下载直接由 Cloudflare 提供，访问时不依赖 GitHub。
#
# 用法：
#   scripts/site-sync-release.sh                 从 GitHub 取最近 KEEP 个正式版（默认 2）
#   scripts/site-sync-release.sh --apk <path> --tag v1.5.0
#                                                发版流水线里用：本地刚构建的 APK 作为最新版，
#                                                旧版本仍从 GitHub 取
# 环境变量：KEEP（保留几个版本的 APK，默认 2）、GITHUB_TOKEN（可选，避免 API 限流）、
#           REPO（默认 winterszhangdong/cleanphotos）
# 每个文件都核对 sha256：GitHub 资源自带的 digest 对不上就失败；签名证书对不上也失败。
set -euo pipefail

REPO="${REPO:-winterszhangdong/cleanphotos}"
KEEP="${KEEP:-2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/site/release"
# 与 README「Verify the APK」和 scripts/release-notes.sh 一致
CERT_SHA256="78:A6:BD:4F:43:2A:F2:7A:D7:57:3B:95:1F:89:8D:7D:33:BC:7D:74:7C:2A:42:BF:EE:B2:93:04:D5:8F:49:80"

local_apk=""; local_tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) local_apk="$2"; shift 2 ;;
    --tag) local_tag="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$local_apk" && -z "$local_tag" ]] && { echo "--apk needs --tag" >&2; exit 2; }

auth=(); [[ -n "${GITHUB_TOKEN:-}" ]] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
apksigner_bin() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  command -v apksigner 2>/dev/null || ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true
}
check_cert() {   # 有 apksigner 就核对签名证书；没有就跳过（只靠 sha256）
  local s; s="$(apksigner_bin)"
  if [[ -z "$s" ]]; then echo "  (apksigner not found, skip certificate check)"; return 0; fi
  local got; got="$("$s" verify --print-certs "$1" | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1 | tr a-f A-F | sed 's/../&:/g; s/:$//')"
  [[ "$got" == "$CERT_SHA256" ]] || { echo "error: $1 signing certificate $got != $CERT_SHA256" >&2; exit 1; }
  echo "  certificate ok"
}

mkdir -p "$OUT"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
curl -fsSL ${auth[@]+"${auth[@]}"} -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases?per_page=20" > "$tmp/releases.json"

# 选出要放进官网的版本：正式版（非草稿、非预发布）、带 CleanPhotos-*.apk 的，按发布时间倒序取 KEEP 个
python3 - "$tmp/releases.json" "$KEEP" "$local_tag" > "$tmp/plan.tsv" <<'PY'
import json, sys
rels, keep, local_tag = json.load(open(sys.argv[1])), int(sys.argv[2]), sys.argv[3]
rows = []
if local_tag:   # 流水线里：这一版可能还没出现在 API 列表里（或刚出现），以本地 APK 为准
    rows.append((local_tag, '', '', '', ''))
for r in rels:
    if r['draft'] or r['prerelease'] or r['tag_name'] == local_tag: continue
    a = next((a for a in r['assets'] if a['name'].startswith('CleanPhotos-') and a['name'].endswith('.apk')), None)
    if not a: continue
    rows.append((r['tag_name'], r['published_at'], a['browser_download_url'], (a.get('digest') or '').removeprefix('sha256:'), str(a['size'])))
for row in rows[:keep]: print('\t'.join(row))
PY

entries=()
while IFS=$'\t' read -r tag date url digest size; do
  v="${tag#v}"; f="CleanPhotos-$v.apk"; dst="$OUT/$f"
  echo "== $tag"
  if [[ -n "$local_apk" && "$tag" == "$local_tag" ]]; then
    cp "$local_apk" "$dst"; date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; url="https://github.com/$REPO/releases/download/$tag/$f"
  elif [[ -f "$dst" && -n "$digest" && "$(sha256 "$dst")" == "$digest" ]]; then
    echo "  cached"
  else
    curl -fsSL --retry 3 -o "$dst.part" "$url"; mv "$dst.part" "$dst"
  fi
  got="$(sha256 "$dst")"
  if [[ -n "$digest" && "$got" != "$digest" ]]; then echo "error: $f sha256 $got != GitHub digest $digest" >&2; exit 1; fi
  echo "  sha256 $got"
  check_cert "$dst"
  bytes="$(wc -c < "$dst" | tr -d ' ')"
  entries+=("$v"$'\t'"$f"$'\t'"$bytes"$'\t'"$got"$'\t'"$date"$'\t'"$url")
done < "$tmp/plan.tsv"
[[ ${#entries[@]} -gt 0 ]] || { echo "error: no release with an APK found" >&2; exit 1; }

# 删掉不再保留的旧 APK
keep_files=" $(printf '%s\n' "${entries[@]}" | cut -f2 | tr '\n' ' ')"
for f in "$OUT"/CleanPhotos-*.apk; do [[ -e "$f" ]] || continue; [[ "$keep_files" == *" $(basename "$f") "* ]] || { echo "remove $(basename "$f")"; rm -f "$f"; }; done

printf '%s\n' "${entries[@]}" | python3 -c '
import json, sys
cert = sys.argv[1]; repo = sys.argv[2]
vs = []
for line in sys.stdin.read().splitlines():
    v, f, size, sha, date, gh = line.split("\t")
    vs.append({"version": v, "file": f, "size": int(size), "sha256": sha, "date": date, "github": gh})
json.dump({"repo": f"https://github.com/{repo}", "certSha256": cert, "latest": vs[0]["version"], "versions": vs}, sys.stdout, ensure_ascii=False, indent=2)
print()' "$CERT_SHA256" "$REPO" > "$OUT/releases.json"
echo "→ $OUT/releases.json (latest $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["latest"])' "$OUT/releases.json"))"
