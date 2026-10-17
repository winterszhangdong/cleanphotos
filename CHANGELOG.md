# Changelog

本文件记录项目所有值得注意的变更。All notable changes to this project are documented in this file.

格式参照 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/spec/v2.0.0.html)。每个版本先写中文，英文在下。
The format is based on Keep a Changelog, and this project adheres to Semantic Versioning. Chinese first, English below.

## [Unreleased]

## [1.2.2] - 2026-10-17

### Added

- 首个 iOS 版（`ios/`，SwiftUI，iOS 17+）：照片 / 收藏 / 垃圾桶三个页面、全屏清理（左右翻页、上滑收藏、下滑移入 App 内垃圾桶、撤回、当天小结页）、长按多选与拖选、清空垃圾桶走系统删除确认、简体中文和英文，交互与「暗房」视觉对齐安卓版。目前需用 Xcode 从源码构建，Release 不附 iOS 安装包。

### Fixed

- 照片网格滚动时新格子先灰一下、很久才出图：显影错开只用于网格首屏，滚动进来的格子解码后直接显示（不再等最多 660ms 再花 520ms 从灰到彩）；缩略图改取系统缩略图（Android 10+），不再每格解码整张原图；网格格子去掉多余的淡入；首屏显影每次启动只播一次，切换 Tab 或从清理页返回时缩略图直接从内存显示，不再重新变灰显影。
- 照片 / 收藏网格快速上下甩动时，惯性抬手容易误点进清理页：滚动中及停稳后约 300ms 内忽略点按和长按。
- 启动时申请屏幕最高刷新率，高刷机上滚动不再被锁在约 60fps。
- 清空垃圾桶时最后一张照片停在半溶解、直到网格收起：溶解时钟少走了一个步长（60ms），现在时钟覆盖最后一个可见格子的起点再加完整的 380ms，每张都溶解完才收起；单格时长和 60ms 步长不变。

### Changed

- 清理页上滑收藏 / 下滑移到垃圾桶松手后（以及点撤回后），底部导航胶囊不再作为落点浮现约 1.6s；清理页全程不显示导航，离场动画、提示圆钮、琥珀光与下一张升起不变。
- 上滑收藏离场的两层余影改为照片本身的半透明副本（透明度 0.4 / 0.18，延迟 50 / 100ms），带 1px 琥珀描边与向下偏移的琥珀光晕（对应设计稿动效 04），不再是两块纯色琥珀方块；时长与路径不变。

---

### Added

- First iOS version (`ios/`, SwiftUI, iOS 17+): Photos, Favorites and Trash tabs, full-screen review (left/right paging, swipe up to favorite, swipe down to the in-app trash, undo, day summary pages), long-press multi-select with drag-select, emptying the trash through the system delete prompt, and Simplified Chinese and English, matching the Android app's interactions and darkroom design. For now it must be built from source with Xcode; releases do not include an iOS build.

### Fixed

- Scrolling the photo grid showed gray cells that took a long time to fill in: the staggered develop now only applies to the first screen, cells scrolled in show as soon as they decode (instead of waiting up to 660 ms and then fading from gray over 520 ms), thumbnails come from the system thumbnail cache on Android 10+ instead of decoding each full-size original, and the redundant crossfade on grid cells is gone. The first-screen develop now plays once per launch; switching tabs or coming back from review shows thumbnails straight from memory instead of graying them out again.
- Fast vertical flings on the Photos / Favorites grids no longer open review by accident: taps and long-presses are ignored while scrolling and for about 300 ms after the grid settles.
- On launch the app requests the display's highest refresh rate so scrolling is not capped near 60 fps on high-refresh phones.
- Emptying the trash left the last photo frozen half-dissolved until the grid cleared: the dissolve clock stopped one stagger step (60 ms) early. It now runs to the last visible tile's start plus the full 380 ms, so every tile finishes before the grid clears; per-tile duration and the 60 ms stagger are unchanged.

### Changed

- In review, releasing a swipe up (favorite) or down (trash), or tapping Undo, no longer floats the bottom nav capsule in as a landing cue for about 1.6 s; the nav stays hidden throughout review. The exit animation, release cue, amber glow and next-photo rise are unchanged.
- The two afterimages trailing a swipe-up favorite are now semi-transparent copies of the photo itself (opacity 0.4 / 0.18, delayed 50 / 100 ms) with a 1 px amber ring and a downward amber glow, matching motion mockup 04, instead of two solid amber blocks. Timing and path are unchanged.

## [1.2.1] - 2026-10-15

### Fixed

- 照片页顶栏张数很大时（如英文「12,655 photos · 12,655 unseen」）文字重叠、挤到右侧按钮下面：标题区占满按钮左侧剩余宽度，放不下时「未看 / 共」小字换到大数字下方一行。
- Android 13+ 点「⋯ → 语言」会跳到系统语言设置，部分 ROM 落到「添加语言」页并提示更改系统语言：现在所有版本都用 App 内语言弹层。
- 切到英文后无法切回中文：13+ 直接读写系统应用语言设置，弹层始终显示当前实际语言，跟随系统 / English / 简体中文可任意来回切，包括在系统设置或 adb 里改过之后。

