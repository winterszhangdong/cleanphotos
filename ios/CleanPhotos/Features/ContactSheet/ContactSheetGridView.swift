import Photos
import SwiftUI
import UIKit

/// 印样表胶片网格。点按、横滑连圈、竖滑滚动、长按偷看或拿起都在同一个手势里判定。
struct ContactSheetGridView: UIViewRepresentable {
    let photos: [PhotoItem]
    let circled: Set<String>
    let busy: Bool
    let edgeDate: Date?
    let pipeline: ThumbnailPipeline
    var bottomInset: CGFloat
    var bridge: SheetBridge
    let onToggle: (String) -> Void
    let onCircle: (String) -> Void
    let onPeek: (PhotoItem?) -> Void
    let onPickUp: (CGPoint) -> Void
    let onDrag: (CGPoint) -> Void
    let onDrop: (CGPoint) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIView(context: Context) -> UICollectionView {
        let coordinator = context.coordinator
        let layout = UICollectionViewCompositionalLayout { _, env in
            coordinator.section(in: env)
        }
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.backgroundColor = DarkroomUIColors.room
        cv.showsVerticalScrollIndicator = false
        cv.alwaysBounceVertical = true
        cv.contentInsetAdjustmentBehavior = .never
        cv.clipsToBounds = false
        cv.allowsSelection = false
        cv.register(FilmStripCell.self, forCellWithReuseIdentifier: FilmStripCell.reuseIdentifier)
        cv.delegate = coordinator
        cv.prefetchDataSource = coordinator
        let touch = FilmTouchRecognizer()
        touch.bridge = coordinator
        cv.addGestureRecognizer(touch)
        coordinator.collectionView = cv
        coordinator.configureDataSource(cv)
        coordinator.applySnapshot(from: self)
        bridge.grid = coordinator
        return cv
    }

    func updateUIView(_ uiView: UICollectionView, context: Context) {
        context.coordinator.update(with: self, collectionView: uiView)
        bridge.grid = context.coordinator
    }

    static func dismantleUIView(_ uiView: UICollectionView, coordinator: Coordinator) {
        coordinator.teardown()
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDelegate, UICollectionViewDataSourcePrefetching {
        var parent: ContactSheetGridView
        weak var collectionView: UICollectionView?
        private var dataSource: UICollectionViewDiffableDataSource<Int, String>?
        private let prefetch: ThumbnailPrefetchWindow
        private var itemsById: [String: PhotoItem] = [:]
        private var indexById: [String: Int] = [:]
        private var currentOrder: [String] = []
        private var currentCircled: Set<String> = []
        private var playedEnter = false
        private var slotAlpha: [String: CGFloat] = [:]
        private var layoutColumns = 3
        private var layoutBottom: CGFloat = 24

        init(parent: ContactSheetGridView) {
            self.parent = parent
            prefetch = ThumbnailPrefetchWindow(pipeline: parent.pipeline)
        }

        func teardown() { prefetch.reset() }

        func section(in env: NSCollectionLayoutEnvironment) -> NSCollectionLayoutSection {
            let cols = max(1, FilmGeometryRules.columns(count: parent.photos.count))
            let full = max(env.container.effectiveContentSize.width, 1)
            let strip = max(1, full - FilmGeometryRules.pageInset * 2)
            let cellW = strip / CGFloat(cols)
            let cellH = FilmGeometryRules.stripHeight(stripWidth: strip, cols: cols)
            let itemSize = NSCollectionLayoutSize(widthDimension: .absolute(cellW), heightDimension: .absolute(cellH))
            let item = NSCollectionLayoutItem(layoutSize: itemSize)
            let groupSize = NSCollectionLayoutSize(widthDimension: .absolute(strip), heightDimension: .absolute(cellH))
            let group = NSCollectionLayoutGroup.horizontal(layoutSize: groupSize, repeatingSubitem: item, count: cols)
            group.interItemSpacing = .fixed(0)
            let section = NSCollectionLayoutSection(group: group)
            section.interGroupSpacing = FilmGeometryRules.stripGap
            section.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: FilmGeometryRules.pageInset, bottom: parent.bottomInset, trailing: FilmGeometryRules.pageInset)
            return section
        }

        func configureDataSource(_ cv: UICollectionView) {
            dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: cv) { [weak self] cv, indexPath, id in
                let cell = cv.dequeueReusableCell(withReuseIdentifier: FilmStripCell.reuseIdentifier, for: indexPath) as! FilmStripCell
                self?.fill(cell, id: id, index: indexPath.item, playEnter: false)
                return cell
            }
        }

