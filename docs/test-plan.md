#  测试用例设计说明

## 0 暗房重构测试要求 —— 优先于下文旧用例

规格：`docs/ui-design.md`（第 1 节最终决定、第 2–4 节设计系统与画面规格）、`docs/interaction.md`。下文与之冲突的旧用例（平面轮播 pageScale 0.96 / pageAlpha 0.92、一天/队列边界回弹、`isProcessingAction` 期间忽略新滑动、清空确认旧文案、钴蓝未看标记）以本节为准，在对应阶段改写或删除。

### 0.1 每阶段必做

- `make build`、`make test`、`make lint` 全部通过。
- 安装到 `emulator-5554`，每个画面 `adb exec-out screencap -p` 截图，对照 `docs/ui-design.md` 第 3、4 节逐条核对尺寸、颜色和状态，列出差异。测试照片自备（无版权问题的图片），推进模拟器相册。
- 动效：`adb shell screenrecord` 录屏，逐帧对照 `docs/ui-design.md` 2.4、2.7 核对时长、曲线、编排。
- 性能：清理页连续滑动 50 张，`adb shell dumpsys gfxinfo com.winters.cleanphotos` 查掉帧；缩放、横滑、上下滑无闪烁 / 黑屏（逐条回归 lessons）。
- 降级：API 26 或 30、33、34+ 三档模拟器。
- 系统「移除动画」打开后全部流程可走通、直接显示终态、无限循环停止。

### 0.2 设计系统（P1）单元测试

- `DarkroomColors` 每个值与 HTML `:root` 一致。
- `Motion`：时长 100/150/280/450/600/40；四条贝塞尔控制点；四档弹簧参数（1.0/1500、0.75/400、0.5/400、0.85/200）。
- `DevelopMatrix`：p=0 时亮度 0.06 / 反差 0.55 / 饱和 0；p=1 时为单位矩阵；三通道时间窗（亮度 0–500ms、反差 80–600ms、饱和 200–600ms）。
- `ApiTier`：26/30/31/33/34 各档的模糊、颗粒、触感、预测性返回取值。
- `OdometerText` 逐位差分：增加向上、减少向下，位数变化时正确对齐。
- `AnimationScale`：scale=0 时 `animationsEnabled=false`。

### 0.3 滑动手感验收（interaction.md 硬性要求）

- 距离 < 阈值但松手速度 > 甩动阈值 → 触发（单元测试 `SwipeDecision`）。
- 离场期间立刻开始下一次拖动可被接受；2 秒内连续处理 10 张：不丢操作（ViewModel 收到 10 个操作，数据层 10 条记录）、无闪烁、无卡顿；录屏附报告。
- 左右翻页 snappy 弹簧；跨天不阻塞，日期滚动 + 进度条切换 + CLOCK_TICK。
- 离场主体位移 ≤ 250ms 基本完成（录屏逐帧核对）。
- 小结插页触发条件四项（前进、全部看过、≥6 张、本会话首次）各自的正反用例。
- 方向提示小点：第 20 次成功上下滑后隐藏且重启后仍隐藏。


## 1 测试目标

测试目标是确保第一版核心流程稳定、安全、可恢复。

重点验证：

```text
首页可以正确读取和展示照片
照片可以按日期分组
用户可以从首页进入清理页
清理页可以正确显示当前照片
清理页可以左右滑动切换照片
清理页照片保持原始宽高比
清理页 pinch / 双击缩放过程中不能闪烁、重载或退回占位
横滑轮转动画中当前页和进入页必须同时可见，任意图片比例下动画参数一致
向上滑动可以写入 App 内收藏状态（DataStore，不触发系统授权）
向下滑动可以移动到 App 内垃圾桶（DataStore 软删除，不触发系统授权）
操作后可以撤回
首次教学只显示一次
垃圾桶页可以查看和恢复照片，且预览的缩放 / 横滑反馈与清理页一致
权限、空状态、失败状态都有正确处理
快速连续滑动不会导致重复操作
操作失败不会错误切换照片
```

测试优先级：

```text
P0：照片安全、核心清理流程、撤回、权限、App 内垃圾桶数据一致性
P1：首页展示、教学状态、垃圾桶恢复、AppTrashStateDataSource 解析与编码
P2：UI 细节、动画表现、边界视觉状态
```

***

## 2 测试分层

项目测试分为以下几层：

```text
单元测试
ViewModel 测试
UseCase 测试
Repository 测试
Compose UI 测试
集成测试
手动验收测试
回归测试
```

### 单元测试

用于测试纯业务逻辑，不依赖 Android 系统环境。

覆盖对象：

```text
日期分组逻辑
照片排序逻辑（NewestFirst / OldestFirst）
照片过滤逻辑（AllPhotos / UnseenOnly / Screenshots / LargeFiles / ChatApps）
清理队列索引计算
撤回插入位置
教学状态判断
操作状态流转
AppTrashStateDataSource 编码与解析（encodeEntry / parseEntry）
AppTrashStateDataSource CRUD（upsert / remove / removeEntries / removeMissingEntries）
PhotoPager 的轮播过渡参数（pageScale、pageAlpha、zIndex）、切页位置连续性、任意图片比例验证和稳定请求键
```

### ViewModel 测试

用于测试页面状态和用户事件响应。

覆盖对象：

```text
AllPhotosViewModel
ReviewViewModel
TrashViewModel
```

### UseCase 测试

用于测试单一业务动作。

覆盖对象：

```text
ObserveAllPhotosUseCase
SortPhotosUseCase
FilterPhotosUseCase
BuildPhotoDateGroupsUseCase
StartReviewSessionUseCase
FavoritePhotoUseCase
RemoveFavoritePhotoUseCase
SwitchReviewPhotoUseCase
MovePhotoToTrashUseCase
RestorePhotoFromTrashUseCase
RestorePhotosFromTrashUseCase
DeletePhotosPermanentlyUseCase
UndoPhotoActionUseCase
ObserveTutorialStateUseCase
SetTutorialSeenUseCase
```

### Repository 测试

用于测试数据抽象层行为。

Repository 测试中不直接访问真实系统相册。

使用：

```text
FakePhotoDataSource
FakeTrashDataSource
FakeUserPreferencesDataSource
FakeAppTrashStateDataSource
```

### Compose UI 测试

用于验证页面关键元素是否存在，以及用户操作是否触发正确回调。

覆盖对象：

```text
首页
清理页
垃圾桶页
教学层
撤回提示
缩放与横滑轮转动画回归
```

### 集成测试

用于验证多个模块串联后的完整流程。

覆盖流程：

```text
首页 → 清理页 → 收藏 → 撤回
首页 → 清理页 → 移动到 App 内垃圾桶（DataStore 写入）→ 撤回（DataStore 删除）
首页 → 清理页 → 移动到 App 内垃圾桶 → 垃圾桶页 → 恢复（DataStore 删除，无系统授权）
```

***

## 3 测试数据设计

测试中统一使用假照片数据，不依赖真实系统照片。

### 基础照片数据

```text
photo_1：2024-05-20
photo_2：2024-05-20
photo_3：2024-05-19
photo_4：2024-05-18
```

### 多日期分组数据

用于测试首页日期分组。

```text
2024-05-20：10 张照片
2024-05-19：3 张照片
2024-05-18：1 张照片
```

预期：

```text
首页展示 3 个日期分组
2024-05-20 分组首页只展示 8 张
totalCount 保持 10
日期按倒序排列
```

### 空照片数据

```text
photos = emptyList()
```

用于测试：

```text
空相册状态
清理页无照片状态
垃圾桶空状态
```

### 操作失败数据

模拟以下失败：

```text
App 内收藏写入失败（DataStore 写入失败）
取消收藏写入失败（DataStore 写入失败）
移动到 App 内垃圾桶失败（DataStore 写入失败）
从 App 内垃圾桶恢复失败（DataStore 写入失败）
撤回失败
MediaStore 查询失败
DataStore 写入失败
```

