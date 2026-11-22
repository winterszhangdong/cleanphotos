import Accelerate
import Foundation
@preconcurrency import Photos
import UIKit

/// 缩略图异步请求的取消令牌。线程安全，可在后台队列与主线程之间共享状态，避免任何主线程同步 PhotoKit 调用。
final class ThumbnailToken: @unchecked Sendable {
    private final class WeakOp {
        weak var op: Operation?
        init(_ op: Operation) { self.op = op }
    }

    private let lock = NSLock()
    private var cancelled = false
    private var hasFinal = false
    private var requestID: PHImageRequestID = PHInvalidImageRequestID
    private weak var manager: PHImageManager?
    private weak var cancelQueue: OperationQueue?
    private var operations: [WeakOp] = []

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    var hasDeliveredFinal: Bool {
        lock.lock()
        defer { lock.unlock() }
        return hasFinal
    }

    func markFinalDelivered() {
        lock.lock()
        hasFinal = true
        lock.unlock()
    }

    func attachOperation(_ op: Operation) {
        lock.lock()
        let alreadyCancelled = cancelled
        if !alreadyCancelled {
            operations.removeAll { $0.op == nil }
            operations.append(WeakOp(op))
        }
        lock.unlock()
        if alreadyCancelled {
            op.cancel()
        }
    }

    func attachRequest(_ id: PHImageRequestID, manager: PHImageManager, cancelQueue: OperationQueue) {
        guard id != PHInvalidImageRequestID else { return }
        lock.lock()
        if cancelled {
            lock.unlock()
            let op = BlockOperation {
                manager.cancelImageRequest(id)
            }
            op.queuePriority = .high
            cancelQueue.addOperation(op)
            return
        }
        requestID = id
        self.manager = manager
        self.cancelQueue = cancelQueue
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let id = requestID
        let mgr = manager
        let queue = cancelQueue
        let ops = operations.compactMap(\.op)
        requestID = PHInvalidImageRequestID
        manager = nil
        cancelQueue = nil
        operations.removeAll()
        lock.unlock()

        for op in ops {
            op.cancel()
        }
        if id != PHInvalidImageRequestID, let mgr {
            let cancelWork = {
                mgr.cancelImageRequest(id)
            }
            if let queue {
                let op = BlockOperation(block: cancelWork)
                op.queuePriority = .high
                queue.addOperation(op)
            } else {
                DispatchQueue.global(qos: .userInitiated).async(execute: cancelWork)
            }
        }
    }
}

/// 统一缩略图缓存、后台预解码与 CPU `vImage` 退影出图管线：
/// 可见 Cell 请求与取消全部离主线程调度，预取结果直接写入 `NSCache` 供滚动复用 O(1) 同步命中。
@MainActor
final class ThumbnailPipeline {
    nonisolated let imageManager: PHCachingImageManager
    nonisolated let visibleOptions: PHImageRequestOptions
    nonisolated let prefetchOptions: PHImageRequestOptions
    nonisolated let cachingOptions: PHImageRequestOptions
    nonisolated let displayScale: CGFloat

    nonisolated let cache = NSCache<NSString, UIImage>()
    nonisolated let photoKitQueue: OperationQueue
    nonisolated let prefetchQueue: OperationQueue
    nonisolated let decodeQueue: OperationQueue
    nonisolated let fullImageQueue: OperationQueue
    nonisolated let fullDecodeQueue: OperationQueue

    nonisolated private let prefetchLock = NSLock()
    nonisolated(unsafe) private var inflightPrefetch: [String: ThumbnailToken] = [:]

    nonisolated private static let sRGBColorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
    nonisolated private static let bgrxBitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue

