# Changelog

本文件记录项目所有值得注意的变更。All notable changes to this project are documented in this file.

格式参照 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/spec/v2.0.0.html)。每个版本先写中文，英文在下。
The format is based on Keep a Changelog, and this project adheres to Semantic Versioning. Chinese first, English below.

## [Unreleased]

## [1.6.0] - 2026-10-27

### Added

- 安卓：收藏页可以按天「整理」。这一天的收藏摊成一张印样表：点照片或手指划过把要放到一起的圈出来，点底片袋，或拿起一叠拖到袋子上（停一下袋子自己打开），照片就移进那个相册文件夹，并移出收藏。可以新建袋子，名字和已有相册相同（不分大小写）时直接放进那个相册，不另建；可以顺带加系统收藏（Android 11+）。长按袋子能偷看里面最近几张。
- 安卓：放进相册可以撤回，照片回到原来的文件夹和收藏里。Android 11+ 整批只确认一次，撤回不再确认；Android 8–10 有存储写权限后直接移动。目标文件夹里有同名文件时另起「名字 (1)」，不覆盖。
- 安卓：一天整理完出小结，写清每个相册放进几张，可以接着整理下一天。收藏页顶部写还有几天待整理，底部「已冲洗」列出放进过相册的天，点开能看是哪些照片。

---

### Added

- Android: Favorites can now be sorted day by day. A day of favorites opens as a contact sheet: circle photos by tapping or swiping across them, then tap an envelope, or pick up the stack and drag it onto one (it opens after a short hover). The photos move into that album folder and leave Favorites. You can make a new envelope; if its name matches an existing album (ignoring case), the photos go into that album instead of a new one, and optionally add the photos to system favorites (Android 11+). Long-press an envelope to peek at its latest photos.
- Android: putting photos in an album can be undone, which moves them back to their old folder and Favorites. Android 11+ asks once per batch and does not ask again to undo. Android 8–10 move directly once storage write access is granted. A file with the same name in the target folder is never overwritten; the moved photo is renamed "name (1)".
- Android: finishing a day shows a summary of how many photos went into each album, with a button to sort the next day. The Favorites header shows how many days are left to sort, and a "Filed" section at the bottom lists the days you have filed and the photos in each.

## [1.5.5] - 2026-10-25

### Fixed

- 安卓：照片页排序还在滑动时，点到的是屏幕上看到的那张，不再打开旁边那张。
- 安卓：从垃圾桶切回收藏时，收藏页保持原来的滚动位置。
- 安卓：清理页放大后快速拖动，松手那一下不再被当成点按。
- 安卓：垃圾桶预览放大后快速拖动，最后一段也会跟着移动，不会被当成单击。
- 安卓：垃圾桶预览里当前这张已经不在列表中时，左右滑不再闪退；单击视频作用在还在的那一张上。外面把这张删掉后，停在原位置的下一张；原来是最后一张就停在新的最后一张；一张不剩就回到网格。
- 安卓：从垃圾桶预览往回滑时，背后能看到刚才的网格。
- 安卓：相似照片里，同一张被裁剪或旋转后会重新计算，不再沿用改之前的结果。
- 安卓：第一次把系统收藏迁入时，当时在系统垃圾桶里的收藏也会一起写入。已经迁过的安装不会再迁一次。
- iOS：照片页拖选只选中屏幕上画出来的照片，不再把每天 8 张以后没显示的也选上。

---

### Fixed

- Android: while photos are still sliding into place after a sort, a tap opens the photo you see, not the one beside it.
- Android: switching from Trash back to Favorites keeps the Favorites scroll position.
- Android: on the review screen, a fast drag while zoomed in is no longer treated as a tap when you lift your finger.
- Android: in the trash preview, the last bit of a fast zoomed pan moves with your finger instead of counting as a tap.
- Android: swiping in the trash preview no longer crashes when the current photo has left the list, and tapping a video acts on the photo still shown. If that photo disappears, the preview stays on the next photo at the same position, or the new last photo, or returns to the grid when none remain.
- Android: swiping back from the trash preview shows the grid behind it.
- Android: after a photo is cropped or rotated, similar-photo comparison uses a new result instead of the one from before the edit.
- Android: the one-time import of system favorites also includes photos that were in the system trash at that moment. Installs that already imported do not import again.
- iOS: drag-select on the photo grid only selects photos drawn on screen, not the ones hidden past the eight-per-day cap.

