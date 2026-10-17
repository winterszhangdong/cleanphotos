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
                .environmentObject(languageStore)
                .environmentObject(library)
                .environment(\.locale, languageStore.effectiveLocale)
                .preferredColorScheme(.dark)
                .tint(DarkroomColors.paper)
                .background(DarkroomColors.room.ignoresSafeArea())
        }
    }
}
