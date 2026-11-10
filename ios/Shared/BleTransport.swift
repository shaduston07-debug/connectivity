import CoreBluetooth
import Foundation

/// The Bluetooth LE side of Beam. Both platforms use the same scheme:
///
///  - A device that can receive advertises `service` and runs a GATT service whose one
///    characteristic holds the PSM of an L2CAP channel listener.
///  - A device that wants to talk scans for the advert, reads the PSM, and opens an L2CAP channel.
///    Everything else (handshake, offers, file data) happens on that channel.
///  - While its send screen is open, an Android device advertises `seek` instead. iOS can't be
///    found by scanning while Beam is in the background, but it can keep scanning for `seek`
///    there and connect to the sender itself.
final class BleTransport: NSObject {
    static let service = CBUUID(string: "EA0253FD-80AD-4DEF-9841-329FF222EE2E")
    static let seek = CBUUID(string: "4535B2F7-8164-442D-806B-F8DA5160CBFB")
    static let psmCharacteristic = CBUUID(string: "CB3360C1-4087-4A9E-9B63-B47D50FD534E")

    enum Scan { case off, everything, seekersOnly }

    /// A channel to another device is open. Called on the Bluetooth queue.
    var onChannel: ((Duplex, _ outbound: Bool) -> Void)?
    /// Bluetooth was turned on or off, or permission changed. Called on the Bluetooth queue.
    var onStateChange: (() -> Void)?

    private let queue = DispatchQueue(label: "beam.ble")
    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager?

    private var scan = Scan.off
    private var visible = false
    private var psm: CBL2CAPPSM?
    private var serviceAdded = false

    /// Peripherals we are connecting or connected to. CoreBluetooth needs them kept alive.
    private var dialing: [UUID: CBPeripheral] = [:]
    private var linked: Set<UUID> = []
    private var restored: [CBPeripheral] = []

    /// - Parameter receiver: also act as a device others can find and connect to.
    ///   The share extension passes false: it only sends.
    /// - Parameter restoreID: lets iOS relaunch the app in the background when a sender shows up.
    init(receiver: Bool, restoreID: String?) {
        super.init()
        var options: [String: Any] = [CBCentralManagerOptionShowPowerAlertKey: false]
        if let id = restoreID { options[CBCentralManagerOptionRestoreIdentifierKey] = id }
        central = CBCentralManager(delegate: self, queue: queue, options: options)
        if receiver {
            peripheralManager = CBPeripheralManager(delegate: self, queue: queue,
                                                    options: [CBPeripheralManagerOptionShowPowerAlertKey: false])
        }
    }

    var state: CBManagerState { central.state }
    static var authorization: CBManagerAuthorization { CBManager.authorization }

    func setScan(_ mode: Scan) {
        queue.async { [self] in
            scan = mode
            applyScan()
        }
    }

    func setVisible(_ on: Bool) {
        queue.async { [self] in
            visible = on
            applyAdvertising()
        }
    }

    private func applyScan() {
        guard central.state == .poweredOn else { return }
        central.stopScan()
        switch scan {
        case .off: break
        case .everything: central.scanForPeripherals(withServices: [BleTransport.service, BleTransport.seek])
        case .seekersOnly: central.scanForPeripherals(withServices: [BleTransport.seek])
        }
    }

    private func applyAdvertising() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }
        if psm == nil {
            pm.publishL2CAPChannel(withEncryption: false)
            return // continues in didPublishL2CAPChannel
        }
        if !serviceAdded { return } // continues in didAdd
        if visible {
            if !pm.isAdvertising {
                pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [BleTransport.service]])
            }
        } else if pm.isAdvertising {
            pm.stopAdvertising()
        }
    }

    private func drop(_ p: CBPeripheral) {
        dialing[p.identifier] = nil
        linked.remove(p.identifier)
        if p.state != .disconnected { central.cancelPeripheralConnection(p) }
        // A scan reports each device once. Start it again so this one can be found later.
        queue.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.applyScan() }
    }
}

extension BleTransport: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn {
            dialing.removeAll()
            linked.removeAll()
        } else {
            // Connections from a previous launch can't be picked up mid-stream. Start clean.
            restored.forEach { central.cancelPeripheralConnection($0) }
            restored = []
        }
        applyScan()
        onStateChange?()
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard dialing[peripheral.identifier] == nil, !linked.contains(peripheral.identifier) else { return }
        dialing[peripheral.identifier] = peripheral
        peripheral.delegate = self
        central.connect(peripheral)
        queue.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self = self, self.dialing[peripheral.identifier] === peripheral,
                  !self.linked.contains(peripheral.identifier) else { return }
            self.drop(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BleTransport.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        drop(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        drop(peripheral)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = peripheral.services?.first(where: { $0.uuid == BleTransport.service }) else { return drop(peripheral) }
        peripheral.discoverCharacteristics([BleTransport.psmCharacteristic], for: s)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let c = service.characteristics?.first(where: { $0.uuid == BleTransport.psmCharacteristic }) else {
            return drop(peripheral)
        }
        peripheral.readValue(for: c)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == BleTransport.psmCharacteristic, let v = characteristic.value, v.count >= 2 else {
            return drop(peripheral)
        }
        guard !linked.contains(peripheral.identifier) else { return }
        peripheral.openL2CAPChannel(CBL2CAPPSM(UInt16(v[v.startIndex]) << 8 | UInt16(v[v.startIndex + 1])))
    }

    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel = channel else { return drop(peripheral) }
        linked.insert(peripheral.identifier)
        let duplex = L2capDuplex(channel: channel) { [weak self] in
            self?.queue.async { self?.drop(peripheral) }
        }
        onChannel?(duplex, true)
    }
}

