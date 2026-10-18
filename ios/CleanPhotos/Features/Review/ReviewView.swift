import SwiftUI
import Photos
import CoreImage
import os

/// 清理页（暗房）。对齐 Android `ReviewScreen` / `ReviewStage` / `ReviewStageState`：
/// - 自绘三页（上一张 / 当前 / 下一张）按 page id 放在同一个 ZStack，左右 1:1 跟手，松手 snappy 弹簧；
/// - 上下拖动整页（环境光 + 照片）像拎起的相纸：1 → 0.9、32pt 圆角、下滑去色压暗、上滑底部琥珀光 / 下滑暗角；
/// - 提示圆：上滑在底部、下滑在顶部；过阈值 32 → 56 实心 + 1.14 pop；
/// - 过阈值松手：离场层 250ms 飞向导航「收藏 / 垃圾桶」位置，当前页立刻换下一张（0.94 / 亮度 0.6 升起），不锁输入。
struct ReviewView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.reviewClose) private var reviewClose
    @Environment(\.reviewSharedEntry) private var sharedEntry
    @ObservedObject private var shared = PhotoOpenTransition.shared
    /// Android navChrome：共享元素带进来时 400–520ms 淡入，收回时先 100ms 淡出。
    @State private var navChrome: NavChromeFade.State?
    private let seedImage: UIImage?
    private let startPhotoId: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 「⋯」菜单「去垃圾桶」：关掉清理页并切到垃圾桶（Android onNavigateToTrash）。
    var onGoTrash: () -> Void = {}

    @StateObject private var session: ReviewSessionModel
    @StateObject private var images = ReviewImageStore()

    @State private var dx: CGFloat = 0
    @State private var dy: CGFloat = 0
    @State private var axis: DragAxis? = nil
    @State private var slop: CGFloat = 0
    @State private var past = false
    @State private var pastAnim: CGFloat = 0
    @State private var popScale: CGFloat = 1
    @State private var chromeAlpha: Double = 1
    @State private var flights: [ExitFlight] = []
    @State private var releaseCue: ReleaseCue?
    @State private var entry: EntryAnim?
    @State private var lastActed: (photo: PhotoItem, kind: ExitKind, target: CGPoint)?
    /// 撤回飞回时被让位的那一页：随飞回前 30% 减速退到 0.94 / 亮度 0.35。
    @State private var receding: ReviewPage?
    /// 首次教学（has_seen_review_tutorial）：点「知道了」或完成任意一次翻页 / 滑动后永久关闭。
    @State private var tutorial = !ReviewPrefs.hasSeenTutorial
    /// 方向提示小点：累计成功上滑 / 下滑不足 20 次时显示。
    @State private var showHints = ReviewPrefs.showDirectionHints
    @State private var menuOpen = false
    /// 双指缩放 / 双击放大（Android ReviewStageState zoom）：0.8–3×，静止范围 1–3×，松手低于 1× 弹跳回 1×。
    @State private var zoom: CGFloat = 1
    @State private var zoomOffset: CGSize = .zero
    @State private var rawZoom: CGFloat = 1
    @State private var pinchBase: (zoom: CGFloat, offset: CGSize)?
    @State private var panBase: CGSize = .zero
    @State private var pinched = false
    /// 最后一张处理完、正在等离场播完回入口页。
    @State private var endingSession = false
    /// 拖动 / 捏合是否还在进行：系统打断手势（来电、通知中心、控制中心、多指系统手势）时 SwiftUI 不调 onEnded，
    /// 只把 GestureState 复位 —— 靠它把拎起一半 / 拖了一半的照片弹回原位。
    @GestureState private var dragLive = false
    @GestureState private var pinchLive = false

    enum DragAxis { case horizontal, vertical, pan }

    init(launch: ReviewLaunch, library: PhotoLibraryService, seedImage: UIImage? = nil, onGoTrash: @escaping () -> Void = {}) {
        self.onGoTrash = onGoTrash
        self.seedImage = seedImage
        self.startPhotoId = launch.startPhotoId
        let (items, start) = library.reviewQueue(scope: launch.scope, startingAt: launch.startPhotoId, dayId: launch.dayId)
        _session = StateObject(wrappedValue: ReviewSessionModel(scope: launch.scope, items: items, startIndex: start, library: library))
    }

    private var animating: Bool { !flights.isEmpty || releaseCue != nil || entry != nil }

    var body: some View {
        GeometryReader { outer in
            let insets = outer.safeAreaInsets
            let size = CGSize(width: outer.size.width + insets.leading + insets.trailing,
                              height: outer.size.height + insets.top + insets.bottom)
            // 逐帧时钟只包住真正随时间变的两层（舞台、提示圆）：离场 / 升起期间顶栏、撤回条、教学、菜单不再每帧重算 body。
            ZStack {
                DarkroomColors.room
                // 会话最后一张处理完：页序空了，但离场层 / 余影要播完、撤回条还要能点（之前这里立刻换成空白「完成」页）
                if session.pages.isEmpty && flights.isEmpty && !endingSession {
                    emptyDone
                } else {
                    TimelineView(.animation(paused: !animating)) { tl in
                        stage(size: size, insets: insets, now: tl.date)
                    }
                    .contentShape(Rectangle())
                    .gesture(dragGesture(size: size, insets: insets).updating($dragLive) { _, live, _ in live = true })
                    .simultaneousGesture(pinchGesture(size: size).updating($pinchLive) { _, live, _ in live = true })
                    .onTapGesture(count: 2, coordinateSpace: .local) { loc in doubleTap(at: loc, size: size) }
                    .modifier(StageAccessibility(
                        photo: session.isOnSummary ? nil : session.currentPhoto,
                        favorites: session.scope == .favorites,
                        label: session.currentPhoto.map { dateTitle($0.creationDate) + " " + timeLine($0.creationDate) } ?? "",
                        locale: language.effectiveLocale,
                        onUp: { a11ySwipe(up: true, size: size, insets: insets) },
                        onDown: { a11ySwipe(up: false, size: size, insets: insets) },
                        onNext: { if page(at: session.index + 1) != nil { advance(-1, size: size) } },
                        onPrevious: { if page(at: session.index - 1) != nil { advance(1, size: size) } }))
                    chrome(size: size, insets: insets)
                    TimelineView(.animation(paused: releaseCue == nil)) { tl in
                        cueLayer(size: size, insets: insets, now: tl.date)
                    }
                    .allowsHitTesting(false)
                    edgeHint(size: size)
                    undoLayer(size: size, insets: insets)
                    if tutorialVisible {
                        ReviewTutorialOverlay(topInset: insets.top, bottomInset: insets.bottom, locale: language.effectiveLocale) { dismissTutorial() }
                    }
                    moreMenu(insets: insets)
                }
            }
            .frame(width: size.width, height: size.height)
            .ignoresSafeArea()
            .onAppear {
                library.reviewActive = true
                if let id = startPhotoId { images.seed(id, seedImage) }
                prepare(size: size, insets: insets); markSeenCurrent()
                if PerfAutoSwipe.count > 0 { runAutoSwipe(size: size, insets: insets) }
                if sharedEntry {
                    let s = NavChromeFade.State(start: Date(), entering: true)
                    navChrome = s
                    DispatchQueue.main.asyncAfter(deadline: .now() + PhotoOpenTransition.expandEnd + PhotoOpenTransition.chromeFade + 0.05) {
                        if navChrome == s { navChrome = nil }
                    }
                }
            }
            .onDisappear { FrameMonitor.shared.stop("review"); library.endReview() }
            .onChange(of: session.index) { _, _ in prepareSoon(size: size, insets: insets); markSeenCurrent(); resetZoom() }
            .onChange(of: dragLive) { _, live in if !live { DispatchQueue.main.async { dragCancelledIfNeeded(size: size) } } }
            .onChange(of: pinchLive) { _, live in if !live { DispatchQueue.main.async { pinchCancelledIfNeeded(size: size) } } }
            .onChange(of: zoom > 1.01) { _, z in
                if z, let p = session.currentPhoto { images.ensureZoom(p, library: library, side: max(size.width, size.height)) }
            }
            .onChange(of: session.pages.count) { _, _ in
                prepareSoon(size: size, insets: insets)
                // 会话最后一张处理完且不满足小结：回到入口页（interaction.md「会话结束」）
                if session.isCompleted {
                    endingSession = true
                    // 离场主体播完再回入口页（Android LaunchedEffect(isCompleted) + EXIT_MAIN_MAX）；期间点了撤回就不走
                    DispatchQueue.main.asyncAfter(deadline: .now() + ReviewTokens.exitDuration) {
                        if session.isCompleted { close() } else { endingSession = false }
                    }
                }
            }
        }
        .environment(\.locale, language.effectiveLocale)
        .statusBarHidden(false)
    }

    // MARK: - Pages

    private func page(at i: Int) -> ReviewPage? { session.pages.indices.contains(i) ? session.pages[i] : nil }

    private func prepare(size: CGSize, insets: EdgeInsets) {
        let window = (max(0, session.index - 2)...min(session.pages.count, session.index + 3)).compactMap { i -> PhotoItem? in
            if let p = page(at: i), case .photo(let ph) = p { return ph } else { return nil }
        }
        AssetSizeCache.shared.prefetch(window.map(\.asset))
        var keep = Set(flights.compactMap { f -> String? in if case .photo(let p) = f.page { return p.id } else { return nil } })
        // 还能撤回的那张：翻出去几页后它的图也别丢，撤回飞回来时才有画面
        if let acted = lastActed { keep.insert(acted.photo.id) }
        images.ensure(window, keep: keep, library: library, page: size,
                      box: CGSize(width: size.width, height: size.height - insets.top - insets.bottom))
        // 收藏余影的琥珀光按照片尺寸预渲染（后台），松手时直接用
        let box = CGSize(width: size.width, height: size.height - insets.top - insets.bottom)
        AfterimageGlow.shared.prepare(window.map { ReviewMath.fitSize(pixelWidth: $0.asset.pixelWidth, pixelHeight: $0.asset.pixelHeight, in: box) })
    }

    /// 换页时的预取：当前页和下一张的图已经在手里（窗口前后各 2–3 张），就等离场 / 翻页动画走完（~300ms）再发请求、
    /// 回收窗口外的图，不和动画第一帧挤在一起；缺图（快速连翻追上了窗口）时立刻请求。
    private func prepareSoon(size: CGSize, insets: EdgeInsets) {
        if PerfLegacy.on { prepare(size: size, insets: insets); return }
        let needNow = [session.index, session.index + 1].contains { i in
            if let p = page(at: i), case .photo(let ph) = p { return images.image(ph.id) == nil }
            return false
        }
        if needNow { prepare(size: size, insets: insets); return }
        let idx = session.index
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            if session.index == idx { prepare(size: size, insets: insets) }
        }
    }

    private struct Placed: Identifiable {
        let page: ReviewPage
        let role: Int // -1 previous, 0 current, 1 next
        var id: String { page.id }
    }

    private var placedPages: [Placed] {
        var placed: [Placed] = []
        if let cur = page(at: session.index) { placed.append(Placed(page: cur, role: 0)) }
        // 上下拖动时邻页也留着（dx = 0，在屏幕外）：开始拖动 / 松手那一帧不再把两页整页拆掉、重建
        if axis != .vertical || !PerfLegacy.on {
            if let n = page(at: session.index + 1) { placed.append(Placed(page: n, role: 1)) }
            if let p = page(at: session.index - 1) { placed.append(Placed(page: p, role: -1)) }
        }
        return placed
    }

    private func stage(size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        let placed = placedPages
        return ZStack {
            swipeGlow(size: size, now: now).zIndex(1)
            if let r = receding, let e = entry, e.undoFrom != nil {
                let q = CGFloat(CubicBezier.decelerate(min(1, now.timeIntervalSince(e.start) / (ReviewTokens.undoFlight * 0.3))))
                pageContent(r, size: size, insets: insets, rim: false)
                    .colorMultiply(Color(white: Double(1 - (1 - 0.35) * q)))
                    .scaleEffect(1 - (1 - 0.94) * q)
                    .zIndex(2.5)
            }
            if PerfLegacy.on {
                ForEach(placed) { item in
                    if !(receding?.id == item.page.id && entry?.undoFrom != nil) {
                        pageLayer(item, size: size, insets: insets, now: now)
                            .zIndex(item.role == 0 ? 3 : 2)
                    }
                }
                ForEach(flights) { f in
                    flightLayer(f, size: size, insets: insets, now: now).zIndex(4)
                    if f.kind == .up { afterimages(f, size: size, insets: insets, now: now).zIndex(3.5) }
                }
            } else {
                // 离场层与页面放在同一个 ForEach、按 page id 认人：松手时当前页原地变成离场层（同一个视图只换参数），
                // 不再「删掉当前页 + 新建一个离场页」。
                ForEach(stageItems(placed)) { item in
                    stablePageLayer(item, size: size, insets: insets, now: now)
                        .zIndex(item.flight != nil ? 4 : (item.role == 0 ? 3 : 2))
                }
                ForEach(flights) { f in
                    if f.kind == .up { afterimages(f, size: size, insets: insets, now: now).zIndex(3.5) }
                }
            }
        }
        .frame(width: size.width, height: size.height)
        .clipped()
    }

    private func pageLayer(_ item: Placed, size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        legacyPageLayer(item, size: size, insets: insets, now: now)
    }

    private struct StageItem: Identifiable {
        let page: ReviewPage
        let role: Int // -1 previous, 0 current, 1 next（离场层沿用离场前的 0）
        var flight: ExitFlight? = nil
        var id: String { page.id }
    }

    private func stageItems(_ placed: [Placed]) -> [StageItem] {
        var items: [StageItem] = []
        var ids = Set<String>()
        for p in placed where !(receding?.id == p.page.id && entry?.undoFrom != nil) {
            items.append(StageItem(page: p.page, role: p.role)); ids.insert(p.id)
        }
        for f in flights where !ids.contains(f.page.id) {
            items.append(StageItem(page: f.page, role: 0, flight: f)); ids.insert(f.page.id)
        }
        return items
    }

    /// 三页共用同一条修饰链（只是参数不同）：翻页时「下一张」变成「当前」不换视图结构，SwiftUI 不必把整页
    /// （大图 + 环境光 + 滤镜）拆掉重建、重新栅格化 —— 之前每次翻页落定都要在主线程同步重画一整页。
    /// 亮度用黑色蒙版透明度（c × b ≡ c 叠 (1 − b) 黑，逐像素等价），拖动时只改一个图层的 opacity。
    private func stablePageLayer(_ item: StageItem, size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        let flight = item.flight
        let isCur = item.role == 0 && flight == nil
        let e = (isCur && entry?.pageId == item.page.id) ? entry : nil
        let isSummary: Bool = { if case .summary = item.page { return true }; return false }()
        let photoPage = isPhotoPage(item.page)
        let bright: Double
        let sat: Double
        let corner: CGFloat
        let scale: CGFloat
        let offset: CGSize
        var alpha = Double(e?.alpha(now) ?? 1)
        if let f = flight {
            // 离场层：路径同 ExitFlight.pose；亮度用黑色蒙版（不透明内容上与 colorMultiply 逐像素等价）
            let pose = f.pose(f.progress(now))
            sat = Double(pose.saturation); bright = Double(pose.brightness)
            corner = ReviewTokens.liftCorner; scale = pose.scale
            offset = CGSize(width: pose.x, height: pose.y)
            alpha = Double(pose.alpha)
        } else if isCur {
            let entryOffset = e?.offset(now) ?? .zero
            let lift = vertical ? ReviewMath.cueProgress(dy) : 0
            let dim = tutorialVisible && photoPage
            scale = ReviewMath.dragScale(vertical ? dy : 0) * (e?.scale(now) ?? 1) * (photoPage ? zoom : 1)
            sat = Double(ReviewMath.dragSaturation(vertical && !isSummary ? dy : 0) * (e?.saturation(now) ?? 1)) * (dim ? 0.6 : 1)
            bright = Double(ReviewMath.dragBrightness(vertical && !isSummary ? dy : 0) * (e?.brightness(now) ?? 1)) * (dim ? 0.32 : 1)
            corner = (e?.undoFrom != nil) ? ReviewTokens.liftCorner : ReviewTokens.liftCorner * lift
            offset = CGSize(width: dx + entryOffset.x + (isSummary ? 0 : zoomOffset.width),
                            height: (isSummary ? summaryBounce : dy) + entryOffset.y + (isSummary ? 0 : zoomOffset.height))
        } else {
            let towards = item.role == 1 ? min(0, dx) : max(0, dx)
            bright = Double(ReviewMath.enteringBrightness(towards, width: size.width))
            sat = 1; corner = 0; scale = 1
            offset = CGSize(width: dx + CGFloat(item.role) * size.width, height: 0)
        }
        return pageContent(item.page, size: size, insets: insets, rim: isCur && vertical && !isSummary)
            .saturation(sat)
            .overlay { Color.black.opacity(max(0, 1 - bright)).allowsHitTesting(false) }
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .scaleEffect(scale)
            .opacity(alpha)
            .offset(x: offset.width, y: offset.height)
            .allowsHitTesting(flight == nil)
    }

    /// 改之前的写法（-cpLegacy 对照用）：当前页 / 邻页两套修饰链，亮度走 colorMultiply。
    @ViewBuilder
    private func legacyPageLayer(_ item: Placed, size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        switch item.role {
        case 0:
            let e = (entry?.pageId == item.page.id) ? entry : nil
            let entryScale = e?.scale(now) ?? 1
            let entryBright = e?.brightness(now) ?? 1
            let entryOffset = e?.offset(now) ?? .zero
            let lift = vertical ? ReviewMath.cueProgress(dy) : 0
            let s = ReviewMath.dragScale(vertical ? dy : 0) * entryScale * (isPhotoPage(item.page) ? zoom : 1)
            let dim = tutorialVisible && isPhotoPage(item.page)
            let isSummary: Bool = { if case .summary = item.page { return true }; return false }()
            pageContent(item.page, size: size, insets: insets, rim: vertical && !isSummary)
                .saturation(Double(ReviewMath.dragSaturation(vertical && !isSummary ? dy : 0) * (e?.saturation(now) ?? 1)) * (dim ? 0.6 : 1))
                .colorMultiply(Color(white: Double(ReviewMath.dragBrightness(vertical && !isSummary ? dy : 0) * entryBright) * (dim ? 0.32 : 1)))
                .clipShape(RoundedRectangle(cornerRadius: (e?.undoFrom != nil) ? ReviewTokens.liftCorner : ReviewTokens.liftCorner * lift, style: .continuous))
                .scaleEffect(s)
                .opacity(Double(e?.alpha(now) ?? 1))
                .offset(x: dx + entryOffset.x + (isSummary ? 0 : zoomOffset.width), y: (isSummary ? summaryBounce : dy) + entryOffset.y + (isSummary ? 0 : zoomOffset.height))
        default:
            let side = CGFloat(item.role)
            let towards = item.role == 1 ? min(0, dx) : max(0, dx)
            pageContent(item.page, size: size, insets: insets, rim: false)
                .colorMultiply(Color(white: Double(ReviewMath.enteringBrightness(towards, width: size.width))))
                .offset(x: dx + side * size.width)
        }
    }

    private var vertical: Bool { axis == .vertical }
    private var tutorialVisible: Bool { tutorial && !session.isOnSummary && session.currentPhoto != nil }
    private func isPhotoPage(_ p: ReviewPage) -> Bool { if case .photo = p { return true }; return false }
    private var summaryBounce: CGFloat { vertical ? max(-28, min(28, dy * 0.25)) : 0 }

    private struct RimKey: Equatable { let lift: CGFloat; let up: Bool; let past: CGFloat }
    private struct PhotoPageKey: Equatable {
        let id: String; let rev: Int; let size: CGSize; let insets: EdgeInsets; let rim: RimKey?; let flying: Bool; let zoomed: Bool
    }
    private struct SummaryPageKey: Equatable {
        let summary: DaySummary; let isCurrent: Bool; let size: CGSize; let insets: EdgeInsets; let locale: String
    }

    /// 页内容：照片按原比例 Fit 在安全区内居中；没铺满时环境光（放大 1.2、模糊 36、亮度 40%）铺满整页并随页移动缩放。
    /// 包在 EquatableBox 里：横向拖动时只有外层 offset 在变，页内容本身不重算。
    @ViewBuilder
    private func pageContent(_ page: ReviewPage, size: CGSize, insets: EdgeInsets, rim: Bool) -> some View {
        switch page {
        case .photo(let photo):
            let rimKey: RimKey? = rim ? RimKey(lift: ReviewMath.cueProgress(dy), up: dy < 0, past: pastAnim) : nil
            let useZoom = zoom > 1.01 && session.currentPhoto?.id == photo.id
            EquatableBox(key: PhotoPageKey(id: photo.id, rev: images.rev(photo.id), size: size, insets: insets, rim: rimKey,
                                           flying: shared.flyingId == photo.id, zoomed: useZoom)) {
                photoPage(photo, size: size, insets: insets, rim: rimKey, useZoom: useZoom)
            }
            .equatable()
        case .summary(let s):
            // 「下一天」= 往后翻一页（advance(-1)）；原来传 +1 实际是回到上一张。
            let isCurrent = self.page(at: session.index)?.id == page.id && axis == nil
            EquatableBox(key: SummaryPageKey(summary: s, isCurrent: isCurrent, size: size, insets: insets,
                                             locale: language.effectiveLocale.identifier)) {
                DaySummaryView(summary: s, locale: language.effectiveLocale, insets: insets,
                               isCurrent: isCurrent,
                               landscape: size.width > size.height) {
                    if s.isLastDay { close() } else { advance(-1, size: size) }
                } onBack: {
                    close()
                }
                .frame(width: size.width, height: size.height)
            }
            .equatable()
        }
    }

    @ViewBuilder
    private func photoPage(_ photo: PhotoItem, size: CGSize, insets: EdgeInsets, rim: RimKey?, useZoom: Bool) -> some View {
        let box = CGSize(width: size.width, height: size.height - insets.top - insets.bottom)
        let fit = ReviewMath.fitSize(pixelWidth: photo.asset.pixelWidth, pixelHeight: photo.asset.pixelHeight, in: box)
        let letterboxed = fit.width < box.width - 1 || fit.height < box.height - 1
        let centerShift = (insets.top - insets.bottom) / 2
        ZStack {
            DarkroomColors.room
            if let img = images.image(photo.id) {
                if letterboxed {
                    if let glow = images.glow(photo.id) {
                        Image(uiImage: glow)
                            .resizable()
                            .interpolation(.medium)
                            .frame(width: size.width, height: size.height)
                            .accessibilityHidden(true)
                    } else {
                        // 预渲染的环境光还没到（刚打开的第一帧 / -cpLiveGlow）：实时模糊兜底
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                            .frame(width: size.width, height: size.height)
                            .scaleEffect(1.2)
                            .blur(radius: 36)
                            .saturation(1.3)
                            .colorMultiply(Color(white: 0.4))
                            .frame(width: size.width, height: size.height)
                            .clipped()
                            .accessibilityHidden(true)
                    }
                }
                Image(uiImage: (useZoom ? images.zoomImage(photo.id) : nil) ?? img)
                    .resizable()
                    .accessibilityHidden(true)
                    .aspectRatio(contentMode: .fill)
                    .frame(width: fit.width, height: fit.height)
                    .clipped()
                    .opacity(shared.flyingId == photo.id ? 0 : 1)
                    .overlay {
                        if let rim { liftRim(rim, fullBleed: !letterboxed) }
                    }
                    .offset(y: centerShift)
            }
        }
        .frame(width: size.width, height: size.height)
    }

    /// 过阈值前后的照片描边：上滑琥珀 20% → 55%，下滑相纸白 6% → 8%；透明度 × 拎起进度。
    private func liftRim(_ k: RimKey, fullBleed: Bool) -> some View {
        let lift = k.lift
        let color = k.up ? DarkroomColors.amber.opacity(Double(0.2 + 0.35 * k.past))
                         : DarkroomColors.paper.opacity(Double(0.06 + 0.02 * k.past))
        return RoundedRectangle(cornerRadius: fullBleed ? ReviewTokens.liftCorner * lift : 0, style: .continuous)
            .strokeBorder(color, lineWidth: 1)
            .opacity(Double(lift))
            .allowsHitTesting(false)
    }

    private func flightLayer(_ f: ExitFlight, size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        let pose = f.pose(f.progress(now))
        return pageContent(f.page, size: size, insets: insets, rim: false)
            .saturation(Double(pose.saturation))
            .colorMultiply(Color(white: Double(pose.brightness)))
            .clipShape(RoundedRectangle(cornerRadius: ReviewTokens.liftCorner, style: .continuous))
            .scaleEffect(pose.scale)
            .opacity(Double(pose.alpha))
            .offset(x: pose.x, y: pose.y)
            .allowsHitTesting(false)
    }

    /// 两层余影（b-darkroom-motion 卡 04 `.c4-trail`）：照片本身的半透明副本，延迟 50 / 100ms、透明度 0.4 / 0.18，走同一条离场路径。
    /// 每层带琥珀光：1pt 琥珀 40% 外圈 + 琥珀 38% 外发光（下移 24、模糊 70）。光是按尺寸预渲染好的位图（AfterimageGlow，后台生成），
    /// 每帧只改变换和透明度，不在主线程画阴影；照片用已解码好的同一张图。后一层先画、在下面。
    private func afterimages(_ f: ExitFlight, size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        guard case .photo(let p) = f.page, let img = images.image(p.id) else { return AnyView(EmptyView()) }
        let box = CGSize(width: size.width, height: size.height - insets.top - insets.bottom)
        let fit = ReviewMath.fitSize(pixelWidth: p.asset.pixelWidth, pixelHeight: p.asset.pixelHeight, in: box)
        let glow = AfterimageGlow.shared.glow(for: fit)
        let centerShift = (insets.top - insets.bottom) / 2
        // 用不截断的进度：主体在 1 时已淡完，两层余影（晚 0.2 / 0.4）要走到各自的 1 才落地淡出
        let t = f.rawProgress(now)
        let corner = ReviewTokens.liftCorner
        return AnyView(ZStack {
            ForEach(Array(ReviewTokens.afterimages.enumerated().reversed()), id: \.offset) { _, a in
                let pt = t - a.0
                if pt > 0 {
                    let pose = f.pose(pt)
                    ZStack {
                        if let glow {
                            Image(uiImage: glow)
                                .resizable()
                                .frame(width: fit.width + 2 * AfterimageGlow.margin, height: fit.height + 2 * AfterimageGlow.margin)
                        }
                        RoundedRectangle(cornerRadius: corner + 0.5, style: .continuous)
                            .stroke(DarkroomColors.amber.opacity(0.4), lineWidth: 1)
                            .frame(width: fit.width + 1, height: fit.height + 1)
                        Image(uiImage: img)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: fit.width, height: fit.height)
                            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
                    }
                    .offset(y: centerShift)
                    .frame(width: size.width, height: size.height)
                    .scaleEffect(pose.scale)
                    .opacity(a.1 * Double(pose.alpha))
                    .offset(x: pose.x, y: pose.y)
                }
            }
        }
        .frame(width: size.width, height: size.height)
        .allowsHitTesting(false)
        .accessibilityHidden(true))
    }

    /// 上滑底部琥珀显影光（未过阈值约 38%）/ 下滑四周极淡中性暗角；松手后满强度随离场 266ms 淡出。
    private func swipeGlow(size: CGSize, now: Date) -> some View {
        var up = true
        var alpha: CGFloat = 0
        if vertical && dy != 0 && !session.isOnSummary {
            up = dy < 0
            let pre = ReviewMath.cueProgress(dy)
            alpha = up ? pre * ReviewTokens.upGlowPre + (1 - ReviewTokens.upGlowPre) * pastAnim
                       : pre * ReviewTokens.downVignettePre + (1 - ReviewTokens.downVignettePre) * pastAnim
        } else if let rc = releaseCue {
            up = rc.up
            alpha = rc.alpha(now)
        }
        return ZStack {
            if up {
                RadialGradient(
                    stops: [
                        .init(color: DarkroomColors.amber.opacity(0.55), location: 0),
                        .init(color: DarkroomColors.amber.opacity(0.12), location: 0.55),
                        .init(color: .clear, location: 0.75),
                    ],
                    center: .bottom, startRadius: 0, endRadius: size.width * 1.4
                )
            } else {
                RadialGradient(
                    stops: [
                        .init(color: .clear, location: 0.4),
                        .init(color: .black.opacity(0.55), location: 1),
                    ],
                    center: UnitPoint(x: 0.5, y: 0.62), startRadius: 0, endRadius: max(size.width * 1.2, size.height * 0.7)
                )
            }
        }
        .opacity(Double(min(1, max(0, alpha))))
        .allowsHitTesting(false)
    }

    // MARK: - Chrome

    static let warmBlack = Color(red: 0x0A/255, green: 0x09/255, blue: 0x08/255)

    /// 页面装饰（Android ReviewScreen）：上下暖黑渐变、当天进度条、顶栏、元信息行；教学期间只留顶栏返回钮。
    /// 横屏（mock 17）：顶部渐变 96、无底部渐变，进度条左 48 右 24，顶栏左 40 右 24，元信息左 48 底 20、不显示方向小点。
    private struct ChromeKey: Equatable {
        let photo: PhotoItem; let cells: [DayCellState]; let tutorial: Bool; let undoNil: Bool; let hints: Bool
        let size: CGSize; let insets: EdgeInsets; let locale: String
    }

    @ViewBuilder
    private func chrome(size: CGSize, insets: EdgeInsets) -> some View {
        if let photo = session.currentPhoto {
            let cells = session.cells(for: photo)
            EquatableBox(key: ChromeKey(photo: photo, cells: cells, tutorial: tutorialVisible, undoNil: library.undoBanner == nil,
                                        hints: showHints, size: size, insets: insets,
                                        locale: language.effectiveLocale.identifier)) {
                chromeBody(photo, cells: cells, size: size, insets: insets)
            }
            .equatable()
            .opacity(chromeAlpha)
            .modifier(NavChromeFade(state: navChrome))
        }
    }

    private func chromeBody(_ photo: PhotoItem, cells: [DayCellState], size: CGSize, insets: EdgeInsets) -> some View {
            let land = size.width > size.height
            return ZStack {
                if !tutorialVisible {
                    VStack(spacing: 0) {
                        LinearGradient(colors: [Self.warmBlack.opacity(0.62), .clear], startPoint: .top, endPoint: .bottom)
                            .frame(height: land ? 96 : 160)
                        Spacer()
                        if !land {
                            LinearGradient(colors: [.clear, Self.warmBlack.opacity(0.70)], startPoint: .top, endPoint: .bottom)
                                .frame(height: 200)
                        }
                    }
                    .allowsHitTesting(false)
                }

                VStack(spacing: 0) {
                    ReviewProgressBar(cells: cells)
                        .padding(.leading, land ? 48 + insets.leading : 16)
                        .padding(.trailing, land ? 24 + insets.trailing : 16)
                        .padding(.top, insets.top + (land ? 12 : 8))
                        .opacity(tutorialVisible ? 0 : 1)
                    topBar(photo)
                        .padding(.leading, land ? 40 - 12 + insets.leading : 0)
                        .padding(.trailing, land ? 24 - 12 + insets.trailing : 0)
                        .padding(.top, land ? 12 : 12)
                    Spacer()
                    if library.undoBanner == nil && !tutorialVisible {
                        ReviewMetaRow(photo: photo, scope: session.scope, locale: language.effectiveLocale, showHints: showHints && !land)
                            .padding(.leading, land ? 48 + insets.leading : 16)
                            .padding(.trailing, land ? 16 + insets.trailing : 16)
                            .padding(.bottom, insets.bottom + (land ? 20 : 32))
                            .transition(.opacity)
                    }
                }
            }
    }

    /// 关闭清理页：有共享元素宿主时交给它收回到网格瓦片（控件先 100ms 淡出），否则普通 dismiss。
    private func close() {
        library.flushDeferredSeen()
        guard let handler = reviewClose.handler else { dismiss(); return }
        navChrome = NavChromeFade.State(start: Date(), entering: false)
        var id: String?
        if let p = page(at: session.index), case .photo(let ph) = p { id = ph.id }
        handler(id, id.flatMap { images.image($0) })
    }

    /// .rv-top：40pt 玻璃返回 + 日期 15/20 500 + mono 10.5「星期 · 时间」相纸白 66% + 右侧「⋯」（教学期间隐藏）。
    private func topBar(_ photo: PhotoItem) -> some View {
        ZStack {
            HStack {
                GlassCircleButton(systemName: "chevron.left") { close() }
                    .accessibilityLabel(Text(L10n.string("cd_back", locale: language.effectiveLocale)))
                    .accessibilityIdentifier("review-back")
                Spacer()
                if !tutorialVisible {
                    GlassCircleButton(systemName: "ellipsis") { withAnimation(DarkroomMotion.menuIn) { menuOpen = true } }
                        .accessibilityLabel(Text(L10n.string("cd_more", locale: language.effectiveLocale)))
                        .accessibilityIdentifier("review-more")
                }
            }
            VStack(spacing: 0) {
                // Android ReviewTopBar：日期走里程表，方向按「第几天」（epoch day）定
                OdometerText(value: Int64(((photo.creationDate.timeIntervalSince1970 + Double(TimeZone.current.secondsFromGMT())) / 86400).rounded(.down)),
                             text: dateTitle(photo.creationDate),
                             font: Font.custom("NotoSansSC-Medium", size: 15), color: DarkroomColors.paper)
                Text(timeLine(photo.creationDate))
                    .font(DarkroomFonts.plexMono(size: 10.5))
                    .tracking(0.42)
                    .foregroundStyle(DarkroomColors.paper.opacity(0.66))
            }
            // 翻页在 snappy 弹簧事务里换 index：不让这里的文字跟着做隐式插值（带阴影的文字组每帧都要在主线程 CPU 重画）。
            // 日期里程表自己按时间轴滚动，不受影响；时间行与 Android 一样直接换字。
            .transaction { if !PerfLegacy.on { $0.animation = nil } }
            .shadow(color: .black.opacity(0.4), radius: 10, y: 2.6)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("review-date")
        }
        .frame(height: 40)
        .padding(.horizontal, 12)
    }

    /// 「⋯」更多菜单：去垃圾桶（带数量）/ 回到照片。
    @ViewBuilder
    private func moreMenu(insets: EdgeInsets) -> some View {
        if menuOpen {
            ZStack(alignment: .topTrailing) {
                DarkroomColors.menuScrim
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation(DarkroomMotion.menuIn) { menuOpen = false } }
                    .accessibilityHidden(true)
                DarkroomMenuView(header: L10n.string("menu_more_header", locale: language.effectiveLocale), options: [
                    DarkroomMenuOption(label: L10n.string("review_go_trash", locale: language.effectiveLocale),
                                       trailing: library.trash.count > 0 ? "\(library.trash.count)" : nil, selected: false) {
                        menuOpen = false
                        onGoTrash()
                    },
                    DarkroomMenuOption(label: L10n.string("back_to_photos", locale: language.effectiveLocale), trailing: nil, selected: false) {
                        menuOpen = false
                        close()
                    },
                ])
                .padding(.top, insets.top + 8 + 2 + 12 + 40 + 8)
                .padding(.trailing, 12)
            }
        }
    }

    private struct UndoKey: Equatable {
        let banner: UndoBanner?; let size: CGSize; let insets: EdgeInsets; let locale: String
    }

    private func undoLayer(size: CGSize, insets: EdgeInsets) -> some View {
        let shown = (!session.isOnSummary && !vertical && !tutorialVisible) ? library.undoBanner : nil
        return EquatableBox(key: UndoKey(banner: shown, size: size, insets: insets,
                                         locale: language.effectiveLocale.identifier)) {
            undoBody(shown, size: size, insets: insets)
        }
        .equatable()
    }

    @ViewBuilder
    private func undoBody(_ shown: UndoBanner?, size: CGSize, insets: EdgeInsets) -> some View {
        let land = size.width > size.height
        if let banner = shown {
            VStack {
                Spacer()
                HStack {
                    if land { Spacer() }
                    UndoCapsule(banner: banner, locale: language.effectiveLocale) { undo(size: size, insets: insets) }
                        .frame(width: land ? 320 : nil)
                }
                .padding(.leading, land ? 0 : 16)
                .padding(.trailing, land ? 24 + insets.trailing : 16)
                .padding(.bottom, insets.bottom + (land ? 20 : 32))
                .id(banner.id)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            .animation(DarkroomMotion.standard, value: banner.id)
        }
    }

    // MARK: - Cue

    /// 提示：上滑在下方（导航上方 4 / 16），下滑在上方（状态栏下 16 / 24）。松手后满强度 266ms 线性淡出。
    /// 拖动中与松手后是同一个视图（只换参数）：松手那一帧不拆掉重建提示圆。
    @ViewBuilder
    private func cueLayer(size: CGSize, insets: EdgeInsets, now: Date) -> some View {
        let dragging = vertical && dy != 0 && !session.isOnSummary
        let rc = dragging ? nil : releaseCue
        if dragging || rc != nil {
            cuePositioned(up: rc?.up ?? (dy < 0), past: rc != nil ? true : past, released: rc != nil, insets: insets)
                .opacity(Double(rc?.alpha(now) ?? 1))
        }
    }

    private func cuePositioned(up: Bool, past: Bool, released: Bool, insets: EdgeInsets) -> some View {
        VStack(spacing: 0) {
            if up { Spacer(minLength: 0) }
            ReviewCueView(up: up, past: past, progress: ReviewMath.cueProgress(dy), unfavorite: up && session.scope == .favorites,
                          popScale: released ? 1 : popScale)
            if !up { Spacer(minLength: 0) }
        }
        .padding(.top, up ? 0 : insets.top + (past ? 24 : 16))
        .padding(.bottom, up ? insets.bottom + (past ? 16 : 4) : 0)
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
    }

    @ViewBuilder
    private func edgeHint(size: CGSize) -> some View {
        let atStart = axis == .horizontal && dx > 0 && page(at: session.index - 1) == nil
        let atEnd = axis == .horizontal && dx < 0 && page(at: session.index + 1) == nil
        if atStart || atEnd {
            HStack {
                if atEnd { Spacer() }
                Text(L10n.string(atStart ? "edge_first" : "edge_last", locale: language.effectiveLocale))
                    .font(Font.custom("NotoSansSC-Regular", size: 13))
                    .foregroundStyle(DarkroomColors.paper)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(DarkroomColors.tray.opacity(0.78), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .opacity(Double(min(1, abs(dx) / (size.width * 0.3 * 0.5))))
                if atStart { Spacer() }
            }
            .padding(.horizontal, 16)
            .allowsHitTesting(false)
        }
    }

    private var emptyDone: some View {
        VStack(spacing: 16) {
            Text("review_done_title")
                .font(DarkroomType.title)
                .foregroundStyle(DarkroomColors.paper)
            Button { close() } label: {
                Text("back_to_photos")
                    .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                    .foregroundStyle(DarkroomColors.room)
                    .frame(height: 44)
                    .padding(.horizontal, 24)
                    .background(DarkroomColors.paper, in: Capsule())
            }
        }
    }

    // MARK: - Gesture

    private func dragGesture(size: CGSize, insets: EdgeInsets) -> some Gesture {
        DragGesture(minimumDistance: ReviewTokens.touchSlop, coordinateSpace: .global)
            .onChanged { v in
                let t = v.translation
                if pinched { return } // 这次触摸里捏合过：单指位移交给缩放
                if axis == nil, zoom > 1.01 {
                    axis = .pan; panBase = zoomOffset
                }
                if axis == .pan {
                    zoomOffset = clampZoomOffset(CGSize(width: panBase.width + t.width, height: panBase.height + t.height), zoom, size: size)
                    return
                }
                if axis == nil {
                    guard let a = decideAxis(t) else { return }
                    begin(a, t)
                }
                update(t, size: size)
            }
            .onEnded { v in
                if pinched { pinched = false; if axis == .pan { axis = nil }; return }
                if axis == .pan { axis = nil; return }
                if axis == nil {
                    // 抬起前还没定方向但总位移已过 slop：补一次开始拖动（快速连滑不丢）
                    guard let a = decideAxis(v.translation) else { return }
                    begin(a, v.translation)
                    update(v.translation, size: size)
                }
                let vel = v.velocity
                if axis == .horizontal { releaseHorizontal(vx: vel.width, size: size) }
                else { releaseVertical(vy: vel.height, size: size, insets: insets) }
            }
    }

    /// 性能测量（启动参数 `-cpAutoSwipe N [-cpAutoSwipeSlow YES] -cpfps YES`）：不经 XCUITest（它会打开辅助功能，
    /// 额外引入颜色名本地化查询），直接按手指轨迹喂 begin/update/releaseHorizontal，走的是同一条拖动 / 翻页代码。
    private func runAutoSwipe(size: CGSize, insets: EdgeInsets) {
        let n = PerfAutoSwipe.count, slow = PerfAutoSwipe.slow
        if PerfAutoSwipe.dir == "up" || PerfAutoSwipe.dir == "down" { runAutoVertical(size: size, insets: insets); return }
        Task { @MainActor in
            dismissTutorial()
            try? await Task.sleep(for: .milliseconds(PerfAutoSwipe.delayMs))
            os_signpost(.begin, log: PerfLog.log, name: "autoSwipe")
            for k in 0..<n {
                let sign: CGFloat = (k < n * 2 / 3 || PerfAutoSwipe.forwardOnly) ? -1 : 1 // 先往后翻，最后三分之一往回
                let span = size.width * (slow ? 0.55 : 0.32)
                let steps = slow ? 60 : 14 // 120Hz 触摸采样：慢拖 ~500ms，快甩 ~115ms
                axis = nil
                begin(.horizontal, CGSize(width: sign * ReviewTokens.touchSlop, height: 0))
                for i in 1...steps {
                    let p = CGFloat(i) / CGFloat(steps)
                    update(CGSize(width: sign * (ReviewTokens.touchSlop + span * p), height: 0), size: size)
                    try? await Task.sleep(for: .milliseconds(8))
                }
                releaseHorizontal(vx: sign * (slow ? 400 : 2400), size: size)
                try? await Task.sleep(for: .milliseconds(slow ? 400 : 450))
            }
            os_signpost(.end, log: PerfLog.log, name: "autoSwipe")
            FrameMonitor.shared.stop(slow ? "review-auto-slow" : "review-auto-fast")
        }
    }

    /// 上滑收藏 / 下滑移到垃圾桶的离场测量（-cpAutoSwipeDir up|down）：约 115ms 拖过阈值、松手速度 1500pt/s，
    /// 每次间隔 700ms（离场 250ms + 下一张升起 600ms 基本走完）。PerfDryRun 下收藏 / 垃圾桶只改内存。
    private func runAutoVertical(size: CGSize, insets: EdgeInsets) {
        let n = PerfAutoSwipe.count, up = PerfAutoSwipe.dir == "up"
        Task { @MainActor in
            dismissTutorial()
            try? await Task.sleep(for: .milliseconds(PerfAutoSwipe.delayMs))
            os_signpost(.begin, log: PerfLog.log, name: "autoSwipe")
            for _ in 0..<n {
                if session.isOnSummary {
                    advance(-1, size: size)
                    try? await Task.sleep(for: .milliseconds(700))
                    continue
                }
                let sign: CGFloat = up ? -1 : 1
                let span = ReviewTokens.verticalThreshold * 1.6
                let steps = 14
                axis = nil
                begin(.vertical, CGSize(width: 0, height: sign * ReviewTokens.touchSlop))
                for i in 1...steps {
                    let p = CGFloat(i) / CGFloat(steps)
                    update(CGSize(width: 0, height: sign * (ReviewTokens.touchSlop + span * p)), size: size)
                    try? await Task.sleep(for: .milliseconds(8))
                }
                releaseVertical(vy: sign * 1500, size: size, insets: insets)
                try? await Task.sleep(for: .milliseconds(700))
            }
            os_signpost(.end, log: PerfLog.log, name: "autoSwipe")
            FrameMonitor.shared.stop(up ? "review-auto-up" : "review-auto-down")
        }
    }

    private func decideAxis(_ t: CGSize) -> DragAxis? {
        let ax = abs(t.width), ay = abs(t.height)
        if ax >= ay * ReviewTokens.directionRatio && ax > 0 { return .horizontal }
        if ay >= ax * ReviewTokens.directionRatio && ay > 0 { return .vertical }
        return nil
    }

    private func begin(_ a: DragAxis, _ t: CGSize) {
        FrameMonitor.shared.start()
        axis = a
        // 定方向那一刻扣掉 touch slop，跟手不跳
        let along = a == .horizontal ? t.width : t.height
        slop = (along < 0 ? -1 : 1) * min(ReviewTokens.touchSlop, abs(along))
        if a == .vertical {
            ReviewHaptics.prepare()
            dx = 0
            withAnimation(.linear(duration: 0.08)) { chromeAlpha = 0 }
        } else {
            dy = 0
        }
    }

    private func update(_ t: CGSize, size: CGSize) {
        switch axis {
        case .horizontal:
            let raw = t.width - slop
            let hasPrev = page(at: session.index - 1) != nil
            let hasNext = page(at: session.index + 1) != nil
            if (raw > 0 && !hasPrev) || (raw < 0 && !hasNext) {
                dx = ReviewMath.edgeResistance(raw, length: size.width * 0.3)
            } else {
                dx = raw
            }
        case .vertical:
            dy = t.height - slop
            guard !session.isOnSummary else { return }
            let nowPast = abs(dy) > ReviewTokens.verticalThreshold
            if nowPast != past { setPast(nowPast) }
        case .pan, .none:
            break
        }
    }

    /// 手势结束后的下一轮主循环：正常抬起时 onEnded 已经把 axis 清掉；还没清 = 被系统取消了，按「没过阈值松手」弹回。
    private func dragCancelledIfNeeded(size: CGSize) {
        pinched = false
        guard let a = axis else { return }
        axis = nil
        switch a {
        case .horizontal:
            withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { dx = 0 }
        case .vertical:
            if past { setPast(false) }
            withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { dy = 0 }
            withAnimation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short)) { chromeAlpha = 1 }
        case .pan:
            break
        }
    }

    /// 捏合被系统取消：onEnded 没来，pinchBase 还在 —— 按正常松手收尾（低于 1× 弹回）。
    private func pinchCancelledIfNeeded(size: CGSize) {
        guard pinchBase != nil else { return }
        pinchEnded(size: size)
    }

    private func setPast(_ p: Bool) {
        past = p
        if p {
            ReviewHaptics.cross()
            withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: DarkroomMotion.short)) { pastAnim = 1 }
            popScale = 1
            withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: DarkroomMotion.short)) { popScale = ReviewTokens.popOvershoot }
            DispatchQueue.main.asyncAfter(deadline: .now() + DarkroomMotion.short) {
                withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { popScale = 1 }
            }
        } else {
            ReviewHaptics.back()
            withAnimation(.timingCurve(0.3, 0, 0.8, 0.15, duration: DarkroomMotion.micro)) { pastAnim = 0 }
        }
    }

    private func releaseHorizontal(vx: CGFloat, size: CGSize) {
        let dir = ReviewMath.horizontalRelease(dx: dx, vx: vx, width: size.width)
        let hasPrev = page(at: session.index - 1) != nil
        let hasNext = page(at: session.index + 1) != nil
        axis = nil
        if dir == -1 && hasNext {
            advance(-1, size: size, velocity: vx)
        } else if dir == 1 && hasPrev {
            advance(1, size: size, velocity: vx)
        } else {
            let rel = dx == 0 ? 0 : Double(-vx / dx)
            withAnimation(reduceMotion ? nil : SpringToken.settle.animation(initialVelocity: rel)) { dx = 0 }
        }
    }

    /// 翻页：同一个动画事务里改 index 和 dx，按 page id 绑定的三页各自从原位置弹到新位置。
    /// direction: -1 = 下一张，+1 = 上一张。
    private func advance(_ direction: Int, size: CGSize, velocity: CGFloat = 0) {
        // 只有用户翻页才关教学（Android：页面 settle 到别的页）；重建页序导致的 index 变化不算
        dismissTutorial()
        let fromDay = session.currentPhoto.map { ReviewSessionModel.dayKey($0.creationDate) }
        let remaining = size.width - abs(dx)
        let rel = remaining > 1 ? Double(abs(velocity) / remaining) : 0
        // Reduce Motion：直接换页（interaction.md「移除动画」）
        withAnimation(reduceMotion ? nil : SpringToken.snappy.animation(initialVelocity: rel)) {
            if direction < 0 { session.goNext() } else { session.goPrevious() }
            dx = 0
        }
        let toDay = session.currentPhoto.map { ReviewSessionModel.dayKey($0.creationDate) }
        if fromDay != nil, toDay != nil, fromDay != toDay { ReviewHaptics.tick() }
    }

    private func releaseVertical(vy: CGFloat, size: CGSize, insets: EdgeInsets) {
        if session.isOnSummary {
            axis = nil
            withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { dy = 0 }
            return
        }
        let release = ReviewMath.verticalRelease(dy: dy, vy: vy)
        guard release != .none, let photo = session.currentPhoto else {
            if past { setPast(false) }
            let rel = dy == 0 ? 0 : Double(-vy / dy)
            withAnimation(reduceMotion ? nil : SpringToken.settle.animation(initialVelocity: rel)) { dy = 0 }
            axis = nil
            withAnimation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short)) { chromeAlpha = 1 }
            return
        }
        trigger(photo, up: release == .up, vy: vy, size: size, insets: insets)
    }

    /// 读屏自定义操作：和过阈值松手走同一条路径（离场 + 撤回条）。
    private func a11ySwipe(up: Bool, size: CGSize, insets: EdgeInsets) {
        guard !session.isOnSummary, let photo = session.currentPhoto else { return }
        trigger(photo, up: up, vy: up ? -1500 : 1500, size: size, insets: insets)
    }

    /// 导航胶囊「收藏」（第 2 项）/「垃圾桶」（第 3 项）中心，相对屏幕中心。
    private func navTarget(_ kind: ExitKind, size: CGSize, insets: EdgeInsets) -> CGPoint {
        let x: CGFloat = kind == .up ? 0 : DarkroomDimens.navItemWidth // Android: (index − 1) · NavItemWidth，三项之间无间距
        let y = size.height / 2 - (insets.bottom + DarkroomDimens.navBottom + DarkroomDimens.navHeight / 2)
        return CGPoint(x: x, y: y)
    }

    private func trigger(_ photo: PhotoItem, up: Bool, vy: CGFloat, size: CGSize, insets: EdgeInsets) {
        let now = Date()
        let kind: ExitKind = up ? .up : .down
        let e = (entry?.pageId == ReviewPage.photo(photo).id) ? entry : nil
        let target = navTarget(kind, size: size, insets: insets)
        let flight = ExitFlight(
            page: .photo(photo),
            kind: kind,
            startY: dy,
            startScale: ReviewMath.dragScale(dy) * (e?.scale(now) ?? 1),
            startSaturation: ReviewMath.dragSaturation(dy),
            startBrightness: ReviewMath.dragBrightness(dy) * (e?.brightness(now) ?? 1),
            rise: up ? min(max(abs(vy) * 0.04, ReviewTokens.minFlingDistance), 3 * ReviewTokens.minFlingDistance) : 0,
            target: target,
            start: now
        )
        let bannerBefore = library.undoBanner?.shownAt
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) {
            if !reduceMotion {
                flights.append(flight)
                releaseCue = ReleaseCue(up: up, start: now)
            }
            if up {
                if session.scope == .favorites {
                    session.applyVertical(favorite: false, unfavorite: true, trash: false, library: library)
                } else {
                    session.applyVertical(favorite: true, unfavorite: false, trash: false, library: library)
                }
            } else {
                session.applyVertical(favorite: false, unfavorite: false, trash: true, library: library)
            }
            // 只有这次真的出了新撤回条才认领它（收藏 / 移入没生效时不改上一条的文案，也不让撤回对错照片）
            if let b = library.undoBanner, b.shownAt != bannerBefore, b.sampleId == photo.id {
                decorateUndo(for: photo, trash: !up)
                lastActed = (photo, kind, target)
            }
            dy = 0; dx = 0; axis = nil
            past = false; pastAnim = 0; popScale = 1
            if !reduceMotion, let next = session.pages.indices.contains(session.index) ? session.pages[session.index] : nil {
                entry = EntryAnim(pageId: next.id, start: now)
            }
        }
        ReviewPrefs.recordSwipe()
        showHints = ReviewPrefs.showDirectionHints
        dismissTutorial()
        withAnimation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short)) { chromeAlpha = 1 }
        let fid = flight.id
        // 收藏离场的两层余影比主体晚 20% / 40%：离场层留到最后一层余影走完再拿掉（之前 250ms 一到，余影在 80% / 60% 处整块消失）
        let life = ReviewTokens.exitDuration * (up ? 1 + (ReviewTokens.afterimages.map(\.0).max() ?? 0) : 1)
        DispatchQueue.main.asyncAfter(deadline: .now() + life + 0.02) {
            flights.removeAll { $0.id == fid }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + ReviewTokens.releaseCueFade + 0.02) {
            if let rc = releaseCue, rc.start == now { releaseCue = nil }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.62) {
            if let en = entry, en.start == now { entry = nil }
        }
    }

    /// 撤回：照片从去处减速飞回中央，后半段重新显影（Android ExitPath.undoPose）。
    private func undo(size: CGSize, insets: EdgeInsets) {
        guard let banner = library.undoBanner else { return }
        // 撤回条不是这次清理页动作出的（或已对不上）：只撤图库状态，不往页序里插照片
        guard let acted = lastActed, banner.sampleId == acted.photo.id else { library.undoLast(); return }
        library.undoLast()
        let now = Date()
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) {
            flights.removeAll { $0.page.id == ReviewPage.photo(acted.photo).id }
            receding = reduceMotion ? nil : page(at: session.index)
            session.reinsert(acted.photo, kind: acted.kind)
            if !reduceMotion {
                entry = EntryAnim(pageId: ReviewPage.photo(acted.photo).id, start: now, undoFrom: acted.target, undoKind: acted.kind)
            }
            lastActed = nil
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + ReviewTokens.undoFlight + 0.02) {
            if let en = entry, en.start == now { entry = nil; receding = nil }
        }
    }

    // MARK: - Zoom（Android ReviewStageState.onPinch / onDoubleTap / clampZoomOffset）

    private func pinchGesture(size: CGSize) -> some Gesture {
        MagnifyGesture(minimumScaleDelta: 0.01)
            .onChanged { v in
                guard session.currentPhoto != nil, !session.isOnSummary else { return }
                if pinchBase == nil {
                    pinched = true
                    if axis == .horizontal || axis == .vertical {
                        // 拖动中第二根手指落下：取消拖动，切到缩放
                        if past { setPast(false) }
                        withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { dx = 0; dy = 0 }
                        withAnimation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short)) { chromeAlpha = 1 }
                    }
                    pinchBase = (zoom, zoomOffset)
                    if zoom >= GestureTokensIOS.zoomRestMin { rawZoom = zoom }
                    axis = nil
                }
                guard let base = pinchBase else { return }
                let raw = min(GestureTokensIOS.zoomMax, max(0.1, (base.zoom >= 1 ? base.zoom : rawZoom) * v.magnification))
                rawZoom = raw
                let newZoom = GestureTokensIOS.pinchResistance(raw)
                // 以捏合中心为锚：offset' = (offset − c)·k + c
                let c = CGSize(width: v.startLocation.x - size.width / 2, height: v.startLocation.y - size.height / 2)
                let k = newZoom / max(0.01, base.zoom)
                zoomOffset = CGSize(width: (base.offset.width - c.width) * k + c.width, height: (base.offset.height - c.height) * k + c.height)
                zoom = newZoom
            }
            .onEnded { _ in pinchEnded(size: size) }
    }

    private func pinchEnded(size: CGSize) {
        pinchBase = nil
        // 捏合期间单指拖动没被识别（两指对称张合，重心没动）：拖动的 onEnded 不会来清 pinched，下一次滑动会被整个吞掉
        if !dragLive { pinched = false }
        if zoom < GestureTokensIOS.zoomRestMin {
            withAnimation(reduceMotion ? nil : SpringToken.bouncy.animation()) { zoom = 1; zoomOffset = .zero }
            rawZoom = 1
        } else {
            withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { zoomOffset = clampZoomOffset(zoomOffset, zoom, size: size) }
        }
    }

    private func doubleTap(at loc: CGPoint, size: CGSize) {
        guard session.currentPhoto != nil, !tutorialVisible else { return }
        let target: CGFloat = zoom > 1.01 ? 1 : GestureTokensIOS.doubleTapScale
        let off: CGSize = target == 1 ? .zero : clampZoomOffset(
            CGSize(width: (loc.x - size.width / 2) * (1 - target), height: (loc.y - size.height / 2) * (1 - target)), target, size: size)
        withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { zoom = target; zoomOffset = off }
        rawZoom = target
    }

    /// 放大后没超出屏幕的那一轴不能拖，超出的拖到照片边缘为止（不拖出上下环境光）。
    private func clampZoomOffset(_ o: CGSize, _ z: CGFloat, size: CGSize) -> CGSize {
        guard z > 1, let photo = session.currentPhoto else { return .zero }
        let fit = ReviewMath.fitSize(pixelWidth: photo.asset.pixelWidth, pixelHeight: photo.asset.pixelHeight, in: size)
        let mx = max(0, (fit.width * z - size.width) / 2), my = max(0, (fit.height * z - size.height) / 2)
        return CGSize(width: min(mx, max(-mx, o.width)), height: min(my, max(-my, o.height)))
    }

    private func resetZoom() {
        pinchBase = nil
        if zoom != 1 || zoomOffset != .zero {
            var t = Transaction(); t.disablesAnimations = true
            withTransaction(t) { zoom = 1; zoomOffset = .zero }
        }
        rawZoom = 1
    }

    private func dismissTutorial() {
        guard tutorial else { return }
        tutorial = false
        ReviewPrefs.markTutorialSeen()
    }

    // MARK: - Helpers

    /// 清理页撤回条：单张主文案 + 副文案（「可释放 2.4 MB」/「9月21日 · 第 3 张」）。
    private func decorateUndo(for photo: PhotoItem, trash: Bool) {
        let loc = language.effectiveLocale
        let sub: String
        if trash {
            sub = L10n.format("undo_sub_freeable", locale: loc, FileSizeFormatter.format(photo.byteSize))
        } else {
            sub = L10n.format("undo_sub_position", locale: loc, dateTitle(photo.creationDate), session.positionInDay(photo))
        }
        library.decorateUndo(subtitle: sub)
    }

    private func markSeenCurrent() {
        if let p = session.currentPhoto {
            // 性能测量（-cpAutoSwipe）在真机上跑的是用户自己的相册：不写「已看」，不改动用户数据
            if PerfAutoSwipe.count == 0 { library.markSeen([p.id]) }
            session.markKeptCurrent()
        }
    }

    private func dateTitle(_ date: Date) -> String {
        if DayKeyRule.isUnknown(date) { return L10n.string("date_unknown", locale: language.effectiveLocale) }
        return DateFormatCache.string(date, format: language.isChineseUI ? "M月d日" : "MMM d", locale: language.effectiveLocale)
    }

    private func timeLine(_ date: Date) -> String {
        if DayKeyRule.isUnknown(date) { return "" } // Android reviewMeta：没有拍摄时间时为空
        return DateFormatCache.string(date, format: "EEE · HH:mm", locale: language.effectiveLocale)
    }
}

