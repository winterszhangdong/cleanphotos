# 数据模型

视觉规格见 `docs/ui-design.md`；本文件只描述数据。2026-10-11 暗房重构引入的数据需求见文末「暗房重构新增」。

## PhotoItem

照片基础模型。

```kotlin
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val dateTaken: Long?,
    val dateGroup: String,
    val size: Long?,
    val width: Int?,
    val height: Int?,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val mediaType: MediaType = MediaType.Image,
    val durationMs: Long? = null,
    val relativePath: String? = null,
    val bucketName: String? = null,
)
```

字段说明：

```text
id：MediaStore Files 表 _ID。图片和视频共用这一列，两张表不会撞号；收藏 / 看过 / 垃圾桶记录继续按 id 存
uri：图片用 Images.Media.EXTERNAL_CONTENT_URI + id；视频用 Video.Media.EXTERNAL_CONTENT_URI + id
displayName：文件名
dateTaken：有效拍摄时间（DATE_TAKEN，为空则 DATE_ADDED × 1000）
dateGroup：日期分组文本
size：文件大小（字节），来自 SIZE；≤0 时为 null。用于垃圾桶「可释放」、单张大小、小结卡「释放」、「全部看过了」的待释放
width：宽度（已按 ORIENTATION 校正：旋转 90° / 270° 时与 HEIGHT 对调，即显示时的宽）。视频只在该列存在时对调（Android 10+）
height：高度（同上，显示时的高）
isFavorite：是否已被系统相册标记为收藏。Android 11 / API 30 及以上应来自 MediaStore 的 IS_FAVORITE；低版本不支持系统收藏时不能用本地值伪装为系统收藏成功
isTrashed：是否在 App 内垃圾桶中。由 AppTrashEntry 记录计算而来，不来自 MediaStore IS_TRASHED
mediaType：Image 或 Video。视频和照片走同一套列表、清理、收藏、垃圾桶、看过
durationMs：视频时长（毫秒，MediaStore DURATION）；图片为 null。界面格式为 m:ss，满 1 小时为 h:mm:ss
relativePath：相对外部存储根目录的文件夹，与 RELATIVE_PATH 相同格式、以 / 结尾，例如 Pictures/Screenshots/。Android 10+ 直接读 RELATIVE_PATH；Android 8–9 由 DATA 相对外部存储根目录推出。推不出时为 null
bucketName：所在文件夹名（BUCKET_DISPLAY_NAME）
```

`PhotoItem` 没有动态照片字段。识别结果不进整库查询，只在播放时按需计算。

***

## MotionPhotoVideo

内嵌视频在原文件里的字节范围。识别不到就是没有这个值，不另造一种「未知」状态。

```kotlin
data class MotionPhotoVideo(
    val offset: Long,
    val length: Long,
)
```

```text
offset：内嵌 MP4 的起点，该处起 4 字节之后必须是 ftyp
length：从 offset 走到最后一个完整合法 box 的结尾。vivo 的 uuid 尾盒、OPPO 的 jxrs 尾标不计入
```

## PlaybackTarget

当前页要播什么。只存在于清理页和垃圾桶预览的 UiState，不持久化。

```kotlin
sealed interface PlaybackTarget {
    val uri: Uri
    data class Video(override val uri: Uri) : PlaybackTarget
    data class MotionPhoto(override val uri: Uri, val video: MotionPhotoVideo) : PlaybackTarget
}
```

```text
Video：MediaStore 视频，播整个 uri
MotionPhoto：图片文件里的一段 MP4，按 offset / length 截取
普通图片：UiState.playback 为 null
playbackMuted：这次进入该页的静音，默认 true。不进 DataStore，不进 SavedStateHandle
```

## PlaybackPolicy

```kotlin
enum class PlaybackKind { Video, MotionPhoto }

data class PlaybackPolicy(
    val autoplay: Boolean,
)

fun playbackPolicy(kind: PlaybackKind, animationsEnabled: Boolean): PlaybackPolicy =
    when (kind) {
        PlaybackKind.Video, PlaybackKind.MotionPhoto -> PlaybackPolicy(autoplay = animationsEnabled)
    }
```

```text
autoplay = animationsEnabled
视频和动态照片都不循环。系统关闭动画只关掉自动播放。
视频播完由播放器 seekTo(0) 并暂停，不靠循环策略。
```

## 动态照片识别

`MotionPhotoLocator` 对随机读接口做纯解析。JPEG 在 SOS 之前读段（上限约 512KB，Latin-1），同时认属性 `ns:Key="v"` 和元素 `<ns:Key>v</ns:Key>`。顺序：

