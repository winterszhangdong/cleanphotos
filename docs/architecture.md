# 架构设计

## 技术栈

项目使用：

- Kotlin
- Jetpack Compose
- MVVM
- UseCase
- Coroutines
- Flow
- Coil
- MediaStore
- DataStore
- Hilt
- Navigation Compose

---

## 分层结构

项目采用以下调用链：

```text
Compose
  ↓
ViewModel
  ↓
UseCase
  ↓
Repository
  ↓
MediaStore / DataStore
```

---

## 分层职责

### Compose

负责：

* 渲染界面。
* 展示状态。
* 接收用户操作。
* 调用 ViewModel 暴露的事件回调。
* 管理纯 UI 动画状态。

不负责：

* 不访问 Repository。
* 不访问 MediaStore。
* 不写业务逻辑。
* 不处理数据持久化。

---

### ViewModel

负责：

* 维护页面状态。
* 调用 UseCase。
* 处理页面事件。
* 控制清理流程。
* 控制清理页水平切换当前照片。
* 控制撤回逻辑。
* 控制教学显示状态。
* 控制垃圾桶页模式切换（Grid / Selection / Preview）。
* 根据 ReviewScope 决定清理页手势语义（AllPhotos 上滑收藏，Favorites 上滑取消收藏）。
* 暴露系统媒体授权的一次性事件，并处理授权结果。
* 系统媒体授权仅用于永久删除（DeletePermanently），收藏、取消收藏、移动到 App 内垃圾桶和从 App 内垃圾桶恢复都不需要系统授权。
* 不负责图片请求身份或缩放渲染细节；这些细节统一下沉到共享 `PhotoPager` / `PhotoPagerPhoto` 组件。
* 清理页水平切换使用本地 `displayIndex` / `pendingSwitchIndex` / `settledOffsetXOverride` / `isHorizontalFlinging` 承接 fling 结束瞬间的无跳变切页，fling 动画完成后才同步 ViewModel。
* 页面状态的计算（排序、分组、会话序列、小结、全库 combine）不在主线程：ViewModel 注入 `@DefaultDispatcher`（`core/coroutines/DispatchersModule.kt`），在 combine 后 `flowOn` 到它。数据流默认跑在收集方的 viewModelScope（主线程），一次滑动会连锁触发多轮全库计算（返回栈里的照片页、收藏页也会跟着重算），正好压在离场 / 翻页动画里。清理页上下滑仍由 `act()` 在主线程同步返回下一页 key，UI 不等界面状态。
* 界面状态里内容没变的集合沿用上一份实例（如清理页的 `pages` / `progress` / `summaries`），避免无关字段变化引起整台重组。

---

### UseCase

负责：

* 单一业务动作。
* 封装业务规则。
* 提供可测试的业务入口。

示例：

```text
切换当前清理照片
收藏照片
移动照片到垃圾桶
恢复照片
撤回上一次操作
读取教学状态
保存教学状态
```

---

### Repository

负责：

* 抽象数据来源。
* 对上层隐藏 MediaStore 和 DataStore 细节。
* 提供照片查询、收藏、移动到垃圾桶、恢复等能力。
* 将需要系统媒体授权的操作转换为明确的操作结果。
* MediaStore 查询 / 更新（ContentResolver，binder 调用）一律在 `Dispatchers.IO` 上执行，不依赖调用方所在线程：ViewModel 从 viewModelScope（主线程）调用，单次整库查询在真机上就要 11–17ms。

---

### DataSource

负责：

* 访问 MediaStore。
* 访问 DataStore。
* 执行平台相关逻辑。

包含：

```text
MediaStorePhotoDataSource
MediaStoreTrashDataSource
PhotoHashDataSource
AppTrashStateDataSource
AppFavoriteStateDataSource
AppSeenStateDataSource
```

说明：

