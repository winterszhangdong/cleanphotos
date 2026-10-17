#!/bin/bash
# 清理页翻页帧时间（不经 XCUITest，辅助功能不开）：Release → CP-Perf → 启动参数驱动 30 次快甩 / 15 次慢拖
# 用法: scripts/perf-auto.sh <tag> [extra launch args…]   例：scripts/perf-auto.sh before -cpLegacy YES
# 结果追加到 build/audit/perf/auto-<tag>.log
set -uo pipefail
cd "$(dirname "$0")/.."
UDID=${UDID:-1ED596D9-70CF-48C3-8636-CDD67062B799}
TAG=${1:-run}; shift || true
OUT=build/audit/perf; mkdir -p $OUT
BID=com.winters.cleanphotos
if [ -z "${NOBUILD:-}" ]; then
  xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease build >/tmp/cp-auto-build.log 2>&1 || { grep error: /tmp/cp-auto-build.log | head; exit 1; }
  xcrun simctl install "$UDID" build/DerivedDataRelease/Build/Products/Release-iphonesimulator/CleanPhotos.app
fi
xcrun simctl privacy "$UDID" grant photos $BID 2>/dev/null || true
C="$(xcrun simctl get_app_container "$UDID" $BID data)"
for mode in fast slow; do
  for rep in ${REPS:-1}; do :; done
  rm -f "$C/Library/Caches/cpfps.log"
  xcrun simctl terminate "$UDID" $BID 2>/dev/null; sleep 1
  if [ $mode = fast ]; then N=30; SLOW=NO; WAIT=25; else N=15; SLOW=YES; WAIT=22; fi
  xcrun simctl launch "$UDID" $BID -cpfps YES -cpfpsLabel "$TAG" -cpAutoSwipe $N -cpAutoSwipeSlow $SLOW -cpHasSeenTutorial YES "$@" >/dev/null
  for i in $(seq 1 60); do sleep 1; [ -s "$C/Library/Caches/cpfps.log" ] && grep -q auto "$C/Library/Caches/cpfps.log" && break; done
  grep auto "$C/Library/Caches/cpfps.log" | tee -a $OUT/auto-$TAG.log
done
xcrun simctl terminate "$UDID" $BID 2>/dev/null
