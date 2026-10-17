import Foundation
import os

/// 启动/加载耗时打点：os_signpost（Instruments 可见）+ 一行 NSLog（`log stream` / Xcode 控制台可见）。
enum PerfLog {
    static let log = OSLog(subsystem: "com.winters.cleanphotos", category: .pointsOfInterest)
    /// 进程内首次访问的时间点；App.init 里先访问一次。
    static let t0 = CFAbsoluteTimeGetCurrent()
    private static var marked = Set<String>()

    /// Release 默认不打 NSLog（signpost 仍在，几乎零开销）；调试或启动参数 `-cpperf YES` 时打开。
    static let verbose: Bool = {
        #if DEBUG
        return true
        #else
        return UserDefaults.standard.bool(forKey: "cpperf")
        #endif
    }()

    static func mark(_ name: StaticString, _ detail: String = "") {
        os_signpost(.event, log: log, name: name, "%{public}s", detail)
        guard verbose else { return }
        let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        NSLog("[cpperf] %@ +%dms %@", "\(name)", ms, detail)
    }

    /// 只打一次（比如第一张缩略图）。
    static func once(_ name: StaticString, _ detail: String = "") {
        let key = "\(name)"
        guard !marked.contains(key) else { return }
        marked.insert(key)
        mark(name, detail)
    }
}

/// 性能对比用：启动参数 `-cpLegacy YES` 时关掉这次清理页翻页优化（实时模糊、无缓存、每页重算网格…），
/// 在同一台机器、同一套测量里得到「改之前」的数字。正常运行时恒为 false。
enum PerfLegacy {
    static let on = UserDefaults.standard.bool(forKey: "cpLegacy")
}

/// 真机性能测量（启动参数 -cpAutoSwipe N）跑的是用户自己的相册：收藏 / 垃圾桶 / 已看 / 教学与滑动计数只改内存、
/// 不写 UserDefaults（编码照做，开销与真实一致），进程退出即恢复原样。不会删除任何照片。
enum PerfDryRun {
    static let on = UserDefaults.standard.integer(forKey: "cpAutoSwipe") > 0
}

#if canImport(UIKit)
import UIKit
import QuartzCore

/// 帧时间采样（启动参数 `-cpfps YES` 才开）：CADisplayLink 按屏幕最高刷新率跑，
/// 记录相邻回调间隔；超过期望间隔 1.5 倍算掉帧，掉帧时长 = 间隔 − 期望间隔（近似 Apple hitch time）。
/// stop 时打一行 `[cpfps] label frames=… dur=… hitchMs=… ratio=…ms/s max=… p95=… drops=…`。
final class FrameMonitor: NSObject {
    static let shared = FrameMonitor()
    static let enabled = UserDefaults.standard.bool(forKey: "cpfps")
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var intervals: [Double] = []
    private var expected: [Double] = []
    private var startT: CFTimeInterval = 0

    func start() {
        guard Self.enabled, link == nil else { return }
        intervals.removeAll(); expected.removeAll(); last = 0
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
        startT = CACurrentMediaTime()
    }

    @objc private func tick(_ l: CADisplayLink) {
        if last > 0 {
            intervals.append(l.timestamp - last)
            expected.append(l.targetTimestamp - l.timestamp)
        }
        last = l.timestamp
    }

    func stop(_ label: String) {
        guard let l = link else { return }
        l.invalidate(); link = nil
        let dur = CACurrentMediaTime() - startT
        guard !intervals.isEmpty, dur > 0 else { return }
        var hitch = 0.0, drops = 0
        for (i, iv) in intervals.enumerated() {
            let e = expected[i]
            if iv > e * 1.5 { hitch += iv - e; drops += 1 }
        }
        let sorted = intervals.sorted()
        let p95 = sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
        let p99 = sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.99))]
        let exp = expected.sorted()[expected.count / 2]
        let line = String(format: "[cpfps] %@ frames=%d dur=%.2fs hz=%.0f hitchMs=%.1f ratio=%.1fms/s max=%.1fms p95=%.1fms p99=%.1fms drops=%d",
                          label, intervals.count, dur, 1 / exp, hitch * 1000, hitch * 1000 / dur, sorted.last! * 1000, p95 * 1000, p99 * 1000, drops)
        let tag = UserDefaults.standard.string(forKey: "cpfpsLabel") ?? ""
        let line2 = tag.isEmpty ? line : line.replacingOccurrences(of: "[cpfps] \(label)", with: "[cpfps] \(label)-\(tag)")
        NSLog("%@", line2)
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("cpfps.log")
        if let h = try? FileHandle(forWritingTo: url) { h.seekToEndOfFile(); h.write((line2 + "\n").data(using: .utf8)!); try? h.close() }
        else { try? (line2 + "\n").write(to: url, atomically: true, encoding: .utf8) }
    }
}
#endif
