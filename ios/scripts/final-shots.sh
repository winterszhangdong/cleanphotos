#!/bin/bash
# 最终对照截图：seed CP-Audit → 重装 → FinalShotsUITests 截 NN-name-ios.png 到 build/audit/final/
set -uo pipefail
cd "$(dirname "$0")/.."
UDID=${UDID:-B394BEA0-B40F-46E1-867A-DBC4D7B6A820}
OUT=$PWD/build/audit/final
mkdir -p "$OUT"
[ "${NOSEED:-0}" = 1 ] || scripts/seed-audit-sim.sh
xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease build-for-testing >/tmp/cp-final-build.log 2>&1 || { grep error: /tmp/cp-final-build.log | head; exit 1; }
xcrun simctl uninstall "$UDID" com.winters.cleanphotos 2>/dev/null || true
xcrun simctl install "$UDID" build/DerivedDataRelease/Build/Products/Release-iphonesimulator/CleanPhotos.app
TEST_RUNNER_OUT=$OUT xcodebuild -scheme CleanPhotos -configuration Release -destination "id=$UDID" -derivedDataPath build/DerivedDataRelease -collect-test-diagnostics never test-without-building -only-testing:CleanPhotosUITests/FinalShotsUITests >/tmp/cp-final-test.log 2>&1; echo "test exit $?"
cat "$OUT/final.log" 2>/dev/null | tail -60
