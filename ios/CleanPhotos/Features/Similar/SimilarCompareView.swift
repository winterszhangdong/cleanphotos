import Photos
import SwiftUI

struct SimilarCompareView: View {
    let groupKey: String
    let onClose: () -> Void

    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var session = SimilarSession.shared
    @StateObject private var motion: CompareDrag
    /// 当前大图左下角。换张先清掉，读失败或不是动态照片就不写。
    @State private var motionMark: ComparePhotoMark?
    /// 下滑删成之后单独飞走的那一张。当前页已经换成下一张。
    @State private var exits: [CompareExit] = []
    /// 最后一张飞完才离开。飞的时候顶栏和胶片条还按飞之前留着。
    @State private var flyingLast = false
    @State private var lastPin: LastPin?
    @State private var stageSize: CGSize = .zero

    init(groupKey: String, photoId: String, onClose: @escaping () -> Void) {
        self.groupKey = groupKey
        self.onClose = onClose
        let photos = SimilarSession.shared.deck(routeKey: groupKey)?.photos
            ?? SimilarSession.shared.groups.first { $0.key == groupKey }?.photos
            ?? []
        let index = photos.firstIndex { $0.id == photoId } ?? 0
        _motion = StateObject(wrappedValue: CompareDrag(index: index))
    }

    private var zh: Bool { language.isChineseUI }
    private var liveDeck: SimilarCompareDeck? { session.deck(routeKey: groupKey) }
    private var livePhotos: [PhotoItem] { liveDeck?.photos ?? [] }
    /// 飞最后一张时，会话已经空了，顶栏和胶片条仍用飞之前的那一页。
    private var presentedPhotos: [PhotoItem] {
        if let lastPin { return lastPin.photos }
        return livePhotos
    }
    private var heroPhotos: [PhotoItem] { flyingLast ? [] : livePhotos }
    private var shownMarks: [String: SimilarMark] {
        if let lastPin { return lastPin.marks }
        return liveDeck?.marks ?? [:]
    }

