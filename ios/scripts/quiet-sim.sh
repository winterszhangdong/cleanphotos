#!/bin/bash
# 模拟器里的 PosterBoard（系统锁屏壁纸）在 iOS 27 上会反复断言崩溃。
# 崩溃报告在 Mac 上每崩一次弹一个「意外退出」。测试是无人值守的，所以：
#   1. 本机不再弹「意外退出」（日志仍写在 ~/Library/Logs/DiagnosticReports）。
#   2. 把已开机模拟器里的 PosterBoard 关掉。关掉会保持到下一次 simctl erase。
# 用法：quiet-sim.sh [UDID ...]    不传 UDID 就处理所有已开机的模拟器。
set -euo pipefail

defaults write com.apple.CrashReporter DialogType none

if [[ $# -gt 0 ]]; then
  udids=("$@")
else
  udids=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && udids+=("$line")
  done < <(xcrun simctl list devices booted | sed -nE 's/.*\(([0-9A-Fa-f-]{36})\).*\(Booted\).*/\1/p')
fi

for udid in "${udids[@]+"${udids[@]}"}"; do
  xcrun simctl spawn "$udid" launchctl disable user/foreground/com.apple.PosterBoard
  xcrun simctl spawn "$udid" launchctl bootout user/foreground/com.apple.PosterBoard >/dev/null 2>&1 || true
done