/// 提示圆（Android ReviewCue）：未过阈值 32pt 空心细线圆（描边进度 = 距离 / 阈值）+ 横向文案；
/// 过阈值 56pt 实心（上滑琥珀 + 6pt 光晕 + 投影光；下滑相纸白），主文案 + 副文案，1.14 过冲 pop。
struct ReviewCueView: View {
    @Environment(\.locale) private var cueLocale
    let up: Bool
    let past: Bool
    let progress: CGFloat
    let unfavorite: Bool
    let popScale: CGFloat

    private static let amberInk = Color(red: 0x2A/255, green: 0x1A/255, blue: 0x04/255)
    private static let paperWarm = Color(red: 0xEE/255, green: 0xEE/255, blue: 0xE2/255)

    private var icon: String { up ? (unfavorite ? "star" : "star.fill") : "trash" }

    var body: some View {
        if !past {
            let ring = up ? DarkroomColors.amber.opacity(0.6) : Self.paperWarm.opacity(0.32)
            let text = up ? DarkroomColors.amber.opacity(0.8) : Self.paperWarm.opacity(0.5)
            HStack(spacing: 8) {
                ZStack {
                    Circle().stroke(ring.opacity(0.45), lineWidth: 1.5)
                    Circle().trim(from: 0, to: progress)
                        .stroke(ring, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .regular))
                        .foregroundStyle(text)
                }
                .frame(width: 32, height: 32)
                Text(L10n.string(up ? (unfavorite ? "cue_keep_unfavorite" : "cue_keep_favorite") : "cue_keep_down", locale: cueLocale))
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .tracking(0.56)
                    .foregroundStyle(text)
            }
        } else {
            let accent = up ? DarkroomColors.amber : DarkroomColors.paper
            VStack(spacing: 0) {
                ZStack {
                    Circle()
                        .fill(up ? DarkroomColors.amber.opacity(0.18) : DarkroomColors.paper.opacity(0.08))
                        .frame(width: 68, height: 68)
                    Circle()
                        .fill(accent)
                        .frame(width: 56, height: 56)
                        .shadow(color: up ? DarkroomColors.amberGlow : .clear, radius: 16)
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(up ? Self.amberInk : DarkroomColors.room)
                }
                .frame(width: 56, height: 56)
                .scaleEffect(popScale)
                Spacer().frame(height: 12)
                Text(L10n.string(up ? (unfavorite ? "cue_release_unfavorite" : "cue_release_favorite") : "cue_release_trash", locale: cueLocale))
                    .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                    .foregroundStyle(accent)
                Spacer().frame(height: 4)
                Text(L10n.string(up ? (unfavorite ? "cue_sub_unfavorite" : "cue_sub_favorite") : "cue_sub_trash", locale: cueLocale))
                    .font(DarkroomFonts.plexMono(size: 11))
                    .foregroundStyle(up ? DarkroomColors.amber : DarkroomColors.dim)
            }
        }
    }
}

