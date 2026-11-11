import CoreBluetooth
import Foundation
import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications

struct PeerModel: Identifiable, Equatable {
    let id: String
    let name: String
    let platform: String
}

/// A transfer as the UI sees it. Changed only on the main thread.
final class TransferModel: ObservableObject, Identifiable {
    let transfer: Transfer
    let incoming: Bool
    let summary: String

    @Published var state: TransferState
    @Published var bytesDone: Int64 = 0
    @Published var viaLan = false
    @Published var accepted = false
    @Published var error: String?
    @Published var bytesPerSecond: Int64 = 0
    fileprivate var lastBytes: Int64 = 0
    fileprivate var lastTime = Date()

    var id: String { transfer.id }
    var peerName: String { transfer.peer.name }
    var peerId: String { transfer.peer.id }
    var totalBytes: Int64 { transfer.totalBytes }
    var progress: Double { totalBytes > 0 ? min(1, Double(bytesDone) / Double(totalBytes)) : 0 }
    var isActive: Bool { state == .waiting || state == .transferring }

    init(transfer: Transfer, incoming: Bool) {
        self.transfer = transfer
        self.incoming = incoming
        state = transfer.state
        summary = TransferModel.describe(transfer)
    }

    static func describe(_ t: Transfer) -> String {
        if t.items.isEmpty {
            let text = t.text ?? ""
            return text.hasPrefix("http://") || text.hasPrefix("https://") ? "a link" : "a text message"
        }
        if t.items.count == 1 { return t.items[0].name }
        let kinds = t.items.map { mediaKind(name: $0.name) }
        if kinds.allSatisfy({ $0 == .photo }) { return "\(t.items.count) photos" }
        if kinds.allSatisfy({ $0 == .video }) { return "\(t.items.count) videos" }
        return "\(t.items.count) files"
    }

    var statusText: String {
        switch state {
        case .waiting: return incoming ? (accepted ? "Connecting…" : "Waiting for you") : "Waiting…"
        case .transferring:
            let speed = bytesPerSecond > 0 ? " · \(formatBytes(bytesPerSecond))/s" : ""
            return "\(formatBytes(bytesDone)) of \(formatBytes(totalBytes))\(speed) · \(viaLan ? "Wi-Fi" : "Bluetooth")"
        case .done: return incoming ? "Received" : "Sent"
        case .declined: return "Declined"
        case .cancelled: return error ?? "Cancelled"
        case .failed: return error ?? "Failed"
        }
    }
}

struct HistoryItem: Codable, Identifiable {
    var id = UUID()
    let time: Date
    let peerName: String
    let summary: String
    /// File names in the app's Documents folder.
    let files: [String]
    let photosSaved: Int
    let text: String?
    let incoming: Bool
}

enum MediaKind { case photo, video, other }

func mediaKind(name: String) -> MediaKind {
    guard let type = UTType(filenameExtension: (name as NSString).pathExtension) else { return .other }
    if type.conforms(to: .image) { return .photo }
    if type.conforms(to: .movie) { return .video }
    return .other
}

