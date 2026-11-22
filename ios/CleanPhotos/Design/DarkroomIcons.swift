import SwiftUI
import UIKit

/// 暗房线性图标。24 视口、线宽 1.6、圆头圆角，除非某枚图标另有说明。颜色由调用处 tint。
enum DarkroomIcon: String, Hashable, CaseIterable {
    case back
    case chevron
    case more
    case close
    case star
    case starSolid
    case trash
    case trashMark
    case arrow
    case undo
    case search
    case plus
    case check
    case checkBold
    case up
    case down
    case leftRight
    case play
    case pause
    case motion
    case soundOn
    case soundOff
}

/// 内存缓存的模板图。UIKit 列表直接拿 `UIImage`；SwiftUI 用 `DarkroomIconView`。
enum DarkroomIcons {
    private static let lock = NSLock()
    private static var cache: [String: UIImage] = [:]

    static func template(_ icon: DarkroomIcon, size: CGFloat) -> UIImage {
        let scale = UIScreen.main.scale > 0 ? UIScreen.main.scale : 3
        let key = "\(icon.rawValue)|\(size)|\(scale)"
        lock.lock()
        if let hit = cache[key] {
            lock.unlock()
            return hit
        }
        lock.unlock()
        let image = render(icon, size: size, scale: scale)
        lock.lock()
        cache[key] = image
        lock.unlock()
        return image
    }

