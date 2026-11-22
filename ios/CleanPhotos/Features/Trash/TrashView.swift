import SwiftUI
import Photos

/// 垃圾桶（mock 06 / 11 / 12，Android TrashScreen）：
/// - 页头：眉题「垃圾桶」+ 数量 +「选择」；多选时换成三栏（取消 / 已选择 N 项 / 移出垃圾桶）。
/// - 网格头：「清空后可释放」卡片（托盘色 + 细描边，圆角 20）；多选时换成 40pt 摘要（提示 + 「9.6 MB · 回到相册」）。
/// - 三列格子：饱和 70%、亮度 86%，右下角等宽大小；多选选中缩到 0.86 + 圆角 6 + 恢复原色。
/// - 清空：自绘确认对话框（64% 暗幕、托盘色 24 圆角，「删除」是删除红唯一的完整用法）→ 系统授权 →
///   格子按阅读顺序溶解（每格 380ms、步长 60ms、加速曲线：缩到 0.86、模糊 5、亮度 0.3、透明），「可释放」900ms 滚到 0，
///   溶解完 160ms 后空状态（一叠相纸往下沉）舒缓升起，正文换成「刚刚释放了 X」。
/// - 移出垃圾桶：底部暗房提示条「已移出垃圾桶」（Android 没有撤回条）。
struct TrashView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 对话框打开时把底部导航也压在暗幕下（DockScrim）。
    @Binding var dimTabBar: Bool
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var confirmEmpty = false
    @State private var previewId: String?
    @State private var dissolve: DissolveRun?
    @State private var justFreed: Int64?
    @State private var emptyRise: Double = 1
    @State private var toast: String?
    @State private var toastToken = 0
    @State private var dissolveSizes: [String: Int64] = [:]
    @StateObject private var freedBytes = FreedBytesStore()
    /// 清空进行中（系统确认框 → 删除完成 → 溶解起播之前）：网格和「可释放」照旧显示要删的这批，不在中间闪一帧空状态。
    @State private var pendingDelete: (items: [PhotoItem], bytes: Int64)?
    /// 网格宽量自列表（横屏左右安全区；竖屏 = 屏宽）。
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width
    /// 这次进垃圾桶记住的静音。关掉预览不重置；离开这一页，视图拆掉，下次进来重新静音。
    @State private var playbackMuted = true

    init(dimTabBar: Binding<Bool> = .constant(false)) { _dimTabBar = dimTabBar }

    struct DissolveRun: Equatable {
        let items: [PhotoItem]
        let bytes: Int64
        /// 溶解开始前记下的「最早一张已放 N 天」；条目已经删掉，不能再从 TrashStore 算。
        let oldestDays: Int?
        /// 首帧真正上屏时才起表（清空后整页重排那一帧可能很重，按设置时间起表会吃掉开头几帧）
        var start: Date?
        var clockEnd: Double { DissolveTiming.clockEnd(count: items.count) }
        /// 字数 900ms 归零和时钟都走完才收起
        var total: Double { max(clockEnd, 0.9) }
    }

    private var loc: Locale { language.effectiveLocale }

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()

            if let run = dissolve {
                TimelineView(.animation) { tl in
                    let t = run.start.map { max(0, tl.date.timeIntervalSince($0)) } ?? 0
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            Color.clear.frame(height: DarkroomDimens.headerHeight)
                            // 「可释放」900ms 三次减速滚到 0
                            let p = min(1, t / 0.9)
                            freeCard(bytes: Int64(Double(run.bytes) * pow(1 - p, 3)), enabled: false, oldestDays: run.oldestDays)
                            // Android TrashDissolveGrid：第 0 项是 header，所以第 i 张从 (i+1)·60ms 起；
                            // 单一时钟走到最后一张可见格子的起点 + 380ms，每格都溶解完才收起（与 Android 修复后一致）
                            let clock = min(t, run.clockEnd)
                            let starts = Array(stride(from: 0, to: run.items.count, by: 3))
                            ForEach(starts, id: \.self) { start in
                                trashRow(items: run.items, start: start) { i in
                                    CubicBezier.accelerate(DissolveTiming.linearProgress(index: i, clock: clock))
                                }
                            }
                        }
                    }
                    .scrollDisabled(true)
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { w in if w > 0 { listWidth = w } }
                }
                .onAppear { startDissolve() }
            } else if library.trashItems.isEmpty && pendingDelete == nil {
                emptyState
            } else {
                TrashGridCollectionView(
                    items: pendingDelete?.items ?? library.trashItems,
                    selecting: selecting,
                    selected: selected,
                    totalBytes: pendingDelete?.bytes ?? library.trashBytes,
                    oldestDays: oldestDaysNow(),
                    selectedBytes: selectedBytes,
                    locale: loc,
                    pipeline: library.thumbnails,
                    bytesProvider: { bytes(of: $0) },
                    onTapItem: { item in
                        if selecting {
                            if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
                        } else {
                            previewId = item.id
                        }
                    },
                    onEmptyTap: {
                        if pendingDelete == nil { confirmEmpty = true }
                    }
                )
                .ignoresSafeArea(.container, edges: .bottom)
            }

            VStack(spacing: 0) {
                header
                Spacer()
            }

            if let toast {
                VStack {
                    Spacer()
                    DarkToast(text: toast)
                        .padding(.bottom, DarkroomDimens.navBottom + DarkroomDimens.navHeight + 16)
                        .transition(.opacity)
                }
                .allowsHitTesting(false)
            }

            if confirmEmpty {
                deleteDialog
                    .transition(.opacity)
                    .zIndex(4)
            }
        }
        .animation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short), value: confirmEmpty)
        .animation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short), value: toast)
        .environment(\.locale, loc)
        .onChange(of: confirmEmpty) { _, v in dimTabBar = v }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessTrashPreview)) { _ in
            if previewId == nil { previewId = library.trashItems.first?.id }
        }
        .onAppear { if ScrollProbe.enabled { ScrollProbe.shared.page = "trash" } }
        .onDisappear { dimTabBar = false }
        .fullScreenCover(isPresented: Binding(
            get: { previewId != nil },
            set: { if !$0 { previewId = nil } }
        )) {
            if let previewId {
                TrashPreview(startId: previewId, muted: $playbackMuted, onRestored: { showToast(L10n.string("msg_restored", locale: loc)) }) { self.previewId = nil }
                    .environmentObject(library)
                    .environmentObject(language)
                    .reduceMotionGate()
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            }
        }
    }

    private var selectedBytes: Int64 {
        selected.reduce(Int64(0)) { $0 + bytes(of: $1) }
    }

    /// 最早一张移入已满 30 个 24 小时日时返回整天数，否则 nil（副文字保持 free_sub）。
    private func oldestDaysNow() -> Int? {
        oldestDays(of: library.trashItems)
    }

    private func oldestDays(of items: [PhotoItem]) -> Int? {
        let moved = items.compactMap { library.trash.entry($0.id)?.movedToTrashAt }
        return TrashAge.wholeDays(movedAtMs: moved.map(TrashAge.unixMillis), nowMs: TrashAge.unixMillis(Date().timeIntervalSince1970))
    }

    /// 即将删除的条目大小之和。未知或非正记 0；合计为 0 不写入累计。
    private func recordedBytes(of items: [PhotoItem]) -> Int64 {
        items.reduce(0) { $0 + max(0, library.trash.entry($1.id)?.byteSize ?? 0) }
    }

    /// 垃圾桶照片的大小：移入时已经记在 TrashEntry 里，不在主线程逐格查 PHAssetResource。
    private func bytes(of id: String) -> Int64 {
        if let b = dissolveSizes[id] { return b } // 溶解期间条目已删：用清空前记下的大小，不在逐帧时钟里查 PHAssetResource
        if let b = library.trash.entry(id)?.byteSize, b > 0 { return b }
        return AssetSizeCache.shared.cachedBytes(id) ?? 0 // 批量移入后台补大小期间：不在 body 里同步查
    }

    // MARK: - Header

    private var header: some View {
        Group {
            if selecting {
                // .sel-bar：取消 / 已选择 N 项 / 移出垃圾桶，底对齐
                ZStack(alignment: .bottom) {
                    HStack {
                        TrayTextButton(title: L10n.string("cancel", locale: loc)) { selecting = false; selected = [] }
                            .accessibilityIdentifier("trash-select-cancel")
                        Spacer()
                        PaperButton(title: L10n.string("restore_from_trash", locale: loc), enabled: !selected.isEmpty) {
                            let n = library.restoreFromTrash(ids: Array(selected), showUndo: false)
                            selecting = false
                            selected = []
                            if n > 0 { showToast(L10n.string("msg_restored", locale: loc)) }
                        }
                        .accessibilityIdentifier("trash-batch-restore")
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        let prefix = L10n.string("trash_selected_prefix", locale: loc)
                        let suffix = L10n.string("trash_selected_suffix", locale: loc)
                        if !prefix.isEmpty { Text(prefix).font(Font.custom("NotoSansSC-Medium", size: 15)) }
                        Text("\(selected.count)").font(DarkroomFonts.schibsted(size: 17, weight: .semibold))
                        if !suffix.isEmpty { Text(suffix).font(Font.custom("NotoSansSC-Medium", size: 15)) }
                    }
                    .foregroundStyle(DarkroomColors.paper)
                    .frame(height: 36)
                }
                .padding(.horizontal, DarkroomDimens.screenSide)
                .padding(.bottom, DarkroomDimens.headerBottomPadding)
                .frame(height: DarkroomDimens.headerHeight, alignment: .bottom)
            } else {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.string("tab_trash", locale: loc))
                            .font(DarkroomType.eyebrow)
                            .tracking(1.4)
                            .foregroundStyle(DarkroomColors.dim)
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            OdometerText(value: Int64(library.trashCount), text: "\(library.trashCount)", font: DarkroomType.countBig, color: DarkroomColors.paper)
                            if language.isChineseUI {
                                Text(L10n.string("header_count_unit", locale: loc))
                                    .font(DarkroomType.countUnit)
                                    .foregroundStyle(DarkroomColors.dim)
                            }
                        }
                    }
                    Spacer()
                    if !library.trashItems.isEmpty && dissolve == nil {
                        TrayTextButton(title: L10n.string("trash_select", locale: loc)) { selecting = true }
                            .accessibilityIdentifier("trash-select")
                    }
                }
                .padding(.horizontal, DarkroomDimens.screenSide)
                .padding(.bottom, DarkroomDimens.headerBottomPadding)
                .frame(height: DarkroomDimens.headerHeight, alignment: .bottom)
            }
        }
        .background(DarkroomColors.room)
    }

    // MARK: - Grid header

    /// .free：「清空后可释放」卡片（外边距 4 16 16）。满 30 个 24 小时日才换副文字。
    private func freeCard(bytes: Int64, enabled: Bool, oldestDays: Int?) -> some View {
        TrashFreeCardView(bytes: bytes, enabled: enabled, oldestDays: oldestDays, locale: loc) {
            if enabled { confirmEmpty = true }
        }
    }

    // MARK: - Grid

    private func trashRow(items: [PhotoItem], start: Int, dissolve: ((Int) -> Double)?) -> some View {
        let cols = 3
        let gap = DarkroomDimens.gridGap
        let side = (max(listWidth, 1) - gap * CGFloat(cols - 1)) / CGFloat(cols)
        let end = min(start + cols, items.count)
        return HStack(spacing: gap) {
            ForEach(Array(items[start..<end].enumerated()), id: \.element.id) { offset, item in
                let i = start + offset
                let isSel = selecting && selected.contains(item.id)
                let p = dissolve?(i) ?? 0
                TrashDissolveTile(
                    item: item,
                    side: side,
                    pipeline: library.thumbnails,
                    sizeText: (item.isVideo || isSel) ? nil : FileSizeFormatter.format(bytes(of: item.id))
                )
                // 溶解：缩到 0.86、模糊 5、亮度 0.3、透明
                .overlay(Color.black.opacity(0.7 * p))
                .blur(radius: 5 * p)
                .scaleEffect(1 - 0.14 * p)
                .opacity(1 - p)
                .accessibilityIdentifier("trash-tile-\(i)")
            }
            if end - start < cols { Spacer(minLength: 0) }
        }
        .padding(.bottom, gap)
    }

    // MARK: - Empty

    /// 空状态：一叠相纸往下沉（垃圾桶图标、箭头朝下）+「垃圾桶是空的」+ 正文（刚清空时为「刚刚释放了 X」）。
    /// 累计已释放 > 0 时，正文下方再加一行（只在这一页）。
    private var emptyState: some View {
        let bodyText = justFreed.map { L10n.format("trash_just_freed", locale: loc, FileSizeFormatter.format($0)) }
            ?? L10n.string("trash_empty_body", locale: loc)
        return EmptyStateArea {
            EmptyPileArt(sink: true).padding(.bottom, 32)
            if freedBytes.total > 0 {
                emptyTitleBodyAndTotal(bodyText)
            } else {
                EmptyTitleAndBody(L10n.string("trash_empty_title", locale: loc), bodyText)
            }
        }
        .opacity(min(1, max(0, emptyRise)))
        .offset(y: 12.6 * (1 - emptyRise))
        .accessibilityIdentifier("trash-empty-state")
    }

    /// 有累计行时自己排正文：间距 8，累计行用 11pt 等宽 faint，下边距仍是 24。
    private func emptyTitleBodyAndTotal(_ bodyText: String) -> some View {
        VStack(spacing: 0) {
            Text(L10n.string("trash_empty_title", locale: loc))
                .font(Font.custom("NotoSansSC-SemiBold", size: 18))
                .foregroundStyle(DarkroomColors.paper)
                .multilineTextAlignment(.center)
                .frame(minHeight: 24)
            Text(bodyText)
                .font(Font.custom("NotoSansSC-Regular", size: 13))
                .lineSpacing(5)
                .foregroundStyle(DarkroomColors.dim)
                .multilineTextAlignment(.center)
                .frame(width: 232)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            Text(TrashCopy.totalFreed(FileSizeFormatter.format(freedBytes.total), locale: loc))
                .font(DarkroomFonts.plexMono(size: 11))
                .foregroundStyle(DarkroomColors.faint)
                .multilineTextAlignment(.center)
                .frame(width: 232)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .accessibilityIdentifier("trash-total-freed")
        }
    }

    // MARK: - Dialog

    /// 永久删除确认：64% 暗幕；托盘色对话框左右 24、垂直居中、圆角 24、0 24 48 黑 50% 投影；
    /// 标题 17/24 600，正文 13/20 dim（数字加粗），按钮 40pt：取消（相纸白字）/ 删除（删除红实心）。
    private var deleteDialog: some View {
        ZStack {
            DarkroomColors.dialogScrim.ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { confirmEmpty = false }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.plural("dialog_delete_title", count: library.trashCount, locale: loc))
                    .font(Font.custom("NotoSansSC-SemiBold", size: 17))
                    .foregroundStyle(DarkroomColors.paper)
                    .fixedSize(horizontal: false, vertical: true)
                dialogBody
                    .padding(.top, 8)
                HStack(spacing: 8) {
                    Spacer()
                    Button { confirmEmpty = false } label: {
                        Text(L10n.string("cancel", locale: loc))
                            .font(Font.custom("NotoSansSC-SemiBold", size: 14))
                            .foregroundStyle(DarkroomColors.paper)
                            .padding(.horizontal, 20).frame(height: 40)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("dialog-cancel")
                    Button { confirmDelete() } label: {
                        Text(L10n.string("delete", locale: loc))
                            .font(Font.custom("NotoSansSC-SemiBold", size: 14))
                            .foregroundStyle(DarkroomColors.onDelete)
                            .padding(.horizontal, 20).frame(height: 40)
                            .background(DarkroomColors.delete, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("dialog-delete")
                }
                .padding(.top, 24)
            }
            .padding(24)
            .background(DarkroomColors.tray, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(DarkroomColors.rim, lineWidth: 1))
            .shadow(color: DarkroomColors.dialogShadow, radius: 24, y: 24)
            .padding(.horizontal, 24)
        }
    }

    private var dialogBody: some View {
        let parts = FileSizeFormatter.parts(library.trashBytes)
        let body = L10n.format("dialog_delete_body", locale: loc, "\u{0}")
        let before = body.components(separatedBy: "\u{0}").first ?? body
        let after = body.components(separatedBy: "\u{0}").dropFirst().joined()
        return (Text(before)
                + Text(parts.number).font(DarkroomFonts.schibsted(size: 13, weight: .semibold)).foregroundColor(DarkroomColors.paper)
                + Text(" " + parts.unit)
                + Text(after))
            .font(Font.custom("NotoSansSC-Regular", size: 13))
            .lineSpacing(7)
            .foregroundStyle(DarkroomColors.dim)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func confirmDelete() {
        confirmEmpty = false
        guard pendingDelete == nil, dissolve == nil else { return }
        let items = library.trashItems
        let bytes = library.trashBytes
        let freedNow = recordedBytes(of: items)
        let oldest = oldestDays(of: items)
        dissolveSizes = Dictionary(items.map { ($0.id, self.bytes(of: $0.id)) }, uniquingKeysWith: { a, _ in a })
        pendingDelete = (items, bytes)
        let store = freedBytes
        Task {
            do {
                try await library.permanentlyDelete(ids: items.map(\.id))
                if freedNow > 0 { store.add(freedNow) }
                pendingDelete = nil
                justFreed = bytes
                if reduceMotion {
                    emptyRise = 1
                    showToast(L10n.string("msg_trash_emptied", locale: loc))
                } else {
                    emptyRise = 0
                    dissolve = DissolveRun(items: items, bytes: bytes, oldestDays: oldest, start: nil)
                }
            } catch {
                pendingDelete = nil
                dissolveSizes = [:]
                // 系统确认框点了「不允许」→「操作已取消」；其他失败（没权限、照片已不可用…）→「操作失败」
                showToast(L10n.string(PhotoLibraryService.deleteFailureKey(error), locale: loc))
            }
        }
    }

    /// 溶解首帧上屏后起表；字数归零与时钟都走完才收起，再隔 160ms 空状态舒缓弹簧升起（Android DISSOLVE_EMPTY_DELAY）。
    private func startDissolve() {
        guard var run = dissolve, run.start == nil else { return }
        let start = Date()
        run.start = start
        dissolve = run
        showToast(L10n.string("msg_trash_emptied", locale: loc))
        DispatchQueue.main.asyncAfter(deadline: .now() + run.total) {
            guard dissolve?.start == start else { return }
            dissolve = nil
            dissolveSizes = [:]
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) {
                withAnimation(reduceMotion ? nil : SpringToken.gentle.animation()) { emptyRise = 1 }
            }
        }
    }

    private func showToast(_ text: String) {
        toastToken += 1
        let token = toastToken
        toast = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { if token == toastToken { toast = nil } }
    }
}

