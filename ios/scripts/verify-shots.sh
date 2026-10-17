#!/bin/zsh
# Capture verify screenshots via the in-app harness (CLEANPHOTOS_SHOTS=1, Documents phase/ack handshake).
set -e
UDID=${UDID:-17894F32-78C3-4688-B4C2-0C3506A63DD3}
ROOT=${0:A:h:h}
APP=$ROOT/build/DerivedData/Build/Products/Debug-iphonesimulator/CleanPhotos.app
OUT=$ROOT/build/verify
BID=com.winters.cleanphotos
mkdir -p $OUT
xcrun simctl terminate $UDID $BID 2>/dev/null || true
xcrun simctl install $UDID $APP
xcrun simctl privacy $UDID grant photos $BID
xcrun simctl spawn $UDID defaults write $BID cleanphotos.appLanguage zhHans
xcrun simctl spawn $UDID defaults write $BID cleanphotos.sortOrder newestFirst
SIMCTL_CHILD_CLEANPHOTOS_SHOTS=1 xcrun simctl launch $UDID $BID >/dev/null
sleep 1.5
DOCS=$(xcrun simctl get_app_container $UDID $BID data)/Documents
mkdir -p $DOCS
typeset -A MAP
MAP=(photos 01-photos.png menu-sort 02-menu-sort.png menu-filter 03-menu-filter.png review 04-review.png favorites 05-favorites.png trash 06-trash.png multiselect 07-multiselect.png undo 09-undo.png)
for i in {1..300}; do
  PHASE=$(tr -d '\n\r ' < $DOCS/cp_shot_phase 2>/dev/null || true)
  ACK=$(tr -d '\n\r ' < $DOCS/cp_shot_ack 2>/dev/null || true)
  if [[ -n $PHASE && $PHASE != $ACK ]]; then
    if [[ $PHASE == done ]]; then echo done > $DOCS/cp_shot_ack; break; fi
    [[ -n ${MAP[$PHASE]} ]] && xcrun simctl io $UDID screenshot $OUT/${MAP[$PHASE]} >/dev/null 2>&1 && echo "shot $PHASE -> ${MAP[$PHASE]}"
    echo $PHASE > $DOCS/cp_shot_ack
  fi
  sleep 0.2
done
# Unseen-dot close-up: 2nd tile of first row, 3x crop scaled 4x
sips -c 150 150 --cropOffset 690 300 $OUT/01-photos.png --out /tmp/cp-dot.png >/dev/null
sips -z 600 600 /tmp/cp-dot.png --out $OUT/11-unseen-dot-closeup.png >/dev/null
# English Photos tab
xcrun simctl terminate $UDID $BID 2>/dev/null || true
xcrun simctl spawn $UDID defaults write $BID cleanphotos.appLanguage english
xcrun simctl launch $UDID $BID >/dev/null
sleep 2.5
xcrun simctl io $UDID screenshot $OUT/10-photos-en.png >/dev/null 2>&1
xcrun simctl spawn $UDID defaults write $BID cleanphotos.appLanguage zhHans
echo finished