* `AppTrashStateDataSource` 使用 DataStore 管理 App 内垃圾桶状态（写入/读取/删除软删除记录），垃圾桶操作不再直接操作 MediaStore IS_TRASHED 字段。
* `AppFavoriteStateDataSource` 使用 DataStore 管理 App 内收藏状态，收藏/取消收藏不再触发系统授权，仅写入本地 DataStore。
* `AppSeenStateDataSource` 使用 DataStore 管理"已看过"状态，用于未看光点、「仅未看过」过滤、当天进度与月份条「整理完的天」。

---

## 项目结构

```text
com.example.photocleaner
├── core
│   ├── design
│   ├── navigation
│   ├── permission
│   ├── datastore
│   ├── media
│   └── util
├── data
│   ├── datasource
│   ├── preferences
│   └── repository
├── domain
│   ├── model
│   ├── repository
│   └── usecase
├── ui
│   └── photopager
└── feature
    ├── allphotos
    ├── favorites
    ├── review
    ├── similar
    └── trash
```

---

## core 模块

### core/design

放置：

* 主题
* 颜色（`DarkroomColors` — 暗房色：room/tray/rim/paper/dim/faint/fade/fadeDeep/delete/amber/glass，数值与 docs/ui-design.md 一致）
* 字体（`DarkroomType`：Noto Sans SC 400/500/600/700、Schibsted Grotesk 500/600/700 tnum、IBM Plex Mono 400/500；图标仍用 Material Symbols 子集）
* 尺寸与圆角（`Dimens`）
* Motion（`Motion.kt`：时长档位、四条贝塞尔曲线、四档弹簧、显影参数、手势阈值；`AnimationScale` 读系统动画缩放）
* API 分级（`ApiTier.kt`，唯一的 SDK_INT 判断处；`Haptics.kt` 按分级选择触感常量）
* 玻璃背景模糊 `GlassBackdrop.kt`（Haze 1.7.0，按 `ApiTier.hasBlur` 分级）。`hazeSource` 每帧 `GraphicsLayer.record` 整棵子树，所以只包导航底下那条实色带，不包网格；预测性返回的上一页快照只在离开前那一帧录一次（`captureBackdropOnce`）
* 共享导航层（`core/navigation`）：`AppDock` / `AppDockBar` / `AppShellViewModel` 把底部导航提升到 NavHost 之外，各页用 `AppDockEffect` 声明显示、隐藏和遮罩；`SharedPhoto.kt` 共享元素；`PredictiveBack.kt` 预测性返回
* 启动时后台预载全部字重（`CleanPhotosApp`），避免首次用到某个中文字重时主线程同步加载
* 动画中的色调（清理页压暗 / 去色、显影）用 `graphicsLayer` 的 `colorFilter`，不用每帧 `saveLayer`：内容在离屏层里只画一次，之后每帧只改合成参数。会随动画每帧变化的绘制（如清理页的过阈值描边、导航下划线）放在色调层 / 玻璃模糊层之外的独立层里，否则会让整层每帧重画
* 动效是否流畅以 release 包为准（`make install-release`，R8 + 库自带基线配置）；debug 包同样的动画会明显更卡
* Coil：内存缓存 30%，解码限 2 个并发；注册 coil-video 的 `VideoFrameDecoder`，清理页、小结页、垃圾桶预览直接用视频 uri 画首帧（不播放）；网格缩略图和清理页大图都用 `RGB_565`。清理页环境光（全屏模糊）只画当前页、离场页和撤回让位页，左右邻页和 alpha 为 0 的垫底页不画
* 通用组件：玻璃按钮 `GlassIconButton`、底部导航胶囊 `AppBottomBar`、里程表数字 `OdometerText`、显影 `Modifier.develop`、未看光点 `UnseenDot`
* 间距
* 通用组件
* 图标（`DraftIcons` 画排序、筛选、返回、日期箭头和勾；其余仍可用 `MaterialSymbol`）
* `AppDropdownMenu`（P3 改为暗色玻璃菜单：192dp、圆角 16、内边距 4、菜单项 44dp/圆角 12）
* 动效常量
* 未看标记是 6dp 相纸白光点（1.5dp 暗环 + 8dp 柔光）

