import Foundation

public struct ItemMeta {
    public let name: String
    public let size: Int64
    public let mime: String

    public init(name: String, size: Int64, mime: String) {
        self.name = name
        self.size = size
        self.mime = mime
    }
}

/// Something to send.
public protocol Source {
    var meta: ItemMeta { get }
    func open() throws -> SourceReader
}

public protocol SourceReader {
    /// Returns empty data at the end.
    func read(max: Int) throws -> Data
    func close()
}

/// Where a received file is written.
public protocol FileSink {
    func write(_ data: Data) throws
    /// Closes the file and returns where it was saved.
    func finish() throws -> String
    func abort()
}

public protocol SinkFactory {
    func create(item: ItemMeta) throws -> FileSink
}

public final class DropEnv {
    public let local: LocalInfo
    public let sinks: SinkFactory
    public let lan: LanServer?
    /// Move file data to Wi-Fi when both devices are on the same network, or can reach each other directly.
    public var useLan = true
    /// A router-free Wi-Fi link such as Wi-Fi Aware, if this device has one.
    public var direct: DirectLink?

    public init(local: LocalInfo, sinks: SinkFactory, lan: LanServer?) {
        self.local = local
        self.sinks = sinks
        self.lan = lan
    }
}

/// A router-free radio link set up on demand for one transfer, such as Wi-Fi Aware.
/// The receiver's `LanServer` still checks the connection's one-time token, so this only has to
/// deliver a byte stream between the two devices.
public protocol DirectLink: AnyObject {
    /// Receiver: get ready for the sender to connect. Returns what the sender needs to know (sent over
    /// the encrypted Bluetooth link), or nil if the link isn't available right now. Must not block for long.
    func prepare(transferId: String, lanPort: UInt16) -> [String: Any]?
    /// Receiver: the transfer is over; tear down anything `prepare` set up.
    func release(transferId: String)
    /// Sender: open the link described by `info` and hand each candidate stream to `attach`, which runs
    /// the handshake and returns a session if it reached the right device. Blocks for a few seconds at most.
    func connect(info: [String: Any], attach: (Duplex) -> Session?, cancelled: () -> Bool) -> Session?
}

public enum TransferState: String {
    case waiting = "WAITING"
    case transferring = "TRANSFERRING"
    case done = "DONE"
    case declined = "DECLINED"
    case cancelled = "CANCELLED"
    case failed = "FAILED"
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

struct CancelledError: Error {}

public class Transfer {
    public let id: String
    public let peer: PeerInfo
    public let items: [ItemMeta]
    public let text: String?
    public let totalBytes: Int64
    public let startedAt = Date()

    public internal(set) var bytesDone: Int64 = 0
    public internal(set) var state: TransferState = .waiting
    /// True when the file data went over Wi-Fi instead of Bluetooth.
    public internal(set) var viaLan = false
    public internal(set) var error: String?

    /// Called on a background thread when progress or state changes.
    public var onUpdate: ((Transfer) -> Void)?

    let lock = NSRecursiveLock()
    private var lastNotify: Int64 = 0

    init(id: String, peer: PeerInfo, items: [ItemMeta], text: String?) {
        self.id = id
        self.peer = peer
        self.items = items
        self.text = text
        totalBytes = items.reduce(0) { $0 + $1.size }
    }

    public var isFinished: Bool { state != .waiting && state != .transferring }

    func notifyUpdate() {
        lastNotify = nowMs()
        onUpdate?(self)
    }

    func addBytes(_ n: Int) {
        bytesDone += Int64(n)
        if nowMs() - lastNotify >= 80 { notifyUpdate() }
    }

    @discardableResult
    func finish(_ s: TransferState, _ message: String? = nil) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if isFinished { return false }
        error = message
        state = s
        notifyUpdate()
        return true
    }

    public func cancel() {}

