import Foundation
import QuartzCore
import UIKit

/// 列表滚动掉帧归因。启动参数 `-cpScrollProbe YES` 才会计数。
/// 热路径只加整数；停稳（约 0.3s 没有新的滚动事件）打一行 `[cpscroll]`，并追加到 Library/Caches/cpscroll.log。
/// 正常启动不采样。
final class ScrollProbe: NSObject {
    static let shared = ScrollProbe()
    static let enabled = UserDefaults.standard.bool(forKey: "cpScrollProbe")

    /// 当前页面名。滚动开始前由各页写上。
    var page = "unknown"

    private var link: CADisplayLink?
    private var lastTick: CFTimeInterval = 0
    private var lastEvent: CFTimeInterval = 0
    private var expected: Double = 1.0 / 120

    private var tileKVO = 0
    private var tileLayout = 0
    private var convertSec = 0.0
    private var offsetCB = 0
    private var geoCB = 0
    private var ingestCB = 0
    private var ingestSec = 0.0
    private var tasks = 0
    private var thumbMain = 0
    private var thumbDegraded = 0
    private var thumbFinal = 0

    private var frames = 0
    private var movingFrames = 0
    private var drops = 0
    private var movingDrops = 0
    private var hitch = 0.0
    private var movingHitch = 0.0
    private var maxInterval = 0.0
    private var movingMax = 0.0
    private var movingIntervals: [Double] = []
    private var sumTileKVO = 0
    private var sumTileLayout = 0
    private var sumConvert = 0.0
    private var sumOffset = 0
    private var sumGeo = 0
    private var sumIngestN = 0
    private var sumIngest = 0.0
    private var sumTasks = 0
    private var sumThumb = 0
    private var dropTileKVO = 0
    private var dropTileLayout = 0
    private var dropConvert = 0.0
    private var dropOffset = 0
    private var dropGeo = 0
    private var dropIngest = 0.0
    private var dropTasks = 0
    private var dropThumb = 0
    private var sessionStart: CFTimeInterval = 0
    private var aliveSinks = 0
    private var open = false

    func sinkAlive(_ delta: Int) {
        guard Self.enabled else { return }
        aliveSinks += delta
    }

    /// 格子在滚动偏移变化时做的坐标转换。
    func tileFromOffset(convert seconds: Double) {
        guard Self.enabled else { return }
        arm()
        tileKVO += 1
        convertSec += seconds
    }

    /// 格子布局时做的坐标转换（新格子出现，不是每帧）。
    func tileFromLayout(convert seconds: Double) {
        guard Self.enabled else { return }
        arm()
        tileLayout += 1
        convertSec += seconds
    }

    func offsetCallback() {
        guard Self.enabled else { return }
        arm()
        offsetCB += 1
    }

    func geometry() {
        guard Self.enabled else { return }
        arm()
        geoCB += 1
    }

    func ingest(_ seconds: Double) {
        guard Self.enabled else { return }
        arm()
        ingestCB += 1
        ingestSec += seconds
    }

    func taskSpawn() {
        guard Self.enabled else { return }
        arm()
        tasks += 1
    }

    func thumb(main: Bool, degraded: Bool) {
        guard Self.enabled else { return }
        let bump = {
            self.arm()
            if main { self.thumbMain += 1 }
            if degraded { self.thumbDegraded += 1 } else { self.thumbFinal += 1 }
        }
        if Thread.isMainThread {
            bump()
        } else {
            DispatchQueue.main.async(execute: bump)
        }
    }

    private func arm() {
        lastEvent = CACurrentMediaTime()
        guard link == nil else { return }
        if !open {
            open = true
            sessionStart = lastEvent
            frames = 0
            movingFrames = 0
            drops = 0
            movingDrops = 0
            hitch = 0
            movingHitch = 0
            maxInterval = 0
            movingMax = 0
            movingIntervals.removeAll(keepingCapacity: true)
            sumTileKVO = 0
            sumTileLayout = 0
            sumConvert = 0
            sumOffset = 0
            sumGeo = 0
            sumIngestN = 0
            sumIngest = 0
            sumTasks = 0
            sumThumb = 0
            dropTileKVO = 0
            dropTileLayout = 0
            dropConvert = 0
            dropOffset = 0
            dropGeo = 0
            dropIngest = 0
            dropTasks = 0
            dropThumb = 0
        }
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
        lastTick = 0
    }

