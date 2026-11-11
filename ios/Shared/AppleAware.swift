#if canImport(WiFiAware)
import Foundation
import Network
import WiFiAware

// Wi-Fi Aware between iPhones and iPads (iOS 26): a direct Wi-Fi link with no router, hotspot or
// internet. Devices pair once (one shows a code, the other types it in); after that they find and
// reach each other on their own whenever both have Inkwell Drop running.

private let serviceName = "_inkwelldrop._tcp"

@available(iOS 26.0, *)
extension WAPublishableService {
    static var inkwellDrop: WAPublishableService { allServices[serviceName]! }
}

@available(iOS 26.0, *)
extension WASubscribableService {
    static var inkwellDrop: WASubscribableService { allServices[serviceName]! }
}

@available(iOS 26.0, *)
final class AppleAware: DirectLink {
    /// This device has Wi-Fi Aware and this copy of the app is allowed to use it.
    static var isSupported: Bool {
        WACapabilities.supportedFeatures.contains(.wifiAware)
            && WAPublishableService.allServices[serviceName] != nil
            && WASubscribableService.allServices[serviceName] != nil
    }

    private weak var lan: LanServer?
    private var listenerTask: Task<Void, Never>?
    private var pairedTask: Task<Void, Never>?
    private let lock = NSLock()
    private var _paired: [String] = []

    /// Names of the devices this one has been paired with.
    var pairedNames: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _paired
    }

    var onPairedChange: (([String]) -> Void)?

    init(lan: LanServer?) {
        self.lan = lan
        pairedTask = Task.detached { [weak self] in
            do {
                for try await devices in WAPairedDevice.allDevices {
                    let names = devices.values.map { $0.name ?? "Device" }.sorted()
                    guard let self = self else { return }
                    self.lock.lock()
                    self._paired = names
                    self.lock.unlock()
                    self.onPairedChange?(names)
                }
            } catch {}
        }
    }

    deinit {
        listenerTask?.cancel()
        pairedTask?.cancel()
    }

    /// Accepts connections from paired devices for as long as the app runs.
    func startListening() {
        guard listenerTask == nil, let lan = lan else { return }
        listenerTask = Task.detached {
            while !Task.isCancelled {
                do {
                    let listener = try NetworkListener(
                        for: .wifiAware(.connecting(to: .inkwellDrop, from: .allPairedDevices)),
                        using: .parameters { TCP() })
                    try await listener.run { connection in
                        let duplex = AwareConnectionDuplex(connection)
                        // The transfer engine is blocking code; give it its own thread.
                        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                            Thread.detachNewThread {
                                lan.serve(duplex)
                                done.resume()
                            }
                        }
                    }
                } catch {}
                // The listener stops when Wi-Fi is turned off and the like. Try again later.
                try? await Task.sleep(for: .seconds(10))
            }
        }
    }

    // MARK: DirectLink

    func prepare(transferId: String, lanPort: UInt16) -> [String: Any]? {
        guard listenerTask != nil, !pairedNames.isEmpty else { return nil }
        return ["kind": "aware-apple"]
    }

    func release(transferId: String) {}

    func connect(info: [String: Any], attach: @escaping (Duplex) -> Session?, cancelled: @escaping () -> Bool) -> Session? {
        guard info["kind"] as? String == "aware-apple", !pairedNames.isEmpty else { return nil }
        let found = SessionBox()
        let done = DispatchSemaphore(value: 0)
        let task = Task.detached {
            defer { done.signal() }
            do {
                let browser = NetworkBrowser(for: .wifiAware(.connecting(to: .allPairedDevices, from: .inkwellDrop)))
                var tried = Set<String>()
                let session: Session = try await browser.run { endpoints in
                    for endpoint in endpoints {
                        let key = String(describing: endpoint)
                        if tried.contains(key) { continue }
                        tried.insert(key)
                        let connection = NetworkConnection(to: endpoint, using: .parameters { TCP() })
                        let duplex = AwareConnectionDuplex(connection)
                        // attach() runs the handshake, which checks this is the device we want.
                        let s: Session? = await withCheckedContinuation { result in
                            Thread.detachNewThread { result.resume(returning: attach(duplex)) }
                        }
                        if let s = s { return .finish(s) }
                    }
                    return .continue
                }
                found.set(session)
            } catch {}
        }
        let deadline = Date().addingTimeInterval(10)
        while done.wait(timeout: .now() + .milliseconds(200)) == .timedOut {
            if cancelled() || Date() > deadline {
                task.cancel()
                break
            }
        }
        return found.get()
    }
}

private final class SessionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Session?
    func set(_ s: Session) {
        lock.lock()
        value = s
        lock.unlock()
    }
    func get() -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// Blocking reads and writes on a Wi-Fi Aware connection, for the transfer engine.
@available(iOS 26.0, *)
final class AwareConnectionDuplex: Duplex, @unchecked Sendable {
    private let connection: NetworkConnection<TCP>
    private var inbox = Data()
    private let lock = NSLock()
    private var closed = false
    private var pending: Task<Void, Never>?
    private var timeout = 0

    init(_ connection: NetworkConnection<TCP>) {
        self.connection = connection
    }

    func setReadTimeout(seconds: Int) { timeout = seconds }

    func read(into buffer: UnsafeMutableRawPointer, max: Int) throws -> Int {
        if inbox.isEmpty {
            let done = DispatchSemaphore(value: 0)
            let result = DataBox()
            lock.lock()
            if closed {
                lock.unlock()
                return 0
            }
            pending = Task {
                do {
                    let (data, _) = try await self.connection.receive(atLeast: 1, atMost: 256 * 1024)
                    result.data = data
                } catch {}
                done.signal()
            }
            lock.unlock()
            if timeout > 0 {
                if done.wait(timeout: .now() + .seconds(timeout)) == .timedOut {
                    close()
                    throw ProtocolError("Timed out")
                }
            } else {
                done.wait()
            }
            guard let data = result.data, !data.isEmpty else { return 0 }
            inbox = data
        }
        let n = min(max, inbox.count)
        inbox.copyBytes(to: buffer.assumingMemoryBound(to: UInt8.self), count: n)
        inbox.removeFirst(n)
        return n
    }

    func write(_ data: Data) throws {
        lock.lock()
        let isClosed = closed
        lock.unlock()
        if isClosed { throw ProtocolError("Connection lost") }
        let done = DispatchSemaphore(value: 0)
        let failed = FlagBox()
        Task {
            do { try await self.connection.send(data) } catch { failed.value = true }
            done.signal()
        }
        done.wait()
        if failed.value { throw ProtocolError("Connection lost") }
    }

    func close() {
        lock.lock()
        closed = true
        let task = pending
        lock.unlock()
        task?.cancel()
    }
}

private final class DataBox: @unchecked Sendable { var data: Data? }
private final class FlagBox: @unchecked Sendable { var value = false }
#endif