    var body: some View {
        GeometryReader { proxy in
            let insets = proxy.safeAreaInsets
            VStack(spacing: 0) {
                header
                    .padding(.top, insets.top)
                stage
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                filmstrip
                    .padding(.bottom, 8)
                if let banner = trashUndo {
                    UndoCapsule(banner: banner, locale: language.effectiveLocale) { library.undoLast() }
                        .padding(.horizontal, 16)
                        .padding(.bottom, insets.bottom + 12)
                } else if let deck = liveDeck, deck.onList {
                    SimilarCommitButton(
                        title: SimilarCopy.commit(removeCount: removeCount, zh: zh),
                        enabled: !session.moving && !deck.photos.isEmpty
                    ) {
                        if session.commit(groupKey: deck.sourceKey) { onClose() }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, insets.bottom + 12)
                } else {
                    Color.clear.frame(height: insets.bottom)
                }
            }
            .background(DarkroomColors.room)
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .onChange(of: livePhotos.map(\.id)) { old, new in
            if new.isEmpty {
                leaveIfGone()
                return
            }
            guard !flyingLast else { return }
            let current = old.indices.contains(motion.index) ? old[motion.index] : nil
            if let current, let idx = new.firstIndex(of: current) {
                if idx != motion.index { motion.jump(to: idx) }
            } else {
                motion.clampIndex(new.count)
            }
        }
        .onChange(of: session.phase) { _, phase in
            if phase == .ready { leaveIfGone() }
        }
    }

    /// 扫描结束且这一张确实不在了才离开。最后一张还在飞时不离开。比对中不因为暂时没有组而返回。
    private func leaveIfGone() {
        guard !flyingLast, session.phase == .ready, liveDeck == nil else { return }
        onClose()
    }

    /// 只有移进垃圾桶的撤回才占掉底部胶囊。收藏撤回不属于这一页。
    private var trashUndo: UndoBanner? {
        guard let banner = library.undoBanner, !banner.single else { return nil }
        if case .movedToTrash = banner.kind { return banner }
        return nil
    }

    private var removeCount: Int {
        guard let deck = liveDeck else { return 0 }
        return deck.photos.filter { deck.marks[$0.id] == .remove }.count
    }

    private var header: some View {
        ZStack {
            HStack {
                GlassCircleButton(systemName: "chevron.left", action: onClose)
                    .accessibilityLabel(Text(L10n.string("cd_back", locale: language.effectiveLocale)))
                Spacer()
            }
            VStack(spacing: 0) {
                Text(SimilarCopy.position(index: displayIndex + 1, count: max(presentedPhotos.count, 1)))
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .foregroundStyle(DarkroomColors.paper)
                Text(caption.text)
                    .font(Font.custom("NotoSansSC-Regular", size: 13))
                    .foregroundStyle(caption.remove ? DarkroomColors.fade : DarkroomColors.dim)
                    .frame(height: 20)
                    .opacity(caption.text.isEmpty ? 0 : 1)
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 56)
    }

    private var displayIndex: Int {
        let count = presentedPhotos.count
        guard count > 0 else { return 0 }
        let raw = flyingLast ? (lastPin?.index ?? 0) : motion.index
        return min(max(0, raw), count - 1)
    }

    private var caption: (text: String, remove: Bool) {
        let photos = presentedPhotos
        guard photos.indices.contains(displayIndex) else { return ("", false) }
        let photo = photos[displayIndex]
        let mark = shownMarks[photo.id] ?? .keep
        if mark == .remove { return (SimilarCopy.captionRemove(zh: zh), true) }
        let recommendedId = lastPin?.recommendedId ?? liveDeck?.recommendedId ?? ""
        let sharpness = lastPin?.sharpness ?? liveDeck?.sharpness ?? [:]
        let recommended = photo.id == recommendedId && sharpness[photo.id] != nil && mark == .keep
        if recommended { return (SimilarCopy.recommended(zh: zh), false) }
        return ("", false)
    }

    private var stage: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let height = geo.size.height
            ZStack {
                heroPages(width: width, height: height)
                exitPages(width: width, height: height)
            }
            .frame(width: width, height: geo.size.height)
            .clipped()
            .contentShape(Rectangle())
            .gesture(drag(in: geo.size))
            .simultaneousGesture(pinch)
            .onTapGesture(count: 2) { toggleZoom() }
            .overlay { cue.allowsHitTesting(false) }
            .overlay { compareMark(in: geo.size).allowsHitTesting(false) }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(currentLabel))
            .accessibilityAddTraits(.isImage)
            .accessibilityAction(named: Text(SimilarCopy.actionKeep(zh: zh))) {
                _ = applyVertical(up: true, stage: stageSize)
            }
            .accessibilityAction(named: Text(L10n.string("action_move_to_trash", locale: language.effectiveLocale))) {
                _ = applyVertical(up: false, stage: stageSize)
            }
            .task(id: currentPhotoId) { await loadMotionMark() }
            .onAppear { stageSize = geo.size }
            .onChange(of: geo.size) { _, newSize in
                if stageSize != newSize { stageSize = newSize }
            }
        }
    }

    private func heroPages(width: CGFloat, height: CGFloat) -> some View {
        let current = displayIndex
        let photos = heroPhotos
        return ZStack {
            ForEach(Array(photos.enumerated()), id: \.element.id) { index, photo in
                let slot = index - current
                if abs(slot) <= 1 {
                    SimilarFitPhoto(item: photo)
                        .frame(width: width, height: height)
                        .scaleEffect(index == current ? motion.zoom : 1)
                        .offset(index == current ? motion.zoomOffset : .zero)
                        .offset(
                            x: CGFloat(slot) * width + (index == current ? motion.dx : 0),
                            y: index == current ? motion.dy : 0
                        )
                        .colorMultiply(Color(white: pageBrightness(index: index, width: width)))
                }
            }
        }
    }

    @ViewBuilder
    private func exitPages(width: CGFloat, height: CGFloat) -> some View {
        if !exits.isEmpty {
            TimelineView(.animation) { timeline in
                ZStack {
                    ForEach(exits) { exit in
                        CompareExitPhoto(
                            item: exit.photo,
                            flight: exit.flight,
                            zoomOffset: exit.zoomOffset,
                            now: timeline.date
                        )
                        .frame(width: width, height: height)
                    }
                }
            }
            .allowsHitTesting(false)
        }
    }

    /// 不跟缩放、不去色：标在未缩放的 fit 矩形上，不放进照片那一层。
    @ViewBuilder
    private func compareMark(in box: CGSize) -> some View {
        if let mark = motionMark, mark.id == currentPhotoId, heroPhotos.indices.contains(displayIndex) {
            let photo = heroPhotos[displayIndex]
            let fitted = Self.fitSize(pixelWidth: photo.asset.pixelWidth, pixelHeight: photo.asset.pixelHeight, box: box)
            CompareMotionMark(raw: mark.raw, title: mark.title)
                .padding(DarkroomDimens.screenSide)
                .frame(width: fitted.width, height: fitted.height, alignment: .bottomLeading)
                .frame(width: box.width, height: box.height)
        }
    }

    private static func fitSize(pixelWidth: Int, pixelHeight: Int, box: CGSize) -> CGSize {
        guard pixelWidth > 0, pixelHeight > 0, box.width > 0, box.height > 0 else { return .zero }
        let scale = min(box.width / CGFloat(pixelWidth), box.height / CGFloat(pixelHeight))
        return CGSize(width: CGFloat(pixelWidth) * scale, height: CGFloat(pixelHeight) * scale)
    }

    private var currentPhotoId: String {
        guard heroPhotos.indices.contains(displayIndex) else { return "" }
        return heroPhotos[displayIndex].id
    }

    private func loadMotionMark() async {
        motionMark = nil
        guard heroPhotos.indices.contains(displayIndex) else { return }
        let photo = heroPhotos[displayIndex]
        let id = photo.id
        let asset = photo.asset
        async let raw = Self.rawFile(asset)
        let motion: Bool
        if asset.mediaSubtypes.contains(.photoLive) {
            motion = true
        } else if asset.mediaType == .image {
            do {
                motion = try await EmbeddedMotionCache.locate(asset) != nil
            } catch is CancellationError {
                return
            } catch {
                motion = false
            }
        } else {
            motion = false
        }
        let isRaw = await raw
        guard !Task.isCancelled, currentPhotoId == id else { return }
        guard motion else { return }
        motionMark = ComparePhotoMark(id: id, raw: isRaw, title: PlaybackCopy.motion(zh: zh))
    }

    private static func rawFile(_ asset: PHAsset) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            DispatchQueue.global(qos: .utility).async {
                let name = AssetSizeCache.shared.info(for: asset).originalFilename
                cont.resume(returning: ScopeRules.isRawFileName(name))
            }
        }
    }

    private var filmstrip: some View {
        SimilarFilmstrip(
            photos: presentedPhotos,
            marks: shownMarks,
            index: displayIndex,
            zh: zh,
            locale: language.effectiveLocale
        ) { index in
            guard !flyingLast else { return }
            motion.jump(to: index)
        }
    }

    private var cue: some View {
        Group {
            if motion.showCue {
                SimilarCue(up: motion.cueUp, past: motion.cuePast, progress: motion.cueProgress, text: SimilarCopy.cue(up: motion.cueUp, past: motion.cuePast, zh: zh))
                    .opacity(motion.fingerVertical ? 1 : motion.cueAlpha)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: motion.cueUp ? .bottom : .top)
                    .padding(motion.cueUp ? .bottom : .top, 24)
            }
        }
    }

    private var pinch: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                guard !flyingLast else { return }
                motion.pinchChanged(value)
            }
            .onEnded { _ in
                guard !flyingLast else { return }
                motion.pinchEnded(reduce: reduceMotion)
            }
    }

    private func drag(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: ReviewTokens.touchSlop, coordinateSpace: .local)
            .onChanged { value in
                guard !flyingLast else { return }
                motion.changed(value.translation, stage: size, count: livePhotos.count)
            }
            .onEnded { value in
                guard !flyingLast else { return }
                motion.ended(value.translation, velocity: value.velocity, stage: size, count: livePhotos.count, reduce: reduceMotion) { up in
                    applyVertical(up: up, stage: size)
                }
            }
    }

    private func toggleZoom() {
        guard !flyingLast else { return }
        withAnimation(reduceMotion ? nil : SpringToken.settle.animation()) {
            if motion.zoom > 1.01 {
                motion.zoom = 1
                motion.zoomOffset = .zero
            } else {
                motion.zoom = GestureTokensIOS.doubleTapScale
            }
            motion.syncZoomBase()
        }
    }

    /// 上滑只改成留下。下滑过阈值立刻送进垃圾桶：当前页位移归零，下一张已经在，
    /// 这一张沿清理页的下沉路径单独飞出视口。系统减少动态效果时不飞，直接拿掉；
    /// 最后一张飞完才回列表，减少动态效果时直接回去。
    /// 左右滑先被认成翻页、或放大后单指平移时，不会进到这里。
    private func applyVertical(up: Bool, stage: CGSize) -> Bool {
        guard !flyingLast, let deck = liveDeck, livePhotos.indices.contains(displayIndex) else { return false }
        let photo = livePhotos[displayIndex]
        let sourceKey = deck.sourceKey
        if up {
            session.mark(groupKey: sourceKey, photoId: photo.id, mark: .keep)
            return false
        }
        let last = livePhotos.count <= 1
        let canFly = !reduceMotion && stage.height > 0
        if last && canFly {
            flyingLast = true
            lastPin = LastPin(
                photos: livePhotos,
                marks: deck.marks,
                index: displayIndex,
                recommendedId: deck.recommendedId,
                sharpness: deck.sharpness
            )
        }
        let startY = motion.dy
        let startScale = motion.zoom
        let startBrightness = motion.exitStartBrightness
        let zoomOffset = motion.zoomOffset
        let trashed = session.swipeTrash(groupKey: sourceKey, photoId: photo.id)
        guard trashed else {
            flyingLast = false
            lastPin = nil
            return false
        }
        guard canFly else {
            if last { onClose() }
            return true
        }
        let flight = ExitFlight(
            page: .photo(photo),
            kind: .down,
            startY: startY,
            startScale: startScale,
            startSaturation: 1,
            startBrightness: startBrightness,
            rise: 0,
            target: CGPoint(x: 0, y: stage.height),
            start: Date()
        )
        exits.append(CompareExit(photo: photo, flight: flight, zoomOffset: zoomOffset))
        let token = flight.id
        let closeAfter = last
        DispatchQueue.main.asyncAfter(deadline: .now() + ReviewTokens.exitDuration + 0.02) {
            exits.removeAll { $0.flight.id == token }
            guard closeAfter else { return }
            flyingLast = false
            lastPin = nil
            leaveIfGone()
        }
        return true
    }

    private func pageBrightness(index: Int, width: CGFloat) -> CGFloat {
        if index == displayIndex { return motion.photoBrightness }
        return Double(ReviewMath.enteringBrightness(motion.dx, width: width))
    }

    private var currentLabel: String {
        let photos = presentedPhotos
        guard photos.indices.contains(displayIndex) else { return "" }
        let photo = photos[displayIndex]
        let mark = shownMarks[photo.id] ?? .keep
        let pattern = zh ? "M月d日 HH:mm" : "MMM d HH:mm"
        let date = DateFormatCache.string(photo.creationDate, format: pattern, locale: language.effectiveLocale)
        return SimilarCopy.film(date: date, mark: SimilarCopy.mark(mark, zh: zh), zh: zh)
    }
}

