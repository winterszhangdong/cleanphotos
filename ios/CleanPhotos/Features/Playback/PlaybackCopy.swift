import Foundation

/// 播放控件文案。不进 Localizable.xcstrings，也不走 L10n.plural。
enum PlaybackCopy {
    static func motion(zh: Bool) -> String { zh ? "动态" : "Motion" }
    static func play(zh: Bool) -> String { zh ? "播放" : "Play" }
    static func pause(zh: Bool) -> String { zh ? "暂停" : "Pause" }
    static func soundOn(zh: Bool) -> String { zh ? "打开声音" : "Sound on" }
    static func soundOff(zh: Bool) -> String { zh ? "关闭声音" : "Sound off" }
    static func replay(zh: Bool) -> String { zh ? "播放动态照片" : "Play motion photo" }
    static func showControls(zh: Bool) -> String { zh ? "显示暂停和进度" : "Show pause and progress" }
    static func hideControls(zh: Bool) -> String { zh ? "隐藏暂停和进度" : "Hide pause and progress" }
    static func progress(zh: Bool) -> String { zh ? "播放进度" : "Playback progress" }
}