private struct TrashDissolveTile: View {
    let item: PhotoItem
    let side: CGFloat
    let pipeline: ThumbnailPipeline
    let sizeText: String?

    @State private var image: UIImage?
    @State private var token: ThumbnailToken?

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            DarkroomColors.tileFill
            if let shown = image ?? pipeline.cached(id: item.id, side: side, style: .trashed) {
                Image(uiImage: shown)
                    .resizable()
                    .scaledToFill()
                    .frame(width: side, height: side)
                    .clipped()
            }
            if let label = item.isVideo ? ScopeRules.formatDuration(seconds: item.duration) : sizeText {
                Text(label)
                    .font(DarkroomFonts.plexMono(size: 10))
                    .foregroundStyle(DarkroomColors.paper.opacity(item.isVideo ? 1 : 0.88))
                    .padding(6)
            }
        }
        .frame(width: side, height: side)
        .onAppear {
            if let cached = pipeline.cached(id: item.id, side: side, style: .trashed) {
                image = cached
                return
            }
            token = pipeline.request(asset: item.asset, side: side, style: .trashed) { img, _ in
                image = img
            }
        }
        .onDisappear {
            token?.cancel()
            token = nil
        }
    }
}

/// 垃圾桶全屏预览：黑底。照片 Fit 在状态栏下 144pt 的正方形里（宽 = 屏宽），不去色。
/// 文件信息在方框下 16pt，不压在照片上。顶栏玻璃返回 +「垃圾桶 / i / N」。
/// 距底 100pt 是提示，距底 40pt 是 52pt 相纸白胶囊「移出垃圾桶」。左右滑切换，双击放大。
struct TrashPreview: View {
    let startId: String
    @Binding var muted: Bool
    var onRestored: () -> Void = {}
    var onClose: () -> Void
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var playback = PlaybackEngine()
    @State private var index: Int = 0
    @State private var dx: CGFloat = 0
    @State private var zoom: CGFloat = 1
    @State private var images: [String: UIImage] = [:]
    @State private var zoomed: [String: UIImage] = [:]
    @State private var tokens: [String: ThumbnailToken] = [:]
    @State private var zoomTokens: [String: ThumbnailToken] = [:]
    @State private var loadedMetaInfo: AssetSizeCache.Info?
    @State private var loadedMetaId = ""
    @State private var didInit = false
    @State private var transportShown = false
    @State private var playbackPinnedId: String?
    @State private var playbackUnsettled = false
    @State private var playbackHoldToken = 0
    @State private var dragOpen = false
    @State private var tapTask: Task<Void, Never>?
    @GestureState private var dragLive = false