private struct LastPin {
    var photos: [PhotoItem]
    var marks: [String: SimilarMark]
    var index: Int
    var recommendedId: String
    var sharpness: [String: Double]
}

private struct CompareExit: Identifiable {
    let photo: PhotoItem
    let flight: ExitFlight
    let zoomOffset: CGSize
    var id: UUID { flight.id }
}

/// 离场只画在大图区域里，胶片条不被盖住。路径用清理页同一条下沉：250ms 加速，缩到 0.05，亮度收到 0.14，饱和收到 0。
private struct CompareExitPhoto: View {
    let item: PhotoItem
    let flight: ExitFlight
    let zoomOffset: CGSize
    let now: Date

    var body: some View {
        let progress = flight.progress(now)
        let pose = flight.pose(progress)
        let remain = CGFloat(1 - progress)
        Group {
            if let image = CompareImageHold.image(item.id) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                SimilarFitPhoto(item: item)
            }
        }
        .scaleEffect(pose.scale)
        .offset(x: pose.x + zoomOffset.width * remain, y: pose.y + zoomOffset.height * remain)
        .saturation(Double(pose.saturation))
        .colorMultiply(Color(white: Double(pose.brightness)))
        .opacity(Double(pose.alpha))
    }
}

/// 对比页大图刚解码的那张。飞出层和原来的那一页不是同一个视图，先拿这里的图，避免飞出去的是一块空白。
private enum CompareImageHold {
    static var images: [String: UIImage] = [:]
    static func image(_ id: String) -> UIImage? { images[id] }
    static func store(_ id: String, _ image: UIImage) { images[id] = image }
}

