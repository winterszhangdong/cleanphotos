# Changelog

本文件记录项目所有值得注意的变更。All notable changes to this project are documented in this file.

格式参照 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/spec/v2.0.0.html)。每个版本先写中文，英文在下。
The format is based on Keep a Changelog, and this project adheres to Semantic Versioning. Chinese first, English below.

## [Unreleased]

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

[Unreleased]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/winterszhangdong/cleanphotos/releases/tag/v1.0.0
