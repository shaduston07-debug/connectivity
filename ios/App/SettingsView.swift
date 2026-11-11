import SwiftUI
#if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
import DeviceDiscoveryUI
import WiFiAware
#endif

struct SettingsView: View {
    @ObservedObject private var engine = Engine.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Your name", text: $name)
                        .onSubmit { engine.rename(name) }
                } header: {
                    Text("Your name")
                } footer: {
                    Text("This is what nearby people see.")
                }

                Section {
                    Toggle("Faster over Wi-Fi", isOn: Binding(get: { engine.useWifi }, set: { engine.setUseWifi($0) }))
                } footer: {
                    Text("Big files and videos go much faster when you're both on the same Wi-Fi, or one of you joins the other's hotspot. Nothing is uploaded to the internet.")
                }

                #if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
                if #available(iOS 26.0, *), engine.canPair {
                    PairingSection(paired: engine.pairedDevices)
                }
                #endif

                Section {
                    Label("Open Control Center", systemImage: "1.circle")
                    Label("Touch and hold an empty spot", systemImage: "2.circle")
                    Label("Tap Add a Control, then search “Inkwell”", systemImage: "3.circle")
                } header: {
                    Text("Add to Control Center")
                } footer: {
                    Text("There are buttons to receive, send photos and send files. You can also put them on the Lock Screen or the Action button.")
                }

                if engine.trustedCount > 0 {
                    Section {
                        Button("Ask Again Before Accepting", role: .destructive) { engine.forgetTrusted() }
                    } footer: {
                        Text("You chose to always accept from \(engine.trustedCount == 1 ? "one device" : "\(engine.trustedCount) devices").")
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        engine.rename(name)
                        dismiss()
                    }
                }
            }
            .onAppear { name = engine.deviceName }
        }
    }
}

#if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
/// Pairs two iPhones or iPads once, so they can use a direct Wi-Fi link anywhere.
@available(iOS 26.0, *)
private struct PairingSection: View {
    let paired: [String]

    var body: some View {
        Section {
            DevicePairingView(.wifiAware(.connecting(to: .inkwellDrop, from: .selected([])))) {
                Label("Show a code on this device", systemImage: "number.square")
            } fallback: {
                Text("Pairing isn't available right now.").foregroundStyle(.secondary)
            }
            DevicePicker(.wifiAware(.connecting(to: .selected([]), from: .inkwellDrop))) { _ in
            } label: {
                Label("Enter a code from another device", systemImage: "keyboard")
            } fallback: {
                Text("Pairing isn't available right now.").foregroundStyle(.secondary)
            }
            ForEach(paired, id: \.self) { name in
                Label(name, systemImage: "checkmark.circle.fill").foregroundStyle(.primary)
            }
        } header: {
            Text("Fast anywhere")
        } footer: {
            Text("Pair with another iPhone or iPad once, and the two of you can send big files quickly anywhere, no Wi-Fi network needed. On one device tap “Show a code”, on the other tap “Enter a code”.")
        }
    }
}
#endif