    init(imageManager: PHCachingImageManager) {
        self.imageManager = imageManager
        self.displayScale = UIScreen.main.scale

        let vis = PHImageRequestOptions()
        vis.deliveryMode = .opportunistic
        vis.resizeMode = .fast
        vis.isNetworkAccessAllowed = true
        vis.isSynchronous = false
        self.visibleOptions = vis

        let pre = PHImageRequestOptions()
        pre.deliveryMode = .highQualityFormat
        pre.resizeMode = .fast
        pre.isNetworkAccessAllowed = false
        pre.isSynchronous = true
        self.prefetchOptions = pre

        let caching = PHImageRequestOptions()
        caching.deliveryMode = .opportunistic
        caching.resizeMode = .fast
        caching.isNetworkAccessAllowed = false
        self.cachingOptions = caching

        let pkQ = OperationQueue()
        pkQ.name = "cleanphotos.thumb.photokit"
        pkQ.maxConcurrentOperationCount = 2
        pkQ.qualityOfService = .userInitiated
        self.photoKitQueue = pkQ

        let preQ = OperationQueue()
        preQ.name = "cleanphotos.thumb.prefetch"
        preQ.maxConcurrentOperationCount = 2
        preQ.qualityOfService = .utility
        self.prefetchQueue = preQ

        let decQ = OperationQueue()
        decQ.name = "cleanphotos.thumb.decode"
        decQ.maxConcurrentOperationCount = 3
        decQ.qualityOfService = .userInitiated
        self.decodeQueue = decQ

        let fkQ = OperationQueue()
        fkQ.name = "cleanphotos.full.photokit"
        fkQ.maxConcurrentOperationCount = 2
        fkQ.qualityOfService = .userInitiated
        self.fullImageQueue = fkQ

        let fdQ = OperationQueue()
        fdQ.name = "cleanphotos.full.decode"
        fdQ.maxConcurrentOperationCount = 2
        fdQ.qualityOfService = .userInitiated
        self.fullDecodeQueue = fdQ

        self.cache.countLimit = 800
        self.cache.totalCostLimit = 160 * 1024 * 1024
    }

    nonisolated func targetSize(side: CGFloat) -> CGSize {
        let px = CGFloat(ThumbnailCacheKey.pixelSide(points: side, scale: displayScale))
        return CGSize(width: px, height: px)
    }

    /// 同步命中已预解码的不透明位图缓存。滚动复用阶段 O(1) 取图，零 PhotoKit I/O。
    func cached(id: String, side: CGFloat, style: ThumbnailStyle) -> UIImage? {
        let px = ThumbnailCacheKey.pixelSide(points: side, scale: displayScale)
        let key = ThumbnailCacheKey.make(assetId: id, pixelSide: px, style: style)
        return cache.object(forKey: key as NSString)
    }

    func request(
        asset: PHAsset,
        side: CGFloat,
        style: ThumbnailStyle,
        completion: @escaping @MainActor (_ image: UIImage, _ degraded: Bool) -> Void
    ) -> ThumbnailToken {
        let px = ThumbnailCacheKey.pixelSide(points: side, scale: displayScale)
        let size = CGSize(width: px, height: px)
        let key = ThumbnailCacheKey.make(assetId: asset.localIdentifier, pixelSide: px, style: style)
        let token = ThumbnailToken()
        let scale = displayScale
        let mgr = imageManager
        let opts = visibleOptions
        let cache = self.cache
        let pkQueue = photoKitQueue
        let decQueue = decodeQueue

        let fetchOp = BlockOperation {
            guard !token.isCancelled else { return }
            if let hit = cache.object(forKey: key as NSString) {
                token.markFinalDelivered()
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        guard !token.isCancelled else { return }
                        completion(hit, false)
                    }
                }
                return
            }

