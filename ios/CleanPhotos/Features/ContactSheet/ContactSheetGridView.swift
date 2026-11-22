import Photos
import SwiftUI
import UIKit

/// 印样表 3 列 UICollectionView：Cell 复用 + 预取 + 原生横滑连圈 + 长按看大图，彻底去掉全高 GeometryReader 和每帧坐标 state。
struct ContactSheetGridView: UIViewRepresentable {
    let photos: [PhotoItem]
    let circled: Set<String>
    let busy: Bool
    let locale: Locale
    let pipeline: ThumbnailPipeline
    let onToggle: (String) -> Void
    let onCircle: (String) -> Void
    let onPeek: (PhotoItem?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UICollectionView {
        let coordinator = context.coordinator
        let cv = UICollectionView(frame: .zero, collectionViewLayout: coordinator.makeLayout())
        cv.backgroundColor = DarkroomUIColors.room
        cv.showsVerticalScrollIndicator = false
        cv.alwaysBounceVertical = true
        cv.contentInsetAdjustmentBehavior = .never
        cv.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: 12, right: 0)
        cv.register(PhotoGridCell.self, forCellWithReuseIdentifier: PhotoGridCell.reuseIdentifier)
        cv.delegate = coordinator
        cv.prefetchDataSource = coordinator

        let pan = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.handleCirclePan(_:)))
        pan.delegate = coordinator
        cv.addGestureRecognizer(pan)
        coordinator.circlePan = pan

        let press = UILongPressGestureRecognizer(target: coordinator, action: #selector(Coordinator.handleLongPress(_:)))
        press.minimumPressDuration = 0.4
        press.allowableMovement = 18
        cv.addGestureRecognizer(press)

        coordinator.collectionView = cv
        coordinator.configureDataSource(cv)
        coordinator.applySnapshot(from: self)
        return cv
    }

    func updateUIView(_ uiView: UICollectionView, context: Context) {
        context.coordinator.update(with: self, collectionView: uiView)
    }

    static func dismantleUIView(_ uiView: UICollectionView, coordinator: Coordinator) {
        coordinator.teardown()
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDelegate, UICollectionViewDataSourcePrefetching, UIGestureRecognizerDelegate {
        var parent: ContactSheetGridView
        weak var collectionView: UICollectionView?
        weak var circlePan: UIPanGestureRecognizer?

        private var dataSource: UICollectionViewDiffableDataSource<Int, String>?
        private let prefetch: ThumbnailPrefetchWindow
        private var itemsById: [String: PhotoItem] = [:]
        private var indexById: [String: Int] = [:]
        private var currentOrder: [String] = []
        private var currentCircled: Set<String> = []
        private var lastCirclePoint: CGPoint = .zero

        init(parent: ContactSheetGridView) {
            self.parent = parent
            self.prefetch = ThumbnailPrefetchWindow(pipeline: parent.pipeline)
        }

        func teardown() {
            prefetch.reset()
        }

        func makeLayout() -> UICollectionViewCompositionalLayout {
            UICollectionViewCompositionalLayout { _, env in
                let cols = 3
                let gap = DarkroomDimens.gridGap
                let width = env.container.effectiveContentSize.width
                let side = (max(width, 1) - gap * CGFloat(cols - 1)) / CGFloat(cols)
                let itemSize = NSCollectionLayoutSize(widthDimension: .absolute(side), heightDimension: .absolute(side))
                let item = NSCollectionLayoutItem(layoutSize: itemSize)
                let groupSize = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1.0), heightDimension: .absolute(side))
                let group = NSCollectionLayoutGroup.horizontal(layoutSize: groupSize, repeatingSubitem: item, count: cols)
                group.interItemSpacing = .fixed(gap)
                let sec = NSCollectionLayoutSection(group: group)
                sec.interGroupSpacing = gap
                return sec
            }
        }

        func configureDataSource(_ cv: UICollectionView) {
            dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: cv) { [weak self] cv, indexPath, id in
                let cell = cv.dequeueReusableCell(withReuseIdentifier: PhotoGridCell.reuseIdentifier, for: indexPath) as! PhotoGridCell
                if let self, let item = self.itemsById[id] {
                    let side = self.tileSide(width: cv.bounds.width)
                    cell.configure(self.cellConfig(item: item, side: side), pipeline: self.parent.pipeline, animated: false)
                }
                return cell
            }
        }

        func update(with newParent: ContactSheetGridView, collectionView cv: UICollectionView) {
            let newOrder = newParent.photos.map(\.id)
            let orderChanged = newOrder != currentOrder
            let oldCircled = currentCircled
            let circledChanged = newParent.circled != oldCircled
            parent = newParent

            if orderChanged {
                applySnapshot(from: newParent)
                return
            }

            if circledChanged {
                currentCircled = newParent.circled
                let diff = oldCircled.symmetricDifference(newParent.circled)
                let side = tileSide(width: cv.bounds.width)
                for id in diff {
                    guard let idx = indexById[id],
                          let item = itemsById[id],
                          let cell = cv.cellForItem(at: IndexPath(item: idx, section: 0)) as? PhotoGridCell else {
                        continue
                    }
                    cell.configure(cellConfig(item: item, side: side), pipeline: parent.pipeline, animated: false)
                }
            }
        }

        func applySnapshot(from source: ContactSheetGridView) {
            parent = source
            currentOrder = source.photos.map(\.id)
            itemsById = Dictionary(uniqueKeysWithValues: source.photos.map { ($0.id, $0) })
            indexById = Dictionary(uniqueKeysWithValues: source.photos.enumerated().map { ($0.element.id, $0.offset) })
            currentCircled = source.circled

            var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
            snapshot.appendSections([0])
            snapshot.appendItems(currentOrder, toSection: 0)
            dataSource?.apply(snapshot, animatingDifferences: false) { [weak self] in
                guard let self, let cv = self.collectionView else { return }
                let side = self.tileSide(width: cv.bounds.width)
                for ip in cv.indexPathsForVisibleItems where ip.item < self.parent.photos.count {
                    guard let cell = cv.cellForItem(at: ip) as? PhotoGridCell else { continue }
                    let item = self.parent.photos[ip.item]
                    cell.configure(self.cellConfig(item: item, side: side), pipeline: self.parent.pipeline, animated: false)
                }
                self.prefetch.warmInitial(self.parent.photos.map(\.asset), side: side, style: .normal, limit: 48)
            }
        }

        private func cellConfig(item: PhotoItem, side: CGFloat) -> PhotoGridCellConfig {
            PhotoGridCellConfig(
                item: item,
                side: side,
                style: .normal,
                bottomLeftText: nil,
                bottomRightText: nil,
                showUnseen: false,
                selecting: false,
                selected: false,
                circled: parent.circled.contains(item.id),
                develop: false,
                developDelay: 0,
                interactive: true,
                locale: parent.locale,
                accessibilityId: nil
            )
        }

        private func tileSide(width: CGFloat) -> CGFloat {
            let effective = width > 1 ? width : UIScreen.main.bounds.width
            return (effective - DarkroomDimens.gridGap * 2) / 3
        }

        // MARK: - Gestures & Selection

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard gestureRecognizer === circlePan, let cv = collectionView, let pan = circlePan else { return true }
            guard !parent.busy else { return false }
            let v = pan.velocity(in: cv)
            return abs(v.x) > abs(v.y) && abs(v.x) > 0
        }

        @objc func handleCirclePan(_ g: UIPanGestureRecognizer) {
            guard let cv = collectionView, !parent.busy else { return }
            let point = g.location(in: cv)
            switch g.state {
            case .began:
                lastCirclePoint = point
                circleAt(point, in: cv)
            case .changed:
                circleAlong(from: lastCirclePoint, to: point, in: cv)
                lastCirclePoint = point
            default:
                break
            }
        }

        private func circleAt(_ point: CGPoint, in cv: UICollectionView) {
            guard let ip = cv.indexPathForItem(at: point),
                  let id = dataSource?.itemIdentifier(for: ip) else { return }
            parent.onCircle(id)
        }

        private func circleAlong(from a: CGPoint, to b: CGPoint, in cv: UICollectionView) {
            circleAt(a, in: cv)
            let steps = max(1, Int(hypot(b.x - a.x, b.y - a.y) / 6))
            for step in 1...steps {
                let t = CGFloat(step) / CGFloat(steps)
                let p = CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
                circleAt(p, in: cv)
            }
        }

        @objc func handleLongPress(_ g: UILongPressGestureRecognizer) {
            guard let cv = collectionView else { return }
            switch g.state {
            case .began:
                guard !parent.busy,
                      let ip = cv.indexPathForItem(at: g.location(in: cv)),
                      let id = dataSource?.itemIdentifier(for: ip),
                      !parent.circled.contains(id),
                      let item = itemsById[id] else { return }
                parent.onPeek(item)
            case .ended, .cancelled, .failed:
                parent.onPeek(nil)
            default:
                break
            }
        }

        func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
            guard !parent.busy, let id = dataSource?.itemIdentifier(for: indexPath) else { return }
            parent.onToggle(id)
        }

        // MARK: - Prefetch

        func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
            let assets = indexPaths.compactMap { ip in
                ip.item < parent.photos.count ? parent.photos[ip.item].asset : nil
            }
            prefetch.startCaching(assets, side: tileSide(width: collectionView.bounds.width), style: .normal)
        }

        func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
            let assets = indexPaths.compactMap { ip in
                ip.item < parent.photos.count ? parent.photos[ip.item].asset : nil
            }
            prefetch.stopCaching(assets)
        }
    }
}
