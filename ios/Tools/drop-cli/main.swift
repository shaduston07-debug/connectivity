// A command-line peer used to test the Swift engine against the Android one over TCP.
//
//   drop-cli recv <folder> [--lan] [--direct]   prints "READY <port>", accepts every offer
//   drop-cli send <host> <port> <file>... [--direct]
//
// --direct adds a loopback stand-in for Wi-Fi Aware and, on the receiver, hides the shared network.
//
// Each finished transfer prints "DONE <state> lan=<true|false>".

import DropCore
import Foundation

setvbuf(stdout, nil, _IOLBF, 0)

final class Sinks: SinkFactory {
    let folder: URL
    init(folder: URL) { self.folder = folder }
    func create(item: ItemMeta) throws -> FileSink { try FolderSink(folder: folder, name: item.name) }
}

final class Peer: PeerLinkListener {
    let folder: URL?
    let done = DispatchSemaphore(value: 0)
    let ready = DispatchSemaphore(value: 0)
    init(folder: URL?) { self.folder = folder }

    func linkReady(_ link: PeerLink) { ready.signal() }
    func linkClosed(_ link: PeerLink, error: String?) {
        if let e = error { print("CLOSED \(e)") }
        exit(error == nil ? 0 : 1)
    }

    func link(_ link: PeerLink, offered t: IncomingTransfer) {
        t.onUpdate = { [self] t in
            guard t.isFinished else { return }
            if t.state == .done, let text = t.text, let folder = folder {
                try? text.write(to: folder.appendingPathComponent("text.txt"), atomically: true, encoding: .utf8)
            }
            print("DONE \(t.state.rawValue) lan=\(t.viaLan)")
        }
        t.accept()
    }
}

/// Stands in for Wi-Fi Aware in tests: a loopback connection to the receiver's port.
final class LoopbackDirect: DirectLink {
    func prepare(transferId: String, lanPort: UInt16) -> [String: Any]? { ["kind": "loopback", "port": Int(lanPort)] }
    func release(transferId: String) {}
    func connect(info: [String: Any], attach: @escaping (Duplex) -> Session?, cancelled: @escaping () -> Bool) -> Session? {
        guard info["kind"] as? String == "loopback", let port = info["port"] as? Int,
              let sock = try? TcpSocket.connect(host: "127.0.0.1", port: UInt16(port), timeoutMs: 2000) else { return nil }
        return attach(sock)
    }
}

let args = CommandLine.arguments
let useDirect = args.contains("--direct")
let local = LocalInfo(identity: Identity(), name: "swift", platform: "test")

if args.count >= 3, args[1] == "recv" {
    let folder = URL(fileURLWithPath: args[2])
    var lan: LanServer?
    if args.contains("--lan") || useDirect {
        lan = LanServer(local: local)
        // With --direct there is no shared network, so only the direct link can be used.
        lan!.addressProvider = useDirect ? { [] } : { ["127.0.0.1"] }
        lan!.start()
    }
    let env = DropEnv(local: local, sinks: Sinks(folder: folder), lan: lan)
    if useDirect { env.direct = LoopbackDirect() }
    let listener = try TcpListener()
    print("READY \(listener.port)")
    guard let sock = listener.accept() else { exit(1) }
    let peer = Peer(folder: folder)
    let link = PeerLink(duplex: sock, env: env, chunkSize: 4096, listener: peer)
    link.start()
    dispatchMain()
} else if args.count >= 5, args[1] == "send" {
    let env = DropEnv(local: local, sinks: Sinks(folder: URL(fileURLWithPath: NSTemporaryDirectory())), lan: nil)
    if useDirect { env.direct = LoopbackDirect() }
    let sock = try TcpSocket.connect(host: args[2], port: UInt16(args[3])!, timeoutMs: 3000)
    let peer = Peer(folder: nil)
    let link = PeerLink(duplex: sock, env: env, chunkSize: 4096, listener: peer)
    link.start()
    peer.ready.wait()
    let t = link.offer(sources: args[4...].filter { !$0.hasPrefix("--") }.map { FileSource(url: URL(fileURLWithPath: $0)) }, text: nil)
    t.onUpdate = { t in
        guard t.isFinished else { return }
        print("DONE \(t.state.rawValue) lan=\(t.viaLan)")
        exit(t.state == .done ? 0 : 1)
    }
    dispatchMain()
} else {
    print("usage: drop-cli recv <folder> [--lan] | drop-cli send <host> <port> <file>...")
    exit(2)
}
