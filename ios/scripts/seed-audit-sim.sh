#!/bin/bash
# 审计专用模拟器 CP-Audit：抹掉后导入 build/audit/seed/ 里 22 张设计稿照片（9/21 8 张、9/20 8 张、9/18 6 张，EXIF 日期已写入）。
set -euo pipefail
cd "$(dirname "$0")/.."
UDID=${UDID:-B394BEA0-B40F-46E1-867A-DBC4D7B6A820}
xcrun simctl shutdown "$UDID" 2>/dev/null || true
xcrun simctl erase "$UDID"
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl addmedia "$UDID" build/audit/seed/p*.jpg
echo "seeded $UDID"
