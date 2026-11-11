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
    @ObservedObject private var router = ControlRouter.shared

    @State private var picked: [PhotosPickerItem] = []
    @State private var pickingPhotos = false
    @State private var outgoing: Outgoing?
    @State private var importingFiles = false
    @State private var scopedURLs: [URL] = []
    @State private var writingText = false
    @State private var draft = ""
    @State private var showingSettings = false
    @State private var preview: URL?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    ReadyCard(engine: engine)
                    sendRow
                    activeSection
                    recentSection
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 32)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Inkwell Drop")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
        }
        .sheet(isPresented: $showingSettings) { SettingsView() }
        .sheet(item: $outgoing, onDismiss: releaseFiles) { item in
            SendPanel(payload: item.payload, problem: item.problem) { outgoing = nil }
                .presentationDetents([.height(340)])
                .presentationDragIndicator(.visible)
        }
        .photosPicker(isPresented: $pickingPhotos, selection: $picked, maxSelectionCount: 50,
                      matching: .any(of: [.images, .videos]))
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
        .alert("Send a message or link", isPresented: $writingText) {
            TextField("Type or paste here", text: $draft)
            Button("Next") {
                let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                if !text.isEmpty { outgoing = Outgoing(payload: Payload(text: text)) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert(offerTitle, isPresented: offerShown, presenting: engine.pendingOffer) { offer in
            Button("Accept") { engine.accept(offer, always: false) }
            Button("Trust \(offer.peerName)") { engine.accept(offer, always: true) }
            Button("Decline", role: .cancel) { engine.decline(offer) }
        } message: { offer in
            Text(offer.transfer.text ?? formatBytes(offer.totalBytes))
        }
        .quickLookPreview($preview)
        .onAppear {
            engine.requestNotificationPermission()
            handle(router.pending)
        }
        .onReceive(router.$pending) { handle($0) }
    }

    // MARK: Sections

    private var sendRow: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Send").font(.title3.weight(.semibold))
            HStack(spacing: 12) {
                SendTile(title: "Photos", symbol: "photo.on.rectangle.angled", tint: .blue) { pickingPhotos = true }
                SendTile(title: "Files", symbol: "folder.fill", tint: .orange) { importingFiles = true }
                SendTile(title: "Message", symbol: "text.bubble.fill", tint: .green) {
                    draft = ""
                    writingText = true
                }
            }
            Text("Or tap Share in any app and choose Inkwell Drop.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var activeSection: some View {
        let active = engine.transfers.filter { $0.isActive && ($0.accepted || !$0.incoming) }
        if !active.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("Now").font(.title3.weight(.semibold))
                ForEach(active) { ActiveCard(transfer: $0) }
            }
        }
    }

    @ViewBuilder private var recentSection: some View {
        if !engine.history.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Recent").font(.title3.weight(.semibold))
                    Spacer()
                    Button("Clear") { engine.clearHistory() }.font(.subheadline)
                }
                VStack(spacing: 0) {
                    ForEach(Array(engine.history.prefix(20).enumerated()), id: \.element.id) { index, item in
                        if index > 0 { Divider().padding(.leading, 60) }
                        Button { open(item) } label: { HistoryRow(item: item) }
                            .buttonStyle(.plain)
                    }
                }
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
        }
    }

    private var offerTitle: String {
        guard let o = engine.pendingOffer else { return "" }
        return "\(o.peerName) wants to send you \(o.summary)"
    }

    private var offerShown: Binding<Bool> {
        Binding(get: { engine.pendingOffer != nil }, set: { _ in })
    }

    // MARK: Actions

    private func handle(_ action: ControlAction?) {
        guard let action = action else { return }
        router.pending = nil
        switch action {
        case .receive: break // the app is open, so this device can be found
        case .sendPhotos: pickingPhotos = true
        case .sendFiles: importingFiles = true
        }
    }

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
                if loaded.isEmpty { updated.problem = "Those items couldn't be opened." } else { updated.payload = Payload(sources: loaded) }
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
            if text.hasPrefix("http://") || text.hasPrefix("https://"),
               let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) {
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

// MARK: - Pieces

/// The card at the top: is this device ready, and what do others see it as.
private struct ReadyCard: View {
    @ObservedObject var engine: Engine
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 16) {
            ZStack {
                if engine.bluetooth == .on {
                    Circle().fill(Color.accentColor.opacity(0.18))
                        .frame(width: 56, height: 56)
                        .scaleEffect(pulse ? 1.25 : 0.9)
                        .opacity(pulse ? 0 : 1)
                        .animation(.easeOut(duration: 1.8).repeatForever(autoreverses: false), value: pulse)
                }
                Circle().fill(iconColor.opacity(0.15)).frame(width: 56, height: 56)
                Image(systemName: icon).font(.title2.weight(.semibold)).foregroundStyle(iconColor)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
                if engine.bluetooth == .denied {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .font(.subheadline.weight(.semibold))
                    .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .onAppear { pulse = true }
    }

    private var icon: String {
        switch engine.bluetooth {
        case .on, .unknown: return "dot.radiowaves.left.and.right"
        default: return "exclamationmark"
        }
    }

    private var iconColor: Color { engine.bluetooth == .on || engine.bluetooth == .unknown ? .accentColor : .orange }

    private var title: String {
        switch engine.bluetooth {
        case .on, .unknown: return "Ready"
        case .off: return "Bluetooth is off"
        case .denied: return "Inkwell Drop needs Bluetooth"
        case .unsupported: return "Not available on this device"
        }
    }

    private var detail: String {
        switch engine.bluetooth {
        case .on, .unknown: return "Nearby people see you as “\(engine.deviceName)”"
        case .off: return "Turn it on in Control Center to send and receive."
        case .denied: return "Allow Bluetooth so nearby devices can find you."
        case .unsupported: return "This device can't use Bluetooth Low Energy."
        }
    }
}

private struct SendTile: View {
    let title: String
    let symbol: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(width: 48, height: 48)
                    .background(tint.opacity(0.14), in: Circle())
                Text(title).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 18)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct ActiveCard: View {
    @ObservedObject var transfer: TransferModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(transfer.summary).font(.body.weight(.medium)).lineLimit(1)
                    Text("\(transfer.incoming ? "From" : "To") \(transfer.peerName)")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Stop") { Engine.shared.cancel(transfer) }
                    .font(.subheadline)
                    .buttonStyle(.borderless)
            }
            if transfer.state == .transferring {
                ProgressView(value: transfer.progress).tint(.accentColor)
            } else {
                ProgressView(value: 0).tint(.accentColor)
            }
            HStack(spacing: 6) {
                if transfer.viaLan && transfer.state == .transferring {
                    Label("Fast", systemImage: "bolt.fill").labelStyle(.titleAndIcon).foregroundStyle(.green)
                }
                Text(transfer.statusText).foregroundStyle(.secondary)
            }
            .font(.caption)
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct HistoryRow: View {
    let item: HistoryItem

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(item.incoming ? Color.accentColor : .secondary)
                .frame(width: 34, height: 34)
                .background((item.incoming ? Color.accentColor : Color.secondary).opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(item.text ?? item.summary).lineLimit(1)
                Text("\(item.incoming ? "From" : "To") \(item.peerName) · \(item.time.formatted(.relative(presentation: .named)))")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if item.incoming {
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
    }

    private var symbol: String {
        if let text = item.text {
            return text.hasPrefix("http://") || text.hasPrefix("https://") ? "link" : "text.bubble"
        }
        if item.photosSaved > 0 || item.summary.contains("photo") { return "photo" }
        if item.summary.contains("video") { return "video" }
        return item.incoming ? "arrow.down.doc" : "arrow.up.doc"
    }
}