```text
1. Google Motion Photo v1：Camera:MotionPhoto="1" 或 GCamera:MotionPhoto="1"。每一份 Container:Directory 单独算：Semantic=MotionPhoto 的 Length，加上该项之后、这份目录里其余项的 Length，起点 = 文件大小 − 这个和。Item:Padding 不参与。同一份 XMP 同时出现在 Exif 0x02BC 和 APP1 时不把两份 Length 加在一起。校验通过的第一份就用。
2. 旧 MicroVideo：GCamera:MicroVideo="1" + GCamera:MicroVideoOffset。起点 = 文件大小 − offset。
3. OPPO / 一加 / realme：命名空间 ns.oplus.com/photo/1.0/camera/ 或 ns.oppo.com/photo/1.0/camera/，OpCamera:OLivePhotoVersion + OpCamera:VideoLength。起点 = 文件大小 − VideoLength。校验失败再扫。
4. 尾标。三星 SEF：末 8 字节是 u32LE(backOffset) + SEFT，SEFH 在 size − 8 − backOffset，type 0x0A30 的记录前 64 字节里找 `ftyp`，起点 = 命中处 − 4（有 mpv2 / mpv3 头时 ftyp 在头后面，同样命中）。荣耀 / 华为：末 20 字节 ASCII LIVE_<n>（右侧填充），先试起点 size − 20 − n；校验不过（华为样本视频与尾标之间多一段 20 字节记录，起点会晚 20 字节）就从该点前 64 字节起往后扫 ftyp。
5. 兜底扫描只在两种情况做：XMP 里有 OPPO 标记但校验失败，或 Exif Make 去掉空白再转大写后恰好是 HUAWEI 或 HONOR。从 JPEG EOI（FF D9）之后找 ftyp。
HEIC：走顶层 box，找到 mpvd，视频是该 box 的载荷。品牌含 heic / heix / hevc / hevx / mif1 / msf1 / heim / heis / avif / avis。
```

统一校验：`offset + 8 ≤ size`，`[offset+4, offset+8)` 为 `ftyp`。从 offset 按 MP4 顶层 box 走（32 位 size；size 为 1 时用 64 位 largesize；size 为 0 表示直到文件尾）。必须看到一个完整的 `moov`。类型不是 4 个可打印 ASCII，或 box 自身越出文件，就停在这个 box 之前。`moov` 从未完整落在文件内则返回 null。最后一段完整 box 的结尾是真实 length。

***

## AppTrashEntry

App 内垃圾桶条目。

```kotlin
data class AppTrashEntry(
    val photoId: Long,
    val movedToTrashAt: Long,
    val wasFavorited: Boolean = false,
    val favoritedAt: Long? = null
)
```

字段说明：

```text
photoId：照片在 MediaStore 中的 ID
movedToTrashAt：移入 App 内垃圾桶的时间戳（毫秒）
wasFavorited：移入垃圾桶时该照片是否在 App 内收藏中
favoritedAt：原收藏时间戳，未收藏时为 null
```

规则：

```text
App 内垃圾桶是 App 自己维护的软删除记录，与系统 MediaStore 垃圾桶不同
照片文件不会被删除，只是从 App 首页/清理流中隐藏
只有清空垃圾桶时才真正发起系统永久删除
wasFavorited 和 favoritedAt 用于恢复时还原收藏快照
```

***

## PhotoDateGroup

首页日期分组模型。

```kotlin
data class PhotoDateGroup(
    val date: String,
    val photos: List<PhotoItem>,
    val totalCount: Int
)
```

字段说明：

```text
date：分组键，来自 PhotoItem.dateGroup。界面不直接显示这串字。日期头由 DayDisplay 画成大号日数字加灰色 M月，例如 21 和 9月，不写年份。
photos：该日期下用于展示的照片
totalCount：该日期下照片总数
```

规则：

```text
photos 最多用于首页展示 8 张
totalCount 表示该日期实际照片总数
```

***

## PhotoSortOrder

首页照片排序方式。

```kotlin
enum class PhotoSortOrder {
    NewestFirst,
    OldestFirst
}
```

说明：

```text
NewestFirst：最新照片排在前面（默认）
OldestFirst：最早的照片排在前面。菜单文案是「最早优先」
```

***

## PhotoFilterMode

首页照片过滤模式。

```kotlin
enum class PhotoFilterMode {
    AllPhotos,
    Screenshots,
    LargeFiles,
    ChatApps,
    OnThisDay
}
```

说明：

```text
AllPhotos：展示全部照片（默认）。相似范围在界面上单独存在，映射到这个模式，不进清理页
Screenshots / LargeFiles / ChatApps：规则见 SmartFilterRules
OnThisDay：规则见 OnThisDayRules
「只看没看过」不是这个枚举里的一项，是单独的布尔 unseenOnly：先按范围筛，再去掉 seenIds 里的照片。不持久化。相似范围忽略它
```

判定在 `domain/model/SmartFilterRules.kt`（纯函数，大小写不敏感）：

```text
截图：relativePath 或 bucketName 含 "screenshot"、"截屏"、"截图"；字段为 null 不命中
大文件：size ≥ 10 * 1024 * 1024 字节，图片和视频同一阈值；size 为 null 不算
聊天软件存图：relativePath 含 "weixin"、"whatsapp"、"telegram"，或以 "Pictures/QQ/" 开头
```

「那年今天」在 `domain/model/OnThisDayRules.kt`（纯函数，今天和时区由调用方传入；生产路径用 `LocalDate.now()` 和 `ZoneId.systemDefault()`）：

```text
dateTaken（毫秒）≤ 0 或没有：不命中
按系统时区换成当地日期：月、日都等于今天，且年份早于今年 → 命中
2 月 29 日的照片只在今天也是 2 月 29 日时命中
图片和视频都算
今天只在照片列表或看过状态变化时重算，跨过午夜不会自动刷新
```

***

## TrashScreenMode

垃圾桶页屏幕模式。

```kotlin
enum class TrashScreenMode {
    Grid,
    Selection,
    Preview
}
```

说明：