## 3.1 本轮缩放与横滑轮转动画回归点

本轮额外回归以下用户可见行为：

```text
清理页静止态保持原始比例居中（Fit），没铺满处为模糊环境光；垃圾桶预览静止态用 ContentScale.Fit，保持原始宽高比
pinch 放大与双击缩放期间图片不闪烁、不重复回到占位态
横滑拖动中当前页和进入页必须同时可见（任意图片比例）
pageScale 和 pageAlpha 在过渡中段同步变化
进入页 zIndex 高于当前页，保证视觉层级正确
切页边界（offsetX = ±screenWidth）时目标页位置无缝衔接
垃圾桶预览（TrashPreviewOverlay）与清理页（ReviewScreen）复用同一套缩放与横滑反馈
```

### PhotoPagerLayoutTest 已覆盖项

以下测试点由 PhotoPagerLayoutTest 单元测试直接验证，无需手动回归：

```text
边界页（首张右滑/末张左滑）不生成越界页
过渡中段（左滑/右滑）的 pageScale、pageAlpha、zIndex 参数精确值
静止态无轮播变换（pageScale=1、pageAlpha=1、pageParallaxOffsetPx=0）
切页边界位置连续性（offsetX = ±screenWidth 时目标页偏移无缝衔接）
任意图片比例（横图、竖图、方图、null 尺寸）不影响轮播参数
过渡进度计算（calculatePhotoPagerTransitionProgress）
连续帧位置差异不超过 1 像素
图片请求键稳定性
```

### 权限数据

模拟以下权限状态：

```text
已授权
未授权
用户拒绝
用户永久拒绝
部分照片访问权限
```

***

## 4 首页测试用例

### 用例：首页首次加载照片成功

优先级：

```text
P0
```

前置条件：

```text
用户已授权相册权限
FakePhotoRepository 返回照片列表
```

操作步骤：

```text
1. 创建 AllPhotosViewModel
2. 触发 loadPhotos 或初始化加载
3. 收集 AllPhotosUiState
```

预期结果：

```text
isLoading 最终为 false
hasPermission 为 true
totalCount 等于照片总数
dateGroups 不为空
errorMessage 为空
```

***

### 用例：首页按日期分组

优先级：

```text
P0
```

前置条件：

```text
照片包含多个拍摄日期
```

操作步骤：

```text
1. 输入多日期照片列表
2. 执行 BuildPhotoDateGroupsUseCase
3. 检查分组结果
```

预期结果：

```text
照片按日期正确分组
日期按倒序排列
每组 totalCount 正确
首页每组 photos 最多包含 8 张
```

***

### 用例：首页排序 NewestFirst

优先级：

```text
P1
```

前置条件：

```text
照片包含 photo_1（2024-05-20）、photo_2（2024-05-19）、photo_3（2024-05-18）
```

操作步骤：

```text
1. 设置 sortOrder = NewestFirst
2. 执行 SortPhotosUseCase
3. 检查排序结果
```

预期结果：

```text
photo_1 排在 photo_2 前面
photo_2 排在 photo_3 前面
dateTaken 为 null 的照片排在最后
```

***

### 用例：首页排序 OldestFirst

优先级：

```text
P1
```

前置条件：

```text
照片包含 photo_1（2024-05-20）、photo_2（2024-05-19）、photo_3（2024-05-18）
```

操作步骤：

```text
1. 设置 sortOrder = OldestFirst
2. 执行 SortPhotosUseCase
3. 检查排序结果
```

预期结果：

```text
photo_3 排在 photo_2 前面
photo_2 排在 photo_1 前面
dateTaken 为 null 的照片排在最后
```

***

### 用例：首页过滤 AllPhotos

优先级：

```text
P1
```

前置条件：

```text
照片包含 photo_1（isFavorite=true）、photo_2（isFavorite=false）、photo_3（isFavorite=true）
```

操作步骤：

```text
1. 设置 filterMode = AllPhotos
2. 执行 FilterPhotosUseCase
3. 检查过滤结果
```

预期结果：

```text
返回全部 3 张照片
不过滤任何照片
```

***

### 用例：首页过滤 UnseenOnly

优先级：

```text
P1
```

前置条件：

```text
照片包含 photo_1（未看过）、photo_2（已看过）、photo_3（未看过）
```

操作步骤：

```text
1. 设置 filterMode = UnseenOnly
2. 执行 FilterPhotosUseCase
3. 检查过滤结果
```

预期结果：

```text
仅返回 photo_1 和 photo_3
photo_2 被过滤掉
返回结果仅包含未看过的照片
```

***

### 用例：智能筛选规则与 FilterPhotosUseCase

优先级：

```text
P1
```

前置条件：

```text
自行构造 PhotoItem（mediaType / relativePath / bucketName / size）
```

操作步骤：

```text
1. 截图：relativePath 或 bucketName 含 screenshot / 截屏 / 截图（含大写），以及 null、不含关键字的反例
2. 大文件：size = 10 * 1024 * 1024、恰少 1 字节、null；图片和视频各一
3. 聊天软件：relativePath 含 weixin / whatsapp / telegram，或以 Pictures/QQ/ 开头（含大小写）；Pictures/QQ 无斜杠、Download/QQ/、仅 bucketName 为反例
4. 对同一列表执行 FilterPhotosUseCase 的 Screenshots / LargeFiles / ChatApps，seenIds 含命中项
```

预期结果：

```text
正例命中、反例不命中
三个新模式只返回命中项，已看过的命中项仍保留
UnseenOnly 与 AllPhotos 行为不变
```

***

### 用例：首页筛选数量与结果

优先级：

```text
P1
```

前置条件：

```text
照片页同时有截图、大文件、聊天软件存图和普通照片，其中一张截图已看过
```

操作步骤：

```text
1. 读取 AllPhotosUiState 的 screenshotCount / largeFileCount / chatAppCount
2. 依次切换 Screenshots、LargeFiles、ChatApps、AllPhotos
```

预期结果：

```text
三项数量按全部照片计算，已看过的截图仍计入 screenshotCount
当前筛选的可见列表只含命中项，且保留排序
切回 AllPhotos 后可见数量恢复为全部照片
```

***

### 用例：收藏后 App 内可见性

优先级：

```text
P0
```

前置条件：

```text
首页显示照片列表
photo_1 未收藏（isFavorite=false）
```

操作步骤：

```text
1. 进入清理页，向上滑动收藏 photo_1
2. App 内收藏写入成功
3. 返回首页
```

预期结果：

```text
photo_1 的 isFavorite 变为 true
首页照片列表更新后 photo_1 不再可见
Favorites 页可见 photo_1
如果 filterMode 为 UnseenOnly，photo_1 不在过滤结果中可见
```

***

### 用例：首页无权限状态

优先级：

```text
P0
```

前置条件：

```text
权限状态为未授权
```

操作步骤：

```text
1. 创建 AllPhotosViewModel
2. 模拟无权限
3. 收集页面状态
```

预期结果：

```text
hasPermission 为 false
不触发照片查询
显示权限引导状态
errorMessage 不应是崩溃信息
```

***

### 用例：首页空相册状态

优先级：

```text
P1
```

前置条件：

```text
用户已授权
照片列表为空
```

操作步骤：

```text
1. FakePhotoRepository 返回空列表
2. 加载首页
```

预期结果：

```text
totalCount 为 0
dateGroups 为空
显示空状态
页面不崩溃
```

***

### 用例：首页点击照片进入清理页

优先级：

```text
P0
```

前置条件：

```text
首页存在照片 photo_1
```

操作步骤：

```text
1. 点击 photo_1 缩略图
2. 监听导航事件
```

预期结果：

```text
发出导航到 review/{photoId} 的事件
photoId 等于被点击照片的 id
```

***