## [1.5.4] - 2026-10-25

### Changed

- 安卓：相似照片比对期间，顶栏和列表改成骨架，不再只跳一行进度，顶栏也不再写「0 组」。
- 安卓：第一次比对出结果时，首屏缩略图像洗照片一样显出来；这次打开里滚走再回来不再重播。
- 安卓：一组出现或消失时，旁边的卡片让开；新的一组插到最前面，并且列表停在顶部时，跟着回到顶上。

### Fixed

- 安卓：已经出过结果之后，相册有变化而重新比对时，不再闪回「正在比对」，算完直接换上新结果。

---

### Changed

- Android: while similar photos are being compared, the header and the list show skeletons instead of a single ticking progress line, and the header no longer reads "0 groups".
- Android: the first time results appear, the thumbnails on screen develop in like a print. Scrolling away and back during the same visit does not play that again.
- Android: when a group appears or disappears, the cards beside it move aside. A new group inserted at the front brings the list back to the top when it was already there.

### Fixed

- Android: after results have appeared once, a later comparison caused by library changes no longer flashes back to "Comparing". The new results replace the old ones when they are ready.

## [1.5.3] - 2026-10-25

### Changed

- 安卓：相似照片里，留下的缩略图不再打勾。标成移走的改为右上角一枚相纸白圆，里面是垃圾桶图标，没有黑边，照片底部也不再画灰线。

---

### Changed

- Android: in similar photos, kept thumbnails no longer show a check. Photos marked for removal get a paper-white circle in the top-right with a trash icon and no black ring, and the gray line under the photo is gone.

## [1.5.2] - 2026-10-25

### Fixed

- 安卓：垃圾桶预览放大后，拖动和双击定位不再走两倍距离，也不会拖出半屏黑边。
- 安卓：垃圾桶预览里恢复照片后再左右滑，偶尔会闪退；恢复后补上来的那张是视频时，现在能正常播放。
- 安卓：清理页上下滑后立刻左右翻回，偶尔会停在一页空白上、怎么滑都没反应，现在会回到正常的那一页。
- 安卓：清理页翻页途中按下再双指缩放，照片不再停在偏到一边的位置；很短的上下轻拨之后，顶部进度条不会再消失。
- 安卓：点照片页顶栏、导航条两侧的空白处，不会再打开或选中被挡在下面的照片。
- 安卓：照片页和收藏页滚到很深处再移走照片，后面的照片不再要等好几秒才归位。

---

### Fixed

- Android: when zoomed in the trash preview, dragging and double-tap no longer move twice as far, and you can no longer drag the photo half off the screen.
- Android: swiping left or right after restoring a photo in the trash preview could occasionally crash; a video that moves in after a restore now plays.
- Android: flicking back right after a swipe up or down on the review screen could occasionally leave a blank page that ignored every gesture; it now returns to the right photo.
- Android: pinching after interrupting a page turn no longer leaves the photo shifted to one side, and the top progress bar no longer disappears after a very short vertical flick.
- Android: tapping empty space in the Photos header or beside the navigation bar no longer opens or selects a photo hidden underneath.
- Android: after removing photos deep in a long Photos or Favorites list, the remaining photos no longer take several seconds to settle.

## [1.5.1] - 2026-10-25

### Added

- 新增官网 https://cleanphotos.winters.click ，可以直接下载最新的 APK。

### Changed

- 安卓：照片页的大文件、截图、聊天按各自用途排列。大文件不分天，固定从大到小平铺，格子左下角显示文件大小，⋯ 菜单里不显示排序；从大文件进入清理页，翻页顺序和网格一致，也不再插入「这一天整理完了」小结。截图和聊天改为按月分组、不再截断，月份标题右侧显示「已看 / 总数」，点它从这个月第一张开始清理。全部和那年今天仍按天分组。
- 安卓：带大小或时长角标的缩略图底部加了一层淡渐变，亮色照片上也看得清角标。
- 安卓：拖动或点按视频进度后，从该位置直接开始播放，之前暂停或已播完也一样；拖到结尾仍回到开头并暂停。

### Fixed

- 安卓：多选时全选一天原来只选中露出的前 8 张，现在选中这一天的全部照片，批量操作和撤回也作用于全部。

---

### Added

- New website at https://cleanphotos.winters.click, where you can download the latest APK directly.

### Changed