        func update(with newParent: ContactSheetGridView, collectionView cv: UICollectionView) {
            let order = newParent.photos.map(\.id)
            let cols = FilmGeometryRules.columns(count: newParent.photos.count)
            let layoutChanged = cols != layoutColumns || abs(newParent.bottomInset - layoutBottom) > 0.5
            parent = newParent
            if layoutChanged {
                layoutColumns = cols
                layoutBottom = newParent.bottomInset
                cv.collectionViewLayout.invalidateLayout()
            }
            if order != currentOrder {
                applySnapshot(from: newParent)
                return
            }
            if newParent.circled != currentCircled {
                let diff = currentCircled.symmetricDifference(newParent.circled)
                currentCircled = newParent.circled
                for id in diff {
                    guard let index = indexById[id],
                          let cell = cv.cellForItem(at: IndexPath(item: index, section: 0)) as? FilmStripCell else { continue }
                    cell.setCircled(newParent.circled.contains(id), animated: true)
                    if newParent.circled.contains(id) {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    }
                }
            }
        }

        func applySnapshot(from source: ContactSheetGridView) {
            parent = source
            currentOrder = source.photos.map(\.id)
            itemsById = Dictionary(uniqueKeysWithValues: source.photos.map { ($0.id, $0) })
            indexById = Dictionary(uniqueKeysWithValues: source.photos.enumerated().map { ($0.element.id, $0.offset) })
            currentCircled = source.circled
            layoutColumns = FilmGeometryRules.columns(count: source.photos.count)
            layoutBottom = source.bottomInset
            var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
            snapshot.appendSections([0])
            snapshot.appendItems(currentOrder, toSection: 0)
            let first = !playedEnter
            dataSource?.apply(snapshot, animatingDifferences: false) { [weak self] in
                guard let self, let cv = self.collectionView else { return }
                for ip in cv.indexPathsForVisibleItems {
                    guard let id = self.dataSource?.itemIdentifier(for: ip),
                          let cell = cv.cellForItem(at: ip) as? FilmStripCell else { continue }
                    self.fill(cell, id: id, index: ip.item, playEnter: first)
                }
                self.playedEnter = true
                let side = self.tileSide(width: cv.bounds.width)
                self.prefetch.warmInitial(self.parent.photos.map(\.asset), side: side, style: .normal, limit: 48)
            }
        }

        func setSlotAlpha(_ alpha: CGFloat, id: String) {
            if alpha >= 0.999 { slotAlpha[id] = nil } else { slotAlpha[id] = alpha }
            guard let index = indexById[id],
                  let cell = collectionView?.cellForItem(at: IndexPath(item: index, section: 0)) as? FilmStripCell else { return }
            cell.setSlotAlpha(alpha)
        }

        func photoFrameInWindow(_ id: String) -> CGRect? {
            guard let cv = collectionView, let index = indexById[id] else { return nil }
            let ip = IndexPath(item: index, section: 0)
            guard let cell = cv.cellForItem(at: ip) as? FilmStripCell else {
                guard let attr = cv.layoutAttributesForItem(at: ip) else { return nil }
                let frame = cv.convert(attr.frame, to: nil)
                let cols = FilmGeometryRules.columns(count: parent.photos.count)
                let strip = max(1, cv.bounds.width - FilmGeometryRules.pageInset * 2)
                let col = index % cols
                let width = FilmGeometryRules.frameWidth(stripWidth: strip, cols: cols)
                let left = FilmGeometryRules.frameLeft(stripWidth: strip, cols: cols, col: col)
                return CGRect(x: frame.minX + left, y: frame.minY + FilmGeometryRules.band, width: width, height: width * 3 / 4)
            }
            return cell.convert(cell.photoRect, to: nil)
        }

        func visibleCircledFrames() -> [String: CGRect] {
            guard let cv = collectionView else { return [:] }
            var frames: [String: CGRect] = [:]
            for ip in cv.indexPathsForVisibleItems {
                guard let id = dataSource?.itemIdentifier(for: ip), parent.circled.contains(id) else { continue }
                if let frame = photoFrameInWindow(id) { frames[id] = frame }
            }
            return frames
        }

        private func fill(_ cell: FilmStripCell, id: String, index: Int, playEnter: Bool) {
            guard let item = itemsById[id], let cv = collectionView else { return }
            let cols = FilmGeometryRules.columns(count: parent.photos.count)
            let strip = max(1, cv.bounds.width - FilmGeometryRules.pageInset * 2)
            let col = index % cols
            let row = index / cols
            let last = col == cols - 1 || index == parent.photos.count - 1
            let edge = (last && row % 2 == 0) ? FilmGeometryRules.edgePrint(date: parent.edgeDate) : nil
            cell.configure(
                item: item, number: index + 1, row: row, column: col, columns: cols, stripWidth: strip,
                edge: edge, circled: parent.circled.contains(id), slotAlpha: slotAlpha[id] ?? 1,
                pipeline: parent.pipeline, playEnter: playEnter
            )
        }

