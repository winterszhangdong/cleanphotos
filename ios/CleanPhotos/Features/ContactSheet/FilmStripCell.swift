import Photos
import UIKit

/// 一格胶片：这一段的底、齿孔、格号、照片和油性笔圈。同一行绕整条中心转同一个摆角。
final class FilmStripCell: UICollectionViewCell {
    static let reuseIdentifier = "FilmStripCell"

    private let imageView = UIImageView()
    private let veil = UIView()
    private let numberLabel = UILabel()
    private let edgeLabel = UILabel()
    private let rim = CAShapeLayer()
    private let pen = CAShapeLayer()
    private var token: ThumbnailToken?
    private var assetId: String?
    private var entering = false

    var photoId: String = ""
    private(set) var column = 0
    private(set) var columns = 3
    private(set) var row = 0
    var stripWidth: CGFloat = 1

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = DarkroomUIColors.film
        clipsToBounds = false
        contentView.clipsToBounds = false
        layer.masksToBounds = false

        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        contentView.addSubview(imageView)

        veil.backgroundColor = DarkroomUIColors.room
        veil.alpha = 0
        veil.isUserInteractionEnabled = false
        contentView.addSubview(veil)

        numberLabel.font = DarkroomUIFonts.plexMono(size: 6.5)
        numberLabel.textColor = DarkroomUIColors.filmEdgeText
        contentView.addSubview(numberLabel)

        edgeLabel.font = DarkroomUIFonts.plexMono(size: 6.5)
        edgeLabel.textColor = DarkroomUIColors.filmEdgeText
        edgeLabel.textAlignment = .right
        contentView.addSubview(edgeLabel)

        rim.strokeColor = DarkroomUIColors.rim2.cgColor
        rim.fillColor = nil
        rim.lineWidth = 1
        rim.isHidden = true
        contentView.layer.addSublayer(rim)

