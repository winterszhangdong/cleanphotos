import Photos
import UIKit

struct PhotoGridCellConfig {
    let item: PhotoItem
    let side: CGFloat
    let style: ThumbnailStyle
    var bottomLeftText: String? = nil
    var bottomRightText: String? = nil
    var showUnseen: Bool = false
    var selecting: Bool = false
    var selected: Bool = false
    var circled: Bool = false
    var develop: Bool = false
    var developDelay: Double = 0
    var developGrid: DevelopGrid? = nil
    var interactive: Bool = true
    var locale: Locale = .current
    var accessibilityId: String? = nil
}

/// 全 App 共享的纯 UIKit 网格瓦片 Cell（零 SwiftUI 子树、零滚动 KVO、不透明合成与按需复用）。
final class PhotoGridCell: UICollectionViewCell {
    static let reuseIdentifier = "PhotoGridCell"

    private static let cornerRadiusSelected: CGFloat = 6
    private static let selectedScale: CGFloat = 0.86
    private static let scrimHeight: CGFloat = 28

    private(set) var representedAssetId: String?
    private var currentSide: CGFloat = 0
    private var currentStyle: ThumbnailStyle = .normal
    private var hasFinalImage = false
    private var token: ThumbnailToken?
    private var didRunDevelop = false
    private var claimedGrid: DevelopGrid?
    private var claimedId: String?
    private let developOverlay = TileDevelopOverlay()
    private var selectAnimator: UIViewPropertyAnimator?

    private var a11yDate: Date = .distantPast
    private var a11yUnseen = false
    private var a11yDuration: String?
    private var a11yLocale: Locale = .current
    private var a11yInteractive = true
    private var bottomRightDimmed = false
    private var currentLeftText: String?
    private var currentRightText: String?
    private var currentTrashedRight = false

    private let tileContainer: UIView = {
        let v = UIView()
        v.backgroundColor = DarkroomUIColors.tileFill
        v.isOpaque = true
        v.clipsToBounds = false
        v.layer.cornerCurve = .continuous
        return v
    }()

    private let imageView: UIImageView = {
        let iv = UIImageView()
        iv.backgroundColor = DarkroomUIColors.tileFill
        iv.isOpaque = true
        iv.contentMode = .scaleAspectFill
        iv.clipsToBounds = true
        iv.isAccessibilityElement = false
        return iv
    }()

    private var scrimImageView: UIImageView?
    private var bottomLeftLabel: UILabel?
    private var bottomRightLabel: UILabel?
    private var unseenImageView: UIImageView?
    private var checkBadge: SelectionBadgeView?
    private var circleRingView: UIView?

