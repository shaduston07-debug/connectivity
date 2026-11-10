import Foundation
#if canImport(Glibc)
import Glibc
private let sockStream = Int32(SOCK_STREAM.rawValue)
private let sendFlags = Int32(MSG_NOSIGNAL)
#else
import Darwin
private let sockStream = SOCK_STREAM
private let sendFlags: Int32 = 0
#endif

private func makeAddr(_ host: String, _ port: UInt16) -> sockaddr_in? {
    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = port.bigEndian
    guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { return nil }
    return addr
}

/// A blocking TCP socket.
public final class TcpSocket: Duplex {
    private let fd: Int32
    private var isClosed = false
    private let closeLock = NSLock()

    init(fd: Int32) {
        self.fd = fd
        var one: Int32 = 1
        setsockopt(fd, Int32(IPPROTO_TCP), TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        #if !canImport(Glibc)
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        #endif
    }

    deinit {
        #if canImport(Glibc)
        _ = Glibc.close(fd)
        #else
        _ = Darwin.close(fd)
        #endif
    }

    /// Connects to an IPv4 address, giving up after `timeoutMs`.
    public static func connect(host: String, port: UInt16, timeoutMs: Int32) throws -> TcpSocket {
        guard var addr = makeAddr(host, port) else { throw ProtocolError("Bad address") }
        let fd = socket(AF_INET, sockStream, 0)
        guard fd >= 0 else { throw ProtocolError("No socket") }
        let sock = TcpSocket(fd: fd)
        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                #if canImport(Glibc)
                Glibc.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                #else
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                #endif
            }
        }
        if r != 0 {
            guard errno == EINPROGRESS else { throw ProtocolError("Can't connect") }
            var p = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            guard poll(&p, 1, timeoutMs) == 1 else { throw ProtocolError("Connect timed out") }
            var err: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
            guard err == 0 else { throw ProtocolError("Can't connect") }
        }
        _ = fcntl(fd, F_SETFL, flags)
        return sock
    }

    /// 0 means wait forever.
    public func setReadTimeout(seconds: Int) {
        var tv = timeval(tv_sec: seconds, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    }

    public func read(into buffer: UnsafeMutableRawPointer, max: Int) throws -> Int {
        while true {
            let n = recv(fd, buffer, max, 0)
            if n >= 0 { return n }
            if errno == EINTR { continue }
            throw ProtocolError("Connection lost")
        }
    }

    public func write(_ data: Data) throws {
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var off = 0
            while off < raw.count {
                let n = send(fd, raw.baseAddress! + off, raw.count - off, sendFlags)
                if n < 0 {
                    if errno == EINTR { continue }
                    throw ProtocolError("Connection lost")
                }
                off += n
            }
        }
    }

    public func close() {
        closeLock.lock()
        defer { closeLock.unlock() }
        if isClosed { return }
        isClosed = true
        // shutdown wakes any thread blocked in recv/send. The descriptor is released in deinit.
        shutdown(fd, Int32(SHUT_RDWR))
    }

    /// This device's private IPv4 addresses on Wi-Fi (or Ethernet).
    public static func wifiAddresses() -> [String] {
        var out: [String] = []
        var ifap: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifap) == 0 else { return out }
        defer { freeifaddrs(ifap) }
        var p = ifap
        while let ifa = p {
            defer { p = ifa.pointee.ifa_next }
            guard let sa = ifa.pointee.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let flags = Int32(ifa.pointee.ifa_flags)
            guard flags & Int32(IFF_UP) != 0, flags & Int32(IFF_LOOPBACK) == 0 else { continue }
            let name = String(cString: ifa.pointee.ifa_name)
            #if os(iOS)
            guard name.hasPrefix("en") else { continue } // en0 is Wi-Fi; skips cellular and VPN
            #endif
            _ = name
            let addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            let h = UInt32(bigEndian: addr.s_addr)
            let isPrivate = (h >> 24) == 10 || (h >> 20) == 0xAC1 || (h >> 16) == 0xC0A8
            guard isPrivate else { continue }
            var a = addr
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            if inet_ntop(AF_INET, &a, &buf, socklen_t(INET_ADDRSTRLEN)) != nil { out.append(String(cString: buf)) }
        }
        return out
    }
}

public final class TcpListener {
    private let fd: Int32
    public let port: UInt16

    /// Listens on a free port. Pass a port to choose one.
    public init(port requested: UInt16 = 0) throws {
        let fd = socket(AF_INET, sockStream, 0)
        self.fd = fd
        guard fd >= 0 else { throw ProtocolError("No socket") }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = requested.bigEndian
        addr.sin_addr.s_addr = 0
        let b = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard b == 0, listen(fd, 16) == 0 else { throw ProtocolError("Can't listen") }
        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
        }
        port = UInt16(bigEndian: bound.sin_port)
    }

    /// Blocks until a connection arrives. Returns nil once the listener is closed.
    public func accept() -> TcpSocket? {
        while true {
            #if canImport(Glibc)
            let c = Glibc.accept(fd, nil, nil)
            #else
            let c = Darwin.accept(fd, nil, nil)
            #endif
            if c >= 0 { return TcpSocket(fd: c) }
            if errno == EINTR { continue }
            return nil
        }
    }

    public func close() {
        shutdown(fd, Int32(SHUT_RDWR))
        #if canImport(Glibc)
        _ = Glibc.close(fd)
        #else
        _ = Darwin.close(fd)
        #endif
    }
}
