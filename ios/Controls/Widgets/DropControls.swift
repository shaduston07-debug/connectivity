import AppIntents
import SwiftUI
import WidgetKit

// Buttons for Control Center, the Lock Screen and the Action button (iOS 18).
// Add them from Control Center: touch and hold an empty space, tap Add a Control, search "Inkwell".

@main
struct DropControls: WidgetBundle {
    var body: some Widget {
        ReceiveControl()
        SendPhotosControl()
        SendFilesControl()
    }
}

struct ReceiveControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.mangashaduston.inkwelldrop.receive") {
            ControlWidgetButton(action: OpenToReceiveIntent()) {
                Label("Inkwell Drop", systemImage: "dot.radiowaves.left.and.right")
            }
        }
        .displayName("Inkwell Drop")
        .description("Open Inkwell Drop so nearby devices can send to you.")
    }
}

struct SendPhotosControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.mangashaduston.inkwelldrop.sendphotos") {
            ControlWidgetButton(action: SendPhotosIntent()) {
                Label("Send Photos", systemImage: "photo.on.rectangle.angled")
            }
        }
        .displayName("Send Photos")
        .description("Pick photos and videos and send them to a nearby device.")
    }
}

struct SendFilesControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.mangashaduston.inkwelldrop.sendfiles") {
            ControlWidgetButton(action: SendFilesIntent()) {
                Label("Send Files", systemImage: "folder")
            }
        }
        .displayName("Send Files")
        .description("Pick files and send them to a nearby device.")
    }
}