    /// 收藏页「整理」胶囊里的 14×10 小胶片：中间一条，上下两排 2×2 齿孔。
    static func filmMiniTemplate() -> UIImage {
        let scale = UIScreen.main.scale > 0 ? UIScreen.main.scale : 3
        let key = "film-mini|\(scale)"
        lock.lock()
        if let hit = cache[key] {
            lock.unlock()
            return hit
        }
        lock.unlock()
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: CGSize(width: 14, height: 10), format: format).image { ctx in
            let c = ctx.cgContext
            c.setFillColor(UIColor.black.cgColor)
            c.fill(CGRect(x: 0, y: 3, width: 14, height: 4))
            for x in stride(from: CGFloat(0), to: 14, by: 4) {
                c.fill(CGRect(x: x, y: 0, width: 2, height: 2))
                c.fill(CGRect(x: x, y: 8, width: 2, height: 2))
            }
        }.withRenderingMode(.alwaysTemplate)
        lock.lock()
        cache[key] = image
        lock.unlock()
        return image
    }

    private static func render(_ icon: DarkroomIcon, size: CGFloat, scale: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        let viewport: CGFloat = icon == .trashMark ? 256 : 24
        return UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format).image { renderer in
            let ctx = renderer.cgContext
            ctx.scaleBy(x: size / viewport, y: size / viewport)
            ctx.setFillColor(UIColor.black.cgColor)
            ctx.setStrokeColor(UIColor.black.cgColor)
            draw(icon, in: ctx)
        }.withRenderingMode(.alwaysTemplate)
    }

    private static func draw(_ icon: DarkroomIcon, in ctx: CGContext) {
        switch icon {
        case .back:
            stroke([[p(14.5, 6), p(8.5, 12), p(14.5, 18)]], width: 1.6, ctx)
        case .chevron:
            stroke([[p(9.5, 6), p(15.5, 12), p(9.5, 18)]], width: 1.6, ctx)
        case .more:
            fillCircles([p(6, 12), p(12, 12), p(18, 12)], radius: 1.3, ctx)
        case .close:
            stroke([[p(6.5, 6.5), p(17.5, 17.5)], [p(17.5, 6.5), p(6.5, 17.5)]], width: 1.6, ctx)
        case .star:
            stroke([starPoints], width: 1.6, ctx, close: true)
        case .starSolid:
            let path = polyline(starPoints, close: true)
            ctx.addPath(path)
            ctx.fillPath()
            stroke([starPoints], width: 1.6, ctx, close: true)
        case .trash:
            stroke([
                [p(4.5, 7), p(19.5, 7)],
                [p(9.5, 7), p(9.5, 4.5), p(14.5, 4.5), p(14.5, 7)],
                [p(10, 11), p(10, 17)],
                [p(14, 11), p(14, 17)],
            ], width: 1.6, ctx)
            // M6.5 7 l.8 11.2 A2 2 0 0 0 9.3 20 h5.4 a2 2 0 0 0 2-1.8 L17.5 7
            // sweep 0 在 y 向下的坐标系里是逆时针，底部两角圆心约 (9.29, 18) 与 (14.71, 18)。
            let body = CGMutablePath()
            body.move(to: p(6.5, 7))
            body.addLine(to: p(7.3, 18.2))
            IconGeometry.appendArc(body, center: p(9.29, 18), radius: 2, from: p(7.3, 18.2), to: p(9.3, 20), clockwise: false)
            body.addLine(to: p(14.7, 20))
            IconGeometry.appendArc(body, center: p(14.71, 18), radius: 2, from: p(14.7, 20), to: p(16.7, 18.2), clockwise: false)
            body.addLine(to: p(17.5, 7))
            strokePath(body, width: 1.6, ctx)
        case .trashMark:
            ctx.addPath(trashMarkPath())
            ctx.fillPath(using: .evenOdd)
        case .arrow:
            stroke([[p(5, 12), p(19, 12)], [p(13, 6), p(19, 12), p(13, 18)]], width: 1.6, ctx)
        case .undo:
            stroke([[p(9, 14), p(4.5, 9.5), p(9, 5)]], width: 1.6, ctx)
            let hook = CGMutablePath()
            hook.move(to: p(4.5, 9.5))
            hook.addLine(to: p(14, 9.5))
            IconGeometry.appendArc(hook, center: p(14, 15), radius: 5.5, from: p(14, 9.5), to: p(14, 20.5), clockwise: true)
            hook.addLine(to: p(11, 20.5))
            strokePath(hook, width: 1.6, ctx)
        case .search:
            strokeEllipse(center: p(10.5, 10.5), radius: 6, width: 1.6, ctx)
            stroke([[p(15, 15), p(19.5, 19.5)]], width: 1.6, ctx)
        case .plus:
            stroke([[p(12, 5), p(12, 19)], [p(5, 12), p(19, 12)]], width: 1.6, ctx)
        case .check:
            stroke([[p(5.5, 12.5), p(9.5, 16.5), p(18.5, 7.5)]], width: 1.6, ctx)
        case .checkBold:
            stroke([[p(5.5, 12.5), p(9.5, 16.5), p(18.5, 7.5)]], width: 2.4, ctx)
        case .up:
            stroke([[p(12, 19), p(12, 5)], [p(6, 11), p(12, 5), p(18, 11)]], width: 1.6, ctx)
        case .down:
            stroke([[p(12, 5), p(12, 19)], [p(6, 13), p(12, 19), p(18, 13)]], width: 1.6, ctx)
        case .leftRight:
            stroke([
                [p(4, 12), p(20, 12)],
                [p(8, 8), p(4, 12), p(8, 16)],
                [p(16, 8), p(20, 12), p(16, 16)],
            ], width: 1.6, ctx)
        case .play:
            let path = polyline([p(7.7, 2.4), p(23.7, 12), p(7.7, 21.6)], close: true)
            ctx.addPath(path)
            ctx.fillPath()
        case .pause:
            ctx.fill(CGRect(x: 7, y: 3.2, width: 3.2, height: 17.6))
            ctx.fill(CGRect(x: 13.8, y: 3.2, width: 3.2, height: 17.6))
        case .motion:
            strokeEllipse(center: p(12, 12), radius: 3.4, width: 1.6, ctx)
            let dashes: [(CGPoint, CGPoint)] = [
                (p(12, 3.8), p(15.07, 4.4)),
                (p(17.8, 6.2), p(19.55, 8.8)),
                (p(20.2, 12), p(19.6, 15.07)),
                (p(17.8, 17.8), p(15.2, 19.55)),
                (p(12, 20.2), p(8.93, 19.6)),
                (p(6.2, 17.8), p(4.45, 15.2)),
                (p(3.8, 12), p(4.4, 8.93)),
                (p(6.2, 6.2), p(8.8, 4.45)),
            ]
            let ring = CGMutablePath()
            for (start, end) in dashes {
                ring.move(to: start)
                IconGeometry.appendArc(ring, center: p(12, 12), radius: 8.2, from: start, to: end, clockwise: true)
            }
            strokePath(ring, width: 1.6, ctx)
        case .soundOn:
            strokePath(speakerPath(), width: 1.6, ctx)
            let waves = CGMutablePath()
            let innerDx = (3.2 * 3.2 - 2.8 * 2.8).squareRoot()
            waves.move(to: p(14.6, 9.2))
            IconGeometry.appendArc(waves, center: p(14.6 - innerDx, 12), radius: 3.2, from: p(14.6, 9.2), to: p(14.6, 14.8), clockwise: true)
            let outerDx = (6.0 * 6.0 - 5.0 * 5.0).squareRoot()
            waves.move(to: p(17, 7))
            IconGeometry.appendArc(waves, center: p(17 - outerDx, 12), radius: 6, from: p(17, 7), to: p(17, 17), clockwise: true)
            strokePath(waves, width: 1.6, ctx)
        case .soundOff:
            strokePath(speakerPath(), width: 1.6, ctx)
            stroke([[p(16, 9.2), p(20.2, 14.8)], [p(20.2, 9.2), p(16, 14.8)]], width: 1.6, ctx)
        }
    }

    private static let starPoints = [
        p(12, 3.6), p(14.55, 8.9), p(20.35, 9.65), p(16.1, 13.67), p(17.17, 19.42),
        p(12, 16.6), p(6.83, 19.42), p(7.9, 13.67), p(3.65, 9.65), p(9.45, 8.9),
    ]

    private static func speakerPath() -> CGPath {
        polyline([
            p(4.5, 9.5), p(4.5, 14.5), p(7.7, 14.5), p(11.5, 17.5), p(11.5, 6.5), p(7.7, 9.5),
        ], close: true)
    }

    /// Phosphor Regular trash，256 视口，实心 evenOdd。版权见仓库 NOTICE。
    private static func trashMarkPath() -> CGPath {
        let path = CGMutablePath()
        IconGeometry.addCapsule(path, rect: CGRect(x: 32, y: 48, width: 192, height: 16))
        IconGeometry.addTopRoundedRect(path, rect: CGRect(x: 80, y: 16, width: 96, height: 32), radius: 24)
        IconGeometry.addTopRoundedRect(path, rect: CGRect(x: 96, y: 32, width: 64, height: 16), radius: 8)
        IconGeometry.addBottomRoundedRect(path, rect: CGRect(x: 48, y: 64, width: 160, height: 160), radius: 16)
        path.addRect(CGRect(x: 64, y: 64, width: 128, height: 144))
        IconGeometry.addCapsule(path, rect: CGRect(x: 96, y: 96, width: 16, height: 80))
        IconGeometry.addCapsule(path, rect: CGRect(x: 144, y: 96, width: 16, height: 80))
        return path
    }

    private static func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

    private static func polyline(_ points: [CGPoint], close: Bool) -> CGPath {
        let path = CGMutablePath()
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        if close { path.closeSubpath() }
        return path
    }

    private static func stroke(_ lines: [[CGPoint]], width: CGFloat, _ ctx: CGContext, close: Bool = false) {
        let path = CGMutablePath()
        for line in lines {
            guard let first = line.first else { continue }
            path.move(to: first)
            for point in line.dropFirst() { path.addLine(to: point) }
            if close { path.closeSubpath() }
        }
        strokePath(path, width: width, ctx)
    }

    private static func strokePath(_ path: CGPath, width: CGFloat, _ ctx: CGContext) {
        ctx.setLineWidth(width)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.addPath(path)
        ctx.strokePath()
    }

    private static func strokeEllipse(center: CGPoint, radius: CGFloat, width: CGFloat, _ ctx: CGContext) {
        ctx.setLineWidth(width)
        ctx.setLineCap(.round)
        ctx.strokeEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
    }

    private static func fillCircles(_ centers: [CGPoint], radius: CGFloat, _ ctx: CGContext) {
        for center in centers {
            ctx.fillEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        }
    }
}