private struct ComparePhotoMark: Equatable {
    var id: String
    var raw: Bool
    var title: String
}

/// RAW 走等宽；「动态」是汉字，走 Noto。没有衬底。
private struct CompareMotionMark: View {
    let raw: Bool
    let title: String

    var body: some View {
        HStack(spacing: 0) {
            if raw {
                Text("RAW · ")
                    .font(DarkroomFonts.plexMono(size: 10.5))
            }
            Text(title)
                .font(Font.custom("NotoSansSC-Regular", size: 10.5))
        }
        .foregroundStyle(DarkroomColors.paper.opacity(0.88))
        .shadow(color: .black.opacity(0.8), radius: 4, y: 1)
        .accessibilityIdentifier("compare-motion-mark")
    }
}

private struct SimilarFitPhoto: View {
    let item: PhotoItem
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    @State private var requestID: PHImageRequestID?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: geo.size.width, height: geo.size.height)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .task(id: item.id) { load(maxSide: max(geo.size.width, geo.size.height)) }
            .onDisappear { cancel() }
        }
    }

    private func load(maxSide: CGFloat) {
        cancel()
        let side = max(maxSide, 1)
        let id = item.id
        requestID = library.requestFullImage(for: item.asset, maxSide: side) { img in
            Task { @MainActor in
                if let img {
                    CompareImageHold.store(id, img)
                    self.image = img
                }
            }
        }
    }

    private func cancel() {
        if let requestID { library.cancelThumbnail(requestID) }
        requestID = nil
    }
}