/// Android GestureTokens 的缩放部分。
enum GestureTokensIOS {
    static let zoomMin: CGFloat = 0.8
    static let zoomRestMin: CGFloat = 1
    static let zoomMax: CGFloat = 3
    static let doubleTapScale: CGFloat = 2
    /// 缩小到 1× 以下继续给阻尼，渐近到 0.8；1× 处斜率为 1，不跳。
    static func pinchResistance(_ raw: CGFloat) -> CGFloat {
        if raw >= zoomRestMin { return raw }
        let room = zoomRestMin - zoomMin
        let x = (zoomRestMin - raw) / room
        return zoomRestMin - room * (1 - 1 / (x + 1))
    }
}

enum PerfAutoSwipe {
    static let count = UserDefaults.standard.integer(forKey: "cpAutoSwipe")
    static let slow = UserDefaults.standard.bool(forKey: "cpAutoSwipeSlow")
    static let forwardOnly = UserDefaults.standard.bool(forKey: "cpAutoSwipeFwd")
    /// h（默认，左右翻页）/ up（上滑收藏）/ down（下滑垃圾桶）
    static let dir = UserDefaults.standard.string(forKey: "cpAutoSwipeDir") ?? "h"
    /// 开始前等待（ms）：真机挂 Instruments 时要留出 attach 时间
    static let delayMs: Int = { let v = UserDefaults.standard.integer(forKey: "cpAutoSwipeDelay"); return v > 0 ? v : 1500 }()
}