    @objc private func tick(_ l: CADisplayLink) {
        let now = l.timestamp
        if lastTick > 0 {
            let iv = now - lastTick
            let exp = l.targetTimestamp - l.timestamp
            if exp > 0 { expected = exp }
            let dropped = iv > exp * 1.5
            let moving = tileKVO > 0 || offsetCB > 0
            frames += 1
            if iv > maxInterval { maxInterval = iv }
            if moving {
                movingFrames += 1
                movingIntervals.append(iv)
                if iv > movingMax { movingMax = iv }
            }
            sumTileKVO += tileKVO
            sumTileLayout += tileLayout
            sumConvert += convertSec
            sumOffset += offsetCB
            sumGeo += geoCB
            sumIngestN += ingestCB
            sumIngest += ingestSec
            sumTasks += tasks
            let thumbs = thumbDegraded + thumbFinal
            sumThumb += thumbs
            if dropped {
                drops += 1
                hitch += iv - exp
                if moving {
                    movingDrops += 1
                    movingHitch += iv - exp
                }
                dropTileKVO += tileKVO
                dropTileLayout += tileLayout
                dropConvert += convertSec
                dropOffset += offsetCB
                dropGeo += geoCB
                dropIngest += ingestSec
                dropTasks += tasks
                dropThumb += thumbs
            }
        }
        tileKVO = 0
        tileLayout = 0
        convertSec = 0
        offsetCB = 0
        geoCB = 0
        ingestCB = 0
        ingestSec = 0
        tasks = 0
        thumbMain = 0
        thumbDegraded = 0
        thumbFinal = 0
        lastTick = now
        if open, now - lastEvent > 0.35, frames >= 8 {
            flush()
        }
    }

    private func flush() {
        link?.invalidate()
        link = nil
        let dur = CACurrentMediaTime() - sessionStart
        guard frames > 0, dur > 0 else { open = false; return }
        let n = Double(frames)
        let d = Double(max(drops, 1))
        let mn = Double(max(movingFrames, 1))
        let sorted = movingIntervals.sorted()
        let p95 = sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
        let line = String(
            format: "[cpscroll] page=%@ frames=%d moving=%d dur=%.2fs hz=%.0f drops=%d movingDrops=%d hitchMs=%.1f movingHitchMs=%.1f movingRatio=%.1fms/s maxMs=%.1f movingMaxMs=%.1f movingP95Ms=%.1f sinks=%d tileKVO=%.1f tileKVODrop=%.1f tileLayout=%.2f convertUs=%.0f offset=%.2f geo=%.2f geoDrop=%.2f ingestUs=%.0f ingestDropUs=%.0f tasks=%.2f tasksDrop=%.2f thumbs=%.2f thumbsDrop=%.2f",
            page,
            frames,
            movingFrames,
            dur,
            1 / expected,
            drops,
            movingDrops,
            hitch * 1000,
            movingHitch * 1000,
            movingFrames > 0 ? movingHitch * 1000 / (mn / (1 / expected)) : 0,
            maxInterval * 1000,
            movingMax * 1000,
            p95 * 1000,
            aliveSinks,
            Double(sumTileKVO) / n,
            Double(dropTileKVO) / d,
            Double(sumTileLayout) / n,
            sumConvert / n * 1_000_000,
            Double(sumOffset) / n,
            Double(sumGeo) / n,
            Double(dropGeo) / d,
            sumIngest / n * 1_000_000,
            dropIngest / d * 1_000_000,
            Double(sumTasks) / n,
            Double(dropTasks) / d,
            Double(sumThumb) / n,
            Double(dropThumb) / d
        )
        NSLog("%@", line)
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cpscroll.log")
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile()
            h.write((line + "\n").data(using: .utf8)!)
            try? h.close()
        } else {
            try? (line + "\n").write(to: url, atomically: true, encoding: .utf8)
        }
        open = false
    }

    /// 一页甩完就收口，避免和下一页并成一段。
    func close() {
        guard Self.enabled, open else { return }
        flush()
    }
}

extension Notification.Name {
    /// 探针自己换页。object 为 photos / favorites / trash。
    static let cleanphotosHarnessTab = Notification.Name("cleanphotosHarnessTab")
}

/// `-cpScrollProbe YES` 时自己把四个列表甩一段，用来取样。只改滚动位置。
enum ScrollProbeSweep {
    private static var started = false