/// 屏幕坐标（y 向下）里的短弧。用采样折线，避免 CoreGraphics 圆弧方向在翻转坐标系里翻反。
private enum IconGeometry {
    static func appendArc(
        _ path: CGMutablePath,
        center: CGPoint,
        radius: CGFloat,
        from start: CGPoint,
        to end: CGPoint,
        clockwise: Bool
    ) {
        let a0 = atan2(start.y - center.y, start.x - center.x)
        let a1 = atan2(end.y - center.y, end.x - center.x)
        var delta = a1 - a0
        if clockwise {
            if delta <= 0 { delta += 2 * .pi }
        } else if delta >= 0 {
            delta -= 2 * .pi
        }
        let steps = max(8, Int(ceil(abs(delta) / (.pi / 18))))
        for i in 1...steps {
            let t = CGFloat(i) / CGFloat(steps)
            let a = a0 + delta * t
            path.addLine(to: CGPoint(x: center.x + cos(a) * radius, y: center.y + sin(a) * radius))
        }
    }

    static func addCapsule(_ path: CGMutablePath, rect: CGRect) {
        let r = rect.height / 2
        let left = CGPoint(x: rect.minX + r, y: rect.midY)
        let right = CGPoint(x: rect.maxX - r, y: rect.midY)
        path.move(to: CGPoint(x: left.x, y: rect.minY))
        // 左端半圆走西侧：y 向下时，从顶到底的顺时针会拐到东侧，所以这里取逆时针。
        appendArc(path, center: left, radius: r, from: CGPoint(x: left.x, y: rect.minY), to: CGPoint(x: left.x, y: rect.maxY), clockwise: false)
        path.addLine(to: CGPoint(x: right.x, y: rect.maxY))
        appendArc(path, center: right, radius: r, from: CGPoint(x: right.x, y: rect.maxY), to: CGPoint(x: right.x, y: rect.minY), clockwise: false)
        path.closeSubpath()
    }

