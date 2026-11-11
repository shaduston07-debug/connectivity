import CoreTransferable
import PhotosUI
import QuickLook
import SwiftUI
import UniformTypeIdentifiers

/// A photo or video picked from the library, copied to a temporary file so it can be streamed.
struct PickedFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { try PickedFile(copying: $0.file) }
        FileRepresentation(importedContentType: .image) { try PickedFile(copying: $0.file) }
        FileRepresentation(importedContentType: .item) { try PickedFile(copying: $0.file) }
    }

    init(copying source: URL) throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing/\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(source.lastPathComponent)
        try FileManager.default.copyItem(at: source, to: dest)
        url = dest
    }
}

/// What the send sheet is showing.
private struct Outgoing: Identifiable {
    var id = UUID()
    var payload: Payload?
    var problem: String?
}

struct ContentView: View {
    @ObservedObject private var engine = Engine.shared

    @State private var picked: [PhotosPickerItem] = []
    @State private var outgoing: Outgoing?
    @State private var importingFiles = false
    @State private var scopedURLs: [URL] = []
    @State private var writingText = false
    @State private var draft = ""
    @State private var renaming = false
    @State private var nameDraft = ""
    @State private var preview: URL?

    var body: some View {
        NavigationStack {
            List {
                statusSection
                sendSection
                activeSection
                recentSection
                settingsSection
            }
            .navigationTitle("Inkwell Drop")
        }
        .sheet(item: $outgoing, onDismiss: releaseFiles) { item in
            SendPanel(payload: item.payload, problem: item.problem) { outgoing = nil }
                .presentationDetents([.height(340)])
                .presentationDragIndicator(.visible)
        }
        .fileImporter(isPresented: $importingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            scopedURLs = urls.filter { $0.startAccessingSecurityScopedResource() }
            let sources = urls.map { FileSource(url: $0, mime: mimeType(name: $0.lastPathComponent)) }
            outgoing = Outgoing(payload: Payload(sources: sources))
        }
        .onChange(of: picked) { items in
            guard !items.isEmpty else { return }
            picked = []
            loadPhotos(items)
        }
        .alert("Send text or a link", isPresented: $writingText) {
            TextField("Text", text: $draft)
            Button("Choose Device") {
                let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                if !text.isEmpty { outgoing = Outgoing(payload: Payload(text: text)) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Name", isPresented: $renaming) {
            TextField("Shown to nearby devices", text: $nameDraft)
            Button("Save") { engine.rename(nameDraft) }
            Button("Cancel", role: .cancel) {}
        }
        .alert(offerTitle, isPresented: offerShown, presenting: engine.pendingOffer) { offer in
            Button("Accept") { engine.accept(offer, always: false) }
            Button("Always Accept from This Device") { engine.accept(offer, always: true) }
            Button("Decline", role: .cancel) { engine.decline(offer) }
        } message: { offer in
            Text(offer.transfer.text ?? "\(formatBytes(offer.totalBytes)). Photos and videos are saved to Photos, other files to the Files app.")
        }
        .quickLookPreview($preview)
        .onAppear { engine.requestNotificationPermission() }
    }

    private var offerTitle: String {
        guard let o = engine.pendingOffer else { return "" }
        return "\(o.peerName) wants to send you \(o.summary)"
    }

    private var offerShown: Binding<Bool> {
        Binding(get: { engine.pendingOffer != nil }, set: { _ in })
    }

    // MARK: Sections

    private var statusSection: some View {
        Section {
            switch engine.bluetooth {
            case .denied:
                notice("Inkwell Drop isn't allowed to use Bluetooth", "Turn on Bluetooth for Inkwell Drop in Settings.", "Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            case .off:
                notice("Bluetooth is off", "Turn on Bluetooth in Control Centre or Settings to send and receive.", nil) {}
            case .unsupported:
                notice("Bluetooth isn't available", "This device can't use Bluetooth LE.", nil) {}
            default:
                HStack(spacing: 14) {
                    Image(systemName: "dot.radiowaves.left.and.right").font(.title2).foregroundStyle(Color.accentColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Ready to receive").font(.headline)
                        Text("Nearby devices see this one as “\(engine.deviceName)” while Inkwell Drop is open.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func notice(_ title: String, _ detail: String, _ button: String?, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: "exclamationmark.triangle.fill").font(.headline).foregroundStyle(.orange)
            Text(detail).font(.footnote).foregroundStyle(.secondary)
            if let button = button { Button(button, action: action).padding(.top, 2) }
        }
        .padding(.vertical, 4)
    }

    private var sendSection: some View {
        Section {
            PhotosPicker(selection: $picked, maxSelectionCount: 50, matching: .any(of: [.images, .videos])) {
                Label("Photos and Videos", systemImage: "photo.on.rectangle")
            }
            Button { importingFiles = true } label: { Label("Files", systemImage: "folder") }
            Button {
                draft = ""
                writingText = true
            } label: { Label("Text or Link", systemImage: "text.bubble") }
        } header: {
            Text("Send")
        } footer: {
            Text("You can also tap Share in any app and choose Inkwell Drop.")
        }
    }

    @ViewBuilder private var activeSection: some View {
        let active = engine.transfers.filter { $0.isActive && ($0.accepted || !$0.incoming) }
        if !active.isEmpty {
            Section("In Progress") {
                ForEach(active) { ActiveRow(transfer: $0) }
            }
        }
    }

    @ViewBuilder private var recentSection: some View {
        if !engine.history.isEmpty {
            Section("Recent") {
                ForEach(engine.history.prefix(30)) { item in
                    Button { open(item) } label: { historyRow(item) }
                        .buttonStyle(.plain)
                }
                Button("Clear List", role: .destructive) { engine.clearHistory() }
            }
        }
    }

    private func historyRow(_ item: HistoryItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.text ?? item.summary).lineLimit(2)
                Text("\(item.incoming ? "From" : "Sent to") \(item.peerName) · \(item.time.formatted(date: .abbreviated, time: .shortened))")
                    .font(.footnote).foregroundStyle(.secondary)
                if item.photosSaved > 0 {
                    Text("Saved to Photos").font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if let label = actionLabel(item) {
                Text(label).font(.subheadline).foregroundStyle(Color.accentColor)
            }
        }
        .contentShape(Rectangle())
    }

    private func actionLabel(_ item: HistoryItem) -> String? {
        guard item.incoming else { return nil }
        if let text = item.text { return text.hasPrefix("http://") || text.hasPrefix("https://") ? "Open" : "Copy" }
        if !item.files.isEmpty { return "Open" }
        return item.photosSaved > 0 ? "Photos" : nil
    }

    private var settingsSection: some View {
        Section {
            Button {
                nameDraft = engine.deviceName
                renaming = true
            } label: {
                HStack {
                    Text("Name").foregroundStyle(.primary)
                    Spacer()
                    Text(engine.deviceName).foregroundStyle(.secondary)
                }
            }
            Toggle("Use Wi-Fi When Available", isOn: Binding(get: { engine.useWifi }, set: { engine.setUseWifi($0) }))
            if engine.trustedCount > 0 {
                Button("Stop Always Accepting (\(engine.trustedCount))", role: .destructive) { engine.forgetTrusted() }
            }
        } header: {
            Text("Settings")
        } footer: {
            Text("When both devices are on the same Wi-Fi network, Inkwell Drop sends over it, which is much faster for videos and large files. No Wi-Fi around? Turn on one phone’s hotspot and connect the other to it. Otherwise everything goes over Bluetooth.")
        }
    }

    // MARK: Actions

    private func loadPhotos(_ items: [PhotosPickerItem]) {
        let sheet = Outgoing()
        outgoing = sheet // shows "Getting ready…" while the photos are exported
        Task {
            var sources: [Source] = []
            for item in items {
                if let file = try? await item.loadTransferable(type: PickedFile.self) {
                    sources.append(FileSource(url: file.url, mime: mimeType(name: file.url.lastPathComponent)))
                }
            }
            let loaded = sources
            await MainActor.run {
                guard outgoing?.id == sheet.id else { return } // the sheet was closed while loading
                var updated = sheet
                if loaded.isEmpty { updated.problem = "Those items couldn't be read." } else { updated.payload = Payload(sources: loaded) }
                outgoing = updated
            }
        }
    }

    private func releaseFiles() {
        scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
        scopedURLs = []
        try? FileManager.default.removeItem(at: FileManager.default.temporaryDirectory.appendingPathComponent("outgoing"))
    }

    private func open(_ item: HistoryItem) {
        guard item.incoming else { return }
        if let text = item.text {
            if text.hasPrefix("http://") || text.hasPrefix("https://"), let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) {
                UIApplication.shared.open(url)
            } else {
                UIPasteboard.general.string = text
            }
        } else if let name = item.files.first {
            let url = Engine.documents.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: url.path) { preview = url }
        } else if item.photosSaved > 0, let url = URL(string: "photos-redirect://") {
            UIApplication.shared.open(url)
        }
    }
}

private struct ActiveRow: View {
    @ObservedObject var transfer: TransferModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(transfer.incoming ? "From" : "To") \(transfer.peerName): \(transfer.summary)").lineLimit(1)
                    Text(transfer.statusText).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Cancel") { Engine.shared.cancel(transfer) }.buttonStyle(.borderless)
            }
            if transfer.state == .transferring {
                ProgressView(value: transfer.progress)
            } else {
                ProgressView().frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.vertical, 2)
    }
}