### 用例：首页点击 Trash 进入垃圾桶页

优先级：

```text
P1
```

前置条件：

```text
首页已显示
```

操作步骤：

```text
1. 点击底部 Trash
2. 监听导航事件
```

预期结果：

```text
发出导航到 trash 的事件
```

***

## 5 清理页测试用例

### 用例：清理页初始化当前照片

优先级：

```text
P0
```

前置条件：

```text
照片列表包含 photo_1、photo_2、photo_3
启动参数 photoId = photo_2.id
```

操作步骤：

```text
1. 创建 ReviewViewModel
2. 调用 initReview(photo_2.id)
3. 收集 ReviewUiState
```

预期结果：

```text
currentPhoto 为 photo_2
currentIndex 为 photo_2 所在索引
totalCount 等于照片总数
```

***

### 用例：清理页显示进度

优先级：

```text
P1
```

前置条件：

```text
当前照片为第 2 张
总数为 10
```

操作步骤：

```text
1. 渲染 ReviewScreen
2. 检查进度文本
```

预期结果：

```text
显示 2 / 10
```

***

### 用例：左滑切换到下一张照片

优先级：

```text
P0
```

前置条件：

```text
照片列表包含 photo_1、photo_2、photo_3
当前照片为 photo_1
```

操作步骤：

```text
1. 调用 onSwipeLeft
2. 收集 ReviewUiState
```

预期结果：

```text
currentPhoto 为 photo_2
currentIndex 为 1
photo_1 未从清理队列移除
FavoritePhotoUseCase 未调用
MovePhotoToTrashUseCase 未调用
不显示 ActionUndoBar
```

***

### 用例：右滑切换到上一张照片

优先级：

```text
P0
```

前置条件：

```text
照片列表包含 photo_1、photo_2、photo_3
当前照片为 photo_2
```

操作步骤：

```text
1. 调用 onSwipeRight
2. 收集 ReviewUiState
```

预期结果：

```text
currentPhoto 为 photo_1
currentIndex 为 0
photo_2 未从清理队列移除
FavoritePhotoUseCase 未调用
MovePhotoToTrashUseCase 未调用
不显示 ActionUndoBar
```

***

### 用例：左右滑动到边界时回弹

> 暗房重构：本用例按第 0 节改写（边界只在会话首尾 / 不缩放不淡出 / 操作入队不忽略 / 会话结束见小结插页），P4 落地时更新。

优先级：

```text
P1
```

前置条件：

```text
当前照片为第一张或最后一张
```

操作步骤：

```text
1. 第一张调用 onSwipeRight
2. 最后一张调用 onSwipeLeft
```

预期结果：

```text
currentPhoto 不变
currentIndex 不越界
不触发业务用例
不显示 ActionUndoBar
```

***

### 用例：首次进入显示教学

优先级：

```text
P0
```

前置条件：

```text
has_seen_review_tutorial = false
```

操作步骤：

```text
1. 初始化 ReviewViewModel
2. 收集 ReviewUiState
```

预期结果：

```text
showTutorial 为 true
页面显示教学层
教学层不是弹窗
教学层不阻断滑动
```

***

### 用例：已看过教学后不显示教学

优先级：

```text
P0
```

前置条件：

```text
has_seen_review_tutorial = true
```

操作步骤：

```text
1. 初始化 ReviewViewModel
2. 收集 ReviewUiState
```

预期结果：

```text
showTutorial 为 false
页面不显示教学层
```

***

### 用例：向上滑动触发收藏

优先级：

```text
P0
```

前置条件：

```text
当前照片为 photo_1
系统收藏用例执行成功
```

操作步骤：

```text
1. 调用 onSwipeUp
2. 等待操作完成
3. 收集 ReviewUiState
```

预期结果：

```text
FavoritePhotoUseCase 被调用
AppFavoriteStateDataSource 写入 App 内收藏状态
photo_1.isFavorite 最终与 App 内收藏状态一致
photo_1 从清理队列移除
currentPhoto 切换到下一张
lastAction.actionType 为 Favorite
actionMessage 为 已收藏
显示 ActionUndoBar
```

***

### ~~用例：系统需要授权后收藏成功~~（已移除）

> **移除原因**：收藏已改为 App 内 DataStore 写入，`FavoritePhotoUseCase` 直接返回 `Success`，不存在 `NeedSystemApproval` 分支。

***

### ~~用例：用户取消系统收藏授权不切换照片~~（已移除）

> **移除原因**：收藏链路已无系统授权步骤，因此不存在“用户取消收藏授权”的失败分支。

***

### 用例：向下滑动触发移动到 App 内垃圾桶

优先级：

```text
P0
```

前置条件：

```text
当前照片为 photo_1
移动到 App 内垃圾桶用例执行成功（DataStore 写入，不触发系统授权）
```

操作步骤：

```text
1. 调用 onSwipeDown
2. 等待操作完成
3. 收集 ReviewUiState
```

预期结果：

```text
MovePhotoToTrashUseCase 被调用
FakeAppTrashStateDataSource 写入 photo_1 的垃圾桶条目
photo_1 从清理队列移除
currentPhoto 切换到下一张
lastAction.actionType 为 MoveToTrash
actionMessage 为 已移动到垃圾桶
显示 ActionUndoBar
无需系统授权，MovePhotoToTrashUseCase 直接返回 Success
```

***

### ~~用例：移动到垃圾桶需要系统授权后成功~~（已移除）

> **移除原因**：App 内垃圾桶改用 DataStore 软删除，MovePhotoToTrashUseCase 直接写入 AppTrashStateDataSource，不再需要系统授权。moveToTrash 直接返回 Success，不存在 NeedSystemApproval 分支。

***

### ~~用例：用户取消移动到垃圾桶系统授权不切换照片~~（已移除）

> **移除原因**：App 内垃圾桶不再需要系统授权，因此不存在用户取消系统授权的场景。moveToTrash 直接返回 Success。

***

### 用例：收藏失败不切换照片

优先级：

```text
P0
```

前置条件：

```text
当前照片为 photo_1
FavoritePhotoUseCase 返回失败，例如 DataStore 写入失败
```

操作步骤：

```text
1. 调用 onSwipeUp
2. 等待操作完成
```

预期结果：

```text
currentPhoto 仍然是 photo_1
App 内收藏状态未改变
photo_1 未从队列移除
lastAction 不更新为成功操作
显示错误提示
isProcessingAction 最终为 false
```

***

### 用例：移动到 App 内垃圾桶失败不切换照片

优先级：

```text
P0
```

前置条件：

```text
当前照片为 photo_1
MovePhotoToTrashUseCase 返回失败（例如 DataStore 写入失败）
```

操作步骤：

```text
1. 调用 onSwipeDown
2. 等待操作完成
```

预期结果：

```text
currentPhoto 仍然是 photo_1
photo_1 未从队列移除
FakeAppTrashStateDataSource 未新增 photo_1 条目
lastAction 不更新为成功操作
显示错误提示
isProcessingAction 最终为 false
```

***

### 用例：快速连续向上滑动不会重复收藏

> 暗房重构：本用例按第 0 节改写（边界只在会话首尾 / 不缩放不淡出 / 操作入队不忽略 / 会话结束见小结插页），P4 落地时更新。

优先级：

```text
P0
```

前置条件：

```text
当前照片为 photo_1
isProcessingAction 控制开启
```

操作步骤：

```text
1. 连续调用 onSwipeUp 两次
2. 等待操作完成
```

预期结果：

```text
FavoritePhotoUseCase 只调用一次
photo_1 只被移除一次
不会跳过两张照片
isProcessingAction 最终为 false
```

***

### 用例：快速连续上下滑动不会重复处理

> 暗房重构：本用例按第 0 节改写（边界只在会话首尾 / 不缩放不淡出 / 操作入队不忽略 / 会话结束见小结插页），P4 落地时更新。

