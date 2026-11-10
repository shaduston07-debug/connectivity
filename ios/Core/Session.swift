import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public enum Proto {
    public static let version = 1
    public static let maxFrame = 1 << 20

    public static let hello: UInt8 = 0x01
    /// "I have stopped looking for devices." A device that connected only to be found can hang up.
    public static let idle: UInt8 = 0x04
    public static let offer: UInt8 = 0x10
    public static let accept: UInt8 = 0x11
    public static let decline: UInt8 = 0x12
    public static let cancel: UInt8 = 0x13
    public static let fileStart: UInt8 = 0x20
    public static let data: UInt8 = 0x21
    public static let fileEnd: UInt8 = 0x22
    public static let done: UInt8 = 0x23
    public static let received: UInt8 = 0x24
    public static let attach: UInt8 = 0x30
}

/// A reliable two-way byte stream: a Bluetooth L2CAP channel or a TCP socket.
public protocol Duplex: AnyObject {
    /// Blocks until some bytes arrive. Returns 0 when the stream has closed.
    func read(into buffer: UnsafeMutableRawPointer, max: Int) throws -> Int
    /// Blocks until the bytes have been handed to the transport.
    func write(_ data: Data) throws
    func close()
}

public struct PeerInfo: Equatable {
    public let id: String
    public let name: String
    public let platform: String
}

public final class LocalInfo {
    public let identity: Identity
    public var name: String
    public let platform: String
    public var id: String { identity.id }

    public init(identity: Identity, name: String, platform: String) {
        self.identity = identity
        self.name = name
        self.platform = platform
    }
}

public struct Frame {
    public let type: UInt8
    public let payload: Data

    func json() throws -> [String: Any] {
        guard let obj = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            throw ProtocolError("Bad message")
        }
        return obj
    }
}

extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String { self[key] as? String ?? "" }
    func int(_ key: String, default d: Int64 = 0) -> Int64 { (self[key] as? NSNumber)?.int64Value ?? d }
}

/// Framing, handshake and encryption on top of a `Duplex`.
/// After `handshake()` every frame is AES-256-GCM encrypted with keys from an ephemeral P-256
/// exchange, and the other device has proved it holds the long-term key behind its ID.
public final class Session {
    private let duplex: Duplex
    private let local: LocalInfo
    private let sendLock = NSLock()
    private var sendKey: SymmetricKey?
    private var recvKey: SymmetricKey?
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0

    public private(set) var peer: PeerInfo?

    public init(duplex: Duplex, local: LocalInfo) {
        self.duplex = duplex
        self.local = local
    }

    public func handshake() throws {
        let eph = P256.KeyAgreement.PrivateKey()
        let epub = eph.publicKey.x963Representation
        let hello: [String: Any] = [
            "v": Proto.version,
            "id": local.id,
            "name": local.name,
            "platform": local.platform,
            "epub": epub.base64EncodedString(),
            "spub": local.identity.publicBytes.base64EncodedString(),
            "sig": try local.identity.sign(epub).base64EncodedString(),
        ]
        var body = Data([Proto.hello])
        body.append(try JSONSerialization.data(withJSONObject: hello))
        try writeRaw(body)

        let theirsRaw = try readRaw()
        guard theirsRaw.first == Proto.hello,
              let theirs = try? JSONSerialization.jsonObject(with: Data(theirsRaw.dropFirst())) as? [String: Any]
        else { throw ProtocolError("Expected hello") }
        guard theirs.int("v") == Int64(Proto.version) else {
            throw ProtocolError("The other device has a different version of Beam. Update both apps.")
        }
        guard let theirEpub = Data(base64Encoded: theirs.string("epub")),
              let theirSpub = Data(base64Encoded: theirs.string("spub")),
              let sig = Data(base64Encoded: theirs.string("sig")),
              theirEpub != epub
        else { throw ProtocolError("Bad hello") }
        guard let staticKey = try? P256.Signing.PublicKey(x963Representation: theirSpub),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: sig),
              staticKey.isValidSignature(signature, for: theirEpub)
        else { throw ProtocolError("The other device failed the identity check") }