    /// Strips anything path-like from a file name sent by another device.
    public static func safeName(_ raw: String) -> String {
        var s = raw
        if let i = s.lastIndex(of: "/") { s = String(s[s.index(after: i)...]) }
        if let i = s.lastIndex(of: "\\") { s = String(s[s.index(after: i)...]) }
        s = String(s.unicodeScalars.filter { $0.value >= 32 && !"<>:\"|?*".unicodeScalars.contains($0) }
            .map(Character.init))
        s = s.trimmingCharacters(in: .whitespaces)
        while s.hasPrefix(".") { s.removeFirst() }
        s = String(s.prefix(200))
        return s.isEmpty ? "file" : s
    }
}

public final class OutgoingTransfer: Transfer {
    // Weak: the UI keeps finished transfers around long after their connection has gone.
    private weak var link: PeerLink?
    private let sources: [Source]
    private let received = DispatchSemaphore(value: 0)
    private var cancelRequested = false
    private var remoteCancelled = false
    private var linkLost = false
    private var lanSession: Session?

    static let lanChunk = 128 * 1024

    init(link: PeerLink, id: String, sources: [Source], text: String?) {
        self.link = link
        self.sources = sources
        super.init(id: id, peer: link.peer, items: sources.map { $0.meta }, text: text)
    }

    public override func cancel() {
        if isFinished { return }
        cancelRequested = true
        if state == .waiting {
            if finish(.cancelled) { link?.trySend(Proto.cancel, ["tid": id]) }
        } else {
            lanSession?.close() // unblocks a write that is stuck
        }
    }

    func onAccepted(_ lan: [String: Any]?, _ direct: [String: Any]?) {
        guard state == .waiting, let link = link else { return }
        // The closure holds the link, so it stays alive while sending.
        link.sendQueue.async { [self] in run(lan, direct, link) }
    }

    func onDeclined() { finish(.declined) }

    func onRemoteCancel() {
        remoteCancelled = true
        if state == .waiting { finish(.cancelled, "Cancelled by the other device") } else { lanSession?.close() }
    }

    func onReceived() { received.signal() }

    func onLinkLost() {
        linkLost = true
        if state == .waiting { finish(.failed, "Connection lost") }
    }

    private func check(_ overLan: Bool) throws {
        if cancelRequested || remoteCancelled { throw CancelledError() }
        if linkLost && !overLan { throw ProtocolError("Connection lost") }
    }

    /// Runs the handshake on a fresh fast connection and joins it to this transfer.
    private func attach(_ duplex: Duplex, token: String, _ link: PeerLink) -> Session? {
        do {
            duplex.setReadTimeout(seconds: 8)
            let s = Session(duplex: duplex, local: link.env.local)
            try s.handshake()
            guard s.peer?.id == peer.id else { throw ProtocolError("Wrong device") }
            try s.send(Proto.attach, json: ["tid": id, "token": token])
            duplex.setReadTimeout(seconds: 0)
            return s
        } catch {
            duplex.close()
            return nil
        }
    }

    /// Tries the receiver's addresses on the shared Wi-Fi network or hotspot.
    private func connectLan(_ lan: [String: Any], _ link: PeerLink) -> Session? {
        let port = lan.int("port")
        let token = lan.string("token")
        guard let addrs = lan["addrs"] as? [String], (1...65535).contains(port), !token.isEmpty else { return nil }
        for addr in addrs.prefix(4) {
            guard let sock = try? TcpSocket.connect(host: addr, port: UInt16(port), timeoutMs: 700) else { continue }
            if let s = attach(sock, token: token, link) { return s }
        }
        return nil
    }

    /// Sets up a router-free link (Wi-Fi Aware) to the receiver.
    private func connectDirect(_ lan: [String: Any], _ direct: [String: Any], _ link: PeerLink) -> Session? {
        let token = lan.string("token")
        guard let d = link.env.direct, !token.isEmpty else { return nil }
        return d.connect(info: direct, attach: { self.attach($0, token: token, link) },
                         cancelled: { self.cancelRequested || self.remoteCancelled })
    }

