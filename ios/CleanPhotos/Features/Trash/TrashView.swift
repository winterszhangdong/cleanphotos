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
    /// 清空进行中（系统确认框 → 删除完成 → 溶解起播之前）：网格和「可释放」照旧显示要删的这批，不在中间闪一帧空状态。
    @State private var pendingDelete: (items: [PhotoItem], bytes: Int64)?
    /// 网格宽量自列表（横屏左右安全区；竖屏 = 屏宽）。
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width

    init(dimTabBar: Binding<Bool> = .constant(false)) { _dimTabBar = dimTabBar }

    struct DissolveRun: Equatable {
        let items: [PhotoItem]
        let bytes: Int64
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
                        VStack(spacing: 0) {
                            Color.clear.frame(height: DarkroomDimens.headerHeight)
                            // 「可释放」900ms 三次减速滚到 0
                            let p = min(1, t / 0.9)
                            freeCard(bytes: Int64(Double(run.bytes) * pow(1 - p, 3)), enabled: false)
                            // Android TrashDissolveGrid：第 0 项是 header，所以第 i 张从 (i+1)·60ms 起；
                            // 单一时钟走到最后一张可见格子的起点 + 380ms，每格都溶解完才收起（与 Android 修复后一致）
                            let clock = min(t, run.clockEnd)
                            grid(items: run.items) { i in
                                CubicBezier.accelerate(DissolveTiming.linearProgress(index: i, clock: clock))
                            }
                        }
                    }
                    .scrollDisabled(true)
                }
                .onAppear { startDissolve() }
            } else if library.trashItems.isEmpty && pendingDelete == nil {
                emptyState
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        Color.clear.frame(height: DarkroomDimens.headerHeight)
                            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { w in if w > 0 { listWidth = w } }
                        if selecting { selectionSummary } else { freeCard(bytes: pendingDelete?.bytes ?? library.trashBytes, enabled: true) }
                        grid(items: pendingDelete?.items ?? library.trashItems, dissolve: nil)
                        Color.clear.frame(height: DarkroomDimens.navBottom + DarkroomDimens.navHeight + 48)
                    }
                }
                .scrollIndicators(.hidden)
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
        .onDisappear { dimTabBar = false }
        .fullScreenCover(isPresented: Binding(
            get: { previewId != nil },
            set: { if !$0 { previewId = nil } }
        )) {
            if let previewId {
                TrashPreview(startId: previewId, onRestored: { showToast(L10n.string("msg_restored", locale: loc)) }) { self.previewId = nil }
                    .environmentObject(library)
                    .environmentObject(language)
                    .reduceMotionGate()
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            }
        }
    }

    static func splitBytes(_ bytes: Int64, locale: Locale) -> (number: String, unit: String) {
        let s = FileSizeFormatter.format(bytes)
        if let r = s.range(of: " ", options: .backwards) {
            return (String(s[..<r.lowerBound]), String(s[r.upperBound...]))
        }
        return (s, "")
    }

    private var selectedBytes: Int64 {
        selected.reduce(Int64(0)) { $0 + bytes(of: $1) }
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
        .background(DarkroomColors.room.opacity(0.92))
    }

    // MARK: - Grid header

    /// .free：「清空后可释放」卡片（外边距 4 16 16）。
    private func freeCard(bytes: Int64, enabled: Bool) -> some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.string("free_label", locale: loc))
                    .font(Font.custom("NotoSansSC-Regular", size: 12))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(height: 16)
                let parts = Self.splitBytes(bytes, locale: loc)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(parts.number)
                        .font(DarkroomFonts.schibsted(size: 32, weight: .semibold))
                        .tracking(-0.64)
                        .foregroundStyle(DarkroomColors.paper)
                        .monospacedDigit()
                    Text(parts.unit)
                        .font(Font.custom("NotoSansSC-Medium", size: 14))
                        .foregroundStyle(DarkroomColors.dim)
                }
                .frame(height: 36)
                .padding(.top, 4)
                Text(L10n.string("free_sub", locale: loc))
                    .font(Font.custom("NotoSansSC-Regular", size: 11))
                    .foregroundStyle(DarkroomColors.faint)
                    .frame(height: 16)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            DeleteOutlineButton(title: L10n.string("empty_trash", locale: loc)) { if enabled { confirmEmpty = true } }
                .accessibilityIdentifier("trash-empty")
        }
        .padding(16)
        .background(DarkroomColors.tray, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(DarkroomColors.rim, lineWidth: 1))
        .padding(.horizontal, DarkroomDimens.screenSide)
        .padding(.top, 4)
        .padding(.bottom, 16)
    }

    /// .sel-sum：40pt，左「点照片选中，再点取消」，右「<b>9.6 MB</b> · 回到相册」。
    private var selectionSummary: some View {
        HStack {
            Text(L10n.string("sel_sum_hint", locale: loc))
                .font(Font.custom("NotoSansSC-Regular", size: 12))
                .foregroundStyle(DarkroomColors.dim)
            Spacer()
            (Text(FileSizeFormatter.format(selectedBytes))
                .foregroundColor(DarkroomColors.paper)
                .font(DarkroomFonts.plexMono(size: 12, weight: .medium))
             + Text(L10n.string("sel_sum_back", locale: loc))
                .foregroundColor(DarkroomColors.dim)
                .font(DarkroomFonts.plexMono(size: 12)))
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .frame(height: 40 - DarkroomDimens.gridGap)
        .padding(.bottom, DarkroomDimens.gridGap)
    }

    // MARK: - Grid

    private func grid(items: [PhotoItem], dissolve: ((Int) -> Double)?) -> some View {
        let cols = 3
        let gap = DarkroomDimens.gridGap
        let w = listWidth
        let side = (w - gap * CGFloat(cols - 1)) / CGFloat(cols)
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: gap), count: cols), spacing: gap) {
            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                let isSel = selecting && selected.contains(item.id)
                let p = dissolve?(i) ?? 0
                ZStack(alignment: .bottomTrailing) {
                    PhotoTileView(item: item, side: side, develop: false, developDelay: 0, showUnseen: false,
                                  selecting: selecting, selected: isSel)
                        // 3.5：饱和 70%、亮度 86%；选中恢复原色（160ms）
                        .saturation(isSel ? 1 : 0.7)
                        .colorMultiply(Color(white: isSel ? 1 : 0.86))
                        .animation(.timingCurve(0.2, 0, 0, 1, duration: 0.16), value: isSel)
                    if !isSel {
                        Text(FileSizeFormatter.format(bytes(of: item.id)))
                            .font(DarkroomFonts.plexMono(size: 10))
                            .foregroundStyle(DarkroomColors.paper.opacity(0.88))
                            .shadow(color: .black.opacity(0.6), radius: 2, y: 1)
                            .padding(6)
                    }
                }
                .frame(width: side, height: side)
                // 溶解：缩到 0.86、模糊 5、亮度 0.3、透明
                .overlay(Color.black.opacity(0.7 * p))
                .blur(radius: 5 * p)
                .scaleEffect(1 - 0.14 * p)
                .opacity(1 - p)
                .contentShape(Rectangle())
                .onTapGesture {
                    guard dissolve == nil else { return }
                    if selecting {
                        if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
                    } else {
                        previewId = item.id
                    }
                }
                .accessibilityIdentifier("trash-tile-\(i)")
            }
        }
    }

    private func grid(items: [PhotoItem], _ dissolve: @escaping (Int) -> Double) -> some View {
        grid(items: items, dissolve: dissolve)
    }

    // MARK: - Empty

    /// 空状态：一叠相纸往下沉（垃圾桶图标、箭头朝下）+「垃圾桶是空的」+ 正文（刚清空时为「刚刚释放了 X」）。
    private var emptyState: some View {
        EmptyStateArea {
            EmptyPileArt(sink: true).padding(.bottom, 32)
            EmptyTitleAndBody(
                L10n.string("trash_empty_title", locale: loc),
                justFreed.map { L10n.format("trash_just_freed", locale: loc, FileSizeFormatter.format($0)) }
                    ?? L10n.string("trash_empty_body", locale: loc)
            )
        }
        .opacity(min(1, max(0, emptyRise)))
        .offset(y: 12.6 * (1 - emptyRise))
        .accessibilityIdentifier("trash-empty-state")
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
        let parts = Self.splitBytes(library.trashBytes, locale: loc)
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
        dissolveSizes = Dictionary(items.map { ($0.id, self.bytes(of: $0.id)) }, uniquingKeysWith: { a, _ in a })
        pendingDelete = (items, bytes)
        Task {
            do {
                try await library.permanentlyDelete(ids: items.map(\.id))
                pendingDelete = nil
                justFreed = bytes
                if reduceMotion {
                    emptyRise = 1
                    showToast(L10n.string("msg_trash_emptied", locale: loc))
                } else {
                    emptyRise = 0
                    dissolve = DissolveRun(items: items, bytes: bytes, start: nil)
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

/// 垃圾桶全屏预览（mock 12 / Android TrashPreviewOverlay）：暗室底；顶栏玻璃返回 +「垃圾桶 / i / N」；
/// 照片放在状态栏下 144、宽 = 屏宽的正方形里 contain，饱和 70% / 亮度 86%；下方两行等宽元信息；
/// 底部「移出后回到照片页的 9月21日」+ 52pt 相纸白「移出垃圾桶」。左右滑切换，双击放大。
struct TrashPreview: View {
    let startId: String
    var onRestored: () -> Void = {}
    var onClose: () -> Void
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var index: Int = 0
    @State private var dx: CGFloat = 0
    @State private var zoom: CGFloat = 1
    @State private var images: [String: UIImage] = [:]
    @State private var requests: [String: PHImageRequestID] = [:]
    @State private var didInit = false
    @GestureState private var dragLive = false

    private var items: [PhotoItem] { library.trashItems }
    private var loc: Locale { language.effectiveLocale }

    var body: some View {
        GeometryReader { geo in
            // 竖屏：屏宽方框距顶 144（原样）。横屏：屏高放不下屏宽方框 —— 方框按可用高度居中，文件信息和「移出」放右侧一栏
            let land = geo.size.width > geo.size.height
            let w = land ? max(120, geo.size.height - 64 - 12) : geo.size.width
            let column = land ? max(160, (geo.size.width - w) / 2 - 32) : geo.size.width
            ZStack(alignment: .top) {
                DarkroomColors.room.ignoresSafeArea()
                // 照片：三页并排跟手
                ZStack {
                    ForEach(max(0, index - 1)...min(max(0, items.count - 1), index + 1), id: \.self) { i in
                        if items.indices.contains(i) {
                            photo(items[i], side: w)
                                .scaleEffect(i == index ? zoom : 1)
                                .offset(x: CGFloat(i - index) * w + dx)
                        }
                    }
                }
                .frame(width: w, height: w)
                .clipped()
                .padding(.top, land ? 64 : 144)
                .contentShape(Rectangle())
                .gesture(drag(width: w).updating($dragLive) { _, live, _ in live = true })
                .onChange(of: dragLive) { _, live in
                    // 系统打断手势（不会有 onEnded）：拖了一半的照片弹回
                    if !live { DispatchQueue.main.async { if dx != 0 { withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { dx = 0 } } } }
                }
                .onTapGesture(count: 2) {
                    withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) { zoom = zoom > 1.01 ? 1 : 2 }
                }
                if items.indices.contains(index) {
                    if land {
                        meta(items[index])
                            .frame(width: column)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .padding(.trailing, 16)
                            .padding(.top, 64)
                    } else {
                        meta(items[index])
                            .padding(.top, 144 + w + 16)
                    }
                }
                topBar
                    .padding(.top, 12)
                    .padding(.horizontal, 12)
                VStack(spacing: 0) {
                    Spacer()
                    if items.indices.contains(index) {
                        Text(L10n.format("trash_restore_hint", locale: loc, cleanDate(items[index].creationDate)))
                            .font(Font.custom("NotoSansSC-Regular", size: 11))
                            .foregroundStyle(DarkroomColors.faint)
                            .padding(.bottom, 100 - 40 - 52 - 0)
                    }
                    Button { restore() } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.up").font(.system(size: 17, weight: .medium))
                            Text(L10n.string("restore_from_trash", locale: loc))
                        }
                        .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                        .foregroundStyle(DarkroomColors.room)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(DarkroomColors.paper, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("trash-preview-restore")
                    .padding(.horizontal, 24)
                    .padding(.bottom, 40)
                }
                .frame(width: land ? column + 32 : nil)
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .environment(\.locale, loc)
        .onAppear {
            if !didInit { index = items.firstIndex { $0.id == startId } ?? 0; didInit = true }
            load()
        }
        .onChange(of: index) { _, _ in load() }
        .onChange(of: items.count) { _, n in if n == 0 { onClose() } else if index >= n { index = n - 1 } }
    }

    private var topBar: some View {
        ZStack {
            HStack {
                GlassCircleButton(systemName: "chevron.left", action: onClose)
                    .accessibilityLabel(Text(L10n.string("cd_back", locale: loc)))
                    .accessibilityIdentifier("trash-preview-back")
                Spacer()
            }
            VStack(spacing: 0) {
                Text(L10n.string("tab_trash", locale: loc))
                    .font(Font.custom("NotoSansSC-Medium", size: 15))
                    .foregroundStyle(DarkroomColors.paper)
                    .frame(height: 20)
                Text("\(index + 1) / \(items.count)")
                    .font(DarkroomFonts.plexMono(size: 10.5))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(height: 16)
            }
        }
        .frame(height: 40)
    }

    private func photo(_ item: PhotoItem, side: CGFloat) -> some View {
        ZStack {
            if let img = images[item.id] {
                Image(uiImage: img).resizable().scaledToFit()
                    .accessibilityHidden(true)
                    .saturation(0.7)
                    .colorMultiply(Color(white: 0.86))
            }
        }
        .frame(width: side, height: side)
    }

    private func meta(_ item: PhotoItem) -> some View {
        // 文件名 / 大小走 AssetSizeCache（load() 已在后台预取前后几张），不在每次 body 里同步查 PHAssetResource
        let name = AssetSizeCache.shared.name(for: item.asset) ?? ""
        let bytes = library.trash.entry(item.id)?.byteSize ?? 0
        let sz = bytes > 0 ? bytes : (AssetSizeCache.shared.cachedBytes(item.id) ?? 0)
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
                let raw = v.translation.width
                let atEdge = (raw > 0 && index == 0) || (raw < 0 && index >= items.count - 1)
                dx = atEdge ? ReviewMath.edgeResistance(raw, length: width * 0.3) : raw
            }
            .onEnded { v in
                guard zoom <= 1.01 else { return }
                let dir = ReviewMath.horizontalRelease(dx: dx, vx: v.velocity.width, width: width)
                withAnimation(reduceMotion ? nil : SpringToken.snappy.animation()) {
                    if dir == -1 && index < items.count - 1 { index += 1 }
                    else if dir == 1 && index > 0 { index -= 1 }
                    dx = 0
                }
            }
    }

    private func restore() {
        guard items.indices.contains(index) else { return }
        let id = items[index].id
        if library.restoreFromTrash(ids: [id], showUndo: false) > 0 { onRestored() }
        onClose()
    }

    private func load() {
        AssetSizeCache.shared.prefetch((max(0, index - 2)...max(0, index + 2)).compactMap { items.indices.contains($0) ? items[$0].asset : nil })
        // 只留当前和前后各一张（每张全屏图 ~9MB，之前翻过的都一直留着）；窗口外还没回来的请求取消
        let keep = Set((max(0, index - 1)...max(0, index + 1)).compactMap { items.indices.contains($0) ? items[$0].id : nil })
        for (id, req) in requests where !keep.contains(id) { library.cancelThumbnail(req); requests[id] = nil }
        for id in images.keys where !keep.contains(id) { images[id] = nil }
        for i in max(0, index - 1)...max(0, index + 1) where items.indices.contains(i) {
            let item = items[i]
            if images[item.id] != nil || requests[item.id] != nil { continue }
            // 方框按 fill 裁切：短边要铺满屏宽 × scale，所以长边按比例放大请求（否则非方图短边不足，发虚）；
            // 全景图长宽比很大时封顶 4 倍屏宽，免得一张几十 MB
            let w = CGFloat(max(1, item.asset.pixelWidth)), h = CGFloat(max(1, item.asset.pixelHeight))
            let screenW = UIScreen.main.bounds.width
            let side = min(screenW * max(w, h) / min(w, h), screenW * 4)
            let id = item.id
            requests[id] = library.requestFullImage(for: item.asset, maxSide: side) { img in
                Task { @MainActor in
                    guard requests[id] != nil else { return }
                    if let img, (images[id].map { $0.size.width * $0.scale < img.size.width * img.scale } ?? true) { images[id] = img }
                }
            }
        }
    }
}