            let reqID = mgr.requestImage(
                for: asset,
                targetSize: size,
                contentMode: .aspectFill,
                options: opts
            ) { image, info in
                guard !token.isCancelled, let image else { return }
                if let phCancelled = info?[PHImageCancelledKey] as? Bool, phCancelled { return }
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if degraded && token.hasDeliveredFinal { return }
                if ScrollProbe.enabled {
                    ScrollProbe.shared.thumb(main: Thread.isMainThread, degraded: degraded)
                }

                let decodeOp = BlockOperation {
                    guard !token.isCancelled else { return }
                    if degraded && token.hasDeliveredFinal { return }
                    let prepared = Self.prepare(
                        image,
                        targetPixelSide: px,
                        style: style,
                        displayScale: scale
                    )
                    guard !token.isCancelled else { return }
                    if !degraded {
                        token.markFinalDelivered()
                        let cost = prepared.cgImage.map { $0.bytesPerRow * $0.height }
                            ?? Int(prepared.size.width * prepared.size.height * prepared.scale * prepared.scale * 4)
                        cache.setObject(prepared, forKey: key as NSString, cost: max(1, cost))
                    } else if token.hasDeliveredFinal {
                        return
                    }
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            guard !token.isCancelled else { return }
                            completion(prepared, degraded)
                        }
                    }
                }
                decodeOp.queuePriority = .high
                token.attachOperation(decodeOp)
                decQueue.addOperation(decodeOp)
            }
            token.attachRequest(reqID, manager: mgr, cancelQueue: pkQueue)
        }
        fetchOp.queuePriority = .veryHigh
        token.attachOperation(fetchOp)
        photoKitQueue.addOperation(fetchOp)
        return token
    }

    /// 离主线程按 `aspectFit` 请求与预解码全屏/预览大图，支持优先级与完整取消传播。
    func requestFitImage(
        asset: PHAsset,
        maxSidePoints: CGFloat,
        fitPixels: CGSize? = nil,
        priority: Operation.QueuePriority = .normal,
        completion: @escaping @MainActor (_ image: UIImage, _ degraded: Bool) -> Void
    ) -> ThumbnailToken {
        let scale = displayScale
        let safeSide = max(1, maxSidePoints)
        let target = CGSize(width: (safeSide * scale).rounded(.up), height: (safeSide * scale).rounded(.up))
        let token = ThumbnailToken()
        let mgr = imageManager
        let opts = visibleOptions
        let fkQueue = fullImageQueue
        let decQueue = fullDecodeQueue

        let fetchOp = BlockOperation {
            guard !token.isCancelled else { return }
            let reqID = mgr.requestImage(
                for: asset,
                targetSize: target,
                contentMode: .aspectFit,
                options: opts
            ) { image, info in
                guard !token.isCancelled, let image else { return }
                if let phCancelled = info?[PHImageCancelledKey] as? Bool, phCancelled { return }
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if degraded && token.hasDeliveredFinal { return }

                let decodeOp = BlockOperation {
                    guard !token.isCancelled else { return }
                    if degraded && token.hasDeliveredFinal { return }
                    let shown: UIImage
                    if let fitPixels,
                       fitPixels.width > 0,
                       fitPixels.height > 0,
                       (image.size.width * image.scale > fitPixels.width * 1.05 ||
                        image.size.height * image.scale > fitPixels.height * 1.05) {
                        shown = image.preparingThumbnail(of: fitPixels) ?? image.preparingForDisplay() ?? image
                    } else {
                        shown = image.preparingForDisplay() ?? image
                    }
                    guard !token.isCancelled else { return }
                    if !degraded {
                        token.markFinalDelivered()
                    } else if token.hasDeliveredFinal {
                        return
                    }
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            guard !token.isCancelled else { return }
                            completion(shown, degraded)
                        }
                    }
                }
                decodeOp.queuePriority = priority
                token.attachOperation(decodeOp)
                decQueue.addOperation(decodeOp)
            }
            token.attachRequest(reqID, manager: mgr, cancelQueue: fkQueue)
        }
        fetchOp.queuePriority = priority
        token.attachOperation(fetchOp)
        fullImageQueue.addOperation(fetchOp)
        return token
    }

    @discardableResult
    func prefetch(asset: PHAsset, side: CGFloat, style: ThumbnailStyle) -> ThumbnailToken? {
        let px = ThumbnailCacheKey.pixelSide(points: side, scale: displayScale)
        let key = ThumbnailCacheKey.make(assetId: asset.localIdentifier, pixelSide: px, style: style)
        if cache.object(forKey: key as NSString) != nil {
            return nil
        }

        prefetchLock.lock()
        if inflightPrefetch[key] != nil {
            prefetchLock.unlock()
            return nil
        }
        let token = ThumbnailToken()
        inflightPrefetch[key] = token
        prefetchLock.unlock()

        let size = CGSize(width: px, height: px)
        let scale = displayScale
        let mgr = imageManager
        let opts = prefetchOptions
        let cache = self.cache

        let op = BlockOperation { [weak self] in
            defer {
                if let self {
                    self.prefetchLock.lock()
                    if self.inflightPrefetch[key] === token {
                        self.inflightPrefetch.removeValue(forKey: key)
                    }
                    self.prefetchLock.unlock()
                }
            }
            guard !token.isCancelled, cache.object(forKey: key as NSString) == nil else { return }

            var fetched: UIImage?
            var isDegraded = false
            _ = mgr.requestImage(
                for: asset,
                targetSize: size,
                contentMode: .aspectFill,
                options: opts
            ) { image, info in
                fetched = image
                isDegraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
            }

            guard !token.isCancelled, let image = fetched, !isDegraded else { return }
            let prepared = Self.prepare(
                image,
                targetPixelSide: px,
                style: style,
                displayScale: scale
            )
            guard !token.isCancelled else { return }
            let cost = prepared.cgImage.map { $0.bytesPerRow * $0.height }
                ?? Int(prepared.size.width * prepared.size.height * prepared.scale * prepared.scale * 4)
            cache.setObject(prepared, forKey: key as NSString, cost: max(1, cost))
        }
        op.queuePriority = .low
        token.attachOperation(op)
        prefetchQueue.addOperation(op)
        return token
    }

    nonisolated static func prepare(
        _ image: UIImage,
        targetPixelSide: Int,
        style: ThumbnailStyle,
        displayScale: CGFloat
    ) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let geo = ThumbnailSizeRule.decodedGeometry(
            sourceWidth: cg.width,
            sourceHeight: cg.height,
            targetPixelSide: targetPixelSide
        )
        let bytesPerRow = ((geo.width * 4 + 63) / 64) * 64
        guard let ctx = CGContext(
            data: nil,
            width: geo.width,
            height: geo.height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: sRGBColorSpace,
            bitmapInfo: bgrxBitmapInfo
        ) else {
            return image
        }
        ctx.interpolationQuality = .medium
        ctx.setFillColor(red: 0x1C / 255.0, green: 0x1A / 255.0, blue: 0x18 / 255.0, alpha: 1.0)
        ctx.fill(CGRect(x: 0, y: 0, width: geo.width, height: geo.height))
        ctx.draw(cg, in: geo.drawRect)

        if style == .trashed, let data = ctx.data {
            var src = vImage_Buffer(
                data: data,
                height: vImagePixelCount(geo.height),
                width: vImagePixelCount(geo.width),
                rowBytes: bytesPerRow
            )
            var dst = src
            _ = vImageMatrixMultiply_ARGB8888(
                &src,
                &dst,
                TrashedColorMatrix.bgraMatrix,
                TrashedColorMatrix.divisor,
                nil,
                nil,
                vImage_Flags(kvImageNoFlags)
            )
        }

        guard let outCG = ctx.makeImage() else { return image }
        return UIImage(cgImage: outCG, scale: displayScale, orientation: image.imageOrientation)
    }
}

