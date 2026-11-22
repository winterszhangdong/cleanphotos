import UIKit

/// 照片页日期/月份头通用纯 UIKit 视图（列表内 header 与顶部吸顶叠层共用），全 frame 布局、零 Auto Layout。
final class PhotosDayHeaderView: UIView {
    private static let dayNumFont = DarkroomUIFonts.schibsted(size: 24, weight: .semibold)
    private static let metaFont = DarkroomUIFonts.notoSC(size: 12)
    private static let rightFont = DarkroomUIFonts.plexMono(size: 11)
    private static let rightMediumFont = DarkroomUIFonts.plexMono(size: 11, weight: .medium)

    private let dayNumLabel: UILabel = {
        let l = UILabel()
        l.font = PhotosDayHeaderView.dayNumFont
        l.textColor = DarkroomUIColors.paper
        l.numberOfLines = 1
        return l
    }()

    private let metaLabel: UILabel = {
        let l = UILabel()
        l.font = PhotosDayHeaderView.metaFont
        l.textColor = DarkroomUIColors.dim
        l.lineBreakMode = .byTruncatingTail
        l.numberOfLines = 1
        return l
    }()

    private lazy var collapseButton: UIButton = {
        let b = UIButton(type: .system)
        b.tintColor = DarkroomUIColors.paper
        b.titleLabel?.font = PhotosDayHeaderView.rightFont
        b.contentHorizontalAlignment = .center
        b.addTarget(self, action: #selector(tappedCollapse), for: .touchUpInside)
        b.isHidden = true
        return b
    }()

    private lazy var countButton: UIButton = {
        let b = UIButton(type: .system)
        b.titleLabel?.font = PhotosDayHeaderView.rightFont
        b.contentHorizontalAlignment = .trailing
        b.addTarget(self, action: #selector(tappedCount), for: .touchUpInside)
        return b
    }()

    private lazy var selectAllButton: UIButton = {
        let b = UIButton(type: .system)
        b.tintColor = DarkroomUIColors.paper
        b.titleLabel?.font = PhotosDayHeaderView.rightMediumFont
        b.contentHorizontalAlignment = .trailing
        b.addTarget(self, action: #selector(tappedSelectAll), for: .touchUpInside)
        b.isHidden = true
        return b
    }()

    var onOpenDay: (() -> Void)?
    var onCollapse: (() -> Void)?
    var onToggleSelectAll: (() -> Void)?

    private var isMonthMode = false
    private var monthBaselineMode = false
    private var currentKey = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        addSubview(dayNumLabel)
        addSubview(metaLabel)
        addSubview(collapseButton)
        addSubview(countButton)
        addSubview(selectAllButton)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(
        day: PhotoDaySection,
        chinese: Bool,
        locale: Locale,
        showSelectAll: Bool,
        allSelected: Bool,
        selecting: Bool,
        showCollapse: Bool
    ) {
        let nextKey = [
            day.id,
            day.header == .month ? "m" : "d",
            chinese ? "1" : "0",
            locale.identifier,
            showSelectAll ? "1" : "0",
            allSelected ? "1" : "0",
            selecting ? "1" : "0",
            showCollapse ? "1" : "0",
            "\(day.seenCount)/\(day.totalCount)",
        ].joined(separator: "|")

        guard nextKey != currentKey else { return }
        currentKey = nextKey

        isMonthMode = day.header == .month
        monthBaselineMode = isMonthMode && chinese && day.id != DayKeyRule.unknown

        if day.header == .month {
            if day.id == DayKeyRule.unknown {
                dayNumLabel.isHidden = true
                dayNumLabel.attributedText = nil
                metaLabel.isHidden = false
                metaLabel.text = L10n.string("date_unknown", locale: locale)
            } else if chinese {
                let month = Calendar.current.component(.month, from: day.date)
                dayNumLabel.isHidden = false
                dayNumLabel.attributedText = nil
                dayNumLabel.text = "\(month)"
                metaLabel.isHidden = false
                metaLabel.text = PhotoPageCopy.monthUnit(chinese: true)
            } else {
                dayNumLabel.isHidden = false
                dayNumLabel.attributedText = nil
                dayNumLabel.text = DateFormatCache.string(day.date, format: "MMM", locale: locale)
                metaLabel.isHidden = true
                metaLabel.text = nil
            }
        } else {
            dayNumLabel.isHidden = false
            metaLabel.isHidden = false
            let numStr = day.id == DayKeyRule.unknown ? "—" : DateFormatCache.string(day.date, format: "d", locale: locale)
            dayNumLabel.attributedText = NSAttributedString(
                string: numStr,
                attributes: [
                    .font: Self.dayNumFont,
                    .foregroundColor: DarkroomUIColors.paper,
                    .kern: -0.24,
                ]
            )
            metaLabel.text = day.id == DayKeyRule.unknown
                ? L10n.string("date_unknown", locale: locale)
                : DateFormatCache.string(day.date, format: chinese ? "M月 · EEE" : "MMM · EEE", locale: locale)
        }

        if showSelectAll {
            collapseButton.isHidden = true
            countButton.isHidden = true
            selectAllButton.isHidden = false
            selectAllButton.setTitle(
                L10n.string(allSelected ? "deselect_all" : "select_all", locale: locale),
                for: .normal
            )
            selectAllButton.setTitleColor(DarkroomUIColors.paper, for: .normal)
        } else if !selecting {
            selectAllButton.isHidden = true
            countButton.isHidden = false
            if showCollapse {
                collapseButton.isHidden = false
                collapseButton.setTitle(PhotoPageCopy.dayCollapse(chinese: chinese), for: .normal)
                collapseButton.setTitleColor(DarkroomUIColors.paper, for: .normal)
            } else {
                collapseButton.isHidden = true
            }

            if day.isDone {
                countButton.setAttributedTitle(
                    NSAttributedString(
                        string: PhotoPageCopy.sectionAllSeen(chinese: chinese),
                        attributes: [.font: Self.rightFont, .foregroundColor: DarkroomUIColors.dim]
                    ),
                    for: .normal
                )
            } else {
                let attr = NSMutableAttributedString(
                    string: "\(day.seenCount)",
                    attributes: [.font: Self.rightFont, .foregroundColor: DarkroomUIColors.paper]
                )
                attr.append(NSAttributedString(
                    string: PhotoPageCopy.sectionSlash(day.totalCount),
                    attributes: [.font: Self.rightFont, .foregroundColor: DarkroomUIColors.dim]
                ))
                countButton.setAttributedTitle(attr, for: .normal)
            }
            countButton.accessibilityHint = L10n.string("cd_enter_day", locale: locale)
        } else {
            collapseButton.isHidden = true
            countButton.isHidden = true
            selectAllButton.isHidden = true
        }

        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        let w = b.width
        let h = b.height
        let side = DarkroomDimens.screenSide
        let cy = h / 2
        var leftLimit = w - side

        if !selectAllButton.isHidden {
            let size = selectAllButton.sizeThatFits(CGSize(width: w - side * 2, height: h))
            let bw = ceil(size.width)
            let bh = max(h, DarkroomDimens.minTouch)
            selectAllButton.frame = CGRect(x: w - side - bw, y: cy - bh / 2, width: bw, height: bh)
            leftLimit = selectAllButton.frame.minX - 8
        } else if !countButton.isHidden {
            let size = countButton.sizeThatFits(CGSize(width: w - side * 2, height: h))
            let cw = ceil(size.width)
            let bh = max(h, DarkroomDimens.minTouch)
            countButton.frame = CGRect(x: w - side - cw, y: cy - bh / 2, width: cw, height: bh)
            leftLimit = countButton.frame.minX - 8

            if !collapseButton.isHidden {
                let colSize = collapseButton.sizeThatFits(CGSize(width: w - side * 2, height: h))
                let colW = max(DarkroomDimens.minTouch, ceil(colSize.width))
                collapseButton.frame = CGRect(
                    x: countButton.frame.minX - colW,
                    y: cy - bh / 2,
                    width: colW,
                    height: bh
                )
                leftLimit = collapseButton.frame.minX - 8
            }
        }

        let maxLeftW = max(0, leftLimit - side)
        if !dayNumLabel.isHidden {
            let numSize = dayNumLabel.sizeThatFits(CGSize(width: maxLeftW, height: h))
            let numW = min(maxLeftW, ceil(numSize.width))
            let numH = ceil(numSize.height)
            dayNumLabel.frame = CGRect(x: side, y: cy - numH / 2, width: numW, height: numH)

            if !metaLabel.isHidden {
                let gap: CGFloat = monthBaselineMode ? 2 : 8
                let metaX = side + numW + gap
                let maxMetaW = max(0, leftLimit - metaX)
                let metaSize = metaLabel.sizeThatFits(CGSize(width: maxMetaW, height: h))
                let metaW = min(maxMetaW, ceil(metaSize.width))
                let metaH = ceil(metaSize.height)
                let metaY: CGFloat
                if monthBaselineMode {
                    let numBaseline = dayNumLabel.frame.minY + Self.dayNumFont.ascender
                    metaY = numBaseline - Self.metaFont.ascender
                } else {
                    metaY = cy - metaH / 2
                }
                metaLabel.frame = CGRect(x: metaX, y: metaY, width: metaW, height: metaH)
            }
        } else if !metaLabel.isHidden {
            let metaSize = metaLabel.sizeThatFits(CGSize(width: maxLeftW, height: h))
            let metaW = min(maxLeftW, ceil(metaSize.width))
            let metaH = ceil(metaSize.height)
            metaLabel.frame = CGRect(x: side, y: cy - metaH / 2, width: metaW, height: metaH)
        }
    }

    @objc private func tappedCollapse() { onCollapse?() }
    @objc private func tappedCount() { onOpenDay?() }
    @objc private func tappedSelectAll() { onToggleSelectAll?() }
}

/// 列表内的日期 / 月份头部 Cell。
final class PhotosDayHeaderCell: UICollectionViewCell {
    static let reuseIdentifier = "PhotosDayHeaderCell"

    let headerView = PhotosDayHeaderView()
    private(set) var dayId: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .clear
        contentView.addSubview(headerView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        layoutAttributes
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        headerView.frame = contentView.bounds
    }

    func configure(
        day: PhotoDaySection,
        chinese: Bool,
        locale: Locale,
        showSelectAll: Bool,
        allSelected: Bool,
        selecting: Bool,
        showCollapse: Bool,
        hiddenAsStuck: Bool
    ) {
        dayId = day.id
        headerView.configure(
            day: day,
            chinese: chinese,
            locale: locale,
            showSelectAll: showSelectAll,
            allSelected: allSelected,
            selecting: selecting,
            showCollapse: showCollapse
        )
        setStuckHidden(hiddenAsStuck)
    }

    func setStuckHidden(_ hidden: Bool) {
        contentView.alpha = hidden ? 0 : 1
        isUserInteractionEnabled = !hidden
    }
}

/// 那年今天展开行：「还有 N 张」（纯 frame 布局，整行 48pt 点击热区）。
final class PhotosMoreRowCell: UICollectionViewCell {
    static let reuseIdentifier = "PhotosMoreRowCell"

    private let titleLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.plexMono(size: 11)
        l.textColor = DarkroomUIColors.paper
        l.textAlignment = .center
        l.numberOfLines = 1
        return l
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .clear
        contentView.addSubview(titleLabel)
        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        layoutAttributes
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        titleLabel.frame = contentView.bounds
    }

    func configure(overflowCount: Int, chinese: Bool) {
        let text = PhotoPageCopy.dayMore(overflowCount, chinese: chinese)
        if titleLabel.text != text {
            titleLabel.text = text
            accessibilityLabel = text
        }
    }
}