### Changed

- 英文界面顶栏（照片 / 收藏 / 垃圾桶 / 多选）的大数字后不再写「photos」，小标题已说明；中文保留「张」。

---

### Fixed

- Large counts in the Photos header (e.g. "12,655 photos · 12,655 unseen") overlapped and slid under the header buttons: the title area now takes the remaining width and the unseen/total text moves to its own line when it doesn't fit.
- On Android 13+, "⋯ → Language" opened system language settings, which on some ROMs landed on the "Add language" list offering to change the system language: the in-app language sheet is now used on every version.
- After switching to English it was impossible to switch back to Chinese: on 13+ the app now reads and writes the system per-app locale directly, the sheet always shows the actual current language, and Follow system / English / 简体中文 round-trip freely, including after changes made in system settings or via adb.

### Changed

- In English, the big number in the Photos / Favorites / Trash / selection headers no longer repeats "photos" (the title already says it); Chinese keeps 张.

## [1.2.0] - 2026-10-15

### Added

- 界面支持简体中文和英文，默认跟随系统语言（中文系统显示简体中文，繁体中文系统也显示中文，其他语言显示英文）。
- 照片页顶栏新增「⋯」菜单，可切换语言：Android 13+ 跳到系统「应用语言」设置，Android 8–12 在 App 内选择「跟随系统 / 简体中文 / English」，切换后立即生效、不丢失当前页面。

### Changed

- 日期、数字和文件大小按界面语言格式化（如英文「Sep 21」「Mon · 07:42」）。

---

### Added

- Simplified Chinese and English UI, following the system language by default (Chinese systems, including Traditional Chinese, show Simplified Chinese; all others English).
- New "⋯" menu in the Photos header to switch language: on Android 13+ it opens the system per-app language settings; on Android 8–12 an in-app sheet offers Follow system / 简体中文 / English. Changes apply immediately without leaving the current screen.

### Changed

- Dates, numbers and file sizes are formatted for the UI language (e.g. "Sep 21", "Mon · 07:42" in English).

## [1.1.0] - 2026-10-14

### Added

- 照片页长按多选，支持拖动连选（靠近边缘自动滚动）和按天全选。
- 多选操作栏可批量收藏、批量移入 App 内垃圾桶，撤回胶囊可整批撤回。

---

### Added

- Long-press multi-select on the photo grid, with drag-select (auto-scrolls near the edges) and per-day select-all.
- Batch favorite and batch move to the in-app trash from the selection bar, with an undo capsule that reverts the whole batch.

## [1.0.0] - 2026-10-13

首个公开版本。First public release.

### Added

- 照片页按年、按天分组，带未看标记，支持最新 / 最早排序和「仅未看过」筛选。
- 全屏清理：跨天连续左右翻页，上滑收藏、下滑移入 App 内垃圾桶，按距离或快速甩动触发，过阈值有触感反馈。
- 每次操作后显示撤回条；快速连续滑动排队执行，不丢操作。
- 一天至少 6 张且全部看完后，在两天之间插入当天小结页。
- 收藏页；在收藏里上滑取消收藏。
- 垃圾桶页：可释放空间汇总、多选恢复、全屏预览，清空前二次确认并走系统删除授权。
- 双指和双击缩放，网格到清理页的共享元素过渡，Android 14+ 预测性返回。
- 首次授权页（Android 14+ 支持部分照片访问）和滑动教学。
- 「暗房」暗色设计系统，按 API 等级优雅降级，遵循系统动画缩放。
- 无网络权限，所有状态只存在本地 DataStore。

---

### Added

- Photo grid grouped by year and day, with unseen markers, newest/oldest sorting and an "unseen only" filter.
- Full-screen review: continuous left/right paging across days, swipe up to favorite, swipe down to move to the in-app trash, distance or fling to trigger, haptic feedback at the threshold.
- Undo bar after each action; rapid consecutive swipes are queued without dropping actions.
- Day summary page inserted between days once a day with at least 6 photos has been fully reviewed.
- Favorites tab; swiping up in favorites removes the favorite.
- Trash tab with free-space summary, multi-select restore, full-screen preview and empty-trash with confirmation and the system delete prompt.
- Pinch and double-tap zoom, shared-element transition from grid to review, predictive back on Android 14+.
- First-use permission screen (with partial photo access on Android 14+) and a swipe tutorial.
- Dark "darkroom" design system with graceful degradation by API level and respect for the system animation scale.
- No network permission; all state stored locally in DataStore.

[Unreleased]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.2...HEAD
[1.2.2]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/winterszhangdong/cleanphotos/releases/tag/v1.0.0