```text
Grid：网格浏览模式，展示所有垃圾桶照片缩略图
Selection：多选模式，用户可选中多张照片批量操作
Preview：预览模式，查看单张照片大图
```

***

## AppFavoriteEntry

App 内收藏条目。

```kotlin
data class AppFavoriteEntry(
    val photoId: Long,
    val favoritedAt: Long
)
```

字段说明：

```text
photoId：照片在 MediaStore 中的 ID
favoritedAt：收藏时间戳（毫秒）
```

规则：

```text
与系统 MediaStore IS_FAVORITE 不同，这是 App 自己维护的收藏登记
收藏/取消收藏不再触发系统授权，仅写入本地 DataStore
照片页隐藏已收藏照片，收藏页专门展示已收藏照片
```

***

## ReviewScope

清理会话来源枚举，区分清理页的入口来源，决定候选队列和手势语义。

```kotlin
enum class ReviewScope {
    AllPhotos,
    Favorites
}
```

说明：

```text
AllPhotos：从全部照片页进入，上滑 = 收藏，下滑 = 移入垃圾桶
Favorites：从收藏页进入，上滑 = 取消收藏，下滑 = 移入垃圾桶
```

***

## PhotoActionType

照片操作类型。

```kotlin
enum class PhotoActionType {
    Favorite,
    Unfavorite,
    MoveToTrash
}
```

说明：

```text
Favorite：收藏照片（AllPhotos scope 上滑）
Unfavorite：取消收藏（Favorites scope 上滑）
MoveToTrash：移动到垃圾桶
```

***

## PhotoOperationResult

照片业务操作结果。

收藏、取消收藏、移动到 App 内垃圾桶、从 App 内垃圾桶恢复、永久删除的结果封装。其中只有收藏、取消收藏和永久删除可能因系统媒体权限而需要额外授权；移动到 App 内垃圾桶和从 App 内垃圾桶恢复仅写 DataStore，直接返回 Success。

```kotlin
sealed interface PhotoOperationResult {
    data object Success : PhotoOperationResult
    data class NeedSystemApproval(
        val request: SystemMediaActionRequest
    ) : PhotoOperationResult
    data class Failure(
        val reason: PhotoOperationFailureReason
    ) : PhotoOperationResult
}
```

字段说明：

```text
Success：系统操作已经完成，可以更新清理队列
NeedSystemApproval：系统要求用户授权媒体修改，页面需要启动系统授权流程
Failure：操作失败，不能切换照片
```

### SystemMediaActionRequest

```kotlin
enum class SystemMediaActionType {
    SetFavorite,
    RemoveFavorite,
    MoveToTrash,
    RestoreFromTrash,
    DeletePermanently
}
```

说明：

```text
SetFavorite：收藏照片（触发系统授权）
RemoveFavorite：取消收藏（触发系统授权）
DeletePermanently：永久删除（触发系统授权）
MoveToTrash：保留枚举值，运行时不再触发 NeedSystemApproval（App 内垃圾桶仅写 DataStore）
RestoreFromTrash：保留枚举值，运行时不再触发 NeedSystemApproval（App 内垃圾桶仅写 DataStore）
```

```kotlin
data class SystemMediaActionRequest(
    val photos: List<PhotoItem>,
    val actionType: SystemMediaActionType,
    val requestToken: String,
    val intentSender: IntentSender
)
```

字段说明：

```text
photos：需要执行系统媒体操作的照片列表
actionType：本次系统媒体操作类型
requestToken：ViewModel 用于关联系统授权回调和待继续操作的标识
intentSender：data 层通过 MediaStore 创建的系统授权入口，ViewModel 只转发给 UI 层启动
```

实现说明：

```text
data 层负责通过 MediaStore 生成 PendingIntent 或 IntentSender
ViewModel 将 IntentSender 包装成一次性 ReviewEffect
Compose 只负责启动 ViewModel 暴露的一次性系统授权事件，不直接访问 MediaStore
```

### PhotoOperationFailureReason

```kotlin
enum class PhotoOperationFailureReason {
    SystemFavoriteUnsupported,
    PermissionDenied,
    UserCancelled,
    MediaStoreFailed,
    WritePermissionRequired
}
```

说明：

```text
SystemFavoriteUnsupported：当前系统版本或媒体提供方不支持系统收藏
PermissionDenied：没有足够权限
UserCancelled：用户取消系统授权
MediaStoreFailed：MediaStore 操作失败
WritePermissionRequired：Android 8–10 永久删除缺少 WRITE_EXTERNAL_STORAGE，由界面申请后重试
```

***

## PhotoAction

照片操作记录，用于撤回。

```kotlin
data class PhotoAction(
    val photo: PhotoItem,
    val actionType: PhotoActionType,
    val previousIndex: Int,
    val timestamp: Long
)
```

字段说明：

```text
photo：被操作的照片
actionType：操作类型
previousIndex：操作前照片在清理队列中的位置
timestamp：操作时间
```

***

## ReviewSession

清理会话模型。

```kotlin
data class ReviewSession(
    val photos: List<PhotoItem>,
    val currentIndex: Int,
    val totalCount: Int,
    val lastAction: PhotoAction?
)
```

字段说明：

```text
photos：当前待清理照片队列
currentIndex：当前照片索引
totalCount：清理会话总照片数
lastAction：最近一次操作，用于撤回
```

***

## 页面状态模型