    /// 预渲染 1×28pt 底部压暗渐变图，避免 CAGradientLayer 事务及每帧重排。
    private static let scrimImage: UIImage = {
        let fmt = UIGraphicsImageRendererFormat()
        fmt.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: 1, height: scrimHeight), format: fmt).image { ctx in
            let cg = ctx.cgContext
            let colors = [UIColor.clear.cgColor, DarkroomUIColors.scrimBottom.cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                cg.drawLinearGradient(
                    gradient,
                    start: CGPoint(x: 0.5, y: 0),
                    end: CGPoint(x: 0.5, y: scrimHeight),
                    options: []
                )
            }
        }
    }()

    /// 预渲染一次「未看」光点 + 暗环 + 柔光，避免每个格子离屏模糊。
    private static let unseenDotImage: UIImage = {
        let glow: CGFloat = DarkroomDimens.unseenGlow
        let dot: CGFloat = DarkroomDimens.unseenDot
        let ring: CGFloat = DarkroomDimens.unseenRing
        let canvasSide = dot + glow * 2 + 4
        let fmt = UIGraphicsImageRendererFormat()
        fmt.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: canvasSide, height: canvasSide), format: fmt).image { ctx in
            let cg = ctx.cgContext
            let center = CGPoint(x: canvasSide / 2, y: canvasSide / 2)
            let colors = [DarkroomUIColors.unseenGlow.cgColor, DarkroomUIColors.unseenGlow.withAlphaComponent(0).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                cg.drawRadialGradient(
                    gradient,
                    startCenter: center,
                    startRadius: dot / 2,
                    endCenter: center,
                    endRadius: dot / 2 + glow,
                    options: []
                )
            }
            let ringRadius = dot / 2 + ring
            DarkroomUIColors.unseenRing.setFill()
            cg.fillEllipse(in: CGRect(x: center.x - ringRadius, y: center.y - ringRadius, width: ringRadius * 2, height: ringRadius * 2))
            DarkroomUIColors.unseenDot.setFill()
            cg.fillEllipse(in: CGRect(x: center.x - dot / 2, y: center.y - dot / 2, width: dot, height: dot))
        }
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = DarkroomUIColors.tileFill
        contentView.isOpaque = true
        contentView.clipsToBounds = false
        contentView.addSubview(tileContainer)
        tileContainer.addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        layoutAttributes
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        token?.cancel()
        token = nil
        abandonDevelopClaim()
        developOverlay.reset()
        selectAnimator?.stopAnimation(true)
        selectAnimator = nil
        representedAssetId = nil
        currentSide = 0
        currentStyle = .normal
        hasFinalImage = false
        didRunDevelop = false
        imageView.image = nil
        imageView.alpha = 1
        tileContainer.transform = .identity
        tileContainer.layer.cornerRadius = 0
        tileContainer.clipsToBounds = false
        checkBadge?.isHidden = true
        checkBadge?.transform = .identity
        checkBadge?.alpha = 1
        circleRingView?.isHidden = true
        unseenImageView?.isHidden = true
        bottomLeftLabel?.isHidden = true
        bottomLeftLabel?.text = nil
        bottomRightLabel?.isHidden = true
        bottomRightLabel?.text = nil
        scrimImageView?.isHidden = true
        currentLeftText = nil
        currentRightText = nil
        currentTrashedRight = false
        contentView.alpha = 1
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let bounds = contentView.bounds
        let w = bounds.width
        let h = bounds.height

        tileContainer.bounds = bounds
        tileContainer.center = CGPoint(x: bounds.midX, y: bounds.midY)
        imageView.frame = bounds

        if let scrimImageView, !scrimImageView.isHidden {
            scrimImageView.frame = CGRect(x: 0, y: h - Self.scrimHeight, width: w, height: Self.scrimHeight)
        }

        let inset = DarkroomDimens.unseenInset
        var rightWidth: CGFloat = 0
        if let bottomRightLabel, !bottomRightLabel.isHidden {
            let size = bottomRightLabel.sizeThatFits(CGSize(width: w - inset * 2, height: h))
            rightWidth = ceil(size.width)
            let rh = ceil(size.height)
            bottomRightLabel.frame = CGRect(x: w - inset - rightWidth, y: h - inset - rh, width: rightWidth, height: rh)
        }
        if let bottomLeftLabel, !bottomLeftLabel.isHidden {
            let hasVisibleRight = bottomRightLabel?.isHidden == false
            let maxLeft = hasVisibleRight ? max(0, w - inset * 3 - rightWidth) : (w - inset * 2)
            let size = bottomLeftLabel.sizeThatFits(CGSize(width: maxLeft, height: h))
            let lw = min(maxLeft, ceil(size.width))
            let lh = ceil(size.height)
            bottomLeftLabel.frame = CGRect(x: inset, y: h - inset - lh, width: lw, height: lh)
        }

        if let unseenImageView, !unseenImageView.isHidden {
            let dotSide = DarkroomDimens.unseenDot
            let imgSize = Self.unseenDotImage.size
            unseenImageView.frame = CGRect(
                x: inset - (imgSize.width - dotSide) / 2,
                y: inset - (imgSize.height - dotSide) / 2,
                width: imgSize.width,
                height: imgSize.height
            )
        }

        if let checkBadge, !checkBadge.isHidden {
            checkBadge.bounds = CGRect(x: 0, y: 0, width: 22, height: 22)
            checkBadge.center = CGPoint(x: w - 8 - 11, y: 8 + 11)
        }

        if let circleRingView, !circleRingView.isHidden {
            let ringFrame = bounds.insetBy(dx: 7, dy: 7)
            circleRingView.frame = ringFrame
            circleRingView.layer.cornerRadius = ringFrame.width / 2
        }
        developOverlay.layout(in: imageView.frame)
    }

    func configure(_ config: PhotoGridCellConfig, pipeline: ThumbnailPipeline, animated: Bool) {
        let sameAsset = representedAssetId == config.item.id
        let sameSize = abs(currentSide - config.side) <= 0.5
        let styleChanged = currentStyle != config.style

        a11yInteractive = config.interactive
        a11yDate = config.item.creationDate
        a11yUnseen = config.showUnseen && config.item.isUnseen
        a11yDuration = config.item.isVideo ? config.bottomRightText : nil
        a11yLocale = config.locale

        isAccessibilityElement = config.interactive
        accessibilityIdentifier = config.accessibilityId
        if config.interactive {
            accessibilityTraits = (config.selecting && config.selected) || config.circled ? [.button, .selected] : .button
        } else {
            accessibilityTraits = []
        }

        bottomRightDimmed = config.style == .trashed && !config.item.isVideo
        applyLabels(left: config.bottomLeftText, right: config.bottomRightText, trashedStyle: bottomRightDimmed)

        let showUnseen = config.showUnseen && config.item.isUnseen
        if showUnseen {
            let iv = ensureUnseenImageView()
            if iv.isHidden {
                iv.isHidden = false
                setNeedsLayout()
            }
        } else if let iv = unseenImageView, !iv.isHidden {
            iv.isHidden = true
        }

        if config.circled {
            let ring = ensureCircleRingView()
            if ring.isHidden {
                ring.isHidden = false
                setNeedsLayout()
            }
        } else if let ring = circleRingView, !ring.isHidden {
            ring.isHidden = true
        }

        let picked = config.selecting && config.selected
        applySelection(selecting: config.selecting, picked: picked, animated: animated && !UIAccessibility.isReduceMotionEnabled)

        if sameAsset && sameSize && !styleChanged && (imageView.image != nil || token != nil) {
            if imageView.image != nil {
                maybeDevelop(config)
            }
            return
        }

        token?.cancel()
        token = nil
        representedAssetId = config.item.id
        currentSide = config.side
        currentStyle = config.style
        hasFinalImage = false

        let shouldCrossDissolve = sameAsset && styleChanged && animated && !UIAccessibility.isReduceMotionEnabled
        if let hit = pipeline.cached(id: config.item.id, side: config.side, style: config.style) {
            setImage(hit, crossDissolve: shouldCrossDissolve)
            hasFinalImage = true
            maybeDevelop(config)
            return
        }

        if !sameAsset {
            abandonDevelopClaim()
            developOverlay.reset()
            didRunDevelop = false
            imageView.image = nil
            imageView.alpha = 1
        }

        let targetId = config.item.id
        let targetStyle = config.style
        token = pipeline.request(asset: config.item.asset, side: config.side, style: config.style) { [weak self] img, degraded in
            guard let self,
                  self.representedAssetId == targetId,
                  self.currentStyle == targetStyle else { return }
            if degraded && self.hasFinalImage { return }
            if !degraded { self.hasFinalImage = true }
            self.setImage(img, crossDissolve: shouldCrossDissolve)
            self.maybeDevelop(config)
        }
    }

    func updateBottomLeft(_ text: String?) {
        let right = (bottomRightLabel?.isHidden == false) ? bottomRightLabel?.text : nil
        applyLabels(left: text, right: right, trashedStyle: bottomRightDimmed)
    }

    func currentImage() -> UIImage? {
        imageView.image
    }

    override var accessibilityLabel: String? {
        get {
            guard a11yInteractive else { return nil }
            return PhotoTileA11yFormatter.label(
                date: a11yDate,
                unseen: a11yUnseen,
                durationText: a11yDuration,
                locale: a11yLocale
            )
        }
        set { super.accessibilityLabel = newValue }
    }

    private func setImage(_ image: UIImage, crossDissolve: Bool) {
        if crossDissolve {
            UIView.transition(
                with: imageView,
                duration: 0.16,
                options: [.transitionCrossDissolve, .allowUserInteraction]
            ) {
                self.imageView.image = image
            }
        } else {
            imageView.image = image
        }
    }

    private func ensureScrimImageView() -> UIImageView {
        if let scrimImageView { return scrimImageView }
        let iv = UIImageView(image: Self.scrimImage)
        iv.contentMode = .scaleToFill
        iv.isOpaque = false
        iv.isUserInteractionEnabled = false
        iv.isHidden = true
        iv.isAccessibilityElement = false
        tileContainer.insertSubview(iv, aboveSubview: imageView)
        self.scrimImageView = iv
        return iv
    }

    private func ensureBottomLeftLabel() -> UILabel {
        if let bottomLeftLabel { return bottomLeftLabel }
        let l = UILabel()
        l.font = DarkroomUIFonts.plexMono(size: 10)
        l.textColor = DarkroomUIColors.paper
        l.lineBreakMode = .byTruncatingTail
        l.numberOfLines = 1
        l.isHidden = true
        l.isAccessibilityElement = false
        tileContainer.addSubview(l)
        self.bottomLeftLabel = l
        return l
    }

    private func ensureBottomRightLabel() -> UILabel {
        if let bottomRightLabel { return bottomRightLabel }
        let l = UILabel()
        l.font = DarkroomUIFonts.plexMono(size: 10)
        l.textColor = DarkroomUIColors.paper
        l.textAlignment = .right
        l.numberOfLines = 1
        l.isHidden = true
        l.isAccessibilityElement = false
        tileContainer.addSubview(l)
        self.bottomRightLabel = l
        return l
    }

    private func ensureUnseenImageView() -> UIImageView {
        if let unseenImageView { return unseenImageView }
        let iv = UIImageView(image: Self.unseenDotImage)
        iv.contentMode = .center
        iv.isHidden = true
        iv.isAccessibilityElement = false
        contentView.addSubview(iv)
        self.unseenImageView = iv
        return iv
    }

    private func ensureCircleRingView() -> UIView {
        if let circleRingView { return circleRingView }
        let v = UIView()
        v.backgroundColor = .clear
        v.layer.borderWidth = 2
        v.layer.borderColor = DarkroomUIColors.paper.cgColor
        v.isUserInteractionEnabled = false
        v.isHidden = true
        contentView.addSubview(v)
        self.circleRingView = v
        return v
    }

    private func applyLabels(left: String?, right: String?, trashedStyle: Bool) {
        let normalizedLeft = (left?.isEmpty == false) ? left : nil
        let normalizedRight = (right?.isEmpty == false) ? right : nil
        guard normalizedLeft != currentLeftText
                || normalizedRight != currentRightText
                || trashedStyle != currentTrashedRight else {
            return
        }

        currentLeftText = normalizedLeft
        currentRightText = normalizedRight
        currentTrashedRight = trashedStyle

        let hasLeft = normalizedLeft != nil
        let hasRight = normalizedRight != nil

        if hasLeft {
            let l = ensureBottomLeftLabel()
            l.text = normalizedLeft
            l.isHidden = false
        } else if let l = bottomLeftLabel {
            l.text = nil
            l.isHidden = true
        }

        if hasRight {
            let l = ensureBottomRightLabel()
            l.text = normalizedRight
            l.textColor = trashedStyle
                ? DarkroomUIColors.paper.withAlphaComponent(0.88)
                : DarkroomUIColors.paper
            l.isHidden = false
        } else if let l = bottomRightLabel {
            l.text = nil
            l.isHidden = true
        }

        if hasLeft || hasRight {
            ensureScrimImageView().isHidden = false
        } else {
            scrimImageView?.isHidden = true
        }
        raiseDevelopChrome()
        setNeedsLayout()
    }

    private func ensureCheckBadge() -> SelectionBadgeView {
        if let checkBadge { return checkBadge }
        let badge = SelectionBadgeView()
        badge.isHidden = true
        contentView.addSubview(badge)
        self.checkBadge = badge
        return badge
    }

    private func applySelection(selecting: Bool, picked: Bool, animated: Bool) {
        let bg = selecting ? DarkroomUIColors.room : DarkroomUIColors.tileFill
        let scale: CGFloat = picked ? Self.selectedScale : 1
        let corner: CGFloat = picked ? Self.cornerRadiusSelected : 0
        let targetTransform = CGAffineTransform(scaleX: scale, y: scale)

        selectAnimator?.stopAnimation(true)
        selectAnimator = nil

        if !animated {
            contentView.backgroundColor = bg
            tileContainer.transform = targetTransform
            tileContainer.layer.cornerRadius = corner
            tileContainer.clipsToBounds = corner > 0
            if picked {
                let badge = ensureCheckBadge()
                let wasHidden = badge.isHidden
                badge.isHidden = false
                badge.transform = .identity
                badge.alpha = 1
                if wasHidden { setNeedsLayout() }
            } else if let badge = checkBadge {
                badge.isHidden = true
                badge.transform = .identity
                badge.alpha = 0
            }
            return
        }

        tileContainer.clipsToBounds = true
        let badge: SelectionBadgeView? = picked ? ensureCheckBadge() : checkBadge
        if picked, let badge, badge.isHidden {
            badge.isHidden = false
            badge.alpha = 0
            badge.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
            setNeedsLayout()
        }
        let anim = UIViewPropertyAnimator(
            duration: 0.16,
            controlPoint1: CGPoint(x: 0.2, y: 0),
            controlPoint2: CGPoint(x: 0, y: 1)
        ) {
            self.contentView.backgroundColor = bg
            self.tileContainer.transform = targetTransform
            self.tileContainer.layer.cornerRadius = corner
            badge?.alpha = picked ? 1 : 0
            badge?.transform = picked ? .identity : CGAffineTransform(scaleX: 0.6, y: 0.6)
        }
        anim.addCompletion { [weak self] _ in
            guard let self else { return }
            if !picked {
                self.tileContainer.clipsToBounds = false
                self.checkBadge?.isHidden = true
                self.checkBadge?.transform = .identity
            }
        }
        selectAnimator = anim
        anim.startAnimation()
    }

    private func maybeDevelop(_ config: PhotoGridCellConfig) {
        guard let image = imageView.image else { return }
        if developOverlay.isActive {
            developOverlay.updateImage(image, assetId: config.item.id)
            return
        }
        if didRunDevelop { return }
        guard config.develop,
              let grid = config.developGrid,
              !config.selecting,
              !UIAccessibility.isReduceMotionEnabled,
              GridDevelopSession.claim(grid: grid, id: config.item.id) else {
            return
        }
        claimedGrid = grid
        claimedId = config.item.id
        didRunDevelop = true
        imageView.alpha = 1
        developOverlay.start(
            image: image,
            assetId: config.item.id,
            delay: max(0, config.developDelay),
            in: tileContainer,
            above: imageView,
            bounds: imageView.bounds.width > 0 ? imageView.bounds : contentView.bounds
        )
        raiseDevelopChrome()
    }

    private func abandonDevelopClaim() {
        if developOverlay.isActive, let grid = claimedGrid, let id = claimedId {
            GridDevelopSession.release(grid: grid, id: id)
        }
        claimedGrid = nil
        claimedId = nil
    }

    private func raiseDevelopChrome() {
        if let scrimImageView { tileContainer.bringSubviewToFront(scrimImageView) }
        if let bottomLeftLabel { tileContainer.bringSubviewToFront(bottomLeftLabel) }
        if let bottomRightLabel { tileContainer.bringSubviewToFront(bottomRightLabel) }
    }
}

private final class SelectionBadgeView: UIView {
    private static let checkImage = DarkroomIcons.template(.checkBold, size: 14)

    private let checkImageView: UIImageView = {
        let iv = UIImageView(image: SelectionBadgeView.checkImage)
        iv.tintColor = DarkroomUIColors.room
        iv.contentMode = .center
        iv.isAccessibilityElement = false
        return iv
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = DarkroomUIColors.paper
        layer.cornerRadius = 11
        layer.borderWidth = 2
        layer.borderColor = DarkroomUIColors.room.cgColor
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.5
        layer.shadowRadius = 4
        layer.shadowOffset = CGSize(width: 0, height: 2)
        layer.shadowPath = UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: 22, height: 22)).cgPath
        addSubview(checkImageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        checkImageView.frame = bounds
    }
}
