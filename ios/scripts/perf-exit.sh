#!/bin/bash
# 真机清理页离场测量：上滑收藏 / 下滑垃圾桶 / 左右翻页，各跑一轮 CADisplayLink 帧时间（-cpfps）。
# -cpAutoSwipe 打开 PerfDryRun：收藏 / 垃圾桶 / 已看只改内存、不落盘，不删任何照片。
# 用法: scripts/perf-exit.sh <tag>      环境变量: NOBUILD=1 跳过构建安装；DIRS="up down h"；N=20；TRACE=1 同时录 Instruments
set -uo pipefail
cd "$(dirname "$0")/.."
DEV=${DEV:?set DEV to the device UDID (xcrun devicectl list devices)}
TAG=${1:-run}
OUT=build/audit/perf; mkdir -p $OUT
BID=com.winters.cleanphotos
if [ -z "${NOBUILD:-}" ]; then
  xcodebuild -scheme CleanPhotos -configuration Release -destination "generic/platform=iOS" -derivedDataPath build/DerivedDataDevice \
    DEVELOPMENT_TEAM=${TEAM:?set TEAM to your Apple Developer Team ID} CODE_SIGN_IDENTITY="Apple Development" -allowProvisioningUpdates build >/tmp/cp-dev.log 2>&1 || { grep error: /tmp/cp-dev.log | head; exit 1; }
  xcrun devicectl device install app --device $DEV build/DerivedDataDevice/Build/Products/Release-iphoneos/CleanPhotos.app >/dev/null || { echo install-failed; exit 1; }
fi
N=${N:-20}
for dir in ${DIRS:-up down h}; do
  L="$TAG-$(date +%H%M%S)"
  if [ $dir = h ]; then n=30; label=review-auto-fast; wait=26; else n=$N; label=review-auto-$dir; wait=$((3 + N * 85 / 100 + 2)); fi
  DELAY=1500; [ -n "${TRACE:-}" ] && DELAY=7000 && wait=$((wait + 6))
  xcrun devicectl device process launch --device $DEV --terminate-existing --json-output /tmp/cp-launch.json $BID -- \
    -cpfps YES -cpfpsLabel "$L" -cpAutoSwipe $n -cpAutoSwipeDir $dir -cpAutoSwipeDelay $DELAY -has_seen_review_tutorial YES ${EXTRA:-} >/tmp/cp-launch.log 2>&1 || { cat /tmp/cp-launch.log | tail -3; exit 2; }
  if [ -n "${TRACE:-}" ]; then
    rm -rf $OUT/exit-$TAG-$dir.trace
    PID=$(python3 -c "import json;print(json.load(open('/tmp/cp-launch.json'))['result']['process']['processIdentifier'])")
    for try in 1 2 3; do
      xcrun xctrace record --template "${TEMPLATE:-Animation Hitches}" --device $DEV --attach $PID --time-limit $((wait - 3 - (try - 1) * 2))s \
        --output $OUT/exit-$TAG-$dir.trace >/tmp/cp-xct.log 2>&1 && break; sleep 1
    done; tail -1 /tmp/cp-xct.log
  else
    sleep $wait
  fi
  for i in $(seq 1 20); do
    xcrun devicectl device copy from --device $DEV --domain-type appDataContainer --domain-identifier $BID \
      --source Library/Caches/cpfps.log --destination /tmp/cpfps-dev.log >/dev/null 2>&1
    grep -q "$label-$L" /tmp/cpfps-dev.log 2>/dev/null && break; sleep 2
  done
  grep "$label-$L" /tmp/cpfps-dev.log | tee -a $OUT/exit-$TAG.log
done