    @MainActor
    static func start() {
        guard ScrollProbe.enabled, !started else { return }
        started = true
        Task { @MainActor in
            resetLog()
            await run()
        }
    }

    private static func resetLog() {
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cpscroll.log")
        try? Data().write(to: url)
    }

    @MainActor
    private static func run() async {
        _ = await waitScroll(8)
        await fling("photos-all")
        notifyTab("favorites")
        try? await Task.sleep(nanoseconds: 700_000_000)
        await fling("favorites")
        notifyTab("trash")
        try? await Task.sleep(nanoseconds: 700_000_000)
        await fling("trash")
        notifyTab("photos")
        try? await Task.sleep(nanoseconds: 400_000_000)
        NotificationCenter.default.post(name: .cleanphotosHarnessScope, object: "similar")
        try? await Task.sleep(nanoseconds: 900_000_000)
        await fling("photos-similar")
        mark("sweep-done")
    }

    @MainActor
    private static func notifyTab(_ name: String) {
        NotificationCenter.default.post(name: .cleanphotosHarnessTab, object: name)
    }

    @MainActor
    private static func waitScroll(_ seconds: Double) async -> UIScrollView? {
        let deadline = CACurrentMediaTime() + seconds
        while CACurrentMediaTime() < deadline {
            if let sv = frontScroll() { return sv }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return nil
    }

    @MainActor
    private static func fling(_ label: String) async {
        ScrollProbe.shared.page = label
        guard let sv = frontScroll() else {
            mark("sweep-skip \(label) no-scroll")
            try? await Task.sleep(nanoseconds: 500_000_000)
            return
        }
        let minY = -sv.adjustedContentInset.top
        let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
        let start = sv.contentOffset.y
        let room = maxY - minY
        guard room > 80 else {
            mark(String(format: "sweep-skip %@ short %.0f", label, room))
            try? await Task.sleep(nanoseconds: 500_000_000)
            return
        }
        let distance = min(2200, room * 0.45)
        let target = start + distance > maxY - 40 ? max(minY, start - distance) : min(maxY, start + distance)
        mark(String(format: "sweep-go %@ from=%.0f to=%.0f content=%.0f", label, start, target, sv.contentSize.height))
        let steps = 48
        var lastY = start
        var stuck = 0
        for i in 1...steps {
            let p = CGFloat(i) / CGFloat(steps)
            let e = 1 - (1 - p) * (1 - p)
            let y = start + (target - start) * e
            sv.setContentOffset(CGPoint(x: sv.contentOffset.x, y: y), animated: false)
            if abs(sv.contentOffset.y - lastY) < 0.5 { stuck += 1 }
            lastY = sv.contentOffset.y
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        try? await Task.sleep(nanoseconds: 450_000_000)
        ScrollProbe.shared.close()
        mark(String(format: "sweep-end %@ landed=%.0f stuckSteps=%d", label, sv.contentOffset.y, stuck))
    }

    @MainActor
    private static func frontScroll() -> UIScrollView? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.flatMap(\.windows).first else { return nil }
        var best: UIScrollView?
        var bestArea: CGFloat = 0
        func walk(_ v: UIView) {
            if let sv = v as? UIScrollView, visible(sv, in: window) {
                let frame = sv.convert(sv.bounds, to: window)
                let area = frame.intersection(window.bounds).width * frame.intersection(window.bounds).height
                let tall = sv.contentSize.height > sv.bounds.height + 40
                if tall, area > bestArea, sv.bounds.height > 200 {
                    best = sv
                    bestArea = area
                }
            }
            for child in v.subviews { walk(child) }
        }
        walk(window)
        return best
    }

    private static func visible(_ v: UIView, in window: UIWindow) -> Bool {
        var cur: UIView? = v
        while let view = cur {
            if view.isHidden || view.alpha < 0.05 { return false }
            cur = view.superview
        }
        let frame = v.convert(v.bounds, to: window)
        return frame.intersects(window.bounds)
    }

    private static func mark(_ text: String) {
        let line = "[cpscroll] \(text)"
        NSLog("%@", line)
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cpscroll.log")
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile()
            h.write((line + "\n").data(using: .utf8)!)
            try? h.close()
        } else {
            try? (line + "\n").write(to: url, atomically: true, encoding: .utf8)
        }
    }
}
