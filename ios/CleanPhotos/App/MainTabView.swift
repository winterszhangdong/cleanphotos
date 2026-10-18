import SwiftUI

enum AppTab: Hashable {
    case photos, favorites, trash
}

struct MainTabView: View {
    @State private var tab: AppTab = .photos
    @State private var reviewLaunch: ReviewLaunch?
    @State private var hideTabBar = false
    @State private var dimTabBar = false
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var shared = PhotoOpenTransition.shared
    /// 共享元素过渡（Android gridRecede / photoEnter / photoExit / gridReturn）
    @State private var clock: SharedClock?
    @State private var flight: SharedFlight?
    @State private var screen: CGSize = .zero
    @State private var safe: EdgeInsets = EdgeInsets()
    @State private var transitionToken = 0
    @State private var seedImage: UIImage?

    /// 网格 / 收藏 / 小结里打开清理页都走这里：先算好共享元素，再挂上清理页。
    private var launchBinding: Binding<ReviewLaunch?> {
        Binding(get: { reviewLaunch }, set: { new in
            if let new { open(new) } else { closeImmediately() }
        })
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            DarkroomColors.room.ignoresSafeArea()

            Group {
                switch tab {
                case .photos:
                    PhotosView(reviewLaunch: launchBinding, hideTabBar: $hideTabBar, dimTabBar: $dimTabBar, onOpenTrash: { tab = .trash })
                case .favorites:
                    FavoritesView(reviewLaunch: launchBinding, onGoReview: { tab = .photos })
                case .trash:
                    TrashView(dimTabBar: $dimTabBar)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .modifier(SharedRecede(role: .grid, clock: clock, open: reviewLaunch != nil))
            // 清理页盖在上面时网格不再接收触摸（网格里有 UIKit 滚动视图与长按识别器，会抢走上层按钮的点按）
            .allowsHitTesting(reviewLaunch == nil)

            if !hideTabBar {
                DarkroomTabBar(selection: $tab, trashCount: library.trashCount)
                    // 菜单打开时导航也压在 48% 暗幕下（DockScrim）
                    .overlay {
                        // 照片页菜单 48%；垃圾桶清空确认对话框 64%（DialogScrim）
                        Capsule().fill(tab == .trash ? DarkroomColors.dialogScrim : DarkroomColors.menuScrim)
                            .opacity(dimTabBar ? 1 : 0)
                            .padding(.bottom, DarkroomDimens.navBottom - 8)
                            .animation(.timingCurve(0.2, 0, 0, 1, duration: DarkroomMotion.short), value: dimTabBar)
                    }
                    .allowsHitTesting(!dimTabBar)
                    .padding(.bottom, 8)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .modifier(SharedRecede(role: .grid, clock: clock, open: reviewLaunch != nil))
            }

        }
        .background {
            GeometryReader { g in
                Color.clear
                    .onAppear { screen = full(g); safe = g.safeAreaInsets }
                    .onChange(of: g.size) { _, _ in screen = full(g); safe = g.safeAreaInsets }
            }
        }
        .animation(DarkroomMotion.menuIn, value: hideTabBar)
        .ignoresSafeArea(.keyboard)
        // 清理页仍用 fullScreenCover 承载（挂在同一层级里时网格的 UIKit 滚动视图会吞掉上层按钮的点按），
        // 但关掉系统上滑动画、背景透明：共享元素飞行 + 淡入淡出在 cover 里画，网格退场在下面同时进行。
        .fullScreenCover(item: $reviewLaunch) { launch in
            ZStack {
                ReviewView(launch: launch, library: library, seedImage: seedImage, onGoTrash: { closeImmediately(); tab = .trash })
                    .environment(\.reviewClose, ReviewCloseAction(handler: { id, img in collapse(photoId: id, image: img) }))
                    .environment(\.reviewSharedEntry, !reduceMotion)
                    .modifier(SharedRecede(role: .review, clock: clock, open: true))
                    .allowsHitTesting(clock == nil)
                if let flight {
                    SharedFlightLayer(flight: flight)
                }
            }
            .environmentObject(library)
            .environmentObject(language)
            .reduceMotionGate()
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .presentationBackground(.clear)
        }
        .task { await ScreenshotHarness.runIfNeeded(tab: $tab, reviewLaunch: launchBinding, library: library) }
        .task {
            // 徽标对照截图：-cpBadgeShot N 把垃圾桶数量调到 N（不足从最前面挪进来、多了按最早放进去的还原；N=0 全部还原），
            // -cpTab trash|photos|favorites 选中项。只改 App 自己的垃圾桶状态，不动系统相册。
            guard AuditHooks.enabled, UserDefaults.standard.object(forKey: "cpBadgeShot") != nil else { return }
            let want = UserDefaults.standard.integer(forKey: "cpBadgeShot")
            for _ in 0..<100 where library.visiblePhotos.isEmpty || library.isPartial { try? await Task.sleep(for: .milliseconds(100)) }
            if library.trashCount < want {
                _ = library.moveToTrash(ids: Array(library.visiblePhotos.prefix(want - library.trashCount).map(\.id)), showUndo: false)
            } else if library.trashCount > want {
                _ = library.restoreFromTrash(ids: Array(library.trashItems.prefix(library.trashCount - want).map(\.id)), showUndo: false)
            }
            switch UserDefaults.standard.string(forKey: "cpTab") {
            case "trash": tab = .trash
            case "favorites": tab = .favorites
            default: tab = .photos
            }
        }
        .task {
            // 性能测量：-cpAutoSwipe N 时启动后直接打开第一张进清理页（见 ReviewView.runAutoSwipe）
            guard PerfAutoSwipe.count > 0 else { return }
            for _ in 0..<100 where library.visiblePhotos.isEmpty || library.isPartial { try? await Task.sleep(for: .milliseconds(100)) }
            if let first = library.visiblePhotos.first { present(ReviewLaunch(scope: .allPhotos, startPhotoId: first.id, dayId: nil)) }
        }
    }

    private func full(_ g: GeometryProxy) -> CGSize {
        CGSize(width: g.size.width + g.safeAreaInsets.leading + g.safeAreaInsets.trailing,
               height: g.size.height + g.safeAreaInsets.top + g.safeAreaInsets.bottom)
    }

    /// 大图在清理页里的位置：与 ReviewView.pageContent 相同的 fit + 上下安全区居中偏移。
    private func fitRect(for id: String) -> CGRect? {
        guard let item = library.item(id: id) else { return nil }
        let box = CGSize(width: screen.width, height: screen.height - safe.top - safe.bottom)
        let fit = ReviewMath.fitSize(pixelWidth: item.asset.pixelWidth, pixelHeight: item.asset.pixelHeight, in: box)
        let cy = screen.height / 2 + (safe.top - safe.bottom) / 2
        return CGRect(x: (screen.width - fit.width) / 2, y: cy - fit.height / 2, width: fit.width, height: fit.height)
    }

    private func open(_ launch: ReviewLaunch) {
        transitionToken += 1
        let token = transitionToken
        let id = launch.startPhotoId
        let thumb = id.flatMap { shared.tileImages[$0] }
        seedImage = thumb
        if reduceMotion {
            clock = nil; flight = nil
            present(launch)
            return
        }
        if let id, let thumb, let from = shared.tileFrames[id], let to = fitRect(for: id) {
            flight = SharedFlight(id: id, kind: .expand, image: thumb, from: from, to: to, start: Date())
            shared.flyingId = id
        } else {
            flight = nil
        }
        clock = SharedClock(kind: .expand, start: flight?.start ?? Date())
        present(launch)
        DispatchQueue.main.asyncAfter(deadline: .now() + PhotoOpenTransition.expandEnd) {
            guard token == transitionToken else { return }
            flight = nil
            clock = nil
            shared.flyingId = nil
        }
    }

    private func collapse(photoId: String?, image: UIImage?) {
        transitionToken += 1
        let token = transitionToken
        if reduceMotion { closeImmediately(); return }
        let now = Date()
        if let id = photoId, let img = image ?? shared.tileImages[id], let from = fitRect(for: id), let to = shared.tileFrames[id] {
            flight = SharedFlight(id: id, kind: .collapse, image: img, from: from, to: to, start: now)
            shared.flyingId = id
        } else {
            flight = nil
        }
        clock = SharedClock(kind: .collapse, start: now)
        DispatchQueue.main.asyncAfter(deadline: .now() + PhotoOpenTransition.collapse) {
            guard token == transitionToken else { return }
            present(nil)
            flight = nil
            clock = nil
            shared.flyingId = nil
        }
    }

    /// 不要系统 cover 的上滑 / 下滑动画。
    private func present(_ launch: ReviewLaunch?) {
        var tx = Transaction(animation: nil)
        tx.disablesAnimations = true
        withTransaction(tx) { reviewLaunch = launch }
    }

    private func closeImmediately() {
        transitionToken += 1
        present(nil)
        clock = nil
        flight = nil
        shared.flyingId = nil
    }
}

struct DarkroomTabBar: View {
    @Binding var selection: AppTab
    var trashCount: Int