    private var items: [PhotoItem] { library.trashItems }
    private var loc: Locale { language.effectiveLocale }
    private var currentItem: PhotoItem? {
        guard let i = TrashPreviewWindow.clampedIndex(index, count: items.count) else { return nil }
        return items[i]
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            GeometryReader { geo in
                let pageW = geo.size.width
                let pageH = geo.size.height
                // 阅读器已经在安全区里，144 是状态栏之下的距离，不要再加一遍 inset。
                let well = TrashPreviewWell.box(width: pageW)
                ZStack(alignment: .top) {
                    ZStack {
                        // 列表被外部一次删短时，先夹到有效下标再取邻页。不能用旧 index 拼闭区间，onChange 来不及挡住这次 body。
                        let shown = TrashPreviewWindow.clampedIndex(index, count: items.count)
                        ForEach(TrashPreviewWindow.neighbors(index: index, count: items.count), id: \.self) { i in
                            photo(items[i], width: well.size, height: well.size)
                                .scaleEffect(i == shown ? zoom : 1)
                                .offset(x: CGFloat(i - (shown ?? 0)) * pageW + dx)
                        }
                    }
                    .frame(width: pageW, height: well.size)
                    .clipped()
                    .contentShape(Rectangle())
                    .gesture(drag(width: pageW).updating($dragLive) { _, live, _ in live = true })
                    .onChange(of: dragLive) { _, live in
                        // 系统打断手势（不会有 onEnded）：拖了一半的照片弹回
                        if !live {
                            DispatchQueue.main.async {
                                guard dragOpen else { return }
                                dragOpen = false
                                if dx != 0 { withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { dx = 0 } }
                                releasePlayback(after: DarkroomMotion.long)
                            }
                        }
                    }
                    .onTapGesture(count: 2) {
                        tapTask?.cancel()
                        withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { zoom = zoom > 1.01 ? 1 : 2 }
                    }
                    .onTapGesture(count: 1) { scheduleTap() }
                    .padding(.top, well.top)
                    if let item = currentItem {
                        let decision = playbackChrome(for: item)
                        PlaybackCenterButton(
                            visible: decision.showCenter,
                            showPause: decision.showPauseIcon,
                            label: decision.showPauseIcon ? PlaybackCopy.pause(zh: language.isChineseUI) : PlaybackCopy.play(zh: language.isChineseUI)
                        ) {
                            playback.togglePlay()
                            transportShown = false
                        }
                        .position(x: pageW / 2, y: well.top + well.size / 2)
                    }
                    topBar
                        .padding(.top, 12)
                        .padding(.horizontal, 12)
                    if let shown = TrashPreviewWindow.clampedIndex(index, count: items.count) {
                        let item = items[shown]
                        let decision = playbackChrome(for: item)
                        VStack(spacing: 0) {
                            Color.clear
                                .frame(height: well.metaTop)
                                .allowsHitTesting(false)
                            VStack(spacing: 0) {
                                if decision.showProgress {
                                    PlaybackProgressRow(engine: playback, muted: muted, zh: language.isChineseUI, onMute: {
                                        muted.toggle()
                                    }, onSeeked: {
                                        transportShown = false
                                    })
                                    .padding(.bottom, DarkroomDimens.playbackGap)
                                }
                                meta(item)
                                    .allowsHitTesting(false)
                            }
                            .padding(.horizontal, TrashPreviewWell.side)
                        }
                        .frame(maxWidth: .infinity, alignment: .top)
                        Text(L10n.format("trash_restore_hint", locale: loc, cleanDate(item.creationDate)))
                            .font(Font.custom("NotoSansSC-Regular", size: 11))
                            .foregroundStyle(DarkroomColors.faint)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, TrashPreviewWell.side)
                            .padding(.bottom, TrashPreviewWell.hintBottom)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                            .allowsHitTesting(false)
                    }
                }
                .frame(width: pageW, height: pageH)
                .overlay(alignment: .bottom) {
                    Button { restore() } label: {
                        Text(L10n.string("restore_from_trash", locale: loc))
                            .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                            .foregroundStyle(DarkroomColors.room)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(DarkroomColors.paper, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("trash-preview-restore")
                    .padding(.horizontal, TrashPreviewWell.side)
                    .padding(.bottom, TrashPreviewWell.actionBottom)
                }
            }
        }
        .environment(\.locale, loc)
        .onAppear {
            if !didInit { index = items.firstIndex { $0.id == startId } ?? 0; didInit = true }
            load()
            loadCurrentPlayback()
        }
        .onDisappear {
            tapTask?.cancel()
            playbackHoldToken += 1
            playback.release()
            cancelLoads()
        }
        .onChange(of: index) { _, _ in
            transportShown = false
            load()
            if playbackSettled { loadCurrentPlayback() }
        }
        .onChange(of: zoom > 1.01) { _, z in
            guard z, let item = currentItem else { return }
            loadZoom(for: item)
        }
        .task(id: currentItem?.id ?? "") {
            await loadCurrentMeta()
        }
        .onChange(of: muted) { _, value in playback.applyMute(remembered: value) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { playback.noteBackground() }
            else if phase == .active { playback.noteActive() }
        }
        .onChange(of: items.count) { _, n in if n == 0 { onClose() } else if index >= n { index = n - 1 } }
        .background {
            PlaybackAutoplayGate(
                engine: playback,
                settled: playbackSettled && playbackHostPhotoId == currentItem?.id,
                animationsEnabled: !reduceMotion
            )
        }
    }

