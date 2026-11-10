// A command-line peer used to test the Swift engine against the Android one over TCP.
//
//   beam-cli recv <folder> [--lan]        prints "READY <port>", accepts every offer
//   beam-cli send <host> <port> <file>...
//
// Each finished transfer prints "DONE <state> lan=<true|false>".

import BeamCore
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

let args = CommandLine.arguments
let local = LocalInfo(identity: Identity(), name: "swift", platform: "test")

if args.count >= 3, args[1] == "recv" {
    let folder = URL(fileURLWithPath: args[2])
    var lan: LanServer?
    if args.contains("--lan") {
        lan = LanServer(local: local)
        lan!.addressProvider = { ["127.0.0.1"] }
        lan!.start()
    }
    let env = BeamEnv(local: local, sinks: Sinks(folder: folder), lan: lan)
    let listener = try TcpListener()
    print("READY \(listener.port)")
    guard let sock = listener.accept() else { exit(1) }
    let peer = Peer(folder: folder)
    let link = PeerLink(duplex: sock, env: env, chunkSize: 4096, listener: peer)
    link.start()
    dispatchMain()
} else if args.count >= 5, args[1] == "send" {
    let env = BeamEnv(local: local, sinks: Sinks(folder: URL(fileURLWithPath: NSTemporaryDirectory())), lan: nil)
    let sock = try TcpSocket.connect(host: args[2], port: UInt16(args[3])!, timeoutMs: 3000)
    let peer = Peer(folder: nil)
    let link = PeerLink(duplex: sock, env: env, chunkSize: 4096, listener: peer)
    link.start()
    peer.ready.wait()
    let t = link.offer(sources: args[4...].map { FileSource(url: URL(fileURLWithPath: $0)) }, text: nil)
    t.onUpdate = { t in
        guard t.isFinished else { return }
        print("DONE \(t.state.rawValue) lan=\(t.viaLan)")
        exit(t.state == .done ? 0 : 1)
    }
    dispatchMain()
} else {
    print("usage: beam-cli recv <folder> [--lan] | beam-cli send <host> <port> <file>...")
    exit(2)
}