### core/navigation

放置：

* 页面路由
* 导航参数
* 导航扩展方法

### core/permission

放置：

* 相册权限判断
* 权限请求状态
* Android 版本差异处理

### core/datastore

放置：

* DataStore 基础配置
* 偏好字段 key

### core/media

放置：

* MediaStore 通用工具
* Uri 构建工具
* 媒体权限辅助方法

### core/util

放置：

* 日期格式化
* 文件大小格式化
* 通用扩展函数

### ui/photopager

放置：

* `PhotoPager`：共享三页横向预览容器，供 `ReviewScreen` 与 `TrashPreviewOverlay` 复用。负责页面过渡 — 水平偏移、pageScale、pageAlpha、parallax、zIndex。全局背景只在根 Box 绘制一次。每页由 wrapper Box 处理过渡参数，内容为 `PhotoPagerPhoto`。本组件是纯渲染组件，不包含手势检测。
* `PhotoPagerPhoto`：纯图片内容组件，只负责 AsyncImage 渲染、zoom 平移与缩放、垂直手势偏移和加载失败 UI。默认 `ContentScale.Fit`。不再处理页面级水平偏移或单页背景。稳定 `ImageRequest` 身份，避免手势重组期间频繁重建请求。
* `PhotoPagerLayout`：纯布局数据模型，输出 `PhotoPagerPage` 列表，每页携带 pageScale、pageAlpha、pageParallaxOffsetPx、zIndex 等过渡参数。`calculatePhotoPagerTransitionProgress` 从水平偏移计算过渡进度（0..1）。已移除 `contentOffsetXPx`、`calculatePhotoPagerFitHorizontalGutter`、`calculatePhotoPagerRevealProgress`。
* `PhotoPagerZoom`：共享缩放参数与纯函数（`clampPhotoPagerZoom`、`adjustPinchZoomDelta`），避免清理页和垃圾桶预览参数漂移。
* `PagerGestureDetector`：统一手势检测器（`detectPagerGestures`）和缩放状态（`PagerZoomState`），在单一 pointerInput 中处理 drag / pinch / zoomedPan / doubleTap，避免父子 pointerInput 冲突。
* `HorizontalSwipeState`：纯水平方向滑动状态，用于只需左右翻页的场景（如垃圾桶预览），支持边界阻力、阈值判定、弹性回弹和飞出动画。

---

## data 模块

```text
data
├── datasource
├── preferences
└── repository
```

### datasource

包含：

```text
MediaStorePhotoDataSource
MediaStoreTrashDataSource
PhotoHashDataSource
AppTrashStateDataSource
AppFavoriteStateDataSource
AppSeenStateDataSource
```

职责：

* 查询系统照片和视频：图片表、视频表分别查询后按拍摄时间合并，没有视频权限时只查图片；填充 mediaType / durationMs / relativePath / bucketName（含 IS_FAVORITE，仅用于首次迁移系统收藏）。
* `PhotoHashDataSource`：取小图算 64 位 dHash，结果只缓存在进程内存，供相似照片分组。
* 永久删除照片（移入 / 移出垃圾桶是 App 内语义，只写 DataStore）。
* `AppTrashStateDataSource`：使用 DataStore 管理 App 内垃圾桶的软删除记录（upsertEntry / removeEntry / removeEntries / removeMissingEntries / observeEntries）。
* `AppFavoriteStateDataSource`：使用 DataStore 管理 App 内收藏记录（upsertEntry / upsertEntries / removeEntry / removeEntries / removeMissingEntries / observeEntries）。
* `AppSeenStateDataSource`：使用 DataStore 管理已看过状态（markSeen / removeEntries / removeMissingEntries / observeSeenIds）。

### preferences

包含：

```text
UserPreferencesDataSource
```

职责：

* 保存是否看过教学。
* 读取用户偏好。

### repository

包含：