### AllPhotosUiState

```kotlin
data class AllPhotosUiState(
    val totalCount: Int = 0,
    val dateGroups: List<PhotoDateGroup> = emptyList(),
    val visibleCount: Int = 0,
    val seenIds: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val errorMessage: String? = null,
    val sortOrder: PhotoSortOrder = PhotoSortOrder.NewestFirst,
    val scope: LibraryScope = LibraryScope.All,
    val unseenOnly: Boolean = false,
    val scopeCount: Int = 0,
    val visibleBytes: Long = 0L,
    val onThisDayCount: Int = 0
)
```

说明：

```text
totalCount：全部照片数量
dateGroups：日期分组列表（代码里是 sections：PhotoDaySection）
scopeCount：当前范围、还没叠「只看没看过」的张数
visibleCount：叠上「只看没看过」之后的张数。相似范围不吃开关，等于全部照片张数
visibleBytes：visibleCount 这些照片的 size 之和，null 记 0。顶栏只在大文件 / 截图 / 聊天显示
seenIds：已看过的照片 ID 集合。日期行用它算「已看 / 总数」，不再画缩略图光点
isLoading：是否加载中
hasPermission：是否有相册权限
errorMessage：错误信息
sortOrder：当前排序方式。菜单文案是「最新优先」和「最早优先」
scope：当前范围。LibraryScope = All / Similar / LargeFiles / Screenshots / ChatApps / OnThisDay。相似映射到 PhotoFilterMode.AllPhotos
unseenOnly：「只看没看过」。不持久化
onThisDayCount：全部照片里命中「那年今天」的张数，不受「看过」影响
showOnThisDay：onThisDayCount > 0，或当前范围已经是 OnThisDay；否则范围行不显示「那年今天」
```

***

### ReviewUiState

```kotlin
data class ReviewUiState(
    val photos: List<PhotoItem> = emptyList(),
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentPhoto: PhotoItem? = null,
    val canSwipePrevious: Boolean = false,
    val canSwipeNext: Boolean = false,
    val isSwitchingPhoto: Boolean = false,
    val showTutorial: Boolean = false,
    val isProcessingAction: Boolean = false,
    val lastAction: PhotoAction? = null,
    val actionMessage: String? = null,
    val errorMessage: String? = null,
    val isCompleted: Boolean = false,
    val imageLoadFailed: Boolean = false,
    val scope: ReviewScope = ReviewScope.AllPhotos
)
```

说明：

```text
photos：当前清理队列
currentIndex：当前索引
totalCount：总数
currentPhoto：当前照片
canSwipePrevious：是否可以右滑切换到上一张
canSwipeNext：是否可以左滑切换到下一张
isSwitchingPhoto：是否正在执行水平切换动画
showTutorial：是否展示教学
isProcessingAction：是否正在处理操作
lastAction：最近一次操作
actionMessage：操作结果提示
errorMessage：错误信息
isCompleted：清理队列是否已全部处理完
imageLoadFailed：当前照片是否加载失败
scope：清理会话来源，决定手势语义（AllPhotos 上滑收藏，Favorites 上滑取消收藏）
```

***

### ReviewEffect

清理页一次性事件。

```kotlin
sealed interface ReviewEffect {
    data class LaunchSystemMediaAction(
        val request: SystemMediaActionRequest
    ) : ReviewEffect
}
```

说明：

```text
LaunchSystemMediaAction：启动系统媒体授权页，仅用于收藏（SetFavorite / RemoveFavorite）和永久删除（DeletePermanently）场景
移动到 App 内垃圾桶和从 App 内垃圾桶恢复不触发此 effect
一次性事件不能长期保存在 ReviewUiState 中，避免屏幕旋转或重组后重复启动授权页
```

***

### TrashUiState

```kotlin
data class TrashUiState(
    val photos: List<PhotoItem> = emptyList(),
    val screenMode: TrashScreenMode = TrashScreenMode.Grid,
    val selectedPhotoIds: Set<Long> = emptySet(),
    val previewPhotoId: Long? = null,
    val showEmptyTrashConfirmDialog: Boolean = false,
    val isLoading: Boolean = false,
    val isProcessingAction: Boolean = false,
    val actionMessage: String? = null,
    val errorMessage: String? = null
)
```

说明：

```text
photos：垃圾桶照片列表
screenMode：当前屏幕模式（Grid / Selection / Preview）
selectedPhotoIds：多选模式下已选中的照片 ID 集合
previewPhotoId：预览模式下当前查看的照片 ID
showEmptyTrashConfirmDialog：是否显示清空垃圾桶确认弹窗
isLoading：是否加载中
isProcessingAction：是否正在执行操作（恢复、清空等）
actionMessage：操作结果提示
errorMessage：错误信息
```

***

### FavoritesUiState

```kotlin
data class FavoritesUiState(
    val totalCount: Int = 0,
    val sortOrder: PhotoSortOrder = PhotoSortOrder.NewestFirst,
    val dateGroups: List<PhotoDateGroup> = emptyList(),
    val seenIds: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
```

说明：

```text
totalCount：已收藏照片数量
sortOrder：状态仍保留默认最新优先。收藏页界面不提供排序按钮。
dateGroups：日期分组列表
seenIds：已看过的照片 ID 集合，用于决定缩略图是否画未看光点
isLoading：是否加载中
errorMessage：错误信息
```