        var name = String(theirs.string("name").prefix(64))
        if name.trimmingCharacters(in: .whitespaces).isEmpty { name = "Unknown" }
        peer = PeerInfo(id: CryptoUtil.deviceId(theirSpub), name: name,
                        platform: String(theirs.string("platform").prefix(16)))

        guard let theirKey = try? P256.KeyAgreement.PublicKey(x963Representation: theirEpub) else {
            throw ProtocolError("Bad public key")
        }
        let secret = try eph.sharedSecretFromKeyAgreement(with: theirKey)
        let mineFirst = epub.lexicographicallyPrecedes(theirEpub)
        let lo = mineFirst ? epub : theirEpub
        let hi = mineFirst ? theirEpub : epub
        let salt = Data(SHA256.hash(data: lo + hi))
        let okm = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt,
                                                 sharedInfo: Data("beam/1".utf8), outputByteCount: 64)
        let bytes = okm.withUnsafeBytes { Data($0) }
        let k1 = SymmetricKey(data: bytes.prefix(32))
        let k2 = SymmetricKey(data: bytes.suffix(32))
        sendKey = mineFirst ? k1 : k2
        recvKey = mineFirst ? k2 : k1
    }

    private func nonce(_ counter: UInt64) throws -> AES.GCM.Nonce {
        var d = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { d.append(contentsOf: $0) }
        return try AES.GCM.Nonce(data: d)
    }

    public func send(_ type: UInt8, _ payload: Data) throws {
        sendLock.lock()
        defer { sendLock.unlock() }
        guard let key = sendKey else { throw ProtocolError("No handshake") }
        var plain = Data(capacity: payload.count + 1)
        plain.append(type)
        plain.append(payload)
        let box = try AES.GCM.seal(plain, using: key, nonce: try nonce(sendCounter))
        sendCounter += 1
        var out = Data(capacity: 4 + plain.count + 16)
        withUnsafeBytes(of: UInt32(plain.count + 16).bigEndian) { out.append(contentsOf: $0) }
        out.append(box.ciphertext)
        out.append(box.tag)
        try duplex.write(out)
    }

    public func send(_ type: UInt8, json: [String: Any]) throws {
        try send(type, try JSONSerialization.data(withJSONObject: json))
    }

    /// Blocks until the next frame arrives. Call from one thread only.
    public func receive() throws -> Frame {
        guard let key = recvKey else { throw ProtocolError("No handshake") }
        let body = try readRaw()
        guard body.count >= 17 else { throw ProtocolError("Bad frame") }
        let box = try AES.GCM.SealedBox(nonce: try nonce(recvCounter),
                                        ciphertext: body.dropLast(16), tag: body.suffix(16))
        recvCounter += 1
        guard let plain = try? AES.GCM.open(box, using: key), let type = plain.first else {
            throw ProtocolError("Decryption failed")
        }
        return Frame(type: type, payload: Data(plain.dropFirst()))
    }

    private func writeRaw(_ body: Data) throws {
        sendLock.lock()
        defer { sendLock.unlock() }
        var out = Data(capacity: 4 + body.count)
        withUnsafeBytes(of: UInt32(body.count).bigEndian) { out.append(contentsOf: $0) }
        out.append(body)
        try duplex.write(out)
    }

    private func readRaw() throws -> Data {
        let header = try readFully(4)
        let len = header.reduce(0) { ($0 << 8) | Int($1) }
        guard len >= 1, len <= Proto.maxFrame + 64 else { throw ProtocolError("Bad frame length") }
        return try readFully(len)
    }

    private func readFully(_ count: Int) throws -> Data {
        var data = Data(count: count)
        var off = 0
        while off < count {
            let n = try data.withUnsafeMutableBytes { raw in
                try duplex.read(into: raw.baseAddress! + off, max: count - off)
            }
            if n <= 0 { throw ProtocolError("Connection closed") }
            off += n
        }
        return data
    }

    public func close() { duplex.close() }
}