- Android: Large files, Screenshots and Chat on the Photos page are laid out for what they are. Large files form one flat grid from largest to smallest, with no days, and each thumbnail shows its file size in the bottom-left corner; the ⋯ menu hides sorting there. Reviewing from Large files follows the grid order and no longer inserts the end-of-day summary. Screenshots and Chat are grouped by month without truncation; each month title shows "seen / total", and tapping it starts review from that month's first photo. All and On this day still group by day.
- Android: thumbnails with a size or duration badge get a faint gradient along the bottom, so the badge stays readable on bright photos.
- Android: after dragging or tapping the video progress, playback starts right there, even if the video was paused or had finished; dragging to the very end still returns to the start and pauses.

### Fixed

- Android: in multi-select, selecting a whole day picked only the first 8 photos shown; it now selects every photo of that day, and batch actions and undo cover all of them.

## [1.5.0] - 2026-10-24

### Added

- 安卓：清理页和垃圾桶全屏预览可以播放视频。当前页停稳后静音自动开始，只播一次，播完回到开头并暂停。单击切换播放 / 暂停；暂停、未开始和播完时画面中央有一颗玻璃播放键。详情打开时有进度行（两端时间、可拖动轨道、声音开关），声音只在这次停留里记住，默认静音。上下或左右拖动立刻定格。系统关闭动画时不自动播放。相似对比页和网格仍然不播放。
- 安卓：识别动态照片（Google、小米、vivo、OPPO、三星、荣耀、华为）。清理页停稳后静音自动播一次，播完露出静态图；右上角「动态」胶囊从头再播，始终无声。垃圾桶预览同样播放，单击不做额外操作。

---

### Added

- Android: videos play in review and in the trash full-screen preview. After the page settles they start muted and play once, then return to the first frame and pause. Tap to play or pause; a glass play button sits in the center when paused, not started, or finished. With details open, a progress row shows the times, a draggable track and a sound switch. Sound is remembered only for this visit and starts muted. Dragging up, down, left or right freezes the current frame. With system animations off, playback does not start by itself. The similar-photo compare page and the grid still do not play.
- Android: motion photos are recognized (Google, Xiaomi, vivo, OPPO, Samsung, Honor, Huawei). In review they play once, muted, after the page settles, then the still image shows again. The "Motion" capsule replays from the start and stays silent. The trash preview plays the same way; a tap does nothing extra there.

## [1.4.0] - 2026-10-24

### Fixed

- 安卓：清理页照片四周的环境光提前准备好，左右滑入时已经在，不再等照片落定后过一会儿才亮起来。

### Changed

- 安卓：相似照片可以挑着留了。点缩略图进入对比页看大图，放大后切换照片位置不变，方便逐张比较；上滑留下并打勾，下滑移走，一组可以留多张，也可以全部移走。默认推荐最清晰的一张，处理过的组不会再出现。移走的照片保持彩色，缩略图底部一条退影灰。松手后方向提示停住并淡出。
- 安卓：照片页改版，减少界面上的「点」。顶栏下一排常驻的范围按钮（全部 / 相似 / 大文件 / 截图 / 聊天 / 那年今天），取代原来的筛选菜单，一步就能切到；大号数字显示当前范围的张数，大文件、截图、聊天再显示总大小。往下翻时顶栏和按钮收起，往上一拨就回来。
- 安卓：相似照片不再单独开一页，切到「相似」就在照片页里成组对比。
- 安卓：顶栏只留 ⋯（排序、只看没看过、语言）；「只看没看过」改成开关，可以和截图、大文件等范围叠加。
- 安卓：缩略图不再画未看光点，日期行改为「已看 / 总数」，看完显示「已看完」。照片、收藏、垃圾桶三页顶栏去掉小标题。
- 安卓：垃圾桶提醒不再在照片页弹胶囊；最早一张放满 30 天时，垃圾桶「清空后可释放」卡片写明「最早一张已放 N 天」。
- 安卓：清理页默认只显示顶部进度条；单击照片才显示返回、日期和文件名 · 大小，再单击收起，开始滑动时也会自动收起。右上角的菜单去掉了。

---

### Fixed

- Android: the ambient glow around photos on the Review screen is prepared in advance, so it is already there as you swipe left or right instead of fading in a moment after the photo settles.

### Changed