struct SimilarFilmstrip: View {
    let photos: [PhotoItem]
    let marks: [String: SimilarMark]
    let index: Int
    let zh: Bool
    let locale: Locale
    let onSelect: (Int) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var allowAnim = false

    var body: some View {
        GeometryReader { geo in
            let offset = Self.offset(index: index, count: photos.count, viewport: geo.size.width)
            HStack(spacing: 8) {
                ForEach(Array(photos.enumerated()), id: \.element.id) { i, photo in
                    SimilarThumbView(
                        item: photo,
                        side: 72,
                        mark: marks[photo.id] ?? .keep,
                        framed: i == index,
                        label: label(photo, mark: marks[photo.id] ?? .keep),
                        onTap: { onSelect(i) }
                    )
                }
            }
            .padding(.horizontal, 16)
            .offset(x: -offset)
            .animation(allowAnim && !reduceMotion ? SpringToken.snappy.animation() : nil, value: index)
            .frame(width: geo.size.width, height: 72, alignment: .leading)
        }
        .frame(height: 72)
        .clipped()
        .onAppear {
            DispatchQueue.main.async { allowAnim = true }
        }
    }

    /// 当前张居中；到头或到尾就贴边，不把端点滚出空白。
    static func offset(index: Int, count: Int, viewport: CGFloat) -> CGFloat {
        guard count > 0, viewport > 0 else { return 0 }
        let item: CGFloat = 72
        let gap: CGFloat = 8
        let side: CGFloat = 16
        let content = side * 2 + CGFloat(count) * item + CGFloat(max(0, count - 1)) * gap
        let clamped = min(max(0, index), count - 1)
        let center = side + CGFloat(clamped) * (item + gap) + item / 2
        let raw = center - viewport / 2
        return min(max(0, raw), max(0, content - viewport))
    }

