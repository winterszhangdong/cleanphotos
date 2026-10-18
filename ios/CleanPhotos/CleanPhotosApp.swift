import SwiftUI

@main
struct CleanPhotosApp: App {
    @StateObject private var languageStore = LanguageStore()
    @StateObject private var library = PhotoLibraryService()

    init() { PerfLog.mark("appInit") }

    var body: some Scene {
        WindowGroup {
            RootView()
                .reduceMotionGate()
                // 字号跟随系统「更大字体」，但封顶 xxxLarge：版式里不少固定高度的行（和设计稿 / Android 对齐），再大会被裁切
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .environmentObject(languageStore)
                .environmentObject(library)
                .environment(\.locale, languageStore.effectiveLocale)
                .preferredColorScheme(.dark)
                .tint(DarkroomColors.paper)
                .background(DarkroomColors.room.ignoresSafeArea())
        }
    }
}
