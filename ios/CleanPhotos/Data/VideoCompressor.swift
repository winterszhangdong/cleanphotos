import AVFoundation
import CoreLocation
import Foundation
import Photos

/// 读本地视频、按计划写成 H.264 SDR mp4，再入库。不下载 iCloud，不用 1080p 预设（那个会把小视频放大）。
enum VideoCompressor {
    enum Loaded {
        case notOnDevice
        case unsupported
        case ready(VideoCompressMediaFacts, AVAsset)
    }

    static func load(_ asset: PHAsset, byteSize: Int64) async -> Loaded {
        let requested = await requestLocalAsset(asset)
        guard let av = requested.asset else {
            return requested.inCloud ? .notOnDevice : .unsupported
        }
        do {
            if requested.inCloud {
                let playable = try await av.load(.isPlayable)
                if !playable { return .notOnDevice }
            }
            let duration = try await av.load(.duration).seconds
            guard duration.isFinite, duration > 0 else { return .unsupported }
            let tracks = try await av.loadTracks(withMediaType: .video)
            guard let track = tracks.first else { return .unsupported }
            let natural = try await track.load(.naturalSize)
            let preferred = try await track.load(.preferredTransform)
            let nominal = try await track.load(.nominalFrameRate)
            let affine = VideoCompressAffine(
                a: Double(preferred.a), b: Double(preferred.b),
                c: Double(preferred.c), d: Double(preferred.d),
                tx: Double(preferred.tx), ty: Double(preferred.ty)
            )
            let display = VideoCompressGeometry.displaySize(
                naturalWidth: Double(natural.width),
                naturalHeight: Double(natural.height),
                transform: affine
            )
            let width = Int(display.width.rounded())
            let height = Int(display.height.rounded())
            guard width > 0, height > 0 else { return .unsupported }
            let facts = VideoCompressMediaFacts(
                width: width,
                height: height,
                framesPerSecond: nominal > 0 ? Double(nominal) : nil,
                byteSize: byteSize,
                duration: duration
            )
            return .ready(facts, av)
        } catch {
            return .unsupported
        }
    }

