#!/bin/bash
# 真机清理页翻页帧时间：启动参数驱动（同 perf-auto.sh），不写「已看」、不碰教学标记；结果从 App 沙盒拷回。
# 用法: scripts/perf-device.sh <tag> [extra launch args…]   例：scripts/perf-device.sh legacy -cpLegacy YES
# 先 NOBUILD= 以外会构建并覆盖安装（保留 App 数据）。结果追加到 build/audit/perf/device-<tag>.log
set -uo pipefail
cd "$(dirname "$0")/.."
DEV=${DEV:?set DEV to the device UDID (xcrun devicectl list devices)}
TAG=${1:-run}; shift || true
OUT=build/audit/perf; mkdir -p $OUT
BID=com.winters.cleanphotos
if [ -z "${NOBUILD:-}" ]; then
  xcodebuild -scheme CleanPhotos -configuration Release -destination "generic/platform=iOS" -derivedDataPath build/DerivedDataDevice \
    DEVELOPMENT_TEAM=${TEAM:?set TEAM to your Apple Developer Team ID} CODE_SIGN_IDENTITY="Apple Development" -allowProvisioningUpdates build >/tmp/cp-dev.log 2>&1 || { grep error: /tmp/cp-dev.log | head; exit 1; }
  xcrun devicectl device install app --device $DEV build/DerivedDataDevice/Build/Products/Release-iphoneos/CleanPhotos.app >/dev/null
fi
for mode in ${MODES:-fast slow}; do
  if [ $mode = fast ]; then N=30; SLOW=NO; WAIT=26; else N=15; SLOW=YES; WAIT=24; fi
  L="$TAG-$(date +%H%M%S)"
  xcrun devicectl device process launch --device $DEV --terminate-existing $BID -- \
    -cpfps YES -cpfpsLabel "$L" -cpAutoSwipe $N -cpAutoSwipeSlow $SLOW -has_seen_review_tutorial YES "$@" >/dev/null
  sleep $WAIT
  for i in $(seq 1 20); do
    xcrun devicectl device copy from --device $DEV --domain-type appDataContainer --domain-identifier $BID \
      --source Library/Caches/cpfps.log --destination /tmp/cpfps-dev.log >/dev/null 2>&1
    grep -q "auto-$mode-$L" /tmp/cpfps-dev.log 2>/dev/null && break; sleep 2
  done
  grep "auto-$mode-$L" /tmp/cpfps-dev.log | tee -a $OUT/device-$TAG.log
done