    private var topBar: some View {
        ZStack {
            HStack {
                GlassCircleButton(icon: .back, action: onClose)
                    .accessibilityLabel(Text(L10n.string("cd_back", locale: loc)))
                    .accessibilityIdentifier("trash-preview-back")
                Spacer()
                if let photo = currentItem, isCurrentMotion(photo) {
                    PlaybackMotionPill(
                        title: PlaybackCopy.motion(zh: language.isChineseUI),
                        label: PlaybackCopy.replay(zh: language.isChineseUI)
                    ) {
                        playback.replayMotion()
                    }
                }
            }
            VStack(spacing: 0) {
                Text(L10n.string("tab_trash", locale: loc))
                    .font(Font.custom("NotoSansSC-Medium", size: 15))
                    .foregroundStyle(DarkroomColors.paper)
                    .frame(height: 20)
                Text(items.isEmpty ? "0 / 0" : "\((TrashPreviewWindow.clampedIndex(index, count: items.count) ?? 0) + 1) / \(items.count)")
                    .font(DarkroomFonts.plexMono(size: 10.5))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(height: 16)
            }
            .allowsHitTesting(false)
        }
        .frame(height: 40)
    }

    /// 正方形槽里 aspect fit，保持原色。槽外的黑是全屏底，不是另一层裁框。
    private func photo(_ item: PhotoItem, width: CGFloat, height: CGFloat) -> some View {
        let shown = zoom > 1.01 ? (zoomed[item.id] ?? images[item.id]) : images[item.id]
        return ZStack {
            if let img = shown {
                Image(uiImage: img).resizable().scaledToFit()
                    .accessibilityHidden(true)
            }
            if item.id == playbackHostPhotoId {
                PlaybackSurface(
                    player: playback.player,
                    fadeOut: playback.loaded?.kind == .motion && playback.ended,
                    animateFade: !reduceMotion
                )
                .allowsHitTesting(false)
            }
        }
        .frame(width: width, height: height)
    }