    private func run(_ lan: [String: Any]?, _ direct: [String: Any]?, _ link: PeerLink) {
        if isFinished { return }
        var ls: Session?
        if let lan = lan, link.env.useLan {
            ls = connectLan(lan, link)
            if ls == nil, let direct = direct, !cancelRequested, !remoteCancelled { ls = connectDirect(lan, direct, link) }
        }
        let data = ls ?? link.session
        let overLan = ls != nil
        lock.lock()
        if isFinished {
            lock.unlock()
            ls?.close()
            return
        }
        lanSession = ls
        viaLan = overLan
        state = .transferring
        lock.unlock()
        notifyUpdate()
        do {
            let chunk = overLan ? OutgoingTransfer.lanChunk : link.chunkSize
            for (i, src) in sources.enumerated() {
                try check(overLan)
                try data.send(Proto.fileStart, json: ["tid": id, "index": i])
                let reader = try src.open()
                defer { reader.close() }
                while true {
                    try check(overLan)
                    let d = try autoreleasepool { try reader.read(max: chunk) }
                    if d.isEmpty { break }
                    try data.send(Proto.data, d)
                    addBytes(d.count)
                }
                try data.send(Proto.fileEnd, json: ["tid": id, "index": i])
            }
            try data.send(Proto.done, json: ["tid": id])
            if let ls = ls {
                while true {
                    let f = try ls.receive()
                    if f.type == Proto.received { break }
                    if f.type == Proto.cancel {
                        remoteCancelled = true
                        throw CancelledError()
                    }
                }
            } else {
                while received.wait(timeout: .now() + .milliseconds(200)) == .timedOut { try check(false) }
            }
            finish(.done)
        } catch {
            // When the receiver cancels a Wi-Fi transfer, the data connection can drop a moment
            // before its cancel message arrives over Bluetooth. Give that message time to land.
            if overLan && !cancelRequested {
                let until = nowMs() + 600
                while !remoteCancelled && !linkLost && nowMs() < until { Thread.sleep(forTimeInterval: 0.02) }
            }
            if cancelRequested {
                link.trySend(Proto.cancel, ["tid": id])
                finish(.cancelled)
            } else if remoteCancelled {
                finish(.cancelled, "Cancelled by the other device")
            } else {
                link.trySend(Proto.cancel, ["tid": id])
                finish(.failed, (error as? LocalizedError)?.errorDescription ?? "Transfer failed")
            }
        }
        ls?.close()
    }
}

public final class IncomingTransfer: Transfer {
    // Weak: a transfer that moved to Wi-Fi can outlive the Bluetooth connection it started on.
    private weak var link: PeerLink?
    private let env: DropEnv
    /// Where each finished file was saved.
    public private(set) var results: [String] = []
    public private(set) var accepted = false

    private var sink: FileSink?
    private var index = -1
    private var lanToken: String?
    private var directPrepared = false
    private var lanSession: Session?
    private var linkClosed = false

    init(link: PeerLink, id: String, items: [ItemMeta], text: String?) {
        self.link = link
        env = link.env
        super.init(id: id, peer: link.peer, items: items, text: text)
    }

    public func accept() {
        lock.lock()
        defer { lock.unlock() }
        guard state == .waiting, !accepted else { return }
        accepted = true
        var msg: [String: Any] = ["tid": id]
        if let lan = env.lan, env.useLan, lan.port > 0, !items.isEmpty {
            let addrs = lan.addresses()
            let direct = env.direct?.prepare(transferId: id, lanPort: lan.port)
            if direct != nil { directPrepared = true }
            if !addrs.isEmpty || direct != nil {
                let token = CryptoUtil.randomHex(16)
                lanToken = token
                lan.register(token, self)
                msg["lan"] = ["addrs": addrs, "port": Int(lan.port), "token": token] as [String: Any]
                if let direct = direct { msg["direct"] = direct }
            }
        }
        link?.trySend(Proto.accept, msg)
        notifyUpdate()
    }

    public func decline() {
        lock.lock()
        defer { lock.unlock() }
        if state == .waiting, !accepted, end(.declined, nil) { link?.trySend(Proto.decline, ["tid": id]) }
    }

    public override func cancel() {
        lock.lock()
        defer { lock.unlock() }
        if end(.cancelled, nil) { link?.trySend(Proto.cancel, ["tid": id]) }
    }

    func onRemoteCancel() {
        lock.lock()
        defer { lock.unlock() }
        end(.cancelled, "Cancelled by the other device")
    }