        private func tileSide(width: CGFloat) -> CGFloat {
            let strip = max(1, (width > 1 ? width : UIScreen.main.bounds.width) - FilmGeometryRules.pageInset * 2)
            let cols = FilmGeometryRules.columns(count: parent.photos.count)
            return FilmGeometryRules.frameWidth(stripWidth: strip, cols: cols)
        }

        func id(at point: CGPoint) -> String? {
            guard let cv = collectionView, let ip = cv.indexPathForItem(at: point) else { return nil }
            return dataSource?.itemIdentifier(for: ip)
        }

        func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
            let assets = indexPaths.compactMap { itemsById[dataSource?.itemIdentifier(for: $0) ?? ""]?.asset }
            prefetch.startCaching(assets, side: tileSide(width: collectionView.bounds.width), style: .normal)
        }

        func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
            let assets = indexPaths.compactMap { itemsById[dataSource?.itemIdentifier(for: $0) ?? ""]?.asset }
            prefetch.stopCaching(assets)
        }
    }
}

@MainActor
final class SheetBridge {
    weak var grid: ContactSheetGridView.Coordinator?
}

/// 印样表唯一手势：点按圈/擦，长按前横滑连圈，竖滑交给列表，长按未圈偷看，长按已圈拿起。
final class FilmTouchRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
    weak var bridge: ContactSheetGridView.Coordinator?
    private var start: CGPoint = .zero
    private var decided = false
    private var mode = Mode.none
    private var timer: Timer?
    private enum Mode { case none, scroll, stroke, drag, peek }

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        delegate = self
        cancelsTouchesInView = false
        delaysTouchesBegan = false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = touches.first, let view, bridge?.parent.busy != true else {
            state = .failed
            return
        }
        start = touch.location(in: view)
        decided = false
        mode = .none
        state = .possible
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.fireLongPress() }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = touches.first, let view else { return }
        if mode == .drag {
            bridge?.parent.onDrag(touch.location(in: nil))
            return
        }
        if mode == .peek { return }
        let point = touch.location(in: view)
        let dx = point.x - start.x
        let dy = point.y - start.y
        guard !decided else {
            if mode == .stroke { stroke(to: point) }
            return
        }
        guard hypot(dx, dy) > 10 else { return }
        decided = true
        timer?.invalidate()
        if abs(dx) > abs(dy) * 1.2 {
            mode = .stroke
            state = .began
            view.superview.flatMap { _ in () }
            (view as? UICollectionView)?.isScrollEnabled = false
            stroke(to: point)
        } else {
            mode = .scroll
            state = .failed
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        finish(touches.first)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        finish(touches.first)
    }

    override func reset() {
        timer?.invalidate()
        timer = nil
        mode = .none
        decided = false
        (view as? UICollectionView)?.isScrollEnabled = true
    }

    private func fireLongPress() {
        guard !decided, let view, let bridge else { return }
        decided = true
        let windowPoint = view.convert(start, to: nil)
        if let id = bridge.id(at: start), bridge.parent.circled.contains(id) {
            mode = .drag
            state = .began
            (view as? UICollectionView)?.isScrollEnabled = false
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            bridge.parent.onPickUp(windowPoint)
        } else if bridge.id(at: start) != nil {
            mode = .peek
            state = .began
            (view as? UICollectionView)?.isScrollEnabled = false
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            if let id = bridge.id(at: start), let item = bridge.parent.photos.first(where: { $0.id == id }) {
                bridge.parent.onPeek(item)
            }
        } else {
            state = .failed
        }
    }

    private func stroke(to point: CGPoint) {
        guard let bridge else { return }
        let steps = max(1, Int(hypot(point.x - start.x, point.y - start.y) / 8))
        for step in 0...steps {
            let t = CGFloat(step) / CGFloat(steps)
            let p = CGPoint(x: start.x + (point.x - start.x) * t, y: start.y + (point.y - start.y) * t)
            if let id = bridge.id(at: p) { bridge.parent.onCircle(id) }
        }
        start = point
    }

    private func finish(_ touch: UITouch?) {
        timer?.invalidate()
        defer { state = mode == .none || mode == .scroll ? .failed : .ended }
        guard let bridge else { return }
        switch mode {
        case .none:
            if !decided, let id = bridge.id(at: start) { bridge.parent.onToggle(id) }
        case .drag:
            let point = touch?.location(in: nil) ?? .zero
            bridge.parent.onDrop(point)
        case .peek:
            bridge.parent.onPeek(nil)
        case .stroke, .scroll:
            break
        }
        (view as? UICollectionView)?.isScrollEnabled = true
    }
}