优先级：

```text
P0
```

前置条件：

```text
当前照片为 photo_1
```

操作步骤：

```text
1. 几乎同时调用 onSwipeUp 和 onSwipeDown
2. 等待操作完成
```

预期结果：

```text
只有一个动作被执行
不会同时收藏并移动到垃圾桶
currentIndex 不会异常跳跃
队列状态一致
```

***

### 用例：处理最后一张照片后显示完成状态

> 暗房重构：本用例按第 0 节改写（边界只在会话首尾 / 不缩放不淡出 / 操作入队不忽略 / 会话结束见小结插页），P4 落地时更新。

优先级：

```text
P1
```

前置条件：

```text
清理队列只有 photo_1
```

操作步骤：

```text
1. 调用 onSwipeUp 或 onSwipeDown
2. 等待操作完成
```

预期结果：

```text
photos 为空
currentPhoto 为空
页面显示完成状态
显示返回全部照片入口
显示查看垃圾桶入口
```

***

### 用例：清理页进度文字隐藏

优先级：

```text
P1
```

前置条件：

```text
清理页显示当前照片
```

操作步骤：

```text
1. 渲染 ReviewScreen
2. 检查进度文字是否显示
```

预期结果：

```text
进度文字（如 2 / 10）不显示
页面仅保留照片内容和手势指示器
```

***

### 用例：清理页 ActionUndoBar 位置

优先级：

```text
P1
```

前置条件：

```text
完成一次有效操作（收藏或移动到垃圾桶）
ActionUndoBar 显示
```

操作步骤：

```text
1. 向上或向下滑动完成操作
2. 检查 ActionUndoBar 位置
```

预期结果：

```text
ActionUndoBar 位于屏幕底部、Trash 指示器下方
不遮挡 Favorite 指示器
不遮挡照片内容
```

***

### 用例：清理页首帧显示图片不灰屏（P0 回归）

优先级：

```text
P0
```

前置条件：

```text
照片页显示照片列表
照片列表至少包含 5 张照片
```

操作步骤：

```text
1. 从照片页点击一张中间位置的照片进入清理页
2. 观察首帧渲染
```

预期结果：

```text
首帧直接显示点击的照片，不出现持续灰屏
照片加载过程中最多短暂显示深色 loading 占位，加载完成后稳定显示图片
```

***

### 用例：左滑切换不灰屏（P0 回归）

优先级：

```text
P0
```

前置条件：

```text
清理页显示中间位置的照片
左侧（下一张方向）存在照片
```

操作步骤：

```text
1. 向左滑过阈值，切换到下一张
2. 观察目标照片渲染
```

预期结果：

```text
目标照片稳定显示，不出现先显示再退回灰底的现象
切换完成后图片持续可见
```

***

### 用例：横滑轮转动画 - 拖动中双页同时可见（P0 回归）

优先级：

```text
P0
```

前置条件：

```text
清理页显示中间位置的照片
存在横图、竖图、方图、null 尺寸四类测试照片
```

操作步骤：

```text
1. 向左拖动（不松手），观察当前页和下一页
2. 向右拖动（不松手），观察当前页和上一页
3. 对横图、竖图、方图、null 尺寸分别重复上述操作
```

预期结果：

```text
拖动过程中当前页和进入页同时可见
进入页 zIndex 高于当前页，不被遮挡
任意图片比例下动画参数（pageScale、pageAlpha）一致
图片比例不影响轮转动画的正确性
PhotoPagerLayoutTest 单元测试已覆盖此逻辑
```

***

### 用例：横滑轮转动画 - 过渡参数 pageScale 和 pageAlpha（P0 回归）

> 暗房重构：本用例按第 0 节改写（边界只在会话首尾 / 不缩放不淡出 / 操作入队不忽略 / 会话结束见小结插页），P4 落地时更新。

优先级：

```text
P0
```

前置条件：

```text
清理页显示中间位置的照片
PhotoPagerLayoutTest 已验证过渡参数精确值
```

操作步骤：

```text
1. 左滑到中段（约半屏位置），观察过渡参数
2. 右滑到中段，观察过渡参数
3. 静止态确认无过渡变换
```

预期结果：

```text
中段过渡时当前页和进入页的 pageScale 均为 0.98
中段过渡时当前页和进入页的 pageAlpha 均为 0.96
非进入页（反方向页）不参与过渡，pageScale=1、pageAlpha=1
静止态所有页 pageScale=1、pageAlpha=1、pageParallaxOffsetPx=0
PhotoPagerLayoutTest 已精确验证上述数值
```

***

### 用例：横滑轮转动画 - 切页边界位置连续性（P0 回归）

优先级：

```text
P0
```

前置条件：

```text
清理页显示中间位置的照片
PhotoPagerLayoutTest 已验证位置连续性
```

操作步骤：

```text
1. 左滑到切页边界（offsetX = -screenWidth），观察目标照片位置
2. 切页完成后确认目标照片位置无缝衔接
3. 右滑重复上述步骤
```

预期结果：

```text
切页边界瞬间目标照片水平偏移无缝衔接
切页前后目标照片保持同一屏幕位置
连续帧位置差异不超过 1 像素
PhotoPagerLayoutTest 已验证左滑和右滑的切页连续性
```

***