    private func label(_ photo: PhotoItem, mark: SimilarMark) -> String {
        let pattern = zh ? "M月d日 HH:mm" : "MMM d HH:mm"
        let date = DateFormatCache.string(photo.creationDate, format: pattern, locale: locale)
        return SimilarCopy.film(date: date, mark: SimilarCopy.mark(mark, zh: zh), zh: zh)
    }
}

private struct SimilarCue: View {
    let up: Bool
    let past: Bool
    let progress: CGFloat
    let text: String

    var body: some View {
        if !past {
            HStack(spacing: 8) {
                ZStack {
                    Circle().stroke(DarkroomColors.paper.opacity(0.32), lineWidth: 1.5)
                    Circle()
                        .trim(from: 0, to: progress)
                        .stroke(DarkroomColors.paper.opacity(0.9), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: up ? "checkmark" : "trash.fill")
                        .font(.system(size: 13, weight: .regular))
                        .foregroundStyle(DarkroomColors.paper.opacity(0.8))
                }
                .frame(width: 32, height: 32)
                Text(text)
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .foregroundStyle(DarkroomColors.paper.opacity(0.8))
            }
        } else {
            VStack(spacing: 12) {
                ZStack {
                    Circle().fill(DarkroomColors.paper).frame(width: 56, height: 56)
                    Image(systemName: up ? "checkmark" : "trash.fill")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(DarkroomColors.room)
                }
                Text(text)
                    .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                    .foregroundStyle(DarkroomColors.paper)
            }
        }
    }
}

@MainActor
final class CompareDrag: ObservableObject {
    @Published var index: Int
    @Published var dx: CGFloat = 0
    @Published var dy: CGFloat = 0
    @Published var zoom: CGFloat = 1
    @Published var zoomOffset: CGSize = .zero
    @Published var fingerVertical = false
    @Published var cueHolding = false
    @Published var cueAlpha: Double = 0
    @Published var heldUp = false
    @Published var heldPast = false
    @Published var heldProgress: CGFloat = 0

    private enum Axis { case horizontal, vertical, pan }
    private var axis: Axis?
    private var slop: CGFloat = 0
    private var baseX: CGFloat = 0
    private var baseY: CGFloat = 0
    private var baseZoomOffset: CGSize = .zero
    private var pinching = false
    private var springTask: Task<Void, Never>?
    private var cueTask: Task<Void, Never>?

    init(index: Int) { self.index = index }

    var cueUp: Bool { fingerVertical ? dy < 0 : heldUp }
    var cuePast: Bool { fingerVertical ? abs(dy) >= ReviewTokens.verticalThreshold : heldPast }
    var cueProgress: CGFloat {
        if fingerVertical { return min(1, abs(dy) / ReviewTokens.verticalThreshold) }
        return heldProgress
    }
    var showCue: Bool { (fingerVertical && dy != 0) || (cueHolding && cueAlpha > 0.01) }
    var photoBrightness: CGFloat {
        guard fingerVertical, dy > 0 else { return 1 }
        return Self.downBrightness(dy)
    }

    /// 松手时手指标记已经放下，亮度仍从这一下的位移起算。
    var exitStartBrightness: CGFloat { Self.downBrightness(dy) }