- Android: similar photos are easier to choose from. Tap a thumbnail to compare them full size; the zoom stays put as you switch photos. Swipe up to keep it (a check), down to remove it, and keep as many as you like, or none. The sharpest photo is suggested by default, and groups you have handled will not come back. Photos marked for removal stay in color, with a gray line along the bottom of the thumbnail. The swipe hint freezes and fades out when you let go.
- Android: redesigned Photos page with less visual noise. A permanent row of scope buttons under the header (All / Similar / Large files / Screenshots / Chat / On this day) replaces the filter menu; the big number shows the count for the current scope, plus total size for large files, screenshots and chats. The header and buttons slide away as you scroll down and come back when you scroll up a little.
- Android: similar photos no longer open a separate page; choose "Similar" to compare groups right on the Photos page.
- Android: the header keeps only ⋯ (sort, unseen only, language). "Unseen only" is now a switch that combines with any scope, such as screenshots or large files.
- Android: thumbnails no longer carry unseen dots; each day shows "seen / total", or "All seen" when done. Photos, Favorites and Trash headers drop their small titles.
- Android: the trash reminder no longer pops up on the Photos page; once the oldest item has been in the trash for 30 days, the "Empty to free up" card on the Trash page says so.
- Android: the Review screen shows only the progress bar by default; tap the photo to show back, date and file name · size, tap again to hide them, and they also hide as soon as you start swiping. The top-right menu is gone.

## [1.3.0] - 2026-10-22

### Added

- 安卓：支持视频。视频和照片一起按日期显示，可以收藏、移入垃圾桶、撤回和清空；只显示首帧，网格右下角和清理页底部显示时长。Android 13+ 会多申请视频权限（与照片同一个系统弹窗），已授权照片的用户启动时补请求一次。
- 安卓：首页筛选新增「截图」「大文件」（≥ 10 MB）「聊天软件存图」（微信 / WhatsApp / Telegram / QQ），右侧显示数量，进清理页后只在筛选结果里翻页。
- 安卓：新增「相似照片」页（筛选菜单最后一项）：拍摄时间相差 10 秒以内、画面相近的照片成组，默认保留像素最高的一张，点缩略图可改，「移走其余 N 张」一键移入垃圾桶并可撤回。
- 安卓：首页筛选新增「那年今天」：往年同月同日的照片和视频，右侧显示数量，没有时不显示这一项。
- 安卓：垃圾桶空状态显示「累计已释放 X」，只计清空后确实从图库删掉的照片。
- 安卓：垃圾桶超过 1 GB 或有照片放了 30 天以上时，照片页底部提醒一次（每 7 天最多一次），「去看看」直接切到垃圾桶。只在 App 内提示，不发系统通知。
- iOS：读屏（VoiceOver）可用性：清理页照片整页朗读拍摄时间，提供「收藏 / 取消收藏」「移入垃圾桶」自定义操作，上下轻扫翻页。
- iOS：只授权了部分照片时，照片页 ⋯ 菜单里多一项「选择更多照片」；系统不再在启动时自动弹出选择提示。
- iOS：新增隐私清单（PrivacyInfo.xcprivacy）：不追踪、不收集数据，声明用到的本地设置存储与系统时钟接口。

### Fixed