### 用例：TrashPreviewOverlay 本地缓冲与 ReviewScreen 一致（P0 回归）

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Preview 模式显示多张照片
TrashPreviewOverlay 和 ReviewScreen 共享横向预览组件
```

操作步骤：

```text
1. 在清理页横滑切换照片，观察图片加载行为
2. 在垃圾桶预览页横滑切换照片，观察图片加载行为
3. 对比两处行为是否一致
```

预期结果：

```text
TrashPreviewOverlay 的本地缓冲策略与 ReviewScreen 完全一致
两处横滑动画、缩放反馈、切页行为无差异
横滑拖动中当前页和进入页同时可见
```

***

### 用例：右滑切换不灰屏（P0 回归）

优先级：

```text
P0
```

前置条件：

```text
清理页显示非第一张照片
右侧（上一张方向）存在照片
```

操作步骤：

```text
1. 向右滑过阈值，切换到上一张
2. 观察目标照片渲染
```

预期结果：

```text
目标照片稳定显示，不出现先显示再退回灰底的现象
切换完成后图片持续可见
```

***

### 用例：第一张和最后一张反向滑动仅回弹（P0 回归）

> 暗房重构：本用例按第 0 节改写（边界只在会话首尾 / 不缩放不淡出 / 操作入队不忽略 / 会话结束见小结插页），P4 落地时更新。

优先级：

```text
P0
```

前置条件：

```text
清理页显示第一张或最后一张照片
```

操作步骤：

```text
1. 第一张时向右滑过阈值
2. 最后一张时向左滑过阈值
```

预期结果：

```text
照片弹回原位，不越界
不切换到不存在的照片
不崩溃
当前显示的照片不变
```

***

## 6 撤回测试用例

### 用例：收藏后撤回

优先级：

```text
P0
```

前置条件：

```text
photo_1 已通过向上滑动收藏
lastAction.actionType = Favorite
previousIndex = 0
```

操作步骤：

```text
1. 点击 ActionUndoBar 的撤回
2. 调用 onUndo
3. 等待撤回完成
```

预期结果：

```text
RemoveFavoritePhotoUseCase 被调用
App 内收藏状态被取消
photo_1 插回 previousIndex
currentPhoto 切回 photo_1
lastAction 被清空
ActionUndoBar 隐藏
```

***

### 用例：移动到 App 内垃圾桶后撤回

优先级：

```text
P0
```

前置条件：

```text
photo_1 已通过向下滑动移动到 App 内垃圾桶
lastAction.actionType = MoveToTrash
previousIndex = 0
```

操作步骤：

```text
1. 点击 ActionUndoBar 的撤回
2. 调用 onUndo
3. 等待撤回完成
```

预期结果：

```text
RestorePhotoFromTrashUseCase 被调用
FakeAppTrashStateDataSource 中 photo_1 条目被移除
photo_1 插回 previousIndex
currentPhoto 切回 photo_1
lastAction 被清空
ActionUndoBar 隐藏
撤回直接返回 Success，不需要系统授权
```

***

### ~~用例：撤回收藏需要系统授权后成功~~（已移除）

> **移除原因**：撤回收藏现在只需删除 App 内收藏登记，`RemoveFavoritePhotoUseCase` 直接返回 `Success`，不存在系统授权分支。

***

### ~~用例：撤回移动到垃圾桶需要系统授权后成功~~（已移除）

> **移除原因**：App 内垃圾桶的撤回（restoreFromTrash）只需从 DataStore 删除条目，不需要系统授权，直接返回 Success。

***

### ~~用例：用户取消撤回时的系统授权保留队列~~（已移除）

> **移除原因**：撤回收藏和撤回垃圾桶恢复都已改为纯 App 内状态写入，不存在撤回阶段的系统授权取消场景。

***

### 用例：撤回失败不破坏当前队列

优先级：

```text
P0
```

前置条件：

```text
lastAction 存在
撤回用例返回失败
```

操作步骤：

```text
1. 调用 onUndo
2. 等待操作完成
```

预期结果：

```text
当前 photos 不被错误修改
currentPhoto 不被错误切换
显示撤回失败提示
isProcessingAction 最终为 false
```

***

### 用例：previousIndex 超出范围时安全插入

优先级：

```text
P1
```

前置条件：

```text
lastAction.previousIndex 大于当前 photos.size
```

操作步骤：

```text
1. 调用 onUndo
```

预期结果：

```text
照片插入到队列末尾
不发生数组越界
页面不崩溃
```

***

## 7 手势与 UI 测试用例

手势阈值：

```text
垂直阈值：屏幕高度 12%，最小 72dp，最大 120dp
水平阈值：屏幕宽度 12%，最小 48dp，最大 96dp
```

### 用例：未达到阈值时照片回弹

优先级：

```text
P1
```

前置条件：

```text
清理页显示 photo_1
```

操作步骤：

```text
1. 模拟向上拖动，但距离小于垂直阈值
2. 松手
```

预期结果：

```text
不触发收藏
照片回到中心
不显示 ActionUndoBar
```

***

### 用例：达到向上阈值时触发收藏

优先级：

```text
P0
```

前置条件：

```text
清理页显示 photo_1
```

操作步骤：

```text
1. 模拟向上拖动超过垂直阈值
2. 松手
```

预期结果：

```text
触发 onSwipeUp
照片向上飞出
显示下一张照片
```

***

### 用例：达到向下阈值时触发移动到垃圾桶

优先级：

```text
P0
```

前置条件：

```text
清理页显示 photo_1
```

操作步骤：

```text
1. 模拟向下拖动超过垂直阈值
2. 松手
```

预期结果：

```text
触发 onSwipeDown
照片向下飞出
显示下一张照片
```

***

### 用例：达到水平阈值时只切换照片

优先级：

```text
P0
```

前置条件：

```text
清理页显示 photo_1
photo_2 存在
```

操作步骤：

```text
1. 模拟左滑超过水平阈值
2. 松手
```

预期结果：

```text
触发 onSwipeLeft
显示 photo_2
不触发 onSwipeUp
不触发 onSwipeDown
不显示 ActionUndoBar
```

***

### 用例：斜向滑动按主方向锁定

优先级：

```text
P1
```

前置条件：

```text
清理页显示 photo_1
```

操作步骤：

```text
1. 模拟水平位移明显大于垂直位移的斜向滑动
2. 模拟垂直位移明显大于水平位移的斜向滑动
```

预期结果：

```text
水平主导时只触发照片切换
垂直主导时只触发清理动作
不会一次手势同时触发切换和清理
```

***

### 用例：照片保持原始宽高比

优先级：

```text
P1
```

前置条件：

```text
存在横图、竖图、方图三类测试照片
```

检查点：

```text
照片不被拉伸变形
横图、竖图、方图均按原始比例显示
图片使用完整显示策略
空余区域由背景填充
```

***

### 用例：Favorite 指示器无背景

优先级：

```text
P1
```

前置条件：

```text
清理页已渲染
```

检查点：

```text
Favorite 指示器位于顶部居中
只显示箭头和文字
没有按钮背景
没有卡片背景
没有胶囊背景
```

***

### 用例：Trash 指示器无背景

优先级：

```text
P1
```

前置条件：

```text
清理页已渲染
```

检查点：

```text
Trash 指示器位于底部居中
只显示垃圾桶图标和文字
没有按钮背景
没有卡片背景
没有胶囊背景
```

***

### 用例：ActionUndoBar 样式正确

优先级：

```text
P1
```

前置条件：

```text
完成一次有效操作
```

检查点：

```text
显示黑色半透明胶囊
位于 Trash 指示器下方
显示操作文案
显示撤回按钮
约 3 秒后自动消失
```

***

## 8 垃圾桶页测试用例

### 用例：垃圾桶页加载 App 内垃圾桶照片成功

优先级：

```text
P1
```

前置条件：

```text
FakeTrashRepository 返回 trashedPhotos（来自 App 内垃圾桶）
```

操作步骤：

```text
1. 创建 TrashViewModel
2. 加载垃圾桶页
```

预期结果：

```text
trashedPhotos 不为空
isLoading 最终为 false
errorMessage 为空
```

***

### 用例：垃圾桶为空

优先级：

```text
P1
```

前置条件：

```text
FakeTrashRepository 返回空列表
```

操作步骤：

```text
1. 加载垃圾桶页
```

预期结果：

```text
显示空状态
页面不崩溃
```

***

### 用例：从 App 内垃圾桶恢复照片成功

优先级：

```text
P0
```

前置条件：

```text
App 内垃圾桶中存在 photo_1
RestorePhotoFromTrashUseCase 成功（从 DataStore 删除条目，直接返回 Success）
```

操作步骤：

```text
1. 点击恢复 photo_1
2. 等待操作完成
```

预期结果：

```text
RestorePhotoFromTrashUseCase 被调用
FakeAppTrashStateDataSource 中 photo_1 条目被移除
photo_1 从垃圾桶列表移除
显示恢复成功提示
无需系统授权
```

***

### 用例：从 App 内垃圾桶恢复照片失败

优先级：

```text
P0
```

前置条件：

```text
RestorePhotoFromTrashUseCase 返回失败（例如 DataStore 写入失败）
```

操作步骤：

```text
1. 点击恢复 photo_1
```

预期结果：

```text
photo_1 仍保留在垃圾桶列表
FakeAppTrashStateDataSource 中 photo_1 条目未被移除
显示恢复失败提示
页面不崩溃
```

***

### 用例：垃圾桶预览模式 - 点击进入预览

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Grid 模式显示 photo_1、photo_2、photo_3
```

操作步骤：

```text
1. 点击 photo_1 缩略图
2. 收集 TrashUiState
```

预期结果：

```text
screenMode 切换为 Preview
previewPhotoId 等于 photo_1.id
显示 photo_1 大图预览
```

***

### 用例：垃圾桶预览模式 - 左右切换照片

优先级：

```text
P1
```

前置条件：

```text
垃圾桶页 Preview 模式显示 photo_1
照片列表为 [photo_1, photo_2, photo_3]
使用共享横向预览组件（PhotoPager + HorizontalSwipeState）
TrashPreviewOverlay 与 ReviewScreen 使用相同的本地缓冲策略
```

操作步骤：

```text
1. 左滑切换到下一张
2. 右滑切换到上一张
```

预期结果：