    static func write(
        _ asset: AVAsset,
        plan: VideoCompressPlan.Output,
        to url: URL,
        isCancelled: @escaping @Sendable () -> Bool,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> TimeInterval {
        if isCancelled() { throw VideoCompressWriteError.cancelled }
        let duration = try await asset.load(.duration)
        guard duration.isNumeric, duration.seconds > 0 else { throw VideoCompressWriteError.unsupported }
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        guard let videoTrack = videoTracks.first else { throw VideoCompressWriteError.unsupported }
        let natural = try await videoTrack.load(.naturalSize)
        let preferred = try await videoTrack.load(.preferredTransform)
        let mapped = VideoCompressGeometry.outputTransform(
            naturalWidth: Double(natural.width),
            naturalHeight: Double(natural.height),
            preferred: VideoCompressAffine(
                a: Double(preferred.a), b: Double(preferred.b),
                c: Double(preferred.c), d: Double(preferred.d),
                tx: Double(preferred.tx), ty: Double(preferred.ty)
            ),
            outWidth: Double(plan.width),
            outHeight: Double(plan.height)
        )

        let reader = try AVAssetReader(asset: asset)
        let composition = AVMutableVideoComposition()
        composition.renderSize = CGSize(width: plan.width, height: plan.height)
        composition.frameDuration = CMTime(seconds: 1.0 / plan.framesPerSecond, preferredTimescale: 600)
        composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
        composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
        composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: duration)
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        layer.setTransform(
            CGAffineTransform(a: mapped.a, b: mapped.b, c: mapped.c, d: mapped.d, tx: mapped.tx, ty: mapped.ty),
            at: .zero
        )
        instruction.layerInstructions = [layer]
        composition.instructions = [instruction]

        let pixel: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        ]
        let videoOutput = AVAssetReaderVideoCompositionOutput(videoTracks: [videoTrack], videoSettings: pixel)
        videoOutput.videoComposition = composition
        videoOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOutput) else { throw VideoCompressWriteError.unsupported }
        reader.add(videoOutput)

        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings(plan))
        videoInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoInput) else { throw VideoCompressWriteError.unsupported }
        writer.add(videoInput)

        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        let audio: AudioWire?
        if let audioTrack = audioTracks.first {
            audio = try await attachAudio(track: audioTrack, reader: reader, writer: writer)
        } else {
            audio = nil
        }
        guard reader.startReading() else { throw VideoCompressWriteError.unsupported }
        guard writer.startWriting() else { throw mapWriter(writer.error) }
        writer.startSession(atSourceTime: .zero)

        let seconds = duration.seconds
        let videoPump = Pump(reader: reader, output: videoOutput, input: videoInput, isCancelled: isCancelled) { time in
            guard seconds > 0 else { return }
            onProgress(min(1, max(0, time / seconds)))
        }
        let audioPump: Pump? = audio.map {
            Pump(reader: reader, output: $0.output, input: $0.input, isCancelled: isCancelled, onTime: nil)
        }
        do {
            // 视频和声音要一起抽。只抽完一路再抽另一路，reader 的内部缓冲会堵死。
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { try await videoPump.run() }
                if let audioPump { group.addTask { try await audioPump.run() } }
                try await group.waitForAll()
            }
        } catch {
            reader.cancelReading()
            writer.cancelWriting()
            throw error
        }
        if isCancelled() || reader.status == .cancelled {
            reader.cancelReading()
            writer.cancelWriting()
            throw VideoCompressWriteError.cancelled
        }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            writer.finishWriting { cont.resume() }
        }
        guard writer.status == .completed else { throw mapWriter(writer.error) }
        guard reader.status == .completed else { throw VideoCompressWriteError.unsupported }
        let written = AVURLAsset(url: url)
        let out = try await written.load(.duration).seconds
        guard out.isFinite, out > 0 else { throw VideoCompressWriteError.failed }
        return out
    }

    static func save(_ request: VideoCompressSaveRequest) async throws -> String {
        final class Holder: @unchecked Sendable {
            var id: String?
        }
        let holder = Holder()
        try await PHPhotoLibrary.shared().performChanges {
            let creation = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = request.filename
            creation.addResource(with: .video, fileURL: request.fileURL, options: options)
            creation.creationDate = request.creationDate
            if let latitude = request.latitude, let longitude = request.longitude {
                creation.location = CLLocation(latitude: latitude, longitude: longitude)
            }
            creation.isFavorite = request.systemFavorite
            holder.id = creation.placeholderForCreatedAsset?.localIdentifier
        }
        guard let id = holder.id, fetch(id) != nil else { throw VideoCompressWriteError.failed }
        return id
    }

    static func fetch(_ id: String) -> PHAsset? {
        PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
    }

    static func freeBytes() -> Int64 {
        let values = try? URL(fileURLWithPath: NSTemporaryDirectory())
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let cap = values?.volumeAvailableCapacityForImportantUsage { return cap }
        let attrs = try? FileManager.default.attributesOfFileSystem(forPath: NSTemporaryDirectory())
        return (attrs?[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
    }

    static func makeTempURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("cleanphotos-compress-\(UUID().uuidString).mp4")
    }

    static func deleteTemp(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - Reader / writer

    private struct AudioWire {
        var output: AVAssetReaderTrackOutput
        var input: AVAssetWriterInput
    }

    /// AAC 能进 mp4 就原样拷。reader 和 writer 都接受才提交；否则转成 AAC-LC 立体声 160 kbps。
    private static func attachAudio(track: AVAssetTrack, reader: AVAssetReader, writer: AVAssetWriter) async throws -> AudioWire {
        if await isMP4AAC(track) {
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
            output.alwaysCopiesSampleData = false
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil)
            input.expectsMediaDataInRealTime = false
            if reader.canAdd(output), writer.canAdd(input) {
                reader.add(output)
                writer.add(input)
                return AudioWire(output: output, input: input)
            }
        }
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 2,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: outputSettings)
        output.alwaysCopiesSampleData = false
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 2,
            AVEncoderBitRateKey: VideoCompressPlan.audioBitrate,
        ])
        input.expectsMediaDataInRealTime = false
        guard reader.canAdd(output), writer.canAdd(input) else { throw VideoCompressWriteError.unsupported }
        reader.add(output)
        writer.add(input)
        return AudioWire(output: output, input: input)
    }

    private static func isMP4AAC(_ track: AVAssetTrack) async -> Bool {
        guard let descriptions = try? await track.load(.formatDescriptions) else { return false }
        let aac: Set<FourCharCode> = [
            kAudioFormatMPEG4AAC,
            kAudioFormatMPEG4AAC_HE,
            kAudioFormatMPEG4AAC_HE_V2,
            kAudioFormatMPEG4AAC_LD,
            kAudioFormatMPEG4AAC_ELD,
        ]
        return descriptions.contains { aac.contains(CMFormatDescriptionGetMediaSubType($0)) }
    }

    private static func videoSettings(_ plan: VideoCompressPlan.Output) -> [String: Any] {
        [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: plan.width,
            AVVideoHeightKey: plan.height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: plan.videoBitrate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                AVVideoMaxKeyFrameIntervalDurationKey: 1,
                AVVideoAllowFrameReorderingKey: true,
            ],
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
        ]
    }

    private static func mapWriter(_ error: Error?) -> VideoCompressWriteError {
        guard let error else { return .failed }
        let ns = error as NSError
        if ns.domain == AVFoundationErrorDomain {
            let code = AVError.Code(rawValue: ns.code)
            switch code {
            case .decoderNotFound, .encoderNotFound, .unsupportedOutputSettings, .formatUnsupported,
                 .fileTypeDoesNotSupportSampleReferences, .encoderTemporarilyUnavailable:
                return .unsupported
            default:
                break
            }
        }
        return .failed
    }

    private static func requestLocalAsset(_ asset: PHAsset) async -> (asset: AVAsset?, inCloud: Bool) {
        await withCheckedContinuation { cont in
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = false
            options.deliveryMode = .highQualityFormat
            options.version = .current
            let gate = ResumeOnce<(asset: AVAsset?, inCloud: Bool)>()
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { av, _, info in
                let cancelled = (info?[PHImageCancelledKey] as? Bool) == true
                let inCloud = (info?[PHImageResultIsInCloudKey] as? Bool) == true
                if cancelled {
                    gate.resume(cont, returning: (asset: nil, inCloud: inCloud))
                    return
                }
                gate.resume(cont, returning: (asset: av, inCloud: inCloud))
            }
        }
    }
}

