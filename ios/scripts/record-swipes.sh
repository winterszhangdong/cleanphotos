#!/bin/bash
# 录清理页四向滑动：Release 构建 + XCUITest 拖动 + simctl recordVideo。
# 用法: scripts/record-swipes.sh <out.mp4> [SWIPES=lrud]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:?out.mp4}
# 默认用审计专用模拟器 CP-Audit（iPhone 17 是其他工程共用的，会被别的 UI 测试打断）
UDID=${UDID:-B394BEA0-B40F-46E1-867A-DBC4D7B6A820}
export SWIPES=${SWIPES:-lrud}
xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease build-for-testing >/tmp/cp-rec-build.log 2>&1 || { tail -30 /tmp/cp-rec-build.log; exit 1; }
xcrun simctl install "$UDID" build/DerivedDataRelease/Build/Products/Release-iphonesimulator/CleanPhotos.app
xcrun simctl privacy "$UDID" grant photos com.winters.cleanphotos || true
# 可选：预置累计滑动次数（验证「20 次后隐藏方向提示」）
if [ -n "${SWIPE_COUNT:-}" ]; then
  xcrun simctl terminate "$UDID" com.winters.cleanphotos 2>/dev/null || true
  PREF="$(xcrun simctl get_app_container "$UDID" com.winters.cleanphotos data)/Library/Preferences/com.winters.cleanphotos"
  xcrun simctl spawn "$UDID" defaults write "$PREF" review_swipe_action_count -int "$SWIPE_COUNT"
fi
xcrun simctl io "$UDID" recordVideo --codec h264 --force "$OUT" >/tmp/cp-rec-video.log 2>&1 &
REC=$!
sleep 1.5
TEST_RUNNER_TRASH_N=${TRASH_N:-3} TEST_RUNNER_TILE=${TILE:-tile-1-} TEST_RUNNER_SWIPES=$SWIPES xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease test-without-building -collect-test-diagnostics never -only-testing:${ONLY:-CleanPhotosUITests/ReviewSwipeRecordUITests} >/tmp/cp-rec-test.log 2>&1 || { echo "test failed"; tail -20 /tmp/cp-rec-test.log; }
kill -INT $REC; wait $REC || true
echo "recorded $OUT"