extension BleTransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state != .poweredOn {
            // Published channels and services are gone once Bluetooth has been off.
            psm = nil
            serviceAdded = false
        }
        applyAdvertising()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        guard error == nil else { return }
        psm = PSM
        let value = Data([UInt8(PSM >> 8), UInt8(PSM & 0xff)])
        let characteristic = CBMutableCharacteristic(type: BleTransport.psmCharacteristic, properties: .read,
                                                     value: value, permissions: .readable)
        let service = CBMutableService(type: BleTransport.service, primary: true)
        service.characteristics = [characteristic]
        peripheral.removeAllServices()
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        serviceAdded = error == nil
        applyAdvertising()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel = channel else { return }
        onChannel?(L2capDuplex(channel: channel, onClose: {}), false)
    }
}

/// Turns an L2CAP channel's run-loop streams into the blocking read/write the engine uses.
final class L2capDuplex: NSObject, Duplex, StreamDelegate {
    private let channel: CBL2CAPChannel // the streams die if the channel is released
    private let input: InputStream
    private let output: OutputStream
    private let onClose: () -> Void

    private let cond = NSCondition()
    private var inbox = Data()
    private var outbox = Data()
    private var closed = false
    private var readPaused = false
    private var runLoop: CFRunLoop?

    private static let highWater = 512 * 1024

    init(channel: CBL2CAPChannel, onClose: @escaping () -> Void) {
        self.channel = channel
        input = channel.inputStream
        output = channel.outputStream
        self.onClose = onClose
        super.init()
        let started = DispatchSemaphore(value: 0)
        let thread = Thread { [self] in
            runLoop = CFRunLoopGetCurrent()
            input.delegate = self
            output.delegate = self
            input.schedule(in: .current, forMode: .default)
            output.schedule(in: .current, forMode: .default)
            input.open()
            output.open()
            started.signal()
            // The short limit means a close from another thread is noticed even if its wake-up is missed.
            while !isClosed, RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.5)) {}
            input.close()
            output.close()
            self.onClose()
        }
        thread.name = "beam-l2cap"
        thread.qualityOfService = .userInitiated
        thread.start()
        started.wait()
    }

    private var isClosed: Bool {
        cond.lock()
        defer { cond.unlock() }
        return closed
    }

    /// Runs `block` on the stream thread.
    private func onStreamThread(_ block: @escaping () -> Void) {
        guard let rl = runLoop else { return }
        CFRunLoopPerformBlock(rl, CFRunLoopMode.defaultMode.rawValue, block)
        CFRunLoopWakeUp(rl)
    }

    func stream(_ stream: Stream, handle event: Stream.Event) {
        if event.contains(.hasBytesAvailable) { pumpIn() }
        if event.contains(.hasSpaceAvailable) { pumpOut() }
        if event.contains(.errorOccurred) || event.contains(.endEncountered) { markClosed() }
    }

    private func pumpIn() {
        var buf = [UInt8](repeating: 0, count: 32 * 1024)
        while input.hasBytesAvailable {
            cond.lock()
            if inbox.count >= L2capDuplex.highWater {
                readPaused = true
                cond.unlock()
                return
            }
            cond.unlock()
            let n = input.read(&buf, maxLength: buf.count)
            if n <= 0 {
                if n < 0 { markClosed() }
                return
            }
            cond.lock()
            inbox.append(buf, count: n)
            cond.signal()
            cond.unlock()
        }
    }

    private func pumpOut() {
        while output.hasSpaceAvailable {
            cond.lock()
            if outbox.isEmpty {
                cond.unlock()
                return
            }
            let chunk = outbox.prefix(16 * 1024)
            cond.unlock()
            let n = chunk.withUnsafeBytes { output.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: chunk.count) }
            if n <= 0 {
                if n < 0 { markClosed() }
                return
            }
            cond.lock()
            outbox.removeFirst(n)
            cond.broadcast()
            cond.unlock()
        }
    }

    private func markClosed() {
        cond.lock()
        closed = true
        cond.broadcast()
        cond.unlock()
        if let rl = runLoop {
            CFRunLoopStop(rl)
            CFRunLoopWakeUp(rl)
        }
    }

    func read(into buffer: UnsafeMutableRawPointer, max: Int) throws -> Int {
        cond.lock()
        while inbox.isEmpty && !closed { cond.wait() }
        if inbox.isEmpty {
            cond.unlock()
            return 0
        }
        let n = min(max, inbox.count)
        inbox.copyBytes(to: buffer.assumingMemoryBound(to: UInt8.self), count: n)
        inbox.removeFirst(n)
        let resume = readPaused && inbox.count < L2capDuplex.highWater / 2
        if resume { readPaused = false }
        cond.unlock()
        if resume { onStreamThread { [weak self] in self?.pumpIn() } }
        return n
    }

    func write(_ data: Data) throws {
        cond.lock()
        // Wait for room, so a fast reader of files can't fill memory ahead of the radio.
        while outbox.count > 64 * 1024 && !closed { cond.wait() }
        if closed {
            cond.unlock()
            throw ProtocolError("Connection lost")
        }
        outbox.append(data)
        cond.unlock()
        onStreamThread { [weak self] in self?.pumpOut() }
    }

    func close() { markClosed() }
}