/// 收藏余影外面那圈琥珀光（.c4-trail box-shadow 0 24px 70px rgba(242,169,59,.38)）：按照片显示尺寸预渲染一次，
/// 后台线程用 CIGaussianBlur（σ = 70 / 2）生成、半分辨率，照片区域挖空（CSS 外阴影不画在盒子下面）。
final class AfterimageGlow: @unchecked Sendable {
    static let shared = AfterimageGlow()
    static let margin: CGFloat = 130
    private static let blur: CGFloat = 70
    private static let dropY: CGFloat = 24
    private static let corner: CGFloat = ReviewTokens.liftCorner
    private static let renderScale: CGFloat = 0.5
    private let lock = NSLock()
    private var cache: [String: UIImage] = [:]
    private var pending: Set<String> = []
    private let queue = DispatchQueue(label: "cp.afterimage.glow", qos: .utility)
    private lazy var ci = CIContext(options: [.useSoftwareRenderer: false])

    private static func key(_ fit: CGSize) -> String { "\(Int(fit.width.rounded()))x\(Int(fit.height.rounded()))" }

    func glow(for fit: CGSize) -> UIImage? {
        let k = Self.key(fit)
        lock.lock(); defer { lock.unlock() }
        if cache[k] == nil, !pending.contains(k) { pending.insert(k); queue.async { self.render(fit, key: k) } }
        return cache[k]
    }

