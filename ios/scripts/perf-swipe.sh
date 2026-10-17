#!/bin/bash
# 清理页左右翻页帧时间：Release 构建 → CP-Perf（大图库）→ PerfSwipeUITests → 打印 cpfps.log
# 用法: scripts/perf-swipe.sh <tag>   结果追加到 build/audit/perf/cpfps-<tag>.log
set -uo pipefail
cd "$(dirname "$0")/.."
UDID=${UDID:-1ED596D9-70CF-48C3-8636-CDD67062B799}
TAG=${1:-run}
OUT=build/audit/perf; mkdir -p $OUT
xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease build-for-testing >/tmp/cp-perf-build.log 2>&1 || { grep error: /tmp/cp-perf-build.log | head; exit 1; }
xcrun simctl install "$UDID" build/DerivedDataRelease/Build/Products/Release-iphonesimulator/CleanPhotos.app
xcrun simctl privacy "$UDID" grant photos com.winters.cleanphotos || true
C="$(xcrun simctl get_app_container "$UDID" com.winters.cleanphotos data)"
rm -f "$C/Library/Caches/cpfps.log"
xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease -collect-test-diagnostics never test-without-building -only-testing:CleanPhotosUITests/PerfSwipeUITests${ONLY:+/$ONLY} >/tmp/cp-perf-test.log 2>&1 || { echo "test failed"; grep -E "error|Fail" /tmp/cp-perf-test.log | head -5; }
C="$(xcrun simctl get_app_container "$UDID" com.winters.cleanphotos data)"
cat "$C/Library/Caches/cpfps.log" | tee -a $OUT/cpfps-$TAG.log