    private static func downBrightness(_ dy: CGFloat) -> CGFloat {
        guard dy > 0 else { return 1 }
        let fraction = min(1, dy / ReviewTokens.effectCap)
        return 1 - 0.18 * fraction
    }

    func syncZoomBase() { zoomBase = zoom }
    private var zoomBase: CGFloat = 1

    func pinchChanged(_ scale: CGFloat) {
        pinching = true
        springTask?.cancel()
        let raw = zoomBase * scale
        zoom = min(GestureTokensIOS.zoomMax, max(GestureTokensIOS.zoomMin, raw))
    }

    func pinchEnded(reduce: Bool) {
        pinching = false
        if zoom < GestureTokensIOS.zoomRestMin + 0.01 {
            zoom = 1
            zoomOffset = .zero
        }
        zoomBase = zoom
        if dx != 0 || dy != 0 {
            spring(toX: 0, toY: 0, vx: 0, vy: 0, reduce: reduce, snappy: false)
        }
    }

    func jump(to next: Int) {
        springTask?.cancel()
        cueTask?.cancel()
        axis = nil
        fingerVertical = false
        cueHolding = false
        cueAlpha = 0
        dx = 0
        dy = 0
        index = next
    }

    func clampIndex(_ count: Int) {
        guard count > 0 else { index = 0; return }
        if index > count - 1 { index = count - 1 }
        if index < 0 { index = 0 }
    }

    func changed(_ translation: CGSize, stage: CGSize, count: Int) {
        if pinching { return }
        if axis == nil { begin(translation) }
        guard let axis else { return }
        switch axis {
        case .horizontal:
            let raw = baseX + translation.width - slop
            if (index <= 0 && raw > 0) || (index >= count - 1 && raw < 0) {
                dx = ReviewMath.edgeResistance(raw, length: stage.width * 0.3)
            } else {
                dx = raw
            }
        case .vertical:
            dy = baseY + translation.height - slop
        case .pan:
            zoomOffset = clamp(
                CGSize(width: baseZoomOffset.width + translation.width, height: baseZoomOffset.height + translation.height),
                stage: stage
            )
        }
    }

    func ended(
        _ translation: CGSize,
        velocity: CGSize,
        stage: CGSize,
        count: Int,
        reduce: Bool,
        onVertical: (Bool) -> Bool
    ) {
        if pinching {
            pinching = false
            return
        }
        changed(translation, stage: stage, count: count)
        switch axis {
        case .horizontal:
            endHorizontal(vx: velocity.width, width: stage.width, count: count, reduce: reduce)
        case .vertical:
            endVertical(vy: velocity.height, reduce: reduce, onVertical: onVertical)
        case .pan:
            axis = nil
        case nil:
            break
        }
    }

    private func begin(_ translation: CGSize) {
        let ax = abs(translation.width)
        let ay = abs(translation.height)
        if ax < ReviewTokens.touchSlop && ay < ReviewTokens.touchSlop { return }
        springTask?.cancel()
        if zoom > 1.01 {
            axis = .pan
            baseZoomOffset = zoomOffset
            return
        }
        if ax >= ay * ReviewTokens.directionRatio {
            axis = .horizontal
            slop = signedSlop(translation.width)
            baseX = dx
            baseY = dy
            dismissCue()
        } else if ay >= ax * ReviewTokens.directionRatio {
            axis = .vertical
            slop = signedSlop(translation.height)
            baseX = dx
            baseY = dy
            fingerVertical = true
            cueTask?.cancel()
            cueHolding = false
            cueAlpha = 1
        }
    }

    private func endHorizontal(vx: CGFloat, width: CGFloat, count: Int, reduce: Bool) {
        let dir = ReviewMath.horizontalRelease(dx: dx, vx: vx, width: width)
        let next = index - dir
        guard dir != 0, width > 0, next >= 0, next < count else {
            spring(toX: 0, toY: 0, vx: vx, vy: 0, reduce: reduce, snappy: true)
            return
        }
        // 下标马上换。弹簧只负责把已经接上的位移收回去，中途再按也不会回到上一张。
        let shift: CGFloat = dir < 0 ? width : -width
        index = next
        dx += shift
        spring(toX: 0, toY: 0, vx: vx, vy: 0, reduce: reduce, snappy: true)
    }

