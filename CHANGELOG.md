# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-10-13

First public release.

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

[Unreleased]: https://github.com/winterszhangdong/cleanphotos/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/winterszhangdong/cleanphotos/releases/tag/v1.0.0
