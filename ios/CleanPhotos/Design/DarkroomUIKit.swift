import SwiftUI
import UIKit

/// UIKit 侧暗房调色板，数值与 DarkroomColors 一致。
enum DarkroomUIColors {
    static let room = UIColor(red: 0x0F/255, green: 0x0E/255, blue: 0x0D/255, alpha: 1)
    static let tray = UIColor(red: 0x1A/255, green: 0x18/255, blue: 0x16/255, alpha: 1)
    static let tray2 = UIColor(red: 0x24/255, green: 0x21/255, blue: 0x1E/255, alpha: 1)
    static let rim = UIColor(red: 1, green: 240/255, blue: 225/255, alpha: 0.09)
    static let rim2 = UIColor(red: 1, green: 240/255, blue: 225/255, alpha: 0.16)
    static let paper = UIColor(red: 0xEE/255, green: 0xE9/255, blue: 0xE2/255, alpha: 1)
    static let dim = UIColor(red: 0x8C/255, green: 0x85/255, blue: 0x7D/255, alpha: 1)
    static let faint = UIColor(red: 0x5B/255, green: 0x55/255, blue: 0x4F/255, alpha: 1)
    static let fade = UIColor(red: 0x6E/255, green: 0x68/255, blue: 0x62/255, alpha: 1)
    static let delete = UIColor(red: 0xC4/255, green: 0x55/255, blue: 0x3F/255, alpha: 1)
    static let amber = UIColor(red: 0xF2/255, green: 0xA9/255, blue: 0x3B/255, alpha: 1)
    static let amberGlow = UIColor(red: 0xF2/255, green: 0xA9/255, blue: 0x3B/255, alpha: 0.6)
    static let tileFill = UIColor(red: 0x1C/255, green: 0x1A/255, blue: 0x18/255, alpha: 1)
    static let glass = UIColor(red: 0x14/255, green: 0x12/255, blue: 0x10/255, alpha: 0.56)
    static let glassRim = UIColor(red: 1, green: 240/255, blue: 225/255, alpha: 0.14)
    static let navGlass = UIColor(red: 0x1E/255, green: 0x1C/255, blue: 0x19/255, alpha: 0.72)
    static let navRim = UIColor(red: 1, green: 240/255, blue: 225/255, alpha: 0.10)
    static let navShadow = UIColor.black.withAlphaComponent(0.5)
    static let scrimBottom = UIColor(red: 0x0F/255, green: 0x0E/255, blue: 0x0D/255, alpha: 0.62)
    static let unseenDot = UIColor(red: 1, green: 251/255, blue: 245/255, alpha: 1)
    static let unseenRing = UIColor(red: 0x0F/255, green: 0x0E/255, blue: 0x0D/255, alpha: 0.5)
    static let unseenGlow = UIColor(red: 1, green: 246/255, blue: 232/255, alpha: 0.7)
}

/// UIKit 侧字体 token，PostScript 名称与 DarkroomType / Info.plist 注册一致。
enum DarkroomUIFonts {
    static func schibsted(size: CGFloat, weight: UIFont.Weight = .semibold) -> UIFont {
        let name: String
        switch weight {
        case .bold: name = "SchibstedGrotesk-Bold"
        case .medium: name = "SchibstedGrotesk-Medium"
        default: name = "SchibstedGrotesk-SemiBold"
        }
        return UIFont(name: name, size: size) ?? .systemFont(ofSize: size, weight: weight)
    }

    static func plexMono(size: CGFloat, weight: UIFont.Weight = .regular) -> UIFont {
        let name = weight == .medium ? "IBMPlexMono-Medium" : "IBMPlexMono-Regular"
        return UIFont(name: name, size: size) ?? .monospacedSystemFont(ofSize: size, weight: weight)
    }

    static func notoSC(size: CGFloat, weight: UIFont.Weight = .regular) -> UIFont {
        let name: String
        switch weight {
        case .bold: name = "NotoSansSC-Bold"
        case .semibold: name = "NotoSansSC-SemiBold"
        case .medium: name = "NotoSansSC-Medium"
        default: name = "NotoSansSC-Regular"
        }
        return UIFont(name: name, size: size) ?? .systemFont(ofSize: size, weight: weight)
    }
}

/// 瓦片无障碍日期文案的懒格式化器：只在屏幕朗读或 UITest 读取 accessibilityLabel 时调用，绝不在网格配置热路径里运行。
@MainActor
enum PhotoTileA11yFormatter {
    private static var formatters: [String: DateFormatter] = [:]

    private static func formatter(_ locale: Locale) -> DateFormatter {
        if let f = formatters[locale.identifier] { return f }
        let f = DateFormatter()
        f.locale = locale
        f.setLocalizedDateFormatFromTemplate("MMMdjmm")
        formatters[locale.identifier] = f
        return f
    }

    static func label(date: Date, unseen: Bool, durationText: String?, locale: Locale) -> String {
        var base = formatter(locale).string(from: date)
        if unseen {
            base += ", " + L10n.string("a11y_unseen", locale: locale)
        }
        if let durationText, !durationText.isEmpty {
            base += ", " + durationText
        }
        return base
    }
}

/// 底部导航胶囊的 UIKit 玻璃背景：用显式 `shadowPath` 避免透明圆角阴影离屏重绘，并避开 SwiftUI 叠加组离屏合成。
struct DarkroomGlassCapsuleBackground: UIViewRepresentable {
    func makeUIView(context: Context) -> DarkroomGlassCapsuleView {
        DarkroomGlassCapsuleView()
    }

    func updateUIView(_ uiView: DarkroomGlassCapsuleView, context: Context) {}

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: DarkroomGlassCapsuleView, context: Context) -> CGSize? {
        proposal.replacingUnspecifiedDimensions()
    }
}

final class DarkroomGlassCapsuleView: UIView {
    private let blurView: UIVisualEffectView = {
        let v = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterialDark))
        v.isUserInteractionEnabled = false
        v.clipsToBounds = true
        v.layer.cornerCurve = .continuous
        v.layer.borderWidth = 1
        v.layer.borderColor = DarkroomUIColors.navRim.cgColor
        return v
    }()

    private let tintView: UIView = {
        let v = UIView()
        v.isUserInteractionEnabled = false
        v.backgroundColor = DarkroomUIColors.navGlass
        return v
    }()

    private var lastShadowBounds: CGRect = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        clipsToBounds = false
        layer.shadowColor = DarkroomUIColors.navShadow.cgColor
        layer.shadowOpacity = 1
        layer.shadowRadius = 16
        layer.shadowOffset = CGSize(width: 0, height: 12)
        blurView.contentView.addSubview(tintView)
        addSubview(blurView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        blurView.frame = b
        let r = b.height / 2
        blurView.layer.cornerRadius = r
        tintView.frame = blurView.contentView.bounds
        if b != lastShadowBounds {
            lastShadowBounds = b
            layer.shadowPath = UIBezierPath(roundedRect: b, cornerRadius: r).cgPath
        }
    }
}