    func prepare(_ fits: [CGSize]) {
        for f in fits where f.width > 0 && f.height > 0 { _ = glow(for: f) }
    }

    private func render(_ fit: CGSize, key: String) {
        let s = Self.renderScale
        let full = CGSize(width: fit.width + 2 * Self.margin, height: fit.height + 2 * Self.margin)
        let px = CGSize(width: (full.width * s).rounded(.up), height: (full.height * s).rounded(.up))
        let boxRect = CGRect(x: Self.margin * s, y: Self.margin * s, width: fit.width * s, height: fit.height * s)
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1; fmt.opaque = false
        // 1) 下移 24 的琥珀圆角矩形
        let base = UIGraphicsImageRenderer(size: px, format: fmt).image { ctx in
            UIColor(red: 0xF2/255, green: 0xA9/255, blue: 0x3B/255, alpha: 0.38).setFill()
            UIBezierPath(roundedRect: boxRect.offsetBy(dx: 0, dy: Self.dropY * s), cornerRadius: Self.corner * s).fill()
        }
        // 2) 高斯模糊 σ = blur / 2
        var blurred: UIImage = base
        if let input = CIImage(image: base) {
            let out = input.clampedToExtent().applyingGaussianBlur(sigma: Double(Self.blur / 2 * s)).cropped(to: input.extent)
            if let cg = ci.createCGImage(out, from: input.extent) { blurred = UIImage(cgImage: cg) }
        }
        // 3) 挖掉照片区域
        let final = UIGraphicsImageRenderer(size: px, format: fmt).image { ctx in
            blurred.draw(in: CGRect(origin: .zero, size: px))
            ctx.cgContext.setBlendMode(.clear)
            UIBezierPath(roundedRect: boxRect, cornerRadius: Self.corner * s).fill()
        }
        lock.lock(); cache[key] = final; pending.remove(key)
        if cache.count > 24 { cache.removeAll(); cache[key] = final }
        lock.unlock()
    }
}

/// 清理页舞台的读屏（照片页）：整页一个元素，读拍摄日期；自定义操作「收藏 / 取消收藏」「移入垃圾桶」，上下轻扫（adjustable）翻页。
/// 小结页不合并子元素（「下一天」「回到照片」按钮照常可读）。只加辅助功能信息，不影响绘制与手势。
private struct StageAccessibility: ViewModifier {
    let photo: PhotoItem?
    let favorites: Bool
    let label: String
    let locale: Locale
    let onUp: () -> Void
    let onDown: () -> Void
    let onNext: () -> Void
    let onPrevious: () -> Void

    func body(content: Content) -> some View {
        content
            .accessibilityElement(children: photo == nil ? .contain : .ignore)
            .accessibilityLabel(Text(photo == nil ? "" : label))
            .accessibilityActions {
                if photo != nil {
                    Button(L10n.string(favorites ? "action_unfavorite" : "action_favorite", locale: locale), action: onUp)
                    Button(L10n.string("action_move_to_trash", locale: locale), action: onDown)
                }
            }
            .accessibilityAdjustableAction { dir in
                guard photo != nil else { return }
                switch dir {
                case .increment: onNext()
                case .decrement: onPrevious()
                @unknown default: break
                }
            }
    }
}
