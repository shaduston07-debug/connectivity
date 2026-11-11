import AppIntents
import Combine
import Foundation

/// What a Control Center button asked the app to do when it opened.
enum ControlAction: String {
    case receive, sendPhotos, sendFiles
}

/// Hands a Control Center tap to the app's screen. These intents open the app, so iOS runs them
/// inside the app, where the screen is listening.
final class ControlRouter: ObservableObject {
    static let shared = ControlRouter()
    @Published var pending: ControlAction?
}

private func route(_ action: ControlAction) async {
    await MainActor.run { ControlRouter.shared.pending = action }
}

struct OpenToReceiveIntent: AppIntent {
    static var title: LocalizedStringResource = "Receive with Inkwell Drop"
    static var description = IntentDescription("Opens Inkwell Drop so nearby devices can send to you.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        await route(.receive)
        return .result()
    }
}

struct SendPhotosIntent: AppIntent {
    static var title: LocalizedStringResource = "Send Photos with Inkwell Drop"
    static var description = IntentDescription("Opens Inkwell Drop and lets you pick photos and videos to send.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        await route(.sendPhotos)
        return .result()
    }
}

struct SendFilesIntent: AppIntent {
    static var title: LocalizedStringResource = "Send Files with Inkwell Drop"
    static var description = IntentDescription("Opens Inkwell Drop and lets you pick files to send.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        await route(.sendFiles)
        return .result()
    }
}
