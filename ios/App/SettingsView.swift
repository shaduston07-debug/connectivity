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

                Section {
                    NavigationLink {
                        TrustedDevicesView()
                    } label: {
                        HStack {
                            Text("Trusted Devices")
                            Spacer()
                            Text(engine.trustedCount == 0 ? "None" : "\(engine.trustedCount)").foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("Receive from people you choose without being asked each time.")
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

/// Pick who can send to you without being asked, from the devices you've used before.
struct TrustedDevicesView: View {
    @ObservedObject private var engine = Engine.shared

    var body: some View {
        List {
            Section {
                if engine.trustedDevices.isEmpty {
                    Text("No trusted devices yet.").foregroundStyle(.secondary)
                }
                ForEach(engine.trustedDevices) { device in
                    DeviceRow(name: device.name, detail: "Sends without asking", trusted: true)
                        .swipeActions {
                            Button("Remove", role: .destructive) { engine.untrust(device.id) }
                        }
                        .contextMenu {
                            Button(role: .destructive) { engine.untrust(device.id) } label: {
                                Label("Remove", systemImage: "minus.circle")
                            }
                        }
                }
            } footer: {
                Text("Anything a trusted device sends you starts right away, without asking. Swipe left to remove one.")
            }

            Section {
                let candidates = engine.trustCandidates
                if candidates.isEmpty {
                    Text("People you send to or receive from will show up here.").foregroundStyle(.secondary)
                }
                ForEach(candidates) { device in
                    HStack {
                        DeviceRow(name: device.name, detail: detail(for: device.id), trusted: false)
                        Button("Trust") { withAnimation { engine.trust(device.id, name: device.name) } }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)
                    }
                }
            } header: {
                Text("Add from your recent devices")
            }
        }
        .navigationTitle("Trusted Devices")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detail(for id: String) -> String {
        if engine.peers.contains(where: { $0.id == id }) { return "Nearby now" }
        guard let item = engine.history.first(where: { $0.peerId == id }) else { return "" }
        return "Last \(item.incoming ? "received" : "sent") \(item.time.formatted(.relative(presentation: .named)))"
    }
}

private struct DeviceRow: View {
    let name: String
    let detail: String
    let trusted: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(String(name.prefix(1)).uppercased())
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(trusted ? Color.green : Color.accentColor)
                .frame(width: 34, height: 34)
                .background((trusted ? Color.green : Color.accentColor).opacity(0.14), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(name).lineLimit(1)
                if !detail.isEmpty {
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
    }
}