    private func meta(_ item: PhotoItem) -> some View {
        // 文件名 / 大小只读内存缓存，未命中由 `.task` 在后台加载，绝不在 body 同步查 PHAssetResource
        let cached = AssetSizeCache.shared.cachedInfo(item.id) ?? (loadedMetaId == item.id ? loadedMetaInfo : nil)
        let name = cached?.name ?? ""
        let bytes = library.trash.entry(item.id)?.byteSize ?? 0
        let sz = bytes > 0 ? bytes : (cached?.bytes ?? 0)
        let size = sz > 0 ? " · " + FileSizeFormatter.format(sz) : ""
        var second: [String] = [L10n.format("trash_taken", locale: loc, cleanDate(item.creationDate))]
        if let e = library.trash.entry(item.id) {
            let days = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: Date(timeIntervalSince1970: e.movedToTrashAt)), to: Calendar.current.startOfDay(for: Date())).day ?? 0
            second.append(days == 0 ? L10n.string("trash_moved_today", locale: loc) : L10n.plural("trash_moved_days", count: days, locale: loc))
        }
        return Text(name + size + "\n" + second.joined(separator: " · "))
            .font(DarkroomFonts.plexMono(size: 10.5))
            .lineSpacing(5.5)
            .foregroundStyle(DarkroomColors.dim)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    private func cleanDate(_ d: Date) -> String {
        if DayKeyRule.isUnknown(d) { return L10n.string("date_unknown", locale: loc) }
        return DateFormatCache.string(d, format: language.isChineseUI ? "M月d日" : "MMM d", locale: loc)
    }

    private func drag(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { v in
                guard zoom <= 1.01 else { return }
                if !dragOpen {
                    dragOpen = true
                    pinPlayback()
                }
                dx = previewDX(v.translation.width, width: width)
            }
            .onEnded { v in
                guard zoom <= 1.01 else { return }
                dragOpen = false
                let shown = TrashPreviewWindow.clampedIndex(index, count: items.count) ?? 0
                dx = previewDX(v.translation.width, width: width)
                let dir = ReviewMath.horizontalRelease(dx: dx, vx: v.velocity.width, width: width)
                withAnimation(reduceMotion ? nil : SpringToken.snappy.animation()) {
                    if dir == -1 && shown < items.count - 1 { index = shown + 1 }
                    else if dir == 1 && shown > 0 { index = shown - 1 }
                    dx = 0
                }
                releasePlayback(after: DarkroomMotion.medium)
            }
    }

    /// 用最终 translation 和夹紧后的下标算横向位移，末段越过阈值的快滑不会停在上一次 onChanged。
    private func previewDX(_ raw: CGFloat, width: CGFloat) -> CGFloat {
        let i = TrashPreviewWindow.clampedIndex(index, count: items.count) ?? 0
        let atEdge = items.isEmpty || (raw > 0 && i == 0) || (raw < 0 && i >= items.count - 1)
        return atEdge ? ReviewMath.edgeResistance(raw, length: width * 0.3) : raw
    }

    private func restore() {
        guard let i = TrashPreviewWindow.clampedIndex(index, count: items.count) else { return }
        let id = items[i].id
        if library.restoreFromTrash(ids: [id], showUndo: false) > 0 { onRestored() }
        onClose()
    }

    private func loadCurrentMeta() async {
        guard let item = currentItem else { return }
        if AssetSizeCache.shared.cachedInfo(item.id) != nil {
            if loadedMetaId != item.id {
                loadedMetaInfo = nil
                loadedMetaId = ""
            }
            return
        }
        let info = await AssetSizeCache.shared.loadInfo(for: item.asset)
        guard !Task.isCancelled, currentItem?.id == item.id else { return }
        loadedMetaId = item.id
        loadedMetaInfo = info
    }

    private func load() {
        let prefetch = TrashPreviewWindow.span(index: index, count: items.count, radius: 2)
        AssetSizeCache.shared.prefetch(prefetch.map { items[$0].asset })
        // 只留当前和前后各一张；窗口外还没回来的请求与高清缓存统一离主线程取消并回收
        let near = TrashPreviewWindow.span(index: index, count: items.count, radius: 1)
        let keep = Set(near.map { items[$0].id })
        for (id, tok) in tokens where !keep.contains(id) {
            tok.cancel()
            tokens[id] = nil
        }
        for (id, tok) in zoomTokens where !keep.contains(id) {
            tok.cancel()
            zoomTokens[id] = nil
        }
        for id in Array(images.keys) where !keep.contains(id) { images[id] = nil }
        for id in Array(zoomed.keys) where !keep.contains(id) { zoomed[id] = nil }

        let screen = UIScreen.main.bounds.size
        let scale = UIScreen.main.scale
        let shownIndex = TrashPreviewWindow.clampedIndex(index, count: items.count) ?? index
        for i in near {
            let item = items[i]
            let id = item.id
            if tokens[id] != nil { continue }
            let fit = ReviewMath.fitSize(pixelWidth: item.asset.pixelWidth, pixelHeight: item.asset.pixelHeight, in: screen)
            let px = CGSize(width: (fit.width * scale).rounded(.up), height: (fit.height * scale).rounded(.up))
            let priority: Operation.QueuePriority = (i == shownIndex) ? .veryHigh : .normal
            tokens[id] = library.thumbnails.requestFitImage(
                asset: item.asset,
                maxSidePoints: max(fit.width, fit.height),
                fitPixels: px,
                priority: priority
            ) { img, _ in
                guard tokens[id] != nil else { return }
                let canReplace = images[id].map {
                    img.size.width * img.scale >= $0.size.width * $0.scale
                } ?? true
                if canReplace { images[id] = img }
            }
        }
        if zoom > 1.01, let cur = currentItem {
            loadZoom(for: cur)
        }
    }

    private func loadZoom(for item: PhotoItem) {
        let id = item.id
        guard zoomTokens[id] == nil else { return }
        let side = max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * 2
        zoomTokens[id] = library.thumbnails.requestFitImage(
            asset: item.asset,
            maxSidePoints: side,
            fitPixels: nil,
            priority: .veryHigh
        ) { img, _ in
            guard zoomTokens[id] != nil else { return }
            if let cur = zoomed[id], img.size.width * img.scale <= cur.size.width * cur.scale { return }
            if let base = images[id], img.size.width * img.scale + 0.5 < base.size.width * base.scale { return }
            zoomed[id] = img
        }
    }

    private func cancelLoads() {
        for tok in tokens.values { tok.cancel() }
        for tok in zoomTokens.values { tok.cancel() }
        tokens.removeAll()
        zoomTokens.removeAll()
        images.removeAll()
        zoomed.removeAll()
        EmbeddedMotionCache.cancelPrefetch()
        LiveVideoCache.cancelPrefetch()
    }

    /// 动画事务里 dx 立刻归零，弹簧还在走。停稳看 token，不看 dx。
    private var playbackSettled: Bool { !playbackUnsettled && playbackPinnedId == nil }

    private var playbackHostPhotoId: String? {
        if let playbackPinnedId { return playbackPinnedId }
        guard let photo = currentItem, playback.loaded?.id == photo.id, !playback.loadFailed else { return nil }
        return photo.id
    }

    private func pageIsVideo(_ photo: PhotoItem) -> Bool { photo.isVideo }

    private func isCurrentMotion(_ photo: PhotoItem) -> Bool {
        playback.loaded?.id == photo.id && playback.loaded?.kind == .motion && !playback.loadFailed
    }

    private func playbackChrome(for photo: PhotoItem) -> PlaybackChromeState {
        let identified = playback.loaded?.id == photo.id
        let video = pageIsVideo(photo)
        let failed = identified && playback.loadFailed
        let phase = PlaybackRules.phase(
            started: playback.started, userPaused: playback.userPaused, ended: playback.ended, playing: playback.isPlaying)
        let awaiting = video && !failed && PlaybackRules.awaitingAutoplay(
            animationsEnabled: !reduceMotion,
            settled: playbackSettled && (identified || playback.loaded == nil),
            resumed: playback.resumed,
            started: playback.started,
            userPaused: playback.userPaused,
            ended: playback.ended)
        return PlaybackRules.chrome(
            isVideo: video && (identified || playback.loaded == nil),
            phase: phase,
            gestureActive: playbackUnsettled,
            pinnedOrExiting: playbackPinnedId != nil,
            awaitingAutoplay: awaiting,
            transportShown: transportShown)
    }

    private func loadCurrentPlayback() {
        guard playbackPinnedId == nil else { return }
        if let photo = currentItem {
            playback.ensureLoaded(id: photo.id, asset: photo.asset)
            if let i = TrashPreviewWindow.clampedIndex(index, count: items.count), i + 1 < items.count {
                LiveVideoCache.prefetch(items[i + 1].asset)
                EmbeddedMotionCache.prefetch(items[i + 1].asset)
            }
        } else {
            playback.clear()
        }
        playback.applyMute(remembered: muted)
    }

    private func pinPlayback() {
        playbackHoldToken += 1
        playbackUnsettled = true
        if playbackPinnedId == nil { playbackPinnedId = currentItem?.id }
        playback.pauseForGesture()
        EmbeddedMotionCache.cancelPrefetch()
        LiveVideoCache.cancelPrefetch()
        transportShown = false
    }

    private func releasePlayback(after seconds: Double) {
        playbackHoldToken += 1
        let token = playbackHoldToken
        playbackUnsettled = true
        let delay = reduceMotion ? 0 : seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            guard token == playbackHoldToken else { return }
            playbackUnsettled = false
            playbackPinnedId = nil
            loadCurrentPlayback()
        }
    }

    /// 单击等一下，确认不是双击放大。只有正在播的视频才切换控件。
    private func scheduleTap() {
        tapTask?.cancel()
        guard currentItem != nil else { return }
        tapTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(280))
            guard !Task.isCancelled, let photo = currentItem else { return }
            switch PlaybackRules.trashPhotoTap(
                isVideo: pageIsVideo(photo),
                playing: playback.isPlaying,
                transportShown: transportShown
            ) {
            case .toggleTransport(let show):
                withAnimation(DarkroomMotion.shortCurve) { transportShown = show }
            case nil:
                break
            }
        }
    }
}