    static func addTopRoundedRect(_ path: CGMutablePath, rect: CGRect, radius: CGFloat) {
        let r = min(radius, min(rect.width / 2, rect.height))
        let leftCenter = CGPoint(x: rect.minX + r, y: rect.minY + r)
        let rightCenter = CGPoint(x: rect.maxX - r, y: rect.minY + r)
        path.move(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: leftCenter.y))
        appendArc(path, center: leftCenter, radius: r, from: CGPoint(x: rect.minX, y: leftCenter.y), to: CGPoint(x: leftCenter.x, y: rect.minY), clockwise: true)
        path.addLine(to: CGPoint(x: rightCenter.x, y: rect.minY))
        appendArc(path, center: rightCenter, radius: r, from: CGPoint(x: rightCenter.x, y: rect.minY), to: CGPoint(x: rect.maxX, y: rightCenter.y), clockwise: true)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.closeSubpath()
    }

    static func addBottomRoundedRect(_ path: CGMutablePath, rect: CGRect, radius: CGFloat) {
        let r = min(radius, min(rect.width / 2, rect.height))
        let leftCenter = CGPoint(x: rect.minX + r, y: rect.maxY - r)
        let rightCenter = CGPoint(x: rect.maxX - r, y: rect.maxY - r)
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rightCenter.y))
        appendArc(path, center: rightCenter, radius: r, from: CGPoint(x: rect.maxX, y: rightCenter.y), to: CGPoint(x: rightCenter.x, y: rect.maxY), clockwise: true)
        path.addLine(to: CGPoint(x: leftCenter.x, y: rect.maxY))
        appendArc(path, center: leftCenter, radius: r, from: CGPoint(x: leftCenter.x, y: rect.maxY), to: CGPoint(x: rect.minX, y: leftCenter.y), clockwise: true)
        path.closeSubpath()
    }
}

struct DarkroomIconView: View {
    var icon: DarkroomIcon
    var size: CGFloat

    var body: some View {
        Image(uiImage: DarkroomIcons.template(icon, size: size))
            .resizable()
            .renderingMode(.template)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
