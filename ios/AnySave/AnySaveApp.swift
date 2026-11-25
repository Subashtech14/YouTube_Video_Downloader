import SwiftUI

@main
struct AnySaveApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .tint(model.tab.color)
                .onOpenURL { model.handleOpenURL($0) }
                .onChange(of: phase) { p in
                    if p == .active { model.startPolling() } else { model.stopPolling() }
                }
        }
    }
}
