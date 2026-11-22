# CleanPhotos iOS（理照）

SwiftUI 版「理照」。照片页范围、相似、收藏整理、清理页、垃圾桶，以及视频、实况照片和嵌在 JPEG / HEIC 里的厂商动态照片，播放已与安卓版对齐（规格见仓库根目录 `docs/`）。厂商视频的解析在 `CleanPhotos/Domain/MotionPhotoLocator.swift`，只在清理页、垃圾桶预览和相似对比的当前张读文件。收藏整理写进系统相册，不是安卓那种搬文件。最低 iOS 17，仅暗色。

SwiftUI port of CleanPhotos. Photo scopes, similar photos, filing, review, trash, and playback of videos, Live Photos, and vendor motion clips embedded in JPEG / HEIC match the Android app (specs in `docs/`). The embedded-clip parser is `CleanPhotos/Domain/MotionPhotoLocator.swift`; it reads a file only for the current review, trash-preview, or similar-compare photo. Filing adds photos to PhotoKit albums instead of moving files. iOS 17+, dark only.

## 构建 / Build

工程由 [XcodeGen](https://github.com/yonaskolb/XcodeGen) 从 `project.yml` 生成，`CleanPhotos.xcodeproj` 不入库。
The Xcode project is generated from `project.yml` and is not checked in.

```bash
cd ios
xcodegen generate
open CleanPhotos.xcodeproj
# 或命令行 / or:
xcodebuild -project CleanPhotos.xcodeproj -scheme CleanPhotos \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
xcodebuild -project CleanPhotos.xcodeproj -scheme CleanPhotos \
  -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:CleanPhotosTests test
```

仓库根目录：

```bash
make ios-install                     # 编译 debug，装到当前连上的 iPhone 并打开（保留本机数据）
make ios-install-release             # 编译 release，装到当前连上的 iPhone 并打开（判断动效以它为准）
make ios-install IOS_DEVICE=<UDID>   # 连着多台时指定；release 同样加在命令后面
```

真机签名用本机 `Secrets.xcconfig` 里的 Team（从 `Secrets.example.xcconfig` 复制）。模拟器构建不签名。动效流畅度以 Release 包、真机为准。Judge smoothness on a Release build on a real device.

## 目录 / Layout

- `CleanPhotos/App`：入口、Tab 外壳、语言、照片打开转场
- `CleanPhotos/Design`：颜色、字体、间距、动效 token 与通用组件
- `CleanPhotos/Data`：PhotoKit 读取与删除，收藏 / 已看 / 垃圾桶本地存储
- `CleanPhotos/Domain`：与 UI 无关的规则（审阅账本、小结页、垃圾桶溶解时钟），有单元测试
- `CleanPhotos/Features`：Auth / Photos / Review / Favorites / Trash / Playback

图库还在分批读取时，照片页和收藏页先不进入清理，避免会话停在首屏那一批。垃圾桶预览在列表被系统相册缩短后仍用夹紧后的下标画邻页。
- `scripts/`：模拟器截图、录屏、性能采集等开发脚本（`perf-device.sh` / `perf-exit.sh` 需设置 `DEV` 与 `TEAM`）
