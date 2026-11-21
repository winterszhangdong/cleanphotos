import Foundation

/// 清理页压缩文案。不进 Localizable.xcstrings。
enum CompressCopy {
    static func button(zh: Bool) -> String { zh ? "压缩" : "Compress" }
    static func cancel(zh: Bool) -> String { zh ? "取消" : "Cancel" }
    static func confirm(zh: Bool) -> String { zh ? "压缩" : "Compress" }
    static func title(zh: Bool) -> String { zh ? "压成 1080p？" : "Compress to 1080p?" }
    static func body(zh: Bool) -> String {
        zh ? "原片会移到垃圾桶，清空后才会空出空间。" : "The original moves to the trash. Space is freed only after you empty it."
    }
    static func percent(_ value: Int) -> String { "\(value)%" }

    static func failure(_ failure: VideoCompressFailure, zh: Bool) -> String? {
        switch failure {
        case .unsupported:
            return zh ? "这部手机解不了这种视频" : "This phone can't decode this video"
        case .notEnoughSpace:
            return zh ? "空间不够" : "Not enough space"
        case .durationMismatch:
            return zh ? "压完时长和原来对不上，已停止" : "The length doesn't match the original, so it stopped"
        case .alreadySmall:
            return zh ? "已经够小" : "Already small enough"
        case .notOnDevice:
            return zh ? "视频不在这台手机上" : "This video isn't on this phone"
        case .failed:
            return zh ? "没能压完" : "Couldn't finish compressing"
        case .cancelled:
            return nil
        }
    }
}