```text
PhotoRepositoryImpl
ReviewRepositoryImpl
SimilarPhotoRepositoryImpl
UserPreferencesRepositoryImpl
```

职责：

* 实现 domain 层 Repository 接口。
* 聚合 DataSource。
* 对上层隐藏平台细节。

---

## domain 模块

```text
domain
├── model
├── repository
└── usecase
```

### model

放置业务模型：

```text
PhotoItem（含 mediaType / durationMs / relativePath / bucketName）
MediaType（枚举：Image / Video）
PhotoFilterMode（枚举：AllPhotos / UnseenOnly / Screenshots / LargeFiles / ChatApps / OnThisDay）
SmartFilterRules（截图 / 大文件 / 聊天软件存图判定）
OnThisDayRules（那年今天：本地月日相同且年份更早；今天由调用方传入）
SimilarGroup、SimilarRules、DHash（相似照片分组）
PhotoDateGroup
PhotoAction
PhotoActionType（枚举：Favorite / Unfavorite / MoveToTrash）
ReviewSession
ReviewScope（枚举：AllPhotos / Favorites）
AppTrashEntry（App 内垃圾桶条目：photoId + movedToTrashAt + wasFavorited + favoritedAt）
AppFavoriteEntry（App 内收藏条目：photoId + favoritedAt）
TrashScreenMode（枚举：Grid / Selection / Preview）
```

### repository

定义接口：

```text
PhotoRepository（observeAllPhotos 排除 App 垃圾桶和 App 收藏中的照片；observeFavoritedPhotos 返回 App 收藏中且不在垃圾桶的照片；observeTrashedPhotos 返回 App 内垃圾桶照片；refreshPhotos 含一次性迁移和失效条目清理；只有部分照片访问权时两者都跳过，否则会把未选中照片的状态当成失效删掉）
ReviewRepository（favoritePhoto / removeFavorite / moveToTrash / restoreFromTrash 直接写 DataStore 后返回 Success，不触发系统授权；照片页多选另有 favoritePhotos / removeFavorites / movePhotosToTrash 批量接口，每个 Store 一次写入；deletePhotosPermanently 仍通过 MediaStore）
SimilarPhotoRepository（hashPhotos：给指定照片算 dHash，返回进度流；单张失败跳过）
UserPreferencesRepository
```

### usecase

定义单一业务动作：

```text
ObserveAllPhotosUseCase
ObserveTrashedPhotosUseCase
SortPhotosUseCase
FilterPhotosUseCase
BuildPhotoDateGroupsUseCase
StartReviewSessionUseCase（接受 sortOrder 和 filterMode 参数）
SwitchReviewPhotoUseCase
FavoritePhotoUseCase
RemoveFavoritePhotoUseCase
MovePhotoToTrashUseCase
RestorePhotoFromTrashUseCase
RestorePhotosFromTrashUseCase
DeletePhotosPermanentlyUseCase
UndoPhotoActionUseCase
ObserveTutorialStateUseCase
SetTutorialSeenUseCase
```

---

## feature 模块

### allphotos

负责全部照片页。

包含：

```text
AllPhotosRoute
AllPhotosScreen
AllPhotosViewModel
AllPhotosUiState
AllPhotosTopBar
PhotoDateSection
DatePhotoGrid
DatePhotoThumbnail
```

### review

负责清理页。

包含：

```text
ReviewRoute
ReviewScreen
ReviewViewModel
ReviewUiState
ReviewPhoto
SwipeTutorialLayer
ActionUndoBar
SwipeBand（上滑「收藏」、下滑「移到垃圾桶」，各 84dp）
ReviewSwipeState
ReviewSwipeController
ReviewPhotoPager
ReviewPagerLayout
```

说明：

* `ReviewScreen` 使用本地 `displayIndex`、`pendingSwitchIndex`、`settledOffsetXOverride`、`isHorizontalFlinging` 实现无缝 fling 切页缓冲：fling 动画先在本地完成视觉过渡，完成后再同步 ViewModel 索引，避免 fling 结束瞬间的跳变。
* `ReviewPagerLayout` 是 deprecated shim，直接委托给共享 `buildPhotoPagerPages`。

