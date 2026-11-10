import SwiftUI

/// A nearby device: a round avatar with a progress ring, like the row in the system share sheet.
struct PeerBubble: View {
    let peer: PeerModel
    let state: TransferState?
    let progress: Double
    let viaLan: Bool

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 60, height: 60)
                icon
                if state == .waiting {
                    ProgressView().progressViewStyle(.circular).scaleEffect(1.9).tint(.accentColor.opacity(0.6))
                } else if state == .transferring {
                    Circle().stroke(Color.secondary.opacity(0.2), lineWidth: 4).frame(width: 70, height: 70)
                    Circle().trim(from: 0, to: progress)
                        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 70, height: 70)
                        .animation(.linear(duration: 0.15), value: progress)
                }
            }
            .frame(width: 74, height: 74)
            Text(peer.name).font(.footnote.weight(.medium)).lineLimit(2).multilineTextAlignment(.center)
            Text(subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(width: 96)
        .contentShape(Rectangle())
    }

    @ViewBuilder private var icon: some View {
        switch state {
        case .done:
            Image(systemName: "checkmark").font(.title2.weight(.semibold)).foregroundStyle(Color.accentColor)
        case .declined, .failed, .cancelled:
            Image(systemName: "xmark").font(.title2.weight(.semibold)).foregroundStyle(.red)
        default:
            Image(systemName: peer.platform == "ios" ? "iphone" : "candybarphone")
                .font(.title2).foregroundStyle(Color.accentColor)
        }
    }

    private var subtitle: String {
        switch state {
        case nil: return peer.platform == "ios" ? "iPhone or iPad" : "Android"
        case .waiting: return "Waiting…"
        case .transferring: return "\(Int(progress * 100))%" + (viaLan ? " · Wi-Fi" : "")
        case .done: return "Sent"
        case .declined: return "Declined"
        case .cancelled: return "Cancelled"
        case .failed: return "Failed"
        }
    }
}

/// Keeps a bubble up to date while its transfer runs.
private struct LiveBubble: View {
    let peer: PeerModel
    @ObservedObject var transfer: TransferModel

    var body: some View {
        PeerBubble(peer: peer, state: transfer.state, progress: transfer.progress, viaLan: transfer.viaLan)
    }
}

private struct LiveStatus: View {
    @ObservedObject var transfer: TransferModel
    var body: some View { Text(transfer.statusText) }
}

/// The device picker used by both the app and the share sheet: finds nearby devices, tap one to send.
struct SendPanel: View {
    @ObservedObject var engine = Engine.shared
    /// nil while the shared items are still being read.
    let payload: Payload?
    let problem: String?
    let onClose: () -> Void

    @State private var sent: [String: TransferModel] = [:]
    @State private var closing = false
    private let tick = Timer.publish(every: 0.3, on: .main, in: .common).autoconnect()

    private var running: Bool { sent.values.contains { $0.isActive } }

    var body: some View {
        VStack(spacing: 0) {
            Text("Send with Beam").font(.title3.weight(.semibold)).padding(.top, 22)
            Text(payload?.summary ?? problem ?? "Getting ready…")
                .font(.subheadline).foregroundStyle(.secondary).lineLimit(2).multilineTextAlignment(.center)
                .padding(.top, 4).padding(.horizontal, 24)

            Spacer(minLength: 16)
            content
            Spacer(minLength: 16)

            Button(sent.isEmpty || running ? "Cancel" : "Done") { close() }
                .font(.body.weight(.medium))
                .padding(.bottom, 18)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            engine.start()
            engine.startSeeking()
        }
        .onDisappear {
            engine.stopSeeking()
            if !closing { cancelRunning() }
        }
        .onReceive(tick) { _ in
            // Close by itself once everything has been sent.
            if !closing, !sent.isEmpty, sent.values.allSatisfy({ $0.state == .done }) {
                closing = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { onClose() }
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch engine.bluetooth {
        case .denied:
            message("Beam isn't allowed to use Bluetooth", "Open Settings › Beam and turn on Bluetooth.")
        case .off:
            message("Bluetooth is off", "Turn on Bluetooth in Control Centre to find nearby devices.")
        case .unsupported:
            message("Bluetooth isn't available", "This device can't use Bluetooth LE.")
        default:
            if engine.peers.isEmpty {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Looking for nearby devices…").font(.subheadline)
                    Text("The other device needs Beam installed and Bluetooth on.\nOn an iPhone or iPad, open Beam.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .padding(.horizontal, 24)
            } else {
                VStack(spacing: 10) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 6) {
                            ForEach(engine.peers) { peer in
                                Button { tap(peer) } label: {
                                    if let t = sent[peer.id] {
                                        LiveBubble(peer: peer, transfer: t)
                                    } else {
                                        PeerBubble(peer: peer, state: nil, progress: 0, viaLan: false)
                                    }
                                }
                                .buttonStyle(.plain)
                                .disabled(payload == nil)
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    Group {
                        if let active = sent.values.first(where: { $0.state == .transferring }) {
                            LiveStatus(transfer: active)
                        } else if let failed = sent.values.first(where: { $0.state == .failed }) {
                            Text(failed.error ?? "Couldn't send.")
                        } else if sent.isEmpty {
                            Text("Tap a device to send.")
                        } else {
                            Text(" ")
                        }
                    }
                    .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func message(_ title: String, _ detail: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle").font(.title).foregroundStyle(.orange)
            Text(title).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding(.horizontal, 24)
    }

    private func tap(_ peer: PeerModel) {
        guard let payload = payload else { return }
        if let existing = sent[peer.id] {
            if existing.isActive { return engine.cancel(existing) }
            if existing.state == .done { return }
        }
        engine.send(to: peer.id, payload: payload) { m in
            if let m = m { sent[peer.id] = m }
        }
    }

    private func cancelRunning() {
        for m in sent.values where m.isActive { engine.cancel(m) }
    }

    private func close() {
        closing = true
        cancelRunning()
        onClose()
    }
}
