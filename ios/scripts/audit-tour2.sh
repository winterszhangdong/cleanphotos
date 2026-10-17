#!/bin/bash
# 审计巡检：卸载重装（= 首次启动无权限）→ AuditTourUITests 按用户顺序走一遍，截图 + 录屏到 build/audit/tour2/
set -uo pipefail
cd "$(dirname "$0")/.."
UDID=${UDID:-B394BEA0-B40F-46E1-867A-DBC4D7B6A820}
OUT=$PWD/build/audit/tour2
rm -rf "$OUT"; mkdir -p "$OUT"

xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease build-for-testing >/tmp/cp-tour-build.log 2>&1 || { grep error: /tmp/cp-tour-build.log | head; exit 1; }
xcrun simctl install "$UDID" build/DerivedDataRelease/Build/Products/Release-iphonesimulator/CleanPhotos.app
xcrun simctl privacy "$UDID" grant photos com.winters.cleanphotos || true

xcrun simctl io "$UDID" recordVideo --codec h264 --force "$OUT/tour2.mp4" >/tmp/cp-tour-video.log 2>&1 &
REC=$!; sleep 1.5
TEST_RUNNER_OUT=$OUT xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease -collect-test-diagnostics never test-without-building -only-testing:CleanPhotosUITests/AuditTour2UITests >/tmp/cp-tour-test.log 2>&1; echo "test exit $?"
kill -INT $REC; wait $REC