- 安卓：在清理页切到后台、进程被系统回收后再回来，显示「加载照片失败」并停在一块没有返回按钮的黑屏：现在先读图库再建立会话，并从上次正在看的那张接着看（它已处理过时就近选一张）；会话确实建立不了时提示后自动回到入口页。
- 安卓：在系统相册或其它 App 里增删照片后，回到 App 不刷新、要重开才能看到变化：回到前台时重新读取图库（2 秒内不重复读）；几个页面同时刷新时旧结果不再覆盖新结果。
- 安卓：存储空间满或配置文件损坏时直接闪退：读写失败按普通失败处理（清理页照片插回原位并提示），损坏的配置文件重置后继续运行。
- 安卓：小结页入场动画没播完就翻走，再翻回来时数字和比例条停在半截。
- 安卓：垃圾桶里打开一张预览再返回，网格跳回顶部；清空垃圾桶的溶解也总是从顶部开始。现在都停在原来的位置。
- 安卓：清理页上下拖一下没过阈值、回弹中马上再点或横滑，顶栏、日期和底部信息可能一直透明。
- 安卓：上滑收藏时两层琥珀余影在照片落入后一帧突然消失，现在会走完各自的路径再淡出（外观、透明度和延迟不变）。
- 安卓：撤回条在上下拖动后重新出现时 4 秒倒计时从头开始，反复拖动能让它一直不消失；现在按真实时间计满 4 秒。
- 安卓：系统语言为阿拉伯语等从右到左的语言时，界面整体镜像而文字仍是英文，手势方向和照片飞向的落点错位；App 只有中英文，统一按从左到右显示。
- 安卓：清理页每滑一张要把整份本地状态文件重写三四次；现在操作、下一张记为看过和滑动计数合成一次写入，翻回已看过的照片不再写文件；照片很多时每次滑动也不再在主线程重建整条翻页序列。
- 安卓：清空垃圾桶的几种收尾问题：Android 8–10 有照片删不掉时，已经删掉的仍显示在垃圾桶里；只授权了部分照片时，清空后留下失效的垃圾桶 / 收藏 / 看过记录；在系统删除确认期间 App 被回收，回来后没有任何结果提示。
- 安卓：照片页长按没生效（列表还没停稳、批量处理中）时接着拖动，仍会按上一次的起点批量选中。
- 安卓：清理页左右翻页动画还没落定时按住改成上下拖，照片会一帧跳回中间；现在剩下的横向位移会弹回去。
- 安卓：系统字体调得很大时，底栏标签、页头和日期标题被裁切或挤出：这些固定尺寸位置的文字缩放有上限（默认字号下外观不变）；多选底栏的「移到垃圾桶」不再折行被裁，垃圾桶多选时右侧的已选大小不再被挤成「1.」。
- 安卓：横屏时左右两侧的系统导航栏 / 挖孔会挡住页面内容。
- 安卓：读屏（TalkBack）可用性：清理页提供「收藏 / 取消收藏、移入垃圾桶、上一张、下一张」自定义操作；照片页缩略图可双击打开、双击按住进入多选；计数整体朗读；语言选项读出「已选中」。
- 安卓：快速滚动网格时，被取消的缩略图请求会改去读取整张原图，白白占用读盘和内存。
- iOS：在系统相册或其它 App 里增删照片、调整「部分照片」的选择后，App 不刷新；从系统设置改了照片权限回来要重开 App。现在都会自动刷新（清理页打开期间先记下，关掉清理页时再刷新）。
- iOS：清空垃圾桶要等整个图库重读完才开始溶解（照片多时要等几百毫秒到几秒），中间还可能闪一下空页面；照片已在系统相册里删掉时清空会出错；任何失败都提示「操作已取消」。现在确认后溶解马上开始，只删还在的照片，只有点了「不允许」才提示已取消，其它失败提示「操作失败」。
- iOS：「清空后可释放」把已经不在图库里的照片也算了进去；在 App 外删掉的照片一直留在收藏 / 垃圾桶 / 看过记录里。现在只算还在的照片，失效记录在完整授权下自动清理（只授权部分照片时不清理）。
- iOS：清理页处理完最后一张时，离场动画和余影还没播就直接换成完成页；现在播完再回入口页，期间仍可撤回。
- iOS：清理页拖到一半被来电、通知中心等系统手势打断时，照片停在半路、顶栏不再出现，下一次滑动可能失效；现在照片弹回原位。
- iOS：收藏 / 移入没有生效时点撤回，会撤回上一张或撤错照片；翻出去几页后再撤回，飞回来的照片是空白。撤回「取消收藏」后照片跳到收藏页最前面，现在回到原来的位置。
- iOS：上滑收藏的两层余影在照片落入时突然整块消失，现在走完各自的路径再淡出；撤回条被上下拖动或小结页暂时藏起、再出现时倒计时细线从满格重来，现在按真实剩余时间接着走。
- iOS：清理页顶部进度条当前格的呼吸从没动过；现在按设计 1.6 秒呼吸（减弱动态效果时常亮），呼吸以 30fps 刷新，不让上下滑多掉帧。小结页入场播完后不再每帧重绘。
- iOS：垃圾桶预览翻过的大图一直占着内存（每张约 9MB），文件名和大小在主线程反复查询；照片页批量移到垃圾桶时逐张同步计算文件大小导致卡顿。
- iOS：横屏时网格、清理页顶栏和底部信息被刘海挡住，垃圾桶预览的方框和教学页说明超出屏幕；小屏或大字号下授权页内容和按钮重叠（现在放不下时可以滚动）。
- iOS：照片 / 收藏网格快速甩动时仍可能误点进清理页（现在滚动中和停稳后约 300ms 内不响应点按）；从收藏页打开照片没有展开动画。
- iOS：没有拍摄时间的照片被归到公元 1 年的一天并生成小结，现在归为「未知日期」；英文界面数量为 1 时露出原始文案 key。