/// 每个 UICollectionView 独立持有的滑动窗预取器，离主线程维护 PHCachingImageManager 窗口并将位图预解入 NSCache。
@MainActor
final class ThumbnailPrefetchWindow {
    private let pipeline: ThumbnailPipeline
    private var cachedSide: CGFloat = 0
    private var cachedStyle: ThumbnailStyle = .normal
    private var tracked: [String: (asset: PHAsset, token: ThumbnailToken?)] = [:]

    init(pipeline: ThumbnailPipeline) {
        self.pipeline = pipeline
    }

    func warmInitial(_ assets: [PHAsset], side: CGFloat, style: ThumbnailStyle = .normal, limit: Int = 48) {
        guard side > 1, !assets.isEmpty, limit > 0 else { return }
        startCaching(Array(assets.prefix(limit)), side: side, style: style)
    }

    func startCaching(_ assets: [PHAsset], side: CGFloat, style: ThumbnailStyle = .normal) {
        guard side > 1, !assets.isEmpty else { return }
        if abs(side - cachedSide) > 0.5 || style != cachedStyle {
            reset()
            cachedSide = side
            cachedStyle = style
        }
        var added: [PHAsset] = []
        added.reserveCapacity(assets.count)
        for asset in assets where tracked[asset.localIdentifier] == nil {
            let tok = pipeline.prefetch(asset: asset, side: side, style: style)
            tracked[asset.localIdentifier] = (asset, tok)
            added.append(asset)
        }
        guard !added.isEmpty else { return }
        let mgr = pipeline.imageManager
        let target = pipeline.targetSize(side: side)
        let opts = pipeline.cachingOptions
        let op = BlockOperation {
            mgr.startCachingImages(for: added, targetSize: target, contentMode: .aspectFill, options: opts)
        }
        op.queuePriority = .low
        pipeline.photoKitQueue.addOperation(op)
    }

    func stopCaching(_ assets: [PHAsset]) {
        guard !assets.isEmpty, cachedSide > 1 else { return }
        var removed: [PHAsset] = []
        removed.reserveCapacity(assets.count)
        for asset in assets {
            if let existing = tracked.removeValue(forKey: asset.localIdentifier) {
                existing.token?.cancel()
                removed.append(existing.asset)
            }
        }
        guard !removed.isEmpty else { return }
        let mgr = pipeline.imageManager
        let target = pipeline.targetSize(side: cachedSide)
        let opts = pipeline.cachingOptions
        let op = BlockOperation {
            mgr.stopCachingImages(for: removed, targetSize: target, contentMode: .aspectFill, options: opts)
        }
        op.queuePriority = .low
        pipeline.photoKitQueue.addOperation(op)
    }

    func reset() {
        var assets: [PHAsset] = []
        assets.reserveCapacity(tracked.count)
        for entry in tracked.values {
            entry.token?.cancel()
            assets.append(entry.asset)
        }
        if !assets.isEmpty, cachedSide > 1 {
            let mgr = pipeline.imageManager
            let target = pipeline.targetSize(side: cachedSide)
            let opts = pipeline.cachingOptions
            let op = BlockOperation {
                mgr.stopCachingImages(for: assets, targetSize: target, contentMode: .aspectFill, options: opts)
            }
            op.queuePriority = .low
            pipeline.photoKitQueue.addOperation(op)
        }
        tracked.removeAll()
        cachedSide = 0
        cachedStyle = .normal
    }
}
