#!/usr/bin/env bash
# 发版后核对官网 /api/latest.json 是不是这一版。部署本身成不成功由 wrangler deploy 的结果判断，这一步只是补充核对。
#
# 用法：scripts/site-check-live.sh 1.6.0
# 环境变量：SITE_URL（默认 https://cleanphotos.winters.click）、TRIES（默认 6）、WAIT（每次之间等几秒，默认 10）
#
# 结果：
#   拿到 JSON 且 version 一致                → 通过
#   一直拿不到 JSON（403 / 被拦 / 网络错误）  → ::warning:: 后通过（Cloudflare 会拦 GitHub runner 的请求，不代表部署失败）
#   拿到了 JSON，但 version 到最后都不一致    → ::error:: 失败（真的没上线这一版）
set -euo pipefail

want="${1:?用法：$0 <版本号，如 1.6.0>}"
SITE_URL="${SITE_URL:-https://cleanphotos.winters.click}"
TRIES="${TRIES:-6}"
WAIT="${WAIT:-10}"
body="$(mktemp)"
trap 'rm -f "$body"' EXIT

seen=""      # 最后一次拿到的 JSON 里的版本
blocked=""   # 最后一次拿不到 JSON 的原因（HTTP 状态码）
for ((i = 1; i <= TRIES; i++)); do
  # 带上 run 号绕过边缘缓存；-f 不用，这样 403 也能拿到状态码
  code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 \
    -H 'Cache-Control: no-cache' -A "cleanphotos-release-check/${GITHUB_RUN_ID:-local}" \
    "$SITE_URL/api/latest.json?run=${GITHUB_RUN_ID:-local}-$i" 2>/dev/null)" || code="${code:-000}"
  got=""
  # 只有 200 且是 JSON 对象才算拿到了官网的回答；403、挑战页 HTML、网络错误都算「被拦」
  if [[ "$code" == 200 ]] && jq -e 'type == "object"' "$body" >/dev/null 2>&1; then
    got="$(jq -r '.version // "" | tostring' "$body")"
    [[ -n "$got" ]] || got="（没有 version 字段）"
  fi
  if [[ -n "$got" ]]; then
    if [[ "$got" == "$want" ]]; then
      echo "官网 /api/latest.json 已是 ${got}（第 $i 次）"
      exit 0
    fi
    seen="$got"
    echo "第 $i 次：官网还是 ${got}，期望 $want"
  else
    blocked="HTTP $code"
    echo "第 $i 次：拿不到 JSON（HTTP ${code}）"
  fi
  if ((i < TRIES)); then sleep "$WAIT"; fi
done

if [[ -n "$seen" ]]; then
  echo "::error::官网 /api/latest.json 是 ${seen}，期望 $want"
  exit 1
fi
echo "::warning::从 runner 访问不到官网 /api/latest.json（${blocked}，多半是 Cloudflare 拦了 GitHub runner）。部署已由 wrangler 确认，请在本机核对：curl -s $SITE_URL/api/latest.json"
