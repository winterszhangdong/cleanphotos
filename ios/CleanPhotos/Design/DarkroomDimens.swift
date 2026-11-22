import CoreGraphics

enum DarkroomDimens {
    static let navHeight: CGFloat = 44
    static let navPadding: CGFloat = 4
    static let navCorner: CGFloat = 22
    static let navBottom: CGFloat = 24
    static let navItemWidth: CGFloat = 72
    static let navItemHeight: CGFloat = 36
    static let navIndicatorWidth: CGFloat = 16
    static let navIndicatorHeight: CGFloat = 2
    static let navItemGap: CGFloat = 4

    static let toolButton: CGFloat = 36
    static let toolIcon: CGFloat = 18
    static let headerHeight: CGFloat = 84
    static let headerBottomPadding: CGFloat = 12
    /// 照片页顶栏（收藏 / 垃圾桶仍用上面的 84）。Android `HeaderHeight` / `HeaderBottomPadding`。
    static let photosHeaderHeight: CGFloat = 56
    static let photosHeaderBottom: CGFloat = 8
    static let scopeRowHeight: CGFloat = 40
    static let scopeDialGap: CGFloat = 22
    static let scopeDialEdgeFade: CGFloat = 40
    static let scopeDialTextTop: CGFloat = 6
    static let scopeDialIndicatorTop: CGFloat = 32
    static let minTouch: CGFloat = 48
    static let scrubberThumbWidth: CGFloat = 4
    static let scrubberThumbHeight: CGFloat = 36
    static let scrubberInset: CGFloat = 6
    static let scrubberBubbleGap: CGFloat = 12
    static let screenSide: CGFloat = 16
    static let headerActionGap: CGFloat = 8

    static let yearHeight: CGFloat = 32
    static let dayHeight: CGFloat = 50
    static let dayTop: CGFloat = 16
    static let dayBottom: CGFloat = 10
    static let gridGap: CGFloat = 2
    static let listFade: CGFloat = 128
    static let gridColumns = 4

    static let unseenDot: CGFloat = 6
    static let unseenRing: CGFloat = 1.5
    static let unseenGlow: CGFloat = 8
    static let unseenInset: CGFloat = 6

    static let menuWidth: CGFloat = 192
    static let menuCorner: CGFloat = 16
    static let menuPadding: CGFloat = 4
    static let menuItemHeight: CGFloat = 44
    static let menuItemCorner: CGFloat = 12

    static let langSheetCorner: CGFloat = 24
    static let langItemHeight: CGFloat = 56

    static let playbackCenter: CGFloat = 64
    static let playbackRowHeight: CGFloat = 48
    static let playbackTrackHeight: CGFloat = 3
    static let playbackThumb: CGFloat = 12
    static let playbackThumbDrag: CGFloat = 16
    static let playbackPillPadH: CGFloat = 14
    static let playbackPillGap: CGFloat = 6
    static let playbackMotionIcon: CGFloat = 18
    static let playbackSoundIcon: CGFloat = 20
    static let playbackGap: CGFloat = 8
}