```text
左滑后 previewPhotoId 切换到 photo_2.id
右滑后 previewPhotoId 切换回 photo_1.id
首尾照片滑动到边界时有橡皮筋回弹效果
跟手滑动、边界回弹、滑入/滑出动画与清理页一致
横滑拖动中当前页和进入页同时可见，进入页 zIndex 高于当前页
TrashPreviewOverlay 的本地缓冲行为与 ReviewScreen 一致
```

***

### 用例：垃圾桶预览模式 - 恢复后自动切换

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Preview 模式显示 photo_1
照片列表为 [photo_1, photo_2, photo_3]
RestorePhotosFromTrashUseCase 成功
```

操作步骤：

```text
1. 在预览模式点击恢复 photo_1
2. 等待操作完成
```

预期结果：

```text
photo_1 从垃圾桶列表移除
恢复前快照当前列表和索引，基于快照计算目标照片
previewPhotoId 自动切换到 photo_2.id（原下一张）
仍保持 Preview 模式
```

***

### 用例：垃圾桶预览模式 - 最后一张恢复后退出预览

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Preview 模式显示 photo_1
照片列表仅剩 [photo_1]
RestorePhotosFromTrashUseCase 成功
```

操作步骤：

```text
1. 在预览模式点击恢复 photo_1
2. 等待操作完成
```

预期结果：

```text
photo_1 从垃圾桶列表移除
photos 为空
screenMode 切换回 Grid
显示垃圾桶空状态
```

***

### 用例：垃圾桶预览模式 - 恢复中间张后切到原下一张（回归）

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Preview 模式显示 photo_2（中间张）
照片列表为 [photo_1, photo_2, photo_3]
RestorePhotoFromTrashUseCase 成功
```

操作步骤：

```text
1. 点击恢复 photo_2
2. 等待操作完成
```

预期结果：

```text
photo_2 从垃圾桶列表移除
基于恢复前快照：索引 1 恢复后，原下一张是 photo_3
previewPhotoId 切换到 photo_3.id
不闪退
```

***

### 用例：垃圾桶预览模式 - 恢复最后张后切到新最后张（回归）

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Preview 模式显示 photo_3（最后一张）
照片列表为 [photo_1, photo_2, photo_3]
RestorePhotoFromTrashUseCase 成功
```

操作步骤：

```text
1. 点击恢复 photo_3
2. 等待操作完成
```

预期结果：

```text
photo_3 从垃圾桶列表移除
基于恢复前快照：索引 2 已是最后一张，显示新的最后一张 photo_2
previewPhotoId 切换到 photo_2.id
不闪退
```

***