private final class ResumeOnce<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func resume(_ cont: CheckedContinuation<T, Never>, returning value: T) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        guard first else { return }
        cont.resume(returning: value)
    }
}

private final class Pump: @unchecked Sendable {
    let reader: AVAssetReader
    let output: AVAssetReaderOutput
    let input: AVAssetWriterInput
    let isCancelled: @Sendable () -> Bool
    let onTime: (@Sendable (Double) -> Void)?
    let queue = DispatchQueue(label: "cleanphotos.compress.pump")

    init(
        reader: AVAssetReader,
        output: AVAssetReaderOutput,
        input: AVAssetWriterInput,
        isCancelled: @escaping @Sendable () -> Bool,
        onTime: (@Sendable (Double) -> Void)?
    ) {
        self.reader = reader
        self.output = output
        self.input = input
        self.isCancelled = isCancelled
        self.onTime = onTime
    }

    func run() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let gate = ErrorResumeOnce()
            self.input.requestMediaDataWhenReady(on: self.queue) {
                while self.input.isReadyForMoreMediaData {
                    if self.isCancelled() {
                        self.reader.cancelReading()
                        self.input.markAsFinished()
                        gate.resume(cont, .failure(VideoCompressWriteError.cancelled))
                        return
                    }
                    guard let sample = self.output.copyNextSampleBuffer() else {
                        self.input.markAsFinished()
                        if self.reader.status == .cancelled {
                            gate.resume(cont, .failure(VideoCompressWriteError.cancelled))
                        } else if self.reader.status == .failed {
                            gate.resume(cont, .failure(VideoCompressWriteError.unsupported))
                        } else {
                            gate.resume(cont, .success(()))
                        }
                        return
                    }
                    if !self.input.append(sample) {
                        self.reader.cancelReading()
                        self.input.markAsFinished()
                        gate.resume(cont, .failure(VideoCompressWriteError.failed))
                        return
                    }
                    let time = CMSampleBufferGetPresentationTimeStamp(sample).seconds
                    if time.isFinite { self.onTime?(time) }
                }
            }
        }
    }
}

private final class ErrorResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func resume(_ cont: CheckedContinuation<Void, Error>, _ result: Result<Void, Error>) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        guard first else { return }
        cont.resume(with: result)
    }
}