        pen.strokeColor = DarkroomUIColors.pen.cgColor
        pen.fillColor = UIColor.clear.cgColor
        pen.lineWidth = FilmGeometryRules.penStroke
        pen.lineCap = .round
        pen.lineJoin = .round
        pen.strokeEnd = 0
        contentView.layer.addSublayer(pen)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        token?.cancel()
        token = nil
        assetId = nil
        imageView.image = nil
        imageView.alpha = 1
        veil.alpha = 0
        rim.isHidden = true
        pen.opacity = 1
        pen.strokeEnd = 0
        pen.path = nil
        entering = false
        layer.transform = CATransform3DIdentity
        edgeLabel.text = nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let photo = photoRect
        imageView.frame = photo
        veil.frame = photo
        numberLabel.sizeToFit()
        numberLabel.frame.origin = CGPoint(x: photo.minX + 2, y: 2)
        edgeLabel.sizeToFit()
        edgeLabel.frame = CGRect(
            x: bounds.width - FilmGeometryRules.endInset - edgeLabel.bounds.width,
            y: bounds.height - edgeLabel.bounds.height - 1,
            width: edgeLabel.bounds.width,
            height: edgeLabel.bounds.height
        )
        let rimRect = photo.insetBy(dx: 0.5, dy: 0.5)
        rim.path = UIBezierPath(rect: rimRect).cgPath
        pen.path = FilmGeometryRules.penPath(for: photoId).cgPath(in: photo)
        if !entering { layer.transform = restingTransform(shift: 0, extraTilt: 0) }
    }

    override func draw(_ rect: CGRect) {
        DarkroomUIColors.film.setFill()
        UIRectFill(rect)
        DarkroomUIColors.filmHole.setFill()
        let cellW = rect.width
        let start = cellW * CGFloat(column)
        let pitch = FilmGeometryRules.holePitch
        let holeW = FilmGeometryRules.holeWidth
        let holeH = FilmGeometryRules.holeHeight
        let inset = FilmGeometryRules.holeInset + 2
        let stripW = max(stripWidth, cellW * CGFloat(columns))
        var x = inset
        if start > inset {
            x = inset + ceil((start - inset) / pitch) * pitch
        }
        let limit = min(start + cellW, stripW - inset)
        while x + holeW <= limit {
            let local = x - start
            UIRectFill(CGRect(x: local, y: FilmGeometryRules.holeInset, width: holeW, height: holeH))
            UIRectFill(CGRect(x: local, y: rect.height - FilmGeometryRules.holeInset - holeH, width: holeW, height: holeH))
            x += pitch
        }
    }

    var photoRect: CGRect {
        let width = FilmGeometryRules.frameWidth(stripWidth: stripWidth, cols: columns)
        let left = FilmGeometryRules.frameLeft(stripWidth: stripWidth, cols: columns, col: column)
        return CGRect(x: left, y: FilmGeometryRules.band, width: width, height: width * 3 / 4)
    }

    func configure(
        item: PhotoItem,
        number: Int,
        row: Int,
        column: Int,
        columns: Int,
        stripWidth: CGFloat,
        edge: String?,
        circled: Bool,
        slotAlpha: CGFloat,
        pipeline: ThumbnailPipeline,
        playEnter: Bool
    ) {
        photoId = item.id
        self.row = row
        self.column = column
        self.columns = columns
        self.stripWidth = stripWidth
        numberLabel.text = String(format: "▸%02d", number)
        edgeLabel.text = edge
        edgeLabel.isHidden = edge == nil
        setSlotAlpha(slotAlpha)
        setCircled(circled, animated: window != nil)
        setNeedsDisplay()
        setNeedsLayout()

        if playEnter && !UIAccessibility.isReduceMotionEnabled {
            entering = true
            layer.transform = restingTransform(shift: -FilingMotion.stripEnterShift, extraTilt: FilingMotion.stripEnterTilt)
            veil.alpha = 0.55
            alpha = 0
            let delay = FilingMotion.stagger * Double(min(row, 12))
            UIView.animate(
                withDuration: DarkroomMotion.medium,
                delay: delay,
                options: [.curveEaseOut, .allowUserInteraction]
            ) {
                self.alpha = 1
                self.veil.alpha = 0
                self.layer.transform = self.restingTransform(shift: 0, extraTilt: 0)
            } completion: { _ in
                self.entering = false
            }
        }

        guard assetId != item.id || imageView.image == nil else { return }
        token?.cancel()
        assetId = item.id
        let side = max(photoRect.width, 40)
        if let cached = pipeline.cached(id: item.id, side: side, style: .normal) {
            imageView.image = cached
        }
        token = pipeline.request(asset: item.asset, side: side, style: .normal) { [weak self] image, _ in
            guard let self, self.assetId == item.id else { return }
            self.imageView.image = image
        }
    }

    func setSlotAlpha(_ alpha: CGFloat) {
        imageView.alpha = alpha
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        pen.opacity = Float(alpha)
        rim.isHidden = alpha > 0.98
        rim.opacity = Float(1 - alpha)
        CATransaction.commit()
    }

    func setCircled(_ circled: Bool, animated: Bool) {
        pen.removeAllAnimations()
        let target: CGFloat = circled ? 1 : 0
        guard animated, !UIAccessibility.isReduceMotionEnabled else {
            pen.strokeEnd = target
            return
        }
        let from = pen.presentation()?.strokeEnd ?? pen.strokeEnd
        pen.strokeEnd = target
        let anim = CABasicAnimation(keyPath: "strokeEnd")
        anim.fromValue = from
        anim.toValue = target
        anim.duration = circled ? FilingMotion.penStroke : DarkroomMotion.short
        anim.timingFunction = CAMediaTimingFunction(controlPoints: 0.05, 0.7, 0.1, 1)
        if !circled {
            anim.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0, 0, 1)
        }
        pen.add(anim, forKey: "stroke")
    }

    private func restingTransform(shift: CGFloat, extraTilt: CGFloat) -> CATransform3D {
        let skew = FilingMotion.stripSkew[row % FilingMotion.stripSkew.count]
        return Self.transform(in: bounds, column: column, stripWidth: stripWidth, degrees: skew + extraTilt, shiftX: shift)
    }

    static func transform(in bounds: CGRect, column: Int, stripWidth: CGFloat, degrees: CGFloat, shiftX: CGFloat) -> CATransform3D {
        let pivot = CGPoint(x: stripWidth / 2 - bounds.width * CGFloat(column), y: bounds.midY)
        var t = CATransform3DIdentity
        t = CATransform3DTranslate(t, shiftX, 0, 0)
        t = CATransform3DTranslate(t, pivot.x, pivot.y, 0)
        t = CATransform3DRotate(t, degrees * .pi / 180, 0, 0, 1)
        t = CATransform3DTranslate(t, -pivot.x, -pivot.y, 0)
        return t
    }
}