### 用例：垃圾桶预览模式 - 恢复成功时 Flow 先更新也不闪退（回归）

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Preview 模式显示 photo_2（中间张）
照片列表为 [photo_1, photo_2, photo_3]
RestorePhotoFromTrashUseCase 成功
observeTrashedPhotos Flow 可能在 refreshPhotoLists 前后发出更新
```

操作步骤：

```text
1. 点击恢复 photo_2
2. 模拟 Flow 在恢复成功后、目标计算前发出更新
3. 等待操作完成
```

预期结果：

```text
恢复后 previewPhotoId 基于恢复前快照计算，不受 Flow 更新影响
不出现 indexOfFirst == -1 导致闪退
previewPhotoId 切换到正确目标照片
```

***

### 用例：垃圾桶多选模式 - 选中与取消

优先级：

```text
P1
```

前置条件：

```text
垃圾桶页 Grid 模式显示 photo_1、photo_2、photo_3
```

操作步骤：

```text
1. 进入多选模式
2. 点击 photo_1 选中
3. 点击 photo_2 选中
4. 再次点击 photo_1 取消选中
```

预期结果：

```text
进入多选模式后 screenMode 为 Selection
选中 photo_1 后 selectedPhotoIds 包含 photo_1.id
选中 photo_2 后 selectedPhotoIds 包含 photo_1.id 和 photo_2.id
取消 photo_1 后 selectedPhotoIds 仅包含 photo_2.id
```

***

### 用例：垃圾桶多选模式 - 批量恢复

优先级：

```text
P0
```

前置条件：

```text
垃圾桶页 Selection 模式
selectedPhotoIds 包含 photo_1.id 和 photo_2.id
RestorePhotosFromTrashUseCase 成功
```

操作步骤：

```text
1. 点击批量恢复按钮
2. 等待操作完成
```

预期结果：

```text
RestorePhotosFromTrashUseCase 被调用，参数包含 photo_1 和 photo_2
photo_1 和 photo_2 从垃圾桶列表移除
selectedPhotoIds 清空
screenMode 切换回 Grid
显示恢复成功提示
```

***

### 用例：垃圾桶多选模式 - count 为 0 时禁用操作

优先级：

```text
P1
```

前置条件：

```text
垃圾桶页 Selection 模式
selectedPhotoIds 为空
```

操作步骤：

```text
1. 检查批量恢复按钮状态
```

预期结果：

```text
批量恢复按钮不可点击（disabled）
不触发任何恢复操作
```

***

### 用例：垃圾桶清空 - 打开确认框

优先级：

```text
P0
```

前置条件：

```text
垃圾桶中存在 photo_1、photo_2
```

操作步骤：

```text
1. 点击清空垃圾桶按钮
2. 收集 TrashUiState
```

预期结果：

```text
showEmptyTrashConfirmDialog 为 true
显示确认弹窗
弹窗提示操作不可逆
```

***

### 用例：垃圾桶清空 - 取消确认

优先级：

```text
P0
```

前置条件：

```text
showEmptyTrashConfirmDialog 为 true
```

操作步骤：

```text
1. 点击确认弹窗的取消按钮
```

预期结果：

```text
showEmptyTrashConfirmDialog 为 false
确认弹窗关闭
照片列表不变
不触发删除操作
```

***

### 用例：垃圾桶清空 - 确认清空

优先级：

```text
P0
```

前置条件：

```text
垃圾桶中存在 photo_1、photo_2
showEmptyTrashConfirmDialog 为 true
DeletePhotosPermanentlyUseCase 成功
```

操作步骤：

```text
1. 点击确认弹窗的确认按钮
2. 等待操作完成
```

预期结果：

```text
DeletePhotosPermanentlyUseCase 被调用，参数包含全部垃圾桶照片
photos 为空
showEmptyTrashConfirmDialog 为 false
显示清空成功提示
显示垃圾桶空状态
```

***

### 用例：用户取消系统授权静默处理（仅永久删除）

优先级：

```text
P0
```

前置条件：

```text
垃圾桶中存在 photo_1
操作为永久删除，返回 NeedSystemApproval
用户在系统授权页取消
注意：App 内垃圾桶的恢复操作不再触发系统授权，此场景仅适用于永久删除
```

操作步骤：

```text
1. 触发需要系统授权的永久删除操作
2. 回传授权取消结果
```

预期结果：

```text
不显示错误提示弹窗
不显示 Toast
照片列表不变
isProcessingAction 最终为 false
页面保持当前状态，不崩溃
```

***

## 9 AppTrashStateDataSource 测试用例

### 用例：编码条目格式正确

优先级：

```text
P1
```

操作步骤：

```text
1. 调用 encodeEntry(photoId=123, movedToTrashAt=1700000000000)
2. 检查返回字符串
```

预期结果：

```text
返回 "123,1700000000000"
photoId 和 movedToTrashAt 之间用逗号分隔
```

***

### 用例：解析合法条目

优先级：

```text
P1
```

操作步骤：

```text
1. 调用 parseEntry("123,1700000000000")
```

预期结果：

```text
返回 AppTrashEntry(photoId=123, movedToTrashAt=1700000000000)
```

***

### 用例：解析非法条目直接抛错

优先级：

```text
P1
```

操作步骤：

```text
1. 调用 parseEntry("abc,1700000000000")
2. 调用 parseEntry("123")
3. 调用 parseEntry("123,abc")
4. 调用 parseEntry("")
```

预期结果：

```text
每个调用都抛出 IllegalArgumentException
不静默容错
错误信息包含原始值以便排查
```

***

### 用例：upsert 新增条目

优先级：

```text
P0
```

前置条件：

```text
DataStore 中无任何条目
```

操作步骤：

```text
1. 调用 upsertEntry(photoId=123, movedToTrashAt=1700000000000)
2. 收集 observeEntries()
```

预期结果：

```text
返回列表包含 AppTrashEntry(photoId=123, movedToTrashAt=1700000000000)
```

***

### 用例：upsert 同一 photoId 覆盖旧值

优先级：

```text
P0
```

前置条件：

```text
DataStore 中已存在 photoId=123 的条目，movedToTrashAt=1700000000000
```

操作步骤：

```text
1. 调用 upsertEntry(photoId=123, movedToTrashAt=1700000009999)
2. 收集 observeEntries()
```

预期结果：

```text
返回列表仅包含一条 photoId=123 的条目
movedToTrashAt 更新为 1700000009999
不出现重复条目
```

***

### 用例：upsert 不同 photoId 各自独立

优先级：

```text
P1
```

前置条件：

```text
DataStore 中已存在 photoId=123 的条目
```

操作步骤：

```text
1. 调用 upsertEntry(photoId=456, movedToTrashAt=1700000010000)
2. 收集 observeEntries()
```

预期结果：

```text
返回列表包含两条条目
photoId=123 条目不受影响
按 movedToTrashAt 倒序排列
```

***

### 用例：removeEntry 删除单条

优先级：

```text
P0
```

前置条件：

```text
DataStore 中存在 photoId=123 和 photoId=456 的条目
```

操作步骤：

```text
1. 调用 removeEntry(photoId=123)
2. 收集 observeEntries()
```

预期结果：

```text
photoId=123 的条目被移除
photoId=456 的条目保留
```

***

### 用例：removeEntry 删除不存在的 photoId 无副作用

优先级：

```text
P1
```

前置条件：

```text
DataStore 中存在 photoId=123 的条目
```

操作步骤：

```text
1. 调用 removeEntry(photoId=999)
2. 收集 observeEntries()
```

预期结果：

```text
photoId=123 的条目不受影响
不抛错
```

***

### 用例：removeEntries 批量删除

优先级：

```text
P0
```

前置条件：

```text
DataStore 中存在 photoId=123、456、789 的条目
```

操作步骤：

```text
1. 调用 removeEntries(photoIds=setOf(123, 456))
2. 收集 observeEntries()
```

预期结果：

```text
photoId=123 和 456 的条目被移除
photoId=789 的条目保留
```

***

### 用例：removeEntries 空集合不操作

优先级：

```text
P1
```

前置条件：

```text
DataStore 中存在 photoId=123 的条目
```

操作步骤：

```text
1. 调用 removeEntries(photoIds=emptySet())
2. 收集 observeEntries()
```

预期结果：

```text
photoId=123 的条目不受影响
不触发 DataStore 写入
```

***

### 用例：removeMissingEntries 清理失效条目

优先级：

```text
P0
```

前置条件：

```text
DataStore 中存在 photoId=123、456、789 的条目
```

操作步骤：

```text
1. 调用 removeMissingEntries(existingPhotoIds=setOf(123, 789))
2. 收集 observeEntries()
```

预期结果：

```text
photoId=456 的条目被移除（不在 existingPhotoIds 中）
photoId=123 和 789 的条目保留
```

***

### 用例：removeMissingEntries 全部有效时不变

优先级：

```text
P1
```

前置条件：

```text
DataStore 中存在 photoId=123、456 的条目
```

操作步骤：

```text
1. 调用 removeMissingEntries(existingPhotoIds=setOf(123, 456))
2. 收集 observeEntries()
```

预期结果：

```text
所有条目保留
列表不变
```

***

## 10 StartReviewSessionUseCase 测试用例（sortOrder / filterMode）

### 用例：使用 NewestFirst 排序启动会话

优先级：

```text
P0
```

前置条件：

```text
照片列表包含 photo_1（2024-05-20）、photo_2（2024-05-19）、photo_3（2024-05-18）
startPhotoId = photo_1.id
sortOrder = NewestFirst
filterMode = AllPhotos
```

操作步骤：

```text
1. 调用 StartReviewSessionUseCase(startPhotoId, NewestFirst, AllPhotos)
```

预期结果：

```text
返回 ReviewSession
photos 按 NewestFirst 排序（photo_1 → photo_2 → photo_3）
currentIndex 指向 photo_1
totalCount = 3
```

***

### 用例：使用 OldestFirst 排序启动会话

优先级：

```text
P1
```

前置条件：

```text
照片列表包含 photo_1（2024-05-20）、photo_2（2024-05-19）、photo_3（2024-05-18）
startPhotoId = photo_3.id
sortOrder = OldestFirst
filterMode = AllPhotos
```

操作步骤：

```text
1. 调用 StartReviewSessionUseCase(startPhotoId, OldestFirst, AllPhotos)
```

预期结果：

```text
返回 ReviewSession
photos 按 OldestFirst 排序（photo_3 → photo_2 → photo_1）
currentIndex = 0
```

***

### 用例：使用 UnseenOnly 过滤启动会话

优先级：

```text
P1
```

前置条件：

```text
照片列表包含 photo_1（未看过）、photo_2（已看过）、photo_3（未看过）
startPhotoId = photo_1.id
sortOrder = NewestFirst
filterMode = UnseenOnly
```

操作步骤：

```text
1. 调用 StartReviewSessionUseCase(startPhotoId, NewestFirst, UnseenOnly)
```

预期结果：

```text
返回 ReviewSession
photos 仅包含 photo_1 和 photo_3
photo_2 被过滤掉
currentIndex = 0
totalCount = 2
```

***

### 用例：智能筛选下会话只含筛选结果

优先级：

```text
P1
```

前置条件：

```text
AllPhotos 列表含一张截图、一张普通照片、一张达到 10 MB 的视频
Favorites 列表含这三张
```

操作步骤：

```text
1. scope = AllPhotos，filterMode = Screenshots，从截图启动
2. scope = AllPhotos，filterMode = LargeFiles，从该视频启动
3. scope = Favorites，filterMode = Screenshots，从普通照片启动
```

预期结果：

```text
AllPhotos 会话的 photos 只有该筛选的命中项，currentIndex 指向起点
Favorites 会话跳过过滤，三张都在
```

***

### 用例：startPhotoId 不在过滤后列表中时抛错

优先级：

```text
P0
```

前置条件：

```text
照片列表包含 photo_1（未看过）、photo_2（已看过）
startPhotoId = photo_2.id
sortOrder = NewestFirst
filterMode = UnseenOnly
```

操作步骤：

```text
1. 调用 StartReviewSessionUseCase(startPhotoId, NewestFirst, UnseenOnly)
```

预期结果：

```text
抛出 IllegalStateException
错误信息包含 photoId、sortOrder、filterMode、listSize
不回退到 index 0
```

***

### 用例：startPhotoId 在 App 内垃圾桶中时不在列表中

优先级：

```text
P0
```

前置条件：

```text
照片列表包含 photo_1、photo_2、photo_3
photo_1 已在 App 内垃圾桶中
observeAllPhotos 返回的列表不包含 photo_1
startPhotoId = photo_1.id
```

操作步骤：

```text
1. 调用 StartReviewSessionUseCase(startPhotoId, NewestFirst, AllPhotos)
```

预期结果：

```text
抛出 IllegalArgumentException
因为 photo_1 已被 PhotoRepository 过滤掉，不在最终列表中
```

***

## 11 PhotoRepository App 内垃圾桶过滤测试用例

### 用例：observeAllPhotos 排除 App 内垃圾桶条目

优先级：

```text
P0
```

前置条件：

```text
MediaStore 照片包含 photo_1、photo_2、photo_3
AppTrashStateDataSource 中存在 photo_2 的条目
```

操作步骤：

```text
1. 收集 observeAllPhotos()
```

预期结果：

```text
返回列表包含 photo_1 和 photo_3
photo_2 被过滤掉
```

***

### 用例：observeAllPhotos 垃圾桶为空时返回全部照片

优先级：

```text
P1
```

前置条件：

```text
MediaStore 照片包含 photo_1、photo_2、photo_3
AppTrashStateDataSource 无任何条目
```

操作步骤：

```text
1. 收集 observeAllPhotos()
```

预期结果：

```text
返回列表包含全部 3 张照片
不过滤任何照片
```

***

### 用例：observeTrashedPhotos 返回 App 内垃圾桶中的照片

优先级：

```text
P0
```

前置条件：

```text
MediaStore 照片包含 photo_1、photo_2、photo_3
AppTrashStateDataSource 中存在 photo_2 和 photo_3 的条目
photo_2.movedToTrashAt > photo_3.movedToTrashAt
```

操作步骤：

```text
1. 收集 observeTrashedPhotos()
```

预期结果：

```text
返回列表包含 photo_2 和 photo_3
按 movedToTrashAt 倒序排列（photo_2 在前）
```

***

### 用例：observeTrashedPhotos 条目对应的 MediaStore 照片不存在时跳过

优先级：

```text
P1
```

前置条件：

```text
MediaStore 照片包含 photo_1
AppTrashStateDataSource 中存在 photo_1 和 photo_999（不存在）的条目
```

操作步骤：

```text
1. 收集 observeTrashedPhotos()
```

预期结果：

```text
返回列表仅包含 photo_1
photo_999 被跳过（MediaStore 中不存在）
```

***

### 用例：refreshPhotos 后自动清理失效条目

优先级：

```text
P0
```

前置条件：

```text
MediaStore 当前照片包含 photo_1、photo_2
AppTrashStateDataSource 中存在 photo_1、photo_3（已被外部删除）的条目
```

操作步骤：

```text
1. 调用 refreshPhotos()
2. 收集 AppTrashStateDataSource.observeEntries()
```

预期结果：

```text
photo_3 的条目被移除（不在 MediaStore 中）
photo_1 的条目保留
```

***

## 12 权限测试用例

### 用例：Android 13 及以上请求图片权限

优先级：

```text
P0
```

前置条件：

```text
设备版本 Android 13 或以上
```

预期结果：

```text
请求 READ_MEDIA_IMAGES
不请求 READ_EXTERNAL_STORAGE
```

***

### 用例：Android 12 及以下请求读取权限

优先级：

```text
P0
```

前置条件：

```text
设备版本 Android 12 或以下
```

预期结果：

```text
请求 READ_EXTERNAL_STORAGE
```

***

### 用例：用户拒绝权限

优先级：

```text
P0
```

前置条件：

```text
用户拒绝相册权限
```

预期结果：

```text
不查询照片
显示权限引导
页面不崩溃
```

***

### 用例：用户授权后刷新照片

优先级：

```text
P0
```

前置条件：

```text
用户从未授权变为已授权
```

预期结果：

```text
自动或手动刷新照片列表
首页显示照片内容
```

***

## 13 DataStore 测试用例

### 用例：默认未看过教学

优先级：

```text
P0
```

前置条件：

```text
DataStore 中不存在 has_seen_review_tutorial
```

预期结果：

```text
observeHasSeenReviewTutorial 返回 false
```

***

### 用例：保存已看过教学

优先级：

```text
P0
```

操作步骤：

```text
1. 调用 setHasSeenReviewTutorial(true)
2. 再次读取状态
```

预期结果：

```text
observeHasSeenReviewTutorial 返回 true
```

***

### 用例：教学完成后不再显示

优先级：

```text
P0
```

操作步骤：

```text
1. 首次进入清理页
2. 完成一次有效滑动
3. 退出清理页
4. 再次进入清理页
```

预期结果：

```text
第一次进入 showTutorial 为 true
完成有效滑动后写入 true
再次进入 showTutorial 为 false
```

***

## 14 MediaStore 相关测试说明

MediaStore 行为受 Android 版本、设备厂商、权限状态影响较大，不能完全依赖单元测试。

### 单元测试策略

通过 Fake DataSource 模拟：

```text
查询成功
查询失败
首次迁移读取系统收藏状态成功
App 内收藏状态写入成功
App 内收藏状态写入失败
移动到 App 内垃圾桶成功（DataStore 写入）
移动到 App 内垃圾桶失败（DataStore 写入失败）
从 App 内垃圾桶恢复成功（DataStore 删除）
从 App 内垃圾桶恢复失败（DataStore 删除失败）
权限不足
永久删除需要系统授权
```

### 手动测试设备建议

至少覆盖：

```text
Android 13 及以上设备
Android 12 或以下设备
有大量照片的设备
照片数量为空的设备或模拟环境
存在云端照片占位的设备
存在截图、相机照片、下载图片的设备
```

### 必测场景

```text
读取系统照片
收藏照片后在 Favorites 页可见
撤回收藏后 Favorites 页移除
移动照片到 App 内垃圾桶（DataStore 写入，无需系统授权）
从 App 内垃圾桶恢复照片（DataStore 删除，无需系统授权）
权限被拒绝
权限重新授权
App 被切到后台后恢复
清空垃圾桶时触发系统永久删除（需要系统授权）
```

***

## 15 回归测试清单

每次修改以下模块后，必须执行回归测试：

```text
权限模块
MediaStore 查询模块
清理页手势模块
横滑轮转动画（PhotoPagerLayoutTest 单元测试覆盖）
ReviewViewModel
撤回逻辑
DataStore 教学状态
App 内垃圾桶数据源（AppTrashStateDataSource）
垃圾桶恢复逻辑
导航参数
```

### 核心回归路径

```text
1. 打开 App
2. 授权相册权限
3. 首页显示照片
4. 点击照片进入清理页
5. 左右滑动切换照片
6. 向上滑动收藏
7. 到 Favorites 页确认照片可见
8. 点击撤回
9. 向下滑动移动到 App 内垃圾桶（DataStore 写入）
10. 点击撤回（DataStore 删除条目）
11. 再次向下滑动移动到 App 内垃圾桶
12. 进入垃圾桶页
13. 恢复照片（DataStore 删除条目，无需系统授权）
14. 返回首页
```

预期结果：

```text
全流程无崩溃
照片没有被永久删除（App 内垃圾桶为软删除，仅 DataStore 记录）
撤回逻辑正确
App 内收藏状态正确
首页、清理页、垃圾桶页状态一致
首页照片列表中不包含 App 内垃圾桶中的照片
```

***

## 16 测试完成标准

测试完成需要满足：

```text
P0 测试用例全部通过
P1 测试用例主要路径通过
核心 ViewModel 测试通过
核心 UseCase 测试通过
权限相关测试通过
撤回相关测试通过
快速连续滑动测试通过
操作失败路径测试通过
AppTrashStateDataSource 编码/解析/CRUD 测试通过
PhotoRepository App 内垃圾桶过滤测试通过
PhotoPagerLayoutTest 横滑轮转动画测试通过（边界页、过渡参数、位置连续性、任意图片比例）
无严重崩溃
无照片误删风险
```

如果以下任一问题存在，不能发布：

```text
照片被意外永久删除（App 内垃圾桶应为软删除，仅清空垃圾桶时才触发永久删除）
移动失败后仍切换到下一张
撤回失败后队列错乱
快速连续滑动导致重复处理
首次教学每次都出现
无权限状态下仍查询照片
App 在核心流程中崩溃
首页显示了已在 App 内垃圾桶中的照片
横滑轮转动画中当前页或进入页不可见
切页边界位置不连续导致闪烁
TrashPreviewOverlay 与 ReviewScreen 横滑行为不一致
```
