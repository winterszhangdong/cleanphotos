import AVFoundation
import Foundation
import Photos

struct PlaybackLoaded: Equatable {
    var id: String
    var kind: PlaybackKind
}

enum PlaybackAsset {
    /// 只看当前这张 PHAsset 上已经有的类型。不扫整库，也不读厂商嵌在 JPEG 里的视频。
    static func kind(of asset: PHAsset) -> PlaybackKind? {
        if asset.mediaType == .video { return .video }
        if asset.mediaSubtypes.contains(.photoLive) { return .motion }
        return nil
    }
}

/// 实况照片的配套视频。只在要播的那一张（以及下一张预取）上写临时文件。
enum LiveVideoCache {
    private final class Slot {
        let task: Task<URL, Error>
        init(_ task: Task<URL, Error>) { self.task = task }
    }

    private static let lock = NSLock()
    private static var inflight: [String: Slot] = [:]

    static func prefetch(_ asset: PHAsset) {
        guard PlaybackAsset.kind(of: asset) == .motion else { return }
        Task { _ = try? await file(for: asset) }
    }

    static func file(for asset: PHAsset) async throws -> URL {
        let key = asset.localIdentifier
        let slot = lock.withLock { () -> Slot in
            if let existing = inflight[key] { return existing }
            let created = Slot(Task { try await produce(asset) })
            inflight[key] = created
            return created
        }
        do {
            return try await slot.task.value
        } catch {
            lock.withLock {
                if inflight[key] === slot { inflight[key] = nil }
            }
            throw error
        }
    }

    private static func produce(_ asset: PHAsset) async throws -> URL {
        let folder = try folderURL()
        let name = asset.localIdentifier.replacingOccurrences(of: "/", with: "_") + ".mov"
        let url = folder.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: url.path) { return url }
        let resource: PHAssetResource? = PHAssetResource.assetResources(for: asset).first { $0.type == .pairedVideo }
        guard let resource else { throw CocoaError(.fileNoSuchFile) }
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = false
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options) { error in
                if let error {
                    try? FileManager.default.removeItem(at: url)
                    cont.resume(throwing: error)
                } else {
                    cont.resume()
                }
            }
        }
        trim(folder)
        return url
    }

    private static func folderURL() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cleanphotos-live", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private static func trim(_ folder: URL) {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        guard files.count > 8 else { return }
        let dated = files.map { url -> (URL, Date) in
            let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            return (url, date)
        }.sorted { $0.1 < $1.1 }
        for item in dated.dropLast(8) {
            try? FileManager.default.removeItem(at: item.0)
        }
    }
}

/// 清理页和垃圾桶各持有一个。渲染器，不进数据层。
/// 进度不发布：进度行自己每 200ms 读一次，避免整页跟着重画。
@MainActor
final class PlaybackEngine: ObservableObject {
    /// 进度轮询间隔。不是动效 token。
    static let positionPoll: TimeInterval = 0.2

    @Published private(set) var loaded: PlaybackLoaded?
    @Published private(set) var ended = false
    @Published private(set) var userPaused = false
    @Published private(set) var started = false
    @Published private(set) var resumed = true
    @Published private(set) var loadFailed = false

    let player = AVPlayer()
    private var asset: PHAsset?
    private var token = 0
    private var videoRequest: PHImageRequestID = PHInvalidImageRequestID
    private var endObserver: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var rememberedMute = true
    private var appliedMuted: Bool?
    private var sessionActive = false
    /// 中心键在条目还没到、或上次加载失败时按下。条目一到就播，不等那 250ms。
    private var playWhenReady = false

    init() {
        player.isMuted = true
        player.volume = 0
        player.actionAtItemEnd = .pause
        player.audiovisualBackgroundPlaybackPolicy = .pauses
    }

    var hasItem: Bool { player.currentItem != nil }

    var isPlaying: Bool { player.timeControlStatus == .playing }