    private func endVertical(vy: CGFloat, reduce: Bool, onVertical: (Bool) -> Bool) {
        holdCue(reduce: reduce)
        let deleted: Bool
        switch ReviewMath.verticalRelease(dy: dy, vy: vy) {
        case .up:
            _ = onVertical(true)
            deleted = false
        case .down:
            deleted = onVertical(false)
        case .none:
            deleted = false
        }
        if deleted {
            // 删成的这一张已经离开。位移立刻归零，不跟回弹弹簧走。
            springTask?.cancel()
            dx = 0
            dy = 0
            axis = nil
            return
        }
        spring(toX: 0, toY: 0, vx: 0, vy: vy, reduce: reduce, snappy: false)
    }

    private func holdCue(reduce: Bool) {
        guard fingerVertical else { return }
        heldUp = dy < 0
        heldPast = abs(dy) >= ReviewTokens.verticalThreshold
        heldProgress = min(1, abs(dy) / ReviewTokens.verticalThreshold)
        fingerVertical = false
        cueTask?.cancel()
        if reduce {
            cueHolding = false
            cueAlpha = 0
            return
        }
        cueHolding = true
        cueAlpha = 1
        cueTask = Task { @MainActor in
            let steps = 12
            let slice = ReviewTokens.releaseCueFade / Double(steps)
            for step in 1...steps {
                try? await Task.sleep(nanoseconds: UInt64(slice * 1_000_000_000))
                if Task.isCancelled || fingerVertical { return }
                cueAlpha = 1 - Double(step) / Double(steps)
            }
            if !fingerVertical {
                cueAlpha = 0
                cueHolding = false
            }
        }
    }

    private func dismissCue() {
        cueTask?.cancel()
        fingerVertical = false
        cueHolding = false
        cueAlpha = 0
    }

    private func spring(toX: CGFloat, toY: CGFloat, vx: CGFloat, vy: CGFloat, reduce: Bool, snappy: Bool) {
        springTask?.cancel()
        if reduce {
            dx = toX
            dy = toY
            axis = nil
            return
        }
        let token = snappy ? SpringToken.snappy : SpringToken.settle
        let stiffness = token.stiffness
        let damping = token.damping
        var x = dx
        var y = dy
        var velocityX = vx
        var velocityY = vy
        springTask = Task { @MainActor in
            var last = Date()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 16_000_000)
                if Task.isCancelled { return }
                let now = Date()
                let dt = min(0.032, now.timeIntervalSince(last))
                last = now
                let drag = 2 * damping * stiffness.squareRoot()
                let ax = -stiffness * Double(x - toX) - drag * Double(velocityX)
                let ay = -stiffness * Double(y - toY) - drag * Double(velocityY)
                velocityX += CGFloat(ax * dt)
                velocityY += CGFloat(ay * dt)
                x += velocityX * CGFloat(dt)
                y += velocityY * CGFloat(dt)
                dx = x
                dy = y
                if hypot(x - toX, y - toY) < 0.5 && hypot(velocityX, velocityY) < 40 {
                    dx = toX
                    dy = toY
                    axis = nil
                    return
                }
            }
        }
    }

    private func clamp(_ proposed: CGSize, stage: CGSize) -> CGSize {
        let maxX = max(0, (stage.width * (zoom - 1)) / 2)
        let maxY = max(0, (stage.height * (zoom - 1)) / 2)
        return CGSize(
            width: min(max(proposed.width, -maxX), maxX),
            height: min(max(proposed.height, -maxY), maxY)
        )
    }

    private func signedSlop(_ along: CGFloat) -> CGFloat {
        (along < 0 ? -1 : 1) * min(ReviewTokens.touchSlop, abs(along))
    }
}