### favorites

负责收藏页。

包含：

```text
FavoritesRoute
FavoritesScreen
FavoritesViewModel
FavoritesUiState
```

### trash

负责垃圾桶页。

包含：

```text
TrashRoute
TrashScreen
TrashViewModel
TrashUiState
TrashPhotoGrid
TrashPhotoItem
TrashPreviewOverlay
TrashSelectionTopBar
TrashEffect
TrashScreenMode
```

说明：

* `TrashPreviewOverlay` 使用与 `ReviewScreen` 一致的本地切页缓冲策略：`displayPhotoId`、`displayIndex`、`pendingPhotoId`、`isHorizontalFlinging`、`settledOffsetXOverride`，实现无缝 fling 切页。复用共享 `PhotoPager` 组件渲染全屏预览。

---

## 导航设计

路由：

```text
all_photos
favorites
review/{photoId}/{sortOrder}/{filterMode}/{scope}
trash
```

说明：

* `all_photos` 是首页。
* `favorites` 是收藏页。
* `review/{photoId}/{sortOrder}/{filterMode}/{scope}` 是清理页，从指定照片开始，携带排序、过滤和 scope 上下文，确保清理页的切换顺序与入口页一致。scope 决定手势语义（AllPhotos 上滑收藏，Favorites 上滑取消收藏）。
* `trash` 是垃圾桶页。

---

## 权限设计

Android 13 及以上：

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

图片和视频在同一次请求里申请（系统只弹一次「照片和视频」）。已授权图片、缺视频权限时，每次冷启动进入照片页补请求一次。「完整访问」= 图片和视频都已授权，否则跳过失效清理。

Android 12 及以下：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

Android 8–10 永久删除（maxSdkVersion 29，配合 `requestLegacyExternalStorage`）：

```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
```

清空垃圾桶时 `MediaStoreTrashDataSource` 若缺这项权限，返回 `Failure(WritePermissionRequired)`；`TrashViewModel` 发出 `RequestWritePermission`，同意后重试一次，拒绝则提示错误。Android 11+ 仍走 `createDeleteRequest` 系统确认。

权限状态必须在首页处理。

无权限时：

* 不查询照片。
* 显示权限引导。
* 用户授权后刷新照片列表。

---

## 删除设计

产品层删除含义：

```text
移动到 App 内垃圾桶（软删除，DataStore 记录）
永久删除（清空垃圾桶时才真正删除文件）
```

代码层必须使用：

```text
movePhotoToTrash（写入 DataStore，返回 Success，不触发系统授权）
restorePhotoFromTrash（从 DataStore 移除记录，返回 Success，不触发系统授权）
restorePhotosFromTrash（批量恢复，通过 MediaStore 恢复，可能返回 NeedSystemApproval）
deletePhotosPermanently（永久删除，通过 MediaStore 删除，可能返回 NeedSystemApproval）
```

App 内垃圾桶概念：

```text
moveToTrash 和 restoreFromTrash 操作的是 App 自己维护的 DataStore 软删除记录，不直接操作 MediaStore。
照片文件不会被真正删除，只是从 App 首页/清理流中隐藏。
只有"清空垃圾桶"（restorePhotosFromTrash / deletePhotosPermanently）才真正发起 MediaStore 操作。
```

垃圾桶页操作：

* 从垃圾桶恢复：调用 `restorePhotosFromTrash`（从 App 内垃圾桶移除记录）。
* 永久删除：调用 `deletePhotosPermanently`，需用户确认，通过 MediaStore 真正删除文件。

禁止：

```text
deletePhoto
removeForever
```

除非未来明确支持永久删除。

---

## 首页排序/过滤/分组设计

首页照片加载后，通过以下 UseCase 链完成排序、过滤和分组：