***

## Repository 接口

### PhotoRepository

```kotlin
interface PhotoRepository {
    fun observeAllPhotos(): Flow<List<PhotoItem>>
    fun observeFavoritedPhotos(): Flow<List<PhotoItem>>
    suspend fun refreshPhotos()
    fun observeTrashedPhotos(): Flow<List<PhotoItem>>
    suspend fun refreshTrashedPhotos()
}
```

说明：

```text
observeAllPhotos：返回不在 App 垃圾桶且不在 App 收藏中的照片（排除已收藏和已删除的照片）
observeFavoritedPhotos：返回在 App 收藏中且不在 App 垃圾桶中的照片
observeTrashedPhotos：返回 App 内垃圾桶中的照片列表（根据 AppTrashStateDataSource 的记录从 MediaStore 查询）
refreshTrashedPhotos：刷新垃圾桶照片数据
```

***

### MotionPhotoRepository

```kotlin
interface MotionPhotoRepository {
    suspend fun locate(photo: PhotoItem): MotionPhotoVideo?
}
```

说明：

```text
只对 Image 读文件。缓存键是 id 加 size，上限 256，缓存 null，不缓存抛出的 IO 失败
```

***

### ReviewRepository

```kotlin
interface ReviewRepository {
    suspend fun favoritePhoto(photo: PhotoItem): PhotoOperationResult
    suspend fun removeFavorite(photo: PhotoItem): PhotoOperationResult
    suspend fun movePhotoToTrash(photo: PhotoItem): PhotoOperationResult
    suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult
    suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult
    suspend fun deletePhotosPermanently(photos: List<PhotoItem>): PhotoOperationResult

    // 照片页多选批量接口：每个 Store 一次 edit；不标记看过（由批量 UseCase 统一 markSeen(ids)）
    suspend fun favoritePhotos(photos: List<PhotoItem>): PhotoOperationResult
    suspend fun removeFavorites(photos: List<PhotoItem>): PhotoOperationResult
    suspend fun movePhotosToTrash(photos: List<PhotoItem>): PhotoOperationResult  // 逐张快照 wasFavorited / favoritedAt
}
```

批量 UseCase（`BatchPhotoActionUseCases.kt`）：`ResolveBatchFavoriteUseCase`（混合选择只收藏未收藏的，全部已收藏才取消收藏）、`BatchFavoritePhotosUseCase`、`BatchMovePhotosToTrashUseCase`、`UndoBatchActionUseCase`（只撤 `BatchAction.photos`，移入垃圾桶的撤回走 `restorePhotosFromTrash`）。`SeenStateRepository` 新增 `markSeen(photoIds: Set<Long>)`。

规则：

```text
favoritePhoto 和 removeFavorite 操作 App 内 DataStore 收藏状态，直接返回 Success，不触发系统授权
favoritePhoto：写入 AppFavoriteStore + 标记已看过
removeFavorite：移除 AppFavoriteStore 条目 + 标记已看过
movePhotoToTrash：快照 wasFavorited → 写入 AppTrashStore → 移除 AppFavoriteStore 条目 → 标记已看过，直接返回 Success
restorePhotoFromTrash：读取垃圾桶快照恢复 AppFavoriteStore → 移除 AppTrashStore 条目 → 标记已看过，直接返回 Success
restorePhotosFromTrash：批量恢复收藏快照 + 移除 AppTrashStore 条目，直接返回 Success
deletePhotosPermanently 通过 MediaStore 操作，可能返回 NeedSystemApproval，成功后清理三 Store
```

***

### UserPreferencesRepository

```kotlin
interface UserPreferencesRepository {
    fun observeHasSeenReviewTutorial(): Flow<Boolean>
    suspend fun setHasSeenReviewTutorial(seen: Boolean)
    fun observeSwipeActionCount(): Flow<Int>
    suspend fun incrementSwipeActionCount()
    fun observeTotalFreedBytes(): Flow<Long>
    suspend fun addFreedBytes(bytes: Long) // bytes 必须 > 0，只增不减
}
```

***

## DataStore 字段

```text
has_seen_review_tutorial
app_trash_entries
app_favorite_entries
app_seen_entries
has_migrated_favorites
review_swipe_action_count
total_freed_bytes
```

含义：

```text
has_seen_review_tutorial：是否已经完成清理页首次清理滑动教学
app_trash_entries：App 内垃圾桶条目集合（Set<String>，格式 "photoId,movedToTrashAt,wasFavorited,favoritedAt"）
app_favorite_entries：App 内收藏条目集合（Set<String>，格式 "photoId,favoritedAt"）
app_seen_entries：已看过的照片 ID 集合（Set<String>，每条为 photoId 字符串）
has_migrated_favorites：是否已完成系统 IS_FAVORITE 到 AppFavoriteStore 的一次性迁移
review_swipe_action_count：累计成功的上滑 / 下滑处理次数（Int）。2026-10-23 起清理页不再根据它显示方向提示
total_freed_bytes：累计已确认从垃圾桶永久删除的字节（Long）。只增不减。只计刷新后确认已从图库消失的照片（size 未知按 0）；进程重建后没有删除前快照的那一次不计入
```

照片页不再读写 `trash_reminder_shown_at`。旧安装里如果还留着这个键，App 不管它。

默认值：

