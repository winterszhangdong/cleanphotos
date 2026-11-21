import Foundation
import Photos
import UIKit

/// 清理页上的一次压缩。进度留在这一页；切到后台只靠 beginBackgroundTask 多留一会儿，不做 BGProcessingTask。
@MainActor
final class VideoCompressController: ObservableObject {
    enum Phase: Equatable {
        case idle
        case confirm
        case running(Int)
        case failed(VideoCompressFailure)
    }

    @Published private(set) var phase: Phase = .idle

    var isRunning: Bool {
        if case .running = phase { return true }
        return false
    }
    var isConfirming: Bool { phase == .confirm }
    /// 压缩中和失败短句都把文件信息和按钮留在页面上。
    var keepsDetails: Bool {
        if isRunning { return true }
        if case .failed = phase { return true }
        return false
    }

    private var task: Task<Void, Never>?
    private var cancelFlag = CompressCancelFlag()
    private var background: UIBackgroundTaskIdentifier = .invalid

    func buttonTitle(zh: Bool) -> String {
        if case .running(let percent) = phase { return CompressCopy.percent(percent) }
        return CompressCopy.button(zh: zh)
    }

    func failureMessage(zh: Bool) -> String? {
        guard case .failed(let failure) = phase else { return nil }
        return CompressCopy.failure(failure, zh: zh)
    }

    func ask() {
        guard !isRunning else { return }
        phase = .confirm
    }

    func dismissConfirm() {
        if case .confirm = phase { phase = .idle }
    }

    func dismissFailure() {
        if case .failed = phase { phase = .idle }
    }

    func start(photo: PhotoItem, library: PhotoLibraryService, onDone: @escaping (PhotoItem?) -> Void) {
        guard !isRunning else { return }
        let live = photo.asset.mediaSubtypes.contains(.photoLive)
        guard VideoCompressPlan.offersEntry(isVideo: photo.isVideo, isLivePhoto: live, isTrashed: photo.isTrashed) else {
            phase = .idle
            return
        }
        phase = .running(0)
        let flag = CompressCancelFlag()
        cancelFlag = flag
        beginBackground()
        task = Task { [weak self] in
            let item = await self?.execute(photo: photo, library: library, flag: flag)
            self?.endBackground()
            guard let self else { return }
            if let item {
                self.phase = .idle
                onDone(item)
                return
            }
            if flag.isCancelled {
                self.phase = .idle
                onDone(nil)
                return
            }
            if case .running = self.phase {
                self.phase = .failed(.failed)
            }
            onDone(nil)
        }
    }

    func cancel() {
        guard isRunning else { return }
        cancelFlag.cancel()
        task?.cancel()
    }

    private func execute(photo: PhotoItem, library: PhotoLibraryService, flag: CompressCancelFlag) async -> PhotoItem? {
        let info = AssetSizeCache.shared.info(for: photo.asset)
        let location = photo.asset.location?.coordinate
        let source = VideoCompressSource(
            id: photo.id,
            creationDate: photo.asset.creationDate,
            latitude: location?.latitude,
            longitude: location?.longitude,
            originalFilename: info.originalFilename,
            appFavorite: library.favorites.contains(photo.id),
            systemFavorite: photo.asset.isFavorite
        )
        let loaded = await VideoCompressor.load(photo.asset, byteSize: info.bytes)
        let media: VideoCompressMedia
        let av: AVAsset?
        switch loaded {
        case .notOnDevice:
            media = .notOnDevice
            av = nil
        case .unsupported:
            media = .unsupported
            av = nil
        case .ready(let facts, let asset):
            media = .ready(facts)
            av = asset
        }
        let hold = CreatedAsset()
        let result = await VideoCompressJob.run(
            source: source,
            media: media,
            freeBytes: VideoCompressor.freeBytes(),
            makeTemp: { VideoCompressor.makeTempURL() },
            write: { plan, url, progress in
                guard let av else { throw VideoCompressWriteError.unsupported }
                return try await VideoCompressor.write(av, plan: plan, to: url, isCancelled: { flag.isCancelled }) { fraction in
                    progress(fraction)
                }
            },
            save: { request in
                let id = try await VideoCompressor.save(request)
                if let created = VideoCompressor.fetch(id) { hold.set(created) }
                return id
            },
            addFavorite: { library.favorites.add([$0]) },
            markSeen: { library.markSeen([$0]) },
            moveToTrash: { _ = library.moveToTrash(ids: [$0]) },
            deleteFile: { VideoCompressor.deleteTemp($0) },
            isCancelled: { flag.isCancelled },
            onProgress: { [weak self] fraction in
                let percent = min(99, max(0, Int((fraction * 100).rounded(.down))))
                Task { @MainActor in
                    guard let self, case .running = self.phase else { return }
                    self.phase = .running(percent)
                }
            }
        )
        switch result {
        case .success(let id):
            guard let asset = hold.take() ?? VideoCompressor.fetch(id) else {
                phase = .failed(.failed)
                return nil
            }
            var item = library.insertCreated(asset)
            item.isFavorite = library.favorites.contains(id)
            item.isUnseen = !library.seen.seenIds.contains(id)
            return item
        case .failure(let failure):
            phase = .failed(failure)
            return nil
        }
    }

    private func beginBackground() {
        endBackground()
        background = UIApplication.shared.beginBackgroundTask(withName: "compress-video") { [weak self] in
            Task { @MainActor in self?.endBackground() }
        }
    }

    private func endBackground() {
        let id = background
        background = .invalid
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
    }
}

private final class CompressCancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
    func cancel() {
        lock.lock()
        value = true
        lock.unlock()
    }
}

private final class CreatedAsset: @unchecked Sendable {
    private let lock = NSLock()
    private var value: PHAsset?
    func set(_ asset: PHAsset) {
        lock.lock()
        value = asset
        lock.unlock()
    }
    func take() -> PHAsset? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
