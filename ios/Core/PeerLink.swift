import Foundation

public protocol PeerLinkListener: AnyObject {
    /// The handshake finished and `link.peer` is known.
    func linkReady(_ link: PeerLink)
    func linkClosed(_ link: PeerLink, error: String?)
    func link(_ link: PeerLink, offered transfer: IncomingTransfer)
    /// The other device has closed its send screen and no longer needs this connection.
    func linkPeerIdle(_ link: PeerLink)
}

public extension PeerLinkListener {
    func linkPeerIdle(_ link: PeerLink) {}
}

/// One connection to another device. Either side can offer files over it.
/// The connection usually starts on Bluetooth; file data moves to Wi-Fi when `DropEnv.lan` allows it.
public final class PeerLink {
    public let session: Session
    public let env: DropEnv
    /// Bytes per data message on this connection. Small on Bluetooth so progress and cancel stay responsive.
    public let chunkSize: Int
    public weak var listener: PeerLinkListener?

    public private(set) var ready = false
    public private(set) var closed = false
    public var peer: PeerInfo { session.peer ?? PeerInfo(id: "", name: "Unknown", platform: "") }

    private let lock = NSLock()
    private var outgoing: [String: OutgoingTransfer] = [:]
    private var incoming: [String: IncomingTransfer] = [:]
    private var dataTarget: IncomingTransfer?
    let sendQueue = DispatchQueue(label: "drop.send")

    public init(duplex: Duplex, env: DropEnv, chunkSize: Int, listener: PeerLinkListener?) {
        session = Session(duplex: duplex, local: env.local)
        self.env = env
        self.chunkSize = chunkSize
        self.listener = listener
    }

    public func start() {
        Thread.detachNewThread { [self] in
            var message: String?
            do {
                try session.handshake()
                ready = true
                listener?.linkReady(self)
                while true { try dispatch(try session.receive()) }
            } catch {
                if !closed { message = (error as? LocalizedError)?.errorDescription ?? "Connection lost" }
            }
            shutdown(message)
        }
    }

    /// True while a transfer is waiting or moving data on this connection.
    public var busy: Bool {
        lock.lock()
        defer { lock.unlock() }
        return outgoing.values.contains { !$0.isFinished } || incoming.values.contains { !$0.isFinished }
    }

    public func offer(sources: [Source], text: String?) -> OutgoingTransfer {
        let t = OutgoingTransfer(link: self, id: CryptoUtil.randomHex(8), sources: sources, text: text)
        lock.lock()
        outgoing[t.id] = t
        lock.unlock()
        var msg: [String: Any] = [
            "tid": t.id,
            "items": t.items.map { ["name": $0.name, "size": $0.size, "mime": $0.mime] as [String: Any] },
        ]
        if let text = text { msg["text"] = text }
        if !trySend(Proto.offer, msg) { t.onLinkLost() }
        return t
    }

    /// Tells the other device we are no longer looking for devices to send to.
    public func sendIdle() { trySend(Proto.idle, [:]) }

    @discardableResult
    func trySend(_ type: UInt8, _ json: [String: Any]) -> Bool {
        (try? session.send(type, json: json)) != nil
    }

    private func out(_ tid: String) -> OutgoingTransfer? {
        lock.lock()
        defer { lock.unlock() }
        return outgoing[tid]
    }

    private func inc(_ tid: String) -> IncomingTransfer? {
        lock.lock()
        defer { lock.unlock() }
        return incoming[tid]
    }

    private func dispatch(_ f: Frame) throws {
        switch f.type {
        case Proto.offer:
            let j = try f.json()
            let tid = j.string("tid")
            var items: [ItemMeta] = []
            if let arr = j["items"] as? [[String: Any]] {
                if arr.count > 2000 { throw ProtocolError("Too many files") }
                for o in arr {
                    let size = o.int("size", default: -1)
                    if size < 0 { throw ProtocolError("Bad offer") }
                    items.append(ItemMeta(name: Transfer.safeName(o.string("name")), size: size,
                                          mime: String(o.string("mime").prefix(100))))
                }
            }
            let text = j["text"] as? String
            if tid.isEmpty || inc(tid) != nil || (items.isEmpty && text == nil) { return }
            let t = IncomingTransfer(link: self, id: tid, items: items, text: text)
            lock.lock()
            incoming[tid] = t
            lock.unlock()
            listener?.link(self, offered: t)
        case Proto.accept:
            let j = try f.json()
            out(j.string("tid"))?.onAccepted(j["lan"] as? [String: Any])
        case Proto.decline:
            out(try f.json().string("tid"))?.onDeclined()
        case Proto.cancel:
            let tid = try f.json().string("tid")
            out(tid)?.onRemoteCancel()
            inc(tid)?.onRemoteCancel()
        case Proto.fileStart:
            dataTarget = inc(try f.json().string("tid"))
            dataTarget?.handle(f, from: session)
        case Proto.data, Proto.fileEnd:
            dataTarget?.handle(f, from: session)
        case Proto.done:
            inc(try f.json().string("tid"))?.handle(f, from: session)
        case Proto.received:
            out(try f.json().string("tid"))?.onReceived()
        case Proto.idle:
            listener?.linkPeerIdle(self)
        default:
            break
        }
    }

    public func close() {
        closed = true
        session.close()
    }

    private func shutdown(_ error: String?) {
        closed = true
        session.close()
        lock.lock()
        let outs = Array(outgoing.values)
        let incs = Array(incoming.values)
        lock.unlock()
        outs.forEach { $0.onLinkLost() }
        incs.forEach { $0.onLinkLost() }
        listener?.linkClosed(self, error: error)
    }
}

/// Listens for Wi-Fi data connections. A sender proves it belongs to an accepted transfer with the
/// one-time token it was given over the encrypted Bluetooth connection.
public final class LanServer {
    private let local: LocalInfo
    private var server: TcpListener?
    private let lock = NSLock()
    private var pending: [String: IncomingTransfer] = [:]

    /// Returns this device's Wi-Fi addresses.
    public var addressProvider: () -> [String] = { TcpSocket.wifiAddresses() }

    public init(local: LocalInfo) { self.local = local }

    public var port: UInt16 { server?.port ?? 0 }

    public func start() {
        lock.lock()
        defer { lock.unlock() }
        if server != nil { return }
        guard let s = try? TcpListener() else { return }
        server = s
        Thread.detachNewThread { [weak self] in
            while let sock = s.accept() {
                guard let server = self else { break }
                Thread.detachNewThread { server.serve(sock) }
            }
        }
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }
        server?.close()
        server = nil
    }

    public func addresses() -> [String] { Array(addressProvider().prefix(3)) }

    func register(_ token: String, _ t: IncomingTransfer) {
        lock.lock()
        pending[token] = t
        lock.unlock()
    }

    func unregister(_ token: String) {
        lock.lock()
        pending[token] = nil
        lock.unlock()
    }

    private func serve(_ sock: TcpSocket) {
        var transfer: IncomingTransfer?
        defer { sock.close() }
        do {
            sock.setReadTimeout(seconds: 8)
            let s = Session(duplex: sock, local: local)
            try s.handshake()
            let f = try s.receive()
            guard f.type == Proto.attach else { return }
            let token = try f.json().string("token")
            lock.lock()
            let t = pending.removeValue(forKey: token)
            lock.unlock()
            guard let t = t, s.peer?.id == t.peer.id else { return }
            sock.setReadTimeout(seconds: 0)
            transfer = t
            t.attachLan(s)
            while !t.isFinished { t.handle(try s.receive(), from: s) }
        } catch {
            transfer?.onLanLost()
        }
    }
}