```text
has_seen_review_tutorial = false
app_trash_entries = emptySet()
app_favorite_entries = emptySet()
app_seen_entries = emptySet()
has_migrated_favorites = false
review_swipe_action_count = 0
total_freed_bytes = 0
```

当用户第一次完成有效的向上或向下清理滑动后：

```text
has_seen_review_tutorial = true
```

***

## UseCase 列表

### SortPhotosUseCase

按指定排序方式对照片列表排序。

```kotlin
class SortPhotosUseCase {
    operator fun invoke(
        photos: List<PhotoItem>,
        sortOrder: PhotoSortOrder
    ): List<PhotoItem>
}
```

说明：

```text
根据 sortOrder 对 photos 按 dateTaken 排序
NewestFirst：dateTaken 降序
OldestFirst：dateTaken 升序
dateTaken 为 null 的照片排在最后
```

***

### FilterPhotosUseCase

先按范围筛，再按 `unseenOnly` 去掉已看过的。suspend 版本自己读 seenIds；已经拿到 seenIds 的调用方用纯函数版本。

```kotlin
class FilterPhotosUseCase {
    suspend operator fun invoke(
        photos: List<PhotoItem>,
        filterMode: PhotoFilterMode,
        unseenOnly: Boolean = false,
    ): List<PhotoItem>

    operator fun invoke(
        photos: List<PhotoItem>,
        filterMode: PhotoFilterMode,
        seenIds: Set<Long>,
        unseenOnly: Boolean = false,
    ): List<PhotoItem>
}
```

说明：

```text
AllPhotos：返回全部照片
Screenshots / LargeFiles / ChatApps：按 SmartFilterRules 过滤
OnThisDay：按 OnThisDayRules 过滤，今天取 LocalDate.now()
unseenOnly 为 true：上面的结果再去掉 id 在 seenIds 里的照片
```

***

### BuildPhotoDateGroupsUseCase

将照片列表按日期分组。

```kotlin
class BuildPhotoDateGroupsUseCase {
    operator fun invoke(
        photos: List<PhotoItem>
    ): List<PhotoDateGroup>
}
```

说明：

```text
将已排序、已过滤的照片按 dateGroup 分组
photos 包含该日期下的所有照片
totalCount 记录该日期下实际照片总数
```

***

### RestorePhotosFromTrashUseCase

从垃圾桶批量恢复照片。

```kotlin
class RestorePhotosFromTrashUseCase(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(
        photos: List<PhotoItem>
    ): PhotoOperationResult
}
```

说明：

```text
调用 ReviewRepository.restorePhotosFromTrash 批量恢复照片
系统需要授权时返回 NeedSystemApproval
恢复成功返回 Success
```

***

### DeletePhotosPermanentlyUseCase

永久删除照片（不可恢复）。

```kotlin
class DeletePhotosPermanentlyUseCase(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(
        photos: List<PhotoItem>
    ): PhotoOperationResult
}
```

说明：

```text
调用 ReviewRepository.deletePhotosPermanently 永久删除照片
系统需要授权时返回 NeedSystemApproval
此操作不可逆，必须经过用户确认后才能调用
```

***

### StartReviewSessionUseCase

启动清理会话。

```kotlin
class StartReviewSessionUseCase(
    private val photoRepository: PhotoRepository,
    private val sortPhotosUseCase: SortPhotosUseCase,
    private val filterPhotosUseCase: FilterPhotosUseCase
) {
    suspend operator fun invoke(
        startPhotoId: Long,
        sortOrder: PhotoSortOrder,
        filterMode: PhotoFilterMode,
        scope: ReviewScope,
        unseenOnly: Boolean = false,
    ): ReviewSession
}
```

说明：

```text
接受 startPhotoId、sortOrder、filterMode、scope、unseenOnly
执行顺序：根据 scope 选择数据源（AllPhotos → observeAllPhotos，Favorites → observeFavoritedPhotos）→ 排序 → AllPhotos 再按 filterMode 过滤并叠 unseenOnly，Favorites 跳过过滤、也不吃 unseenOnly → 查找起始照片
找不到 startPhotoId 直接抛错，不回退到 0。起点已被「只看没看过」滤掉时，落到排序上它后面最近的一张，后面没有就取前面最近的一张
scope 决定候选队列：AllPhotos 排除垃圾桶和已收藏，Favorites 只取已收藏且不在垃圾桶的
```

***

### ObserveTrashedPhotosUseCase

观察垃圾桶照片列表。

```kotlin
class ObserveTrashedPhotosUseCase(
    private val photoRepository: PhotoRepository
) {
    operator fun invoke(): Flow<List<PhotoItem>>
}
```

说明：

```text
委托 PhotoRepository.observeTrashedPhotos()，返回实时更新的 App 内垃圾桶照片 Flow
```

***

### LocateMotionPhotoVideoUseCase

按照片类型给出播放目标。

```kotlin
class LocateMotionPhotoVideoUseCase(
    private val motionPhotoRepository: MotionPhotoRepository
) {
    suspend operator fun invoke(photo: PhotoItem): MotionPhotoVideo?
    suspend fun playbackTarget(photo: PhotoItem): PlaybackTarget?
}
```

说明：

```text
invoke 委托 MotionPhotoRepository.locate
playbackTarget：Video 直接返回 PlaybackTarget.Video，不读文件；Image 调用 invoke，有范围则包成 MotionPhoto，否则 null
```

***

