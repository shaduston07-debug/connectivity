import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public struct ProtocolError: Error, LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

enum CryptoUtil {
    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func randomHex(_ bytes: Int) -> String {
        var g = SystemRandomNumberGenerator()
        return hex(Data((0..<bytes).map { _ in UInt8.random(in: 0...255, using: &g) }))
    }

    /// A device's ID is the first 8 bytes of the SHA-256 of its long-term public key.
    static func deviceId(_ staticPub: Data) -> String {
        hex(Data(SHA256.hash(data: staticPub).prefix(8)))
    }
}

/// The long-term key that identifies this install to other devices.
public final class Identity {
    let key: P256.Signing.PrivateKey
    public let publicBytes: Data
    public let id: String

    public init() {
        key = P256.Signing.PrivateKey()
        publicBytes = key.publicKey.x963Representation
        id = CryptoUtil.deviceId(publicBytes)
    }

    public init?(encoded: String) {
        guard let raw = Data(base64Encoded: encoded),
              let k = try? P256.Signing.PrivateKey(rawRepresentation: raw) else { return nil }
        key = k
        publicBytes = k.publicKey.x963Representation
        id = CryptoUtil.deviceId(publicBytes)
    }

    public func encode() -> String { key.rawRepresentation.base64EncodedString() }

    func sign(_ data: Data) throws -> Data { try key.signature(for: data).derRepresentation }

    /// Loads the identity saved in `defaults`, creating one on first use.
    public static func load(from defaults: UserDefaults, key: String = "beam.identity") -> Identity {
        if let s = defaults.string(forKey: key), let id = Identity(encoded: s) { return id }
        let id = Identity()
        defaults.set(id.encode(), forKey: key)
        return id
    }
}