    var currentSeconds: Double {
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, seconds) : 0
    }

    var durationSeconds: Double {
        let seconds = player.currentItem?.duration.seconds ?? 0
        return seconds.isFinite ? max(0, seconds) : 0
    }

    func ensureLoaded(id: String?, asset: PHAsset?) {
        guard let id, let asset, let kind = PlaybackAsset.kind(of: asset) else {
            clear()
            return
        }
        if loaded?.id == id, loaded?.kind == kind, !loadFailed { return }
        if let current = loaded?.id, current != id { playWhenReady = false }
        self.asset = asset
        cancelRequest()
        tearDownItem()
        player.pause()
        player.replaceCurrentItem(with: nil)
        token += 1
        ended = false
        userPaused = false
        started = false
        loadFailed = false
        loaded = PlaybackLoaded(id: id, kind: kind)
        applyMute(remembered: rememberedMute)
        let token = self.token
        switch kind {
        case .video:
            loadVideo(asset, token: token)
        case .motion:
            loadMotion(asset, token: token)
        }
    }

    func clear() {
        token += 1
        cancelRequest()
        tearDownItem()
        player.pause()
        player.replaceCurrentItem(with: nil)
        asset = nil
        loaded = nil
        ended = false
        userPaused = false
        started = false
        loadFailed = false
        playWhenReady = false
        applyMute(remembered: rememberedMute)
    }

    func applyMute(remembered: Bool) {
        rememberedMute = remembered
        let muted = remembered || loaded?.kind == .motion || loaded == nil
        player.isMuted = muted
        player.volume = muted ? 0 : 1
        if appliedMuted == muted { return }
        appliedMuted = muted
        let session = AVAudioSession.sharedInstance()
        if muted {
            try? session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
            if sessionActive {
                try? session.setActive(false, options: [.notifyOthersOnDeactivation])
                sessionActive = false
            }
        } else {
            try? session.setCategory(.playback, mode: .moviePlayback)
            try? session.setActive(true)
            sessionActive = true
        }
    }

    func noteBackground() {
        resumed = false
        player.pause()
    }

    func noteActive() {
        resumed = true
    }

    func pauseForGesture() {
        player.pause()
    }

    func playFromAutoplay() {
        guard hasItem, !userPaused, !ended, !loadFailed else { return }
        started = true
        player.play()
    }

    func togglePlay() {
        if isPlaying {
            userPaused = true
            player.pause()
            return
        }
        if !hasItem {
            userPaused = false
            playWhenReady = true
            if loadFailed { retry() }
            return
        }
        userPaused = false
        started = true
        if ended {
            ended = false
            player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
                guard finished else { return }
                Task { @MainActor in self?.player.play() }
            }
            return
        }
        player.play()
    }

    /// 动态胶囊：从头再播。不改视频记住的静音开关。
    func replayMotion() {
        guard loaded?.kind == .motion, hasItem else { return }
        userPaused = false
        started = true
        ended = false
        player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            guard finished else { return }
            Task { @MainActor in self?.player.play() }
        }
    }

    /// 时长还没有时不跳。拖到结尾若同步播完，就不再 play。
    @discardableResult
    func seekFraction(_ fraction: Double) -> Bool {
        guard hasItem else { return false }
        let duration = durationSeconds
        guard duration > 0 else { return false }
        let pose = PlaybackRules.poseAfterUserSeek()
        ended = pose.ended
        userPaused = pose.userPaused
        started = pose.started
        let at = duration * min(1, max(0, fraction))
        let time = CMTime(seconds: at, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            guard finished else { return }
            Task { @MainActor in
                guard let self, !self.ended else { return }
                self.player.play()
            }
        }
        return true
    }

    func release() {
        token += 1
        cancelRequest()
        tearDownItem()
        player.pause()
        player.replaceCurrentItem(with: nil)
        if sessionActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            sessionActive = false
        }
        appliedMuted = nil
        playWhenReady = false
    }

    private func retry() {
        guard let id = loaded?.id, let asset else { return }
        loaded = nil
        loadFailed = false
        ensureLoaded(id: id, asset: asset)
    }

    private func loadVideo(_ asset: PHAsset, token: Int) {
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = false
        options.deliveryMode = .automatic
        videoRequest = PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { [weak self] item, info in
            Task { @MainActor in
                guard let self, token == self.token else { return }
                if (info?[PHImageCancelledKey] as? Bool) == true { return }
                let inCloud = (info?[PHImageResultIsInCloudKey] as? Bool) == true
                guard let item, !inCloud else {
                    self.loadFailed = true
                    return
                }
                self.attach(item)
            }
        }
    }

    private func loadMotion(_ asset: PHAsset, token: Int) {
        Task { [weak self] in
            do {
                let url = try await LiveVideoCache.file(for: asset)
                await MainActor.run {
                    guard let self, token == self.token else { return }
                    self.attach(AVPlayerItem(url: url))
                }
            } catch {
                await MainActor.run {
                    guard let self, token == self.token else { return }
                    self.loadFailed = true
                }
            }
        }
    }

    private func attach(_ item: AVPlayerItem) {
        tearDownItem()
        player.replaceCurrentItem(with: item)
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.handleEnded() }
        }
        statusObs = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor in self?.loadFailed = true }
        }
        if playWhenReady {
            playWhenReady = false
            userPaused = false
            started = true
            ended = false
            player.play()
        }
    }

    private func handleEnded() {
        guard !ended else { return }
        let kind = loaded?.kind
        ended = true
        player.pause()
        guard kind == .video else { return }
        let pose = PlaybackRules.videoEndedPose()
        let time = CMTime(seconds: Double(pose.positionMs) / 1000, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func cancelRequest() {
        if videoRequest != PHInvalidImageRequestID {
            PHImageManager.default().cancelImageRequest(videoRequest)
            videoRequest = PHInvalidImageRequestID
        }
    }

    private func tearDownItem() {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        statusObs?.invalidate()
        statusObs = nil
    }
}