## DataSource 列表

### MotionPhotoDataSource

按 uri 随机读文件并交给 `MotionPhotoLocator`。整段打开、读取、关闭都在 `Dispatchers.IO`。短读或越界抛 `IOException`。不缓存。

```kotlin
class MotionPhotoDataSource {
    suspend fun locate(uri: Uri): MotionPhotoVideo?
}
```

### AppTrashStateDataSource

App 内垃圾桶持久化数据源。

```kotlin
@Singleton
class AppTrashStateDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    fun observeEntries(): Flow<List<AppTrashEntry>>
    suspend fun upsertEntry(photoId: Long, movedToTrashAt: Long)
    suspend fun removeEntry(photoId: Long)
    suspend fun removeEntries(photoIds: Set<Long>)
    suspend fun removeMissingEntries(existingPhotoIds: Set<Long>)
}
```

方法说明：

```text
observeEntries：观察所有垃圾桶条目，按 movedToTrashAt 倒序排列
upsertEntry：新增或更新一条垃圾桶条目，同一 photoId 重复写入时覆盖旧值
removeEntry：删除单条垃圾桶条目
removeEntries：批量删除垃圾桶条目
removeMissingEntries：清理失效条目，移除所有不在 existingPhotoIds 中的条目（用于 MediaStore 刷新后检测外部删除导致的不一致；只有部分照片访问权时 refreshPhotos 不调用，查询结果不是整个图库）
```

存储格式：

```text
DataStore key: app_trash_entries（stringSet）
每条记录格式：photoId,movedToTrashAt（严格解析，格式非法时直接抛错）
```

***

### AppFavoriteStateDataSource

App 内收藏持久化数据源。

```kotlin
@Singleton
class AppFavoriteStateDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    fun observeEntries(): Flow<List<AppFavoriteEntry>>
    suspend fun upsertEntry(photoId: Long, favoritedAt: Long)
    suspend fun upsertEntries(entries: Map<Long, Long>)
    suspend fun removeEntry(photoId: Long)
    suspend fun removeEntries(photoIds: Set<Long>)
    suspend fun removeMissingEntries(existingPhotoIds: Set<Long>)
}
```

方法说明：

```text
observeEntries：观察所有收藏条目，按 favoritedAt 倒序排列
upsertEntry：新增或更新一条收藏条目，同一 photoId 重复写入时覆盖旧值
upsertEntries：批量新增或更新（photoId → favoritedAt），一次写入；用于首次迁移和批量移出垃圾桶
removeEntry：删除单条收藏条目
removeEntries：批量删除收藏条目
removeMissingEntries：清理失效条目，移除所有不在 existingPhotoIds 中的条目
```

存储格式：

```text
DataStore key: app_favorite_entries（stringSet）
每条记录格式：photoId,favoritedAt（严格解析，格式非法时直接抛错）
```

***

### AppSeenStateDataSource

App 内"已看过"状态持久化数据源。

```kotlin
@Singleton
class AppSeenStateDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    fun observeSeenIds(): Flow<Set<Long>>
    suspend fun markSeen(photoId: Long)
    suspend fun markSeen(photoIds: Set<Long>)
    suspend fun removeEntries(photoIds: Set<Long>)
    suspend fun removeMissingEntries(existingPhotoIds: Set<Long>)
}
```

方法说明：

```text
observeSeenIds：观察已看过的照片 ID 集合
markSeen：标记单张或批量照片为已看过
removeEntries：批量移除已看过记录
removeMissingEntries：清理失效条目，移除所有不在 existingPhotoIds 中的条目
```

存储格式：

```text
DataStore key: app_seen_entries（stringSet）
每条记录为 photoId 的字符串表示
```


***

## 暗房重构新增（P2 已实现）

代码：`domain/model/DayReview.kt`、`DayKey.kt`、`ReviewRules.kt`，`domain/usecase/` 下对应 UseCase，测试 `DayReviewUseCasesTest`。

### 天键 DayKey

`PhotoItem.dateGroup`（"MMMM dd, yyyy"，Locale.US）是全 App 统一的天键；`DayKey.parse` 把它转成 `LocalDate`，`"Unknown date"` 返回 null（不进月份条、不生成小结），其他非法格式直接抛错。

### 会话账本 ReviewLedger

`ReviewSession.photos` 会随处理移除照片，按天进度与小结需要会话**初始**顺序，所以单独记账：

```text
orderedPhotos：会话初始顺序（继承入口排序 / 筛选）
viewedIds：本次会话看过的照片
outcomes：photoId → Favorited / Unfavorited / Trashed
summarizedDays：本次会话已出过小结的天
```

撤回 `undoOutcome` 只去掉结果，照片仍算看过。引用不在会话里的 photoId 直接抛错。（P4 接入 ReviewViewModel。）

### UseCase