    func onLinkLost() {
        lock.lock()
        defer { lock.unlock() }
        linkClosed = true
        if !viaLan { end(.failed, "Connection lost") }
    }

    func onLanLost() {
        // The sender's cancel message travels over Bluetooth and can arrive just after the
        // Wi-Fi connection drops. Wait briefly so a cancel isn't reported as a failure.
        let until = nowMs() + 600
        while !isFinished && !linkClosed && nowMs() < until { Thread.sleep(forTimeInterval: 0.02) }
        lock.lock()
        defer { lock.unlock() }
        end(.failed, "Connection lost")
    }

    func attachLan(_ s: Session) {
        lock.lock()
        defer { lock.unlock() }
        lanSession = s
        viaLan = true
    }

    /// Ends the transfer, discarding any half-written file.
    @discardableResult
    private func end(_ s: TransferState, _ message: String?) -> Bool {
        guard finish(s, message) else { return false }
        if s != .done {
            sink?.abort()
            sink = nil
            lanSession?.close()
        }
        if let t = lanToken { env.lan?.unregister(t) }
        if directPrepared {
            directPrepared = false
            let d = env.direct
            let tid = id
            DispatchQueue.global().async { d?.release(transferId: tid) }
        }
        return true
    }

    /// Handles a data message. `from` is the session it arrived on (Bluetooth or Wi-Fi).
    func handle(_ f: Frame, from: Session) {
        lock.lock()
        defer { lock.unlock() }
        if isFinished || !accepted { return }
        do {
            switch f.type {
            case Proto.fileStart:
                let i = Int(try f.json().int("index", default: -1))
                guard sink == nil, i == index + 1, i < items.count else { throw ProtocolError("Unexpected file") }
                index = i
                sink = try env.sinks.create(item: items[i])
                if state == .waiting {
                    state = .transferring
                    notifyUpdate()
                }
            case Proto.data:
                guard let s = sink else { throw ProtocolError("Unexpected data") }
                guard bytesDone + Int64(f.payload.count) <= totalBytes else { throw ProtocolError("More data than offered") }
                try s.write(f.payload)
                addBytes(f.payload.count)
            case Proto.fileEnd:
                guard let s = sink else { throw ProtocolError("Unexpected end of file") }
                sink = nil
                results.append(try s.finish())
            case Proto.done:
                guard sink == nil, index == items.count - 1 else { throw ProtocolError("Transfer ended early") }
                try from.send(Proto.received, json: ["tid": id])
                end(.done, nil)
            default:
                break
            }
        } catch {
            if end(.failed, (error as? LocalizedError)?.errorDescription ?? "Transfer failed") {
                link?.trySend(Proto.cancel, ["tid": id])
            }
        }
    }
}

// MARK: - Files

public final class FileSource: Source {
    public let meta: ItemMeta
    let url: URL

    public init(url: URL, name: String? = nil, mime: String = "application/octet-stream") {
        self.url = url
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        meta = ItemMeta(name: name ?? url.lastPathComponent, size: size, mime: mime)
    }

    public func open() throws -> SourceReader { FileReader(handle: try FileHandle(forReadingFrom: url)) }

    private final class FileReader: SourceReader {
        let handle: FileHandle
        init(handle: FileHandle) { self.handle = handle }
        func read(max: Int) throws -> Data { try handle.read(upToCount: max) ?? Data() }
        func close() { try? handle.close() }
    }
}

/// Writes a received file into a folder, picking a free name like "photo 2.jpg" if needed.
public final class FolderSink: FileSink {
    public let url: URL
    private let handle: FileHandle

    public init(folder: URL, name: String) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        var candidate = folder.appendingPathComponent(name)
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = folder.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        url = candidate
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
            throw ProtocolError("Couldn't create the file")
        }
        handle = try FileHandle(forWritingTo: url)
    }

    public func write(_ data: Data) throws { try handle.write(contentsOf: data) }

    public func finish() throws -> String {
        try handle.close()
        return url.path
    }

    public func abort() {
        try? handle.close()
        try? FileManager.default.removeItem(at: url)
    }
}

#if os(Linux)
func autoreleasepool<T>(_ body: () throws -> T) rethrows -> T { try body() }
#endif
