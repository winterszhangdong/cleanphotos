# CleanPhotos iOS（理照）

SwiftUI 版「理照」，功能、交互和「暗房」视觉与安卓版对齐（规格见仓库根目录 `docs/`）。最低 iOS 17，仅暗色。

SwiftUI port of CleanPhotos with the same features, interactions and "darkroom" look as the Android app (specs in `docs/`). iOS 17+, dark only.

## 构建 / Build

工程由 [XcodeGen](https://github.com/yonaskolb/XcodeGen) 从 `project.yml` 生成，`CleanPhotos.xcodeproj` 不入库。
The Xcode project is generated from `project.yml` and is not checked in.

```bash
cd ios
xcodegen generate
open CleanPhotos.xcodeproj          # 真机运行时在 Signing 里选自己的 Team / pick your team for device runs
# 或命令行 / or:
xcodebuild -project CleanPhotos.xcodeproj -scheme CleanPhotos \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
xcodebuild -project CleanPhotos.xcodeproj -scheme CleanPhotos \
  -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:CleanPhotosTests test
```

动效流畅度以 Release 包、真机为准。Judge smoothness on a Release build on a real device.

## 目录 / Layout

- `CleanPhotos/App`：入口、Tab 外壳、语言、照片打开转场
- `CleanPhotos/Design`：颜色、字体、间距、动效 token 与通用组件
- `CleanPhotos/Data`：PhotoKit 读取与删除，收藏 / 已看 / 垃圾桶本地存储
- `CleanPhotos/Domain`：与 UI 无关的规则（审阅账本、小结页、垃圾桶溶解时钟），有单元测试
- `CleanPhotos/Features`：Auth / Photos / Review / Favorites / Trash
- `scripts/`：模拟器截图、录屏、性能采集等开发脚本（`perf-device.sh` / `perf-exit.sh` 需设置 `DEV` 与 `TEAM`）