### Changed

- iOS：只支持 iPhone，不再提供 iPad 安装。
- iOS：系统字号最大按 xxxLarge 显示（更大的辅助功能字号不再继续放大），避免固定高度位置的文字被裁切。

---

### Added

- Android: video support. Videos sit alongside photos by date and can be favorited, moved to trash, undone and emptied; only the first frame is shown, with the duration in the grid corner and in the review footer. Android 13+ also asks for video access (same system prompt as photos); users who already granted photo access are asked once on launch.
- Android: new home filters "Screenshots", "Large files" (≥ 10 MB) and "Chat app images" (WeChat / WhatsApp / Telegram / QQ), each with a count; review then pages only through the filtered photos.
- Android: new "Similar photos" page (last item in the filter menu): photos taken within 10 seconds of each other that look alike are grouped, the highest-resolution one is kept by default (tap a thumbnail to change it), and "Remove the other N" moves the rest to the trash with undo.
- Android: new "On this day" home filter: photos and videos from the same date in earlier years, with a count; hidden when there are none.
- Android: the empty trash shows "Freed X in total", counting only items actually removed from the gallery.
- Android: when the trash holds over 1 GB or has items older than 30 days, the Photos page shows a reminder (at most once every 7 days); "Review" jumps to the trash. In-app only, no system notification.
- iOS: VoiceOver support in review: the photo page reads its capture time as one element, offers Favorite/Unfavorite and Move to Trash custom actions, and swiping up or down pages between photos.
- iOS: with limited photo access, the Photos ⋯ menu has a new "Select more photos" entry; the system no longer pops up its own selection prompt on launch.
- iOS: added a privacy manifest (PrivacyInfo.xcprivacy): no tracking, no data collection, and declarations for the local settings storage and system clock APIs used.

### Fixed