```text
Compose
  ↓ 事件回调（排序方式、过滤条件）
ViewModel
  ↓ 调用排序 UseCase
SortPhotosUseCase
  ↓ 排序后的照片列表
FilterPhotosUseCase
  ↓ 过滤后的照片列表
BuildPhotoDateGroupsUseCase
  ↓ 按日期分组的列表
ViewModel
  ↓ 更新 UiState
Compose
```

说明：

* `SortPhotosUseCase`：按日期、大小等维度排序照片列表。
* `FilterPhotosUseCase`：根据条件过滤照片。UnseenOnly 过滤需要读取 AppSeenStateDataSource，因此是 suspend 函数。
* `BuildPhotoDateGroupsUseCase`：将照片列表按日期分组为 `PhotoDateGroup` 列表。
* 原 `ObservePhotoDateGroupsUseCase` 已删除，拆分为以上三个独立 UseCase。

---

## 系统媒体授权请求设计

`SystemMediaActionRequest` 用于将需要系统媒体授权的操作传递到 UI 层。

结构：

```text
SystemMediaActionRequest(
    photos: List<PhotoItem>,
    type: SetFavorite / RemoveFavorite / DeletePermanently
)
```

说明：

* `photos` 改为 `List<PhotoItem>`，支持批量操作。
* 系统媒体授权仅用于永久删除（DeletePermanently）。收藏和取消收藏现在使用 App DataStore，不再触发系统授权。
* `SetFavorite`、`RemoveFavorite`、`MoveToTrash` 和 `RestoreFromTrash` 保留在 `SystemMediaActionType` 枚举中但运行时不再触发 `NeedSystemApproval`。
* ViewModel 将请求包装为一次性 UI effect，Compose 启动系统授权页。
* 授权结果返回后 ViewModel 继续处理对应操作。

---

## 收藏设计

产品层收藏含义：

```text
写入 App 内 DataStore 收藏状态，不触发系统授权
```

实现要求：

* 收藏/取消收藏使用 `AppFavoriteStateDataSource` 管理，仅写入 DataStore，直接返回 Success，不触发系统授权。
* 照片页隐藏已收藏照片（`observeAllPhotos` 排除 App 收藏中的照片）。
* 收藏页专门展示已收藏照片（`observeFavoritedPhotos`）。
* 首次启动时将系统 `IS_FAVORITE` 一次性迁移到 `AppFavoriteStore`，后续不再读取系统收藏状态。
* 移入垃圾桶时快照当前收藏状态（`wasFavorited`、`favoritedAt`），恢复时自动还原。
* `PhotoItem.isFavorite` 来源改为 `AppFavoriteStore`，不再来自 MediaStore IS_FAVORITE。
* Favorites scope 的清理页上滑 = 取消收藏（移除 AppFavoriteStore 条目）。
* AllPhotos scope 的清理页上滑 = 收藏（写入 AppFavoriteStore 条目）。

---

## 清理页手势设计

清理页手势由 UI 层管理纯动画状态，由 ViewModel 管理当前照片索引和业务动作。手势语义由 `ReviewScope` 决定。

```text
Compose
  ↓ 水平/垂直手势事件
ReviewViewModel
  ↓ 水平切换调用 SwitchReviewPhotoUseCase
  ↓ 垂直处理调用 Favorite / RemoveFavorite / MoveToTrash UseCase（由 scope 决定）
```

规则：

* 水平滑动只修改 `currentIndex`，不访问 Repository。
* 垂直滑动通过 UseCase 访问 Repository。
* AllPhotos scope：向上 = 收藏，向下 = 移入垃圾桶。
* Favorites scope：向上 = 取消收藏，向下 = 移入垃圾桶。
* 方向判定、跟手偏移、回弹、飞出动画属于 Compose 的纯 UI 动画状态。
* 是否允许切换上一张或下一张由 ViewModel 根据清理队列计算。
* `isProcessingAction` 为 true 时，Compose 不触发新的水平切换或垂直清理动作。
* 缩放中（`isZoomed`）时，禁用滑动手势。