    @Environment(\.locale) private var locale

    var body: some View {
        // b-darkroom .nav：三项紧挨（无间距），各 72×36
        HStack(spacing: 0) {
            tabItem(.photos, titleKey: "tab_photos")
            tabItem(.favorites, titleKey: "tab_favorites")
            tabItem(.trash, titleKey: "tab_trash", badge: trashCount)
        }
        // 下划线单独一层（Android AppBottomBar drawBehind 层）：切换时前沿走标准曲线 280ms 先到、后沿用落定弹簧跟上（拉伸滑过去）
        .overlay(alignment: .bottomLeading) { NavIndicator(selection: selection) }
        .padding(DarkroomDimens.navPadding)
        .frame(height: DarkroomDimens.navHeight)
        .background {
            Capsule()
                .fill(DarkroomColors.navGlass)
                .overlay(Capsule().strokeBorder(DarkroomColors.navRim, lineWidth: 1))
                .shadow(color: DarkroomColors.navShadow, radius: 16, y: 12)
                .background(.ultraThinMaterial, in: Capsule())
        }
        .padding(.bottom, DarkroomDimens.navBottom - 8)
    }

    /// .ni（= Android AppBottomBar NavItem）：72×36、底部内边距 2、标签与徽标同一行居中、间距 4；
    /// 选中下划线 16×2、圆角 1、离项底 3。徽标 .nav .badge：高 16、最小宽 16、左右 4、圆角 8、相纸白 10% 底、
    /// Plex Mono 10 / 行高 16、dim 色、无描边 —— 跟在标签后面，不是右上角角标。
    @ViewBuilder
    private func tabItem(_ tab: AppTab, titleKey: String, badge: Int = 0) -> some View {
        let on = selection == tab
        Button { selection = tab } label: {
            HStack(alignment: .center, spacing: 4) {
                (PerfLegacy.on ? Text(LocalizedStringKey(titleKey)) : Text(L10n.string(titleKey, locale: locale)))
                    .font(DarkroomType.navLabel)
                    .fontWeight(on ? .medium : .regular)
                    .foregroundStyle(on ? DarkroomColors.paper : DarkroomColors.dim)
                    // 文字颜色 280ms 标准曲线渐变（Android animateColorAsState MEDIUM / Standard）
                    .animation(DarkroomMotion.standard, value: on)
                    .lineLimit(1)
                    .fixedSize()
                if badge > 0 {
                    OdometerText(value: Int64(badge), text: "\(badge)", font: DarkroomType.navBadge, color: DarkroomColors.dim)
                        .frame(height: 16)
                        .padding(.horizontal, 4)
                        .frame(minWidth: 16, minHeight: 16)
                        .background(DarkroomColors.badgeFill, in: Capsule())
                        .fixedSize()
                }
            }
            .frame(width: DarkroomDimens.navItemWidth, height: DarkroomDimens.navItemHeight - 2)
            .padding(.bottom, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 选中下划线 16×2、圆角 1、离项底 3。左右两条边各自动画：往右走时右沿（前沿）用标准曲线 280ms、左沿（后沿）用 Settle 弹簧
/// （0.75 / 400），往左反过来 —— 与 Android AppBottomBar 的 left / right 两个 Animatable 一致。中途再切换从当前位置接着走。
private struct NavIndicator: View {
    let selection: AppTab
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var motion: Motion?
    @State private var settled: AppTab?

    private struct Motion: Equatable {
        let fromLeft: CGFloat, fromRight: CGFloat, toLeft: CGFloat, toRight: CGFloat
        let movingRight: Bool
        let start: Date
    }

    private static let leadDuration = DarkroomMotion.medium
    /// Settle 弹簧（ζ 0.75、ω 20）0.6s 后离终点 < 0.01%，停表。
    private static let trailDuration = 0.6

    private static func left(for tab: AppTab) -> CGFloat {
        let i: CGFloat = tab == .photos ? 0 : tab == .favorites ? 1 : 2
        return i * DarkroomDimens.navItemWidth + (DarkroomDimens.navItemWidth - DarkroomDimens.navIndicatorWidth) / 2
    }

    private static func edges(_ m: Motion, at now: Date) -> (CGFloat, CGFloat) {
        let t = now.timeIntervalSince(m.start)
        let lead = CGFloat(CubicBezier.standard(min(1, max(0, t) / leadDuration)))
        let trail = CGFloat(t >= trailDuration ? 1 : SpringToken.settle.value(at: t))
        let qLeft = m.movingRight ? trail : lead
        let qRight = m.movingRight ? lead : trail
        return (m.fromLeft + (m.toLeft - m.fromLeft) * qLeft, m.fromRight + (m.toRight - m.fromRight) * qRight)
    }

    var body: some View {
        TimelineView(.animation(paused: motion == nil)) { tl in
            let (l, r): (CGFloat, CGFloat) = {
                if let m = motion { return Self.edges(m, at: tl.date) }
                let x = Self.left(for: settled ?? selection)
                return (x, x + DarkroomDimens.navIndicatorWidth)
            }()
            RoundedRectangle(cornerRadius: DarkroomDimens.navIndicatorHeight / 2)
                .fill(DarkroomColors.paper)
                .frame(width: max(0, r - l), height: DarkroomDimens.navIndicatorHeight)
                .offset(x: l)
                .padding(.bottom, 3)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: selection) { old, new in
            let now = Date()
            let (curL, curR): (CGFloat, CGFloat) = {
                if let m = motion { return Self.edges(m, at: now) }
                let x = Self.left(for: settled ?? old)
                return (x, x + DarkroomDimens.navIndicatorWidth)
            }()
            settled = new
            if reduceMotion { motion = nil; return }
            let toL = Self.left(for: new)
            let m = Motion(fromLeft: curL, fromRight: curR, toLeft: toL, toRight: toL + DarkroomDimens.navIndicatorWidth,
                           movingRight: toL > curL, start: now)
            motion = m
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.trailDuration + 0.02) {
                if motion == m { motion = nil }
            }
        }
    }
}