- Android: backgrounding the app during review and coming back after the system killed the process showed "Couldn't load photos" and left a black screen with no back button. The library is now loaded before the session starts and review resumes at the photo you were on (or the nearest one if it was already handled); if the session still can't be created the app shows the error and returns to the previous screen.
- Android: photos added or deleted in the system gallery or another app didn't show up until the app was restarted. The library is now re-read when the app returns to the foreground (at most once every 2 seconds), and when several screens refresh at once an older result can no longer overwrite a newer one.
- Android: the app crashed when storage was full or its settings file was corrupted. Read/write failures are now handled like ordinary failures (in review the photo goes back in place with a message), and a corrupted settings file is reset instead of crashing on every launch.
- Android: leaving a day summary page before its intro finished and coming back left the numbers and bars frozen halfway.
- Android: opening a photo in the trash and going back scrolled the grid to the top, and the empty-trash dissolve always started from the top. Both now keep the scroll position.
- Android: after a short vertical drag in review that didn't pass the threshold, tapping or swiping sideways during the bounce-back could leave the top bar, date and bottom info invisible.
- Android: the two amber afterimages of the swipe-up favorite vanished abruptly one frame after the photo landed; they now finish their path and fade out (same look, opacity and delays).
- Android: the undo bar's 4-second countdown restarted whenever the bar reappeared after a vertical drag, so repeated drags kept it alive. It now runs for 4 seconds of real time.
- Android: with a right-to-left system language such as Arabic, the layout mirrored while the text stayed in English, throwing off swipe directions and where photos fly to. The app only ships Chinese and English, so it now always lays out left to right.
- Android: each swipe in review rewrote the whole local state file three or four times. The action, marking the next photo as seen and the swipe counter are now one write, paging back to an already-seen photo no longer writes at all, and with large libraries a swipe no longer rebuilds the whole page sequence on the main thread.
- Android: several empty-trash edge cases: on Android 8–10, when some photos couldn't be deleted, the ones that were deleted still showed in the trash; with partial photo access, emptying left stale trash/favorite/seen records behind; and if the app was killed while the system delete dialog was open, there was no result message on return.
- Android: dragging after a long press in Photos that didn't take effect (list still settling, batch in progress) still multi-selected from the previous anchor.
- Android: pressing during an unfinished page-flip animation in review and then dragging vertically made the photo jump back to center in one frame; the leftover horizontal offset now springs back.
- Android: with a very large system font, bottom-bar labels, page headers and day headers were clipped or pushed out. Text in these fixed-size spots now has a scaling cap (no change at the default size). "Move to trash" in the selection bar no longer wraps and gets cut off, and the selected size in Trash selection is no longer squeezed to "1.".
- Android: in landscape, the system navigation bar or display cutout on the left/right covered page content.
- Android: TalkBack accessibility: review offers custom actions (favorite/unfavorite, move to trash, previous, next); grid thumbnails can be double-tapped to open and double-tap-held to start multi-select; counters are read as one number; language options announce "selected".
- Android: when scrolling the grid quickly, cancelled thumbnail requests fell back to reading the full original image, wasting disk reads and memory.
- iOS: adding or deleting photos in the Photos app or another app, or changing the limited-access selection, didn't refresh the app, and changing photo permission in Settings needed a restart. Both now refresh automatically (while review is open the change is held until review closes).
- iOS: emptying the trash waited for the whole library to reload before the dissolve started (hundreds of ms to seconds on large libraries) and could flash an empty page; it failed when some photos had already been deleted in the Photos app; and every failure said "cancelled". The dissolve now starts right after confirmation, only photos that still exist are deleted, "cancelled" appears only when you tap Don't Allow, and other failures say the action failed.
- iOS: "can free" counted photos that were no longer in the library, and photos deleted outside the app stayed in the favorite/trash/seen records forever. Only existing photos are counted now, and stale records are cleaned up under full access (never under limited access).
- iOS: finishing the last photo in review replaced the stage with the done page before the exit animation and afterimages played. They now finish before returning, and undo still works meanwhile.
- iOS: when a system gesture (incoming call, Notification Center, etc.) interrupted a review drag, the photo stayed stuck midway, the top bar stayed hidden and the next swipe could be swallowed. The photo now springs back.
- iOS: tapping Undo after a favorite/trash action that didn't take effect could undo the wrong photo, and undoing after paging several photos away flew back a blank image. Undoing an unfavorite moved the photo to the top of Favorites; it now returns to its original place.
- iOS: the two swipe-up favorite afterimages vanished abruptly as the photo landed; they now finish their paths and fade out. When the undo bar reappeared after a vertical drag or a summary page, its countdown line restarted from full; it now continues from the real time left.
- iOS: the breathing glow on the progress bar's current cell never animated. It now breathes over 1.6 s as designed (steady with Reduce Motion), updated at 30 fps so it doesn't add dropped frames to vertical swipes. The day summary page also stops redrawing every frame once its intro has played.
- iOS: trash preview kept every viewed full-size image in memory (about 9 MB each) and looked up file names and sizes on the main thread repeatedly; moving a batch to the trash from the photo grid stalled while it computed each file size.
- iOS: in landscape, the grids, review top bar and bottom info went under the notch, and the trash preview square and tutorial text ran off screen. On small screens or with large text the permission page overlapped its buttons; it now scrolls when the content doesn't fit.
- iOS: fast flings on the Photos/Favorites grids could still open review by accident (taps are now ignored while scrolling and for about 300 ms after the grid settles), and opening a photo from Favorites had no expand animation.
- iOS: photos without a capture date were grouped into a day in year 1 with its own summary; they now go under "Unknown date". With a count of 1, the English UI showed a raw string key.

### Changed

- iOS: iPhone only; the app is no longer offered for iPad.
- iOS: text follows the system size up to xxxLarge (larger accessibility sizes stop growing) so text in fixed-height spots isn't clipped.

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

[Unreleased]: https://github.com/winterszhangdong/cleanphotos/compare/v1.6.0...HEAD
[1.6.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.5.5...v1.6.0
[1.5.5]: https://github.com/winterszhangdong/cleanphotos/compare/v1.5.4...v1.5.5
[1.5.4]: https://github.com/winterszhangdong/cleanphotos/compare/v1.5.3...v1.5.4
[1.5.3]: https://github.com/winterszhangdong/cleanphotos/compare/v1.5.2...v1.5.3
[1.5.2]: https://github.com/winterszhangdong/cleanphotos/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/winterszhangdong/cleanphotos/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.2...v1.3.0
[1.2.2]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/winterszhangdong/cleanphotos/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/winterszhangdong/cleanphotos/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/winterszhangdong/cleanphotos/releases/tag/v1.0.0
