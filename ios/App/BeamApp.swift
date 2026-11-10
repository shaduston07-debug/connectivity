import SwiftUI

@main
struct BeamApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Started here, not from a view, so Bluetooth is running even when iOS relaunches
        // the app in the background because a sender came into range.
        Engine.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onChange(of: scenePhase) { phase in
                    Engine.shared.sceneChanged(active: phase != .background)
                }
        }
    }
}