| UseCase | 作用 |
| --- | --- |
| BuildSessionDayProgressUseCase | 当前照片所在那一天的进度格：Favorited / Unfavorited / Trashed / Kept / Current / Pending；> 40 张 `useContinuousBar` |
| ShouldInsertDaySummaryUseCase | 决定 2 四条件：前进且跨天（toPhotoId = null 表示会话走到末尾）、离开的那天全部看过或处理过、≥ 6 张、本会话首次 |
| BuildDaySummaryUseCase | 小结：收藏 / 取消收藏 / 移走 / 保留数、移走字节、前 8 张（带结果）、月份条、下一天（封面 + 未看数）|
| BuildMonthProgressUseCase | 决定 12：月份条 Done / Highlight / Pending / Empty，跨会话（持久化看过 + 收藏 + 垃圾桶），晚于今天为 Empty |
| ObserveLibraryDayStateUseCase | 相册全量照片（可见 + 收藏 + 垃圾桶）、处理过 id、看过 id |
| ObserveTrashSummaryUseCase | `TrashSummary(count, totalBytes)`，size 未知按 0 |
| ObserveAllSeenStatsUseCase | 决定 11：`AllSeenStats(favoriteCount, trashCount, trashBytes)` |
| ObserveShowDirectionHintsUseCase / RecordSwipeActionUseCase | 决定 10：成功上下滑后 `review_swipe_action_count` +1，撤回不回退。界面不再读取这个数来画提示 |
| ObserveTotalFreedBytesUseCase / AddFreedBytesUseCase | 垃圾桶空状态「累计已释放」：读 / 累加 `total_freed_bytes` |
### TrashReminderRules

照片页提醒已删除。`TrashReminderRules` 只留 `AGE_THRESHOLD_MS`（30 天）。名字和数值不变，垃圾桶页读取。不再有 `TrashReminder`、`TrashReminderReason`、大小阈值、间隔、`evaluate`。`UserPreferencesRepository` 不再读写 `trash_reminder_shown_at`。

### Repository 变更

- 新增 `SeenStateRepository.observeSeenIds()`（实现包 `AppSeenStateDataSource`）。
- `UserPreferencesRepository` 新增 `observeSwipeActionCount()`、`incrementSwipeActionCount()`。

## 相似照片

只读「全部照片」里的图片，不改 `PhotoItem`，也不改 MediaStore 主查询。

### SimilarRules / DHash

```text
MAX_GAP_MS = 10_000
MAX_HAMMING = 10
HASH_PARALLELISM = 2
DHash.fromLuminance：9×8 亮度（72 个）按行比较相邻像素，得到 64 位 Long
DHash.hamming：两个 Long 的不同位数
```

### SimilarGroup

```kotlin
enum class SimilarMark { Keep, Remove }

data class SimilarGroup(
    val photos: List<PhotoItem>, // 拍摄时间升序，至少 2 张
    val recommendedId: Long,     // 推荐，不是唯一能留下的
    val sharpness: Map<Long, Double> = emptyMap(), // 拉普拉斯方差；失败的 id 不在里面
)
```

`key` 由组内 id 串成，和标记无关。扫描结果 `SimilarScan`：`Comparing(done, total)` 或 `Ready(groups)`。

`Sharpness.laplacianVariance` 是纯函数：4 邻域拉普拉斯，方差 = 平方均值 − 均值平方。纯色为 0。清晰度只对已经成组的照片计算。

推荐 `chooseSimilarRecommended`：清晰度高的优先，没有清晰度的最后。其后与旧规则相同：像素数（宽 × 高，缺失算 0）最大，其次 `size` 更大，再其次 `dateTaken` 更早，最后 id 更小。

页面上的标记是 `Map<groupKey, Map<photoId, SimilarMark>>`，只活在 `SimilarViewModel`。默认推荐为留下，其余为移走。上滑写成留下，下滑写成移走。已经是这一侧时不变。

### 已处理记录

`AppSimilarResolvedDataSource`，DataStore `stringSetPreferencesKey("app_similar_resolved")`。每条是 id 升序后用逗号连接。少于两张、不是严格升序、或有非数字，直接抛错，不静默丢掉。

```kotlin
interface SimilarResolvedRepository {
    fun observeResolved(): Flow<List<Set<Long>>>
    suspend fun addResolved(ids: Set<Long>)
    suspend fun removeResolved(ids: Set<Long>)
}
```

一组的 id 集合是某条记录的子集时，这组不出现在列表里。订阅记录变化不重新计算哈希和清晰度。

### SimilarPhotoRepository

```kotlin
interface SimilarPhotoRepository {
    fun hashPhotos(photos: List<PhotoItem>): Flow<SimilarHashProgress>
    fun measureSharpness(photos: List<PhotoItem>): Flow<SimilarSharpnessProgress>
}
```

实现是 `SimilarPhotoRepositoryImpl`，在 IO 上计算，并发不超过 `HASH_PARALLELISM`。单张失败不写入结果。`PhotoHashDataSource` 和 `PhotoSharpnessDataSource` 把结果放在进程内 Map，进程重启后重算。

### UseCase

| UseCase | 作用 |
| --- | --- |
| GroupSimilarPhotosUseCase | 纯函数：时间窗 + 汉明距离链式分组，并给出 `hashCandidates`（只有 10 秒内有邻居的图片） |
| ObserveSimilarGroupsUseCase | 观察全部照片。先哈希再只给组内算清晰度，然后按已处理记录过滤。记录变化不重新哈希 |
| chooseSimilarRecommended | 一组里默认推荐哪张 |

提交复用 `BatchMovePhotosToTrashUseCase`。撤回调用 `UndoBatchActionUseCase`，并 `removeResolved`。

***

### 文件大小文案

`core/util/FileSizeFormatter`：1024 进制，< 10 保留一位小数，≥ 10 取整（「3.1 MB」「31 MB」「1.2 GB」）。
