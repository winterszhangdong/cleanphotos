#!/bin/zsh
# 把 work/media 推进 cleanphotos-promo 模拟器（只动这几个演示目录），重扫媒体库，清空 App 状态并授权、设中文。
export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
S=${SERIAL:-emulator-5560}
A(){ adb -s $S "$@"; }
[[ $(A emu avd name | head -1 | tr -d '\r') == cleanphotos-promo ]] || { echo "not promo avd"; exit 1; }
A shell rm -rf /sdcard/DCIM/Camera /sdcard/Pictures/QQ /sdcard/Pictures/Screenshots /sdcard/Pictures/WeiXin
cd ${0:a:h}/../work/media
A shell mkdir -p /sdcard/DCIM /sdcard/Pictures
A push DCIM/Camera /sdcard/DCIM/ | tail -1
for d in QQ Screenshots WeiXin; do A push Pictures/$d /sdcard/Pictures/ | tail -1; done
A shell content call --uri content://media/ --method scan_volume --arg external_primary
A shell pm clear com.winters.cleanphotos
A shell pm grant com.winters.cleanphotos android.permission.READ_MEDIA_IMAGES
A shell pm grant com.winters.cleanphotos android.permission.READ_MEDIA_VIDEO
A shell cmd locale set-app-locales com.winters.cleanphotos --locales zh-CN
A shell content query --uri content://media/external/file --projection _id --where "media_type\ IN\ \(1,3\)" | wc -l