func mimeType(name: String) -> String {
    UTType(filenameExtension: (name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream"
}

func formatBytes(_ n: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
}

enum BluetoothStatus { case unknown, on, off, denied, unsupported }

/// What is being sent: files, or a piece of text / a link.
struct Payload {
    var sources: [Source] = []
    var text: String?

    var isEmpty: Bool { sources.isEmpty && text == nil }
    var summary: String {
        if sources.isEmpty { return String((text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(80)) }
        let total = sources.reduce(Int64(0)) { $0 + $1.meta.size }
        let what = sources.count == 1 ? sources[0].meta.name : "\(sources.count) items"
        return "\(what) · \(formatBytes(total))"
    }
}

private final class DocumentSinks: SinkFactory {
    func create(item: ItemMeta) throws -> FileSink {
        try FolderSink(folder: Engine.documents, name: item.name)
    }
}

/// Owns the Bluetooth transport, the open connections and every transfer.
/// The app and the share extension each have one.
final class Engine: NSObject, ObservableObject, PeerLinkListener, UNUserNotificationCenterDelegate {
    static let shared = Engine()
    static let isExtension = Bundle.main.bundlePath.hasSuffix(".appex")
    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    /// Devices we have an open connection to, so they can be sent to right away.
    @Published private(set) var peers: [PeerModel] = []
    @Published private(set) var transfers: [TransferModel] = []
    @Published private(set) var history: [HistoryItem] = []
    @Published private(set) var bluetooth = BluetoothStatus.unknown
    @Published private(set) var deviceName: String
    @Published private(set) var useWifi: Bool
    @Published private(set) var trustedCount = 0

    private let defaults = UserDefaults.standard
    private let local: LocalInfo
    private let lan: LanServer?
    private let env: DropEnv
    private var ble: BleTransport!

    private struct LinkEntry {
        let link: PeerLink
        let outbound: Bool
    }

    private var links: [LinkEntry] = []
    private var seekers = 0
    private var inForeground = true
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    private override init() {
        let defaults = UserDefaults.standard
        let name = defaults.string(forKey: "drop.name") ?? UIDevice.current.name
        let wifi = defaults.object(forKey: "drop.useWifi") as? Bool ?? true
        let local = LocalInfo(identity: Identity.load(from: defaults), name: name, platform: "ios")
        let lan = Engine.isExtension ? nil : LanServer(local: local)
        deviceName = name
        useWifi = wifi
        self.local = local
        self.lan = lan
        let env = DropEnv(local: local, sinks: DocumentSinks(), lan: lan)
        env.useLan = wifi
        self.env = env
        super.init()
        trustedCount = trusted.count
        loadHistory()
    }

    /// Turns Bluetooth on for Inkwell Drop. The first call shows the system's Bluetooth permission prompt.
    func start() {
        guard ble == nil else { return }
        #if !DROP_EXTENSION
        // iOS can launch the app straight into the background when a sender comes into range.
        inForeground = UIApplication.shared.applicationState != .background
        #endif
        ble = BleTransport(receiver: !Engine.isExtension, restoreID: Engine.isExtension ? nil : "drop.central")
        ble.onChannel = { [weak self] duplex, outbound in self?.opened(duplex, outbound: outbound) }
        ble.onStateChange = { [weak self] in DispatchQueue.main.async { self?.refresh() } }
        if !Engine.isExtension {
            lan?.start()
            askForLocalNetwork()
            let center = UNUserNotificationCenter.current()
            center.delegate = self
            center.setNotificationCategories([
                UNNotificationCategory(identifier: "offer", actions: [
                    UNNotificationAction(identifier: "accept", title: "Accept", options: []),
                    UNNotificationAction(identifier: "decline", title: "Decline", options: [.destructive]),
                ], intentIdentifiers: []),
            ])
        }
        refresh()
    }

    /// iOS only asks "allow Inkwell Drop to find devices on your local network?" when the app first
    /// reaches out to one, and until then it silently blocks incoming Wi-Fi transfers. Reaching out
    /// once at launch gets the question asked before it matters.
    private func askForLocalNetwork() {
        guard useWifi else { return }
        DispatchQueue.global(qos: .utility).async {
            guard let mine = TcpSocket.wifiAddresses().first else { return }
            var parts = mine.split(separator: ".").map(String.init)
            guard parts.count == 4 else { return }
            parts[3] = parts[3] == "1" ? "2" : "1"
            _ = try? TcpSocket.connect(host: parts.joined(separator: "."), port: 9, timeoutMs: 300)
        }
    }

    // MARK: Settings

    func rename(_ name: String) {
        var n = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        if n.isEmpty { n = UIDevice.current.name }
        deviceName = n
        local.name = n
        defaults.set(n, forKey: "drop.name")
    }

    func setUseWifi(_ on: Bool) {
        useWifi = on
        env.useLan = on
        defaults.set(on, forKey: "drop.useWifi")
    }

    private var trusted: [String] { defaults.stringArray(forKey: "drop.trusted") ?? [] }

    func trust(_ peerId: String) {
        if !trusted.contains(peerId) { defaults.set(trusted + [peerId], forKey: "drop.trusted") }
        trustedCount = trusted.count
    }

    func forgetTrusted() {
        defaults.removeObject(forKey: "drop.trusted")
        trustedCount = 0
    }

    // MARK: Bluetooth state

    /// Makes Bluetooth match what the app needs right now.
    private func refresh() {
        guard let ble = ble else { return }
        switch BleTransport.authorization {
        case .denied, .restricted: bluetooth = .denied
        default:
            switch ble.state {
            case .poweredOn: bluetooth = .on
            case .poweredOff: bluetooth = .off
            case .unauthorized: bluetooth = .denied
            case .unsupported: bluetooth = .unsupported
            default: bluetooth = .unknown
            }
        }
        if Engine.isExtension {
            ble.setScan(seekers > 0 ? .everything : .off)
            return
        }
        ble.setVisible(true)
        if seekers > 0 {
            ble.setScan(.everything)
        } else {
            // In the background nobody can find us by scanning, so look for senders instead.
            ble.setScan(inForeground ? .off : .seekersOnly)
        }
    }

    /// Call while a send screen is showing: looks for nearby devices.
    func startSeeking() {
        seekers += 1
        refresh()
    }

    func stopSeeking() {
        seekers = max(0, seekers - 1)
        refresh()
        guard seekers == 0 else { return }
        // Drop connections we made, and release devices that connected to be found.
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [self] in
            guard seekers == 0 else { return }
            for e in links where !e.link.busy {
                if e.outbound { e.link.close() } else if e.link.ready { e.link.sendIdle() }
            }
        }
    }

    func linkPeerIdle(_ link: PeerLink) {
        DispatchQueue.main.async { [self] in
            if seekers == 0, !link.busy { link.close() }
        }
    }

    func sceneChanged(active: Bool) {
        inForeground = active
        refresh()
    }

    // MARK: Connections

    private func opened(_ duplex: Duplex, outbound: Bool) {
        let link = PeerLink(duplex: duplex, env: env, chunkSize: 16 * 1024, listener: self)
        DispatchQueue.main.async { [self] in
            links.append(LinkEntry(link: link, outbound: outbound))
            link.start()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
            if !link.ready { link.close() }
        }
    }

    func linkReady(_ link: PeerLink) {
        DispatchQueue.main.async { self.rebuildPeers() }
    }

    func linkClosed(_ link: PeerLink, error: String?) {
        DispatchQueue.main.async { [self] in
            links.removeAll { $0.link === link }
            rebuildPeers()
        }
    }

    private func rebuildPeers() {
        var seen: [PeerModel] = []
        for e in links where e.link.ready && !e.link.closed {
            let p = e.link.peer
            if p.id != local.id, !seen.contains(where: { $0.id == p.id }) {
                seen.append(PeerModel(id: p.id, name: p.name, platform: p.platform))
            }
        }
        if seen != peers { peers = seen }
    }

    // MARK: Transfers

    private func track(_ t: Transfer, incoming: Bool) -> TransferModel {
        let m = TransferModel(transfer: t, incoming: incoming)
        transfers.insert(m, at: 0)
        if transfers.count > 30 { transfers.removeLast(transfers.count - 30) }
        t.onUpdate = { [weak self, weak m] _ in
            DispatchQueue.main.async { if let m = m { self?.sync(m) } }
        }
        sync(m)
        updateBackgroundTask()
        return m
    }

    private func sync(_ m: TransferModel) {
        let t = m.transfer
        let wasActive = m.isActive
        let now = Date()
        let dt = now.timeIntervalSince(m.lastTime)
        if dt >= 0.5 {
            let rate = Int64(Double(t.bytesDone - m.lastBytes) / dt)
            m.bytesPerSecond = m.bytesPerSecond == 0 ? rate : (m.bytesPerSecond * 6 + rate * 4) / 10
            m.lastBytes = t.bytesDone
            m.lastTime = now
        }
        m.bytesDone = t.bytesDone
        m.viaLan = t.viaLan
        m.error = t.error
        let accepted = (t as? IncomingTransfer)?.accepted ?? true
        // Views that list transfers by state watch the engine, not each transfer.
        if m.state != t.state || m.accepted != accepted { objectWillChange.send() }
        m.accepted = accepted
        if m.state != t.state { m.state = t.state }
        if wasActive && t.isFinished {
            finished(m)
            updateBackgroundTask()
        }
    }

    /// Sends to a nearby device. Returns nil if the device has gone.
    func send(to peerId: String, payload: Payload, started: @escaping (TransferModel?) -> Void) {
        guard let link = links.map({ $0.link }).first(where: { $0.ready && !$0.closed && $0.peer.id == peerId }) else {
            return started(nil)
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let t = link.offer(sources: payload.sources, text: payload.text)
            DispatchQueue.main.async { started(self.track(t, incoming: false)) }
        }
    }

    func link(_ link: PeerLink, offered transfer: IncomingTransfer) {
        DispatchQueue.main.async { [self] in
            if Engine.isExtension {
                // The share sheet only sends.
                DispatchQueue.global().async { transfer.decline() }
                return
            }
            let m = track(transfer, incoming: true)
            if trusted.contains(transfer.peer.id) {
                accept(m, always: false)
            } else if !inForeground {
                notifyOffer(m)
            }
        }
    }

    func accept(_ m: TransferModel, always: Bool) {
        if always { trust(m.peerId) }
        DispatchQueue.global(qos: .userInitiated).async { (m.transfer as? IncomingTransfer)?.accept() }
    }

    func decline(_ m: TransferModel) {
        DispatchQueue.global(qos: .userInitiated).async { (m.transfer as? IncomingTransfer)?.decline() }
    }

    func cancel(_ m: TransferModel) {
        DispatchQueue.global(qos: .userInitiated).async { m.transfer.cancel() }
    }

    /// The oldest offer waiting for the user to accept or decline.
    var pendingOffer: TransferModel? {
        transfers.last { $0.incoming && $0.state == .waiting && !$0.accepted }
    }

    private func finished(_ m: TransferModel) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [m.id])
        guard m.state == .done else {
            if m.incoming, m.accepted, m.state == .failed, !inForeground {
                notify(id: m.id, title: "Couldn't receive \(m.summary) from \(m.peerName)", body: m.error ?? "The connection was lost.")
            }
            return
        }
        guard let t = m.transfer as? IncomingTransfer else {
            if !Engine.isExtension {
                addHistory(HistoryItem(time: Date(), peerName: m.peerName, summary: m.summary, files: [], photosSaved: 0, text: nil, incoming: false))
            }
            return
        }
        if let text = t.text, inForeground { UIPasteboard.general.string = text }
        let paths = t.results
        saveMediaToPhotos(paths) { [self] kept, savedCount in
            addHistory(HistoryItem(time: Date(), peerName: m.peerName, summary: m.summary,
                                   files: kept.map { ($0 as NSString).lastPathComponent },
                                   photosSaved: savedCount, text: t.text, incoming: true))
            if !inForeground {
                let body = t.text ?? (savedCount > 0 && kept.isEmpty ? "Saved to Photos." : "Open Inkwell Drop to see it. Files are in the Files app, under Inkwell Drop.")
                notify(id: m.id, title: "Received \(m.summary) from \(m.peerName)", body: body)
            }
        }
    }

    /// Moves received photos and videos into the Photos library. Files that can't go there stay in Documents.
    private func saveMediaToPhotos(_ paths: [String], done: @escaping (_ kept: [String], _ saved: Int) -> Void) {
        let media = paths.filter { mediaKind(name: $0) != .other }
        guard !media.isEmpty else { return done(paths, 0) }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                return DispatchQueue.main.async { done(paths, 0) }
            }
            var kept = paths.filter { mediaKind(name: $0) == .other }
            var saved = 0
            let group = DispatchGroup()
            let lock = NSLock()
            for path in media {
                group.enter()
                let url = URL(fileURLWithPath: path)
                PHPhotoLibrary.shared().performChanges({
                    let request = PHAssetCreationRequest.forAsset()
                    let options = PHAssetResourceCreationOptions()
                    options.shouldMoveFile = false
                    request.addResource(with: mediaKind(name: path) == .video ? .video : .photo, fileURL: url, options: options)
                }, completionHandler: { ok, _ in
                    lock.lock()
                    if ok {
                        saved += 1
                        try? FileManager.default.removeItem(at: url)
                    } else {
                        kept.append(path)
                    }
                    lock.unlock()
                    group.leave()
                })
            }
            group.notify(queue: .main) { done(kept, saved) }
        }
    }

    /// Asks iOS for extra time while a transfer runs with the app in the background.
    private func updateBackgroundTask() {
        #if !DROP_EXTENSION
        let active = transfers.contains { $0.isActive }
        if active, backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "drop.transfer") { [self] in
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
        } else if !active, backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
        #endif
    }

    // MARK: Notifications

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notifyOffer(_ m: TransferModel) {
        let content = UNMutableNotificationContent()
        content.title = "\(m.peerName) wants to send you \(m.summary)"
        content.body = m.transfer.text ?? (m.totalBytes > 0 ? formatBytes(m.totalBytes) : "")
        content.sound = .default
        content.categoryIdentifier = "offer"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: m.id, content: content, trigger: nil))
    }

    private func notify(id: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.identifier
        if let m = transfers.first(where: { $0.id == id }), m.state == .waiting, !m.accepted {
            switch response.actionIdentifier {
            case "accept": accept(m, always: false)
            case "decline": decline(m)
            default: break // opening the app shows the prompt
            }
        }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([]) // the app shows its own prompt while it is open
    }

    // MARK: History

    private func loadHistory() {
        guard let data = defaults.data(forKey: "drop.history"),
              let items = try? JSONDecoder().decode([HistoryItem].self, from: data) else { return }
        history = items
    }

    private func addHistory(_ item: HistoryItem) {
        history.insert(item, at: 0)
        if history.count > 60 { history.removeLast(history.count - 60) }
        saveHistory()
    }

    func clearHistory() {
        history = []
        saveHistory()
    }

    private func saveHistory() {
        defaults.set(try? JSONEncoder().encode(history), forKey: "drop.history")
    }
}
