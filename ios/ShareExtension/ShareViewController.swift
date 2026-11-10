import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Holds what was shared while it is read from the host app.
private final class ShareModel: ObservableObject {
    @Published var payload: Payload?
    @Published var problem: String?
}

private struct ShareRoot: View {
    @ObservedObject var model: ShareModel
    let onClose: () -> Void

    var body: some View {
        SendPanel(payload: model.payload, problem: model.problem, onClose: onClose)
    }
}

/// Beam's entry in the system share sheet. The transfer runs right here, so there is no
/// switching to the app: pick Beam, tap a device, done.
final class ShareViewController: UIViewController {
    private let model = ShareModel()
    private let workDir = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(UUID().uuidString)")

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let host = UIHostingController(rootView: ShareRoot(model: model) { [weak self] in self?.finish() })
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        host.didMove(toParent: self)
        if let sheet = sheetPresentationController {
            sheet.detents = [.medium()]
            sheet.prefersGrabberVisible = true
        }
        loadItems()
    }

    private func finish() {
        try? FileManager.default.removeItem(at: workDir)
        extensionContext?.completeRequest(returningItems: nil)
    }

    private func loadItems() {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty else {
            model.problem = "There is nothing to send."
            return
        }
        try? FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)

        let group = DispatchGroup()
        let lock = NSLock()
        var files: [(Int, URL)] = []
        var texts: [(Int, String)] = []

        for (index, provider) in providers.enumerated() {
            let isFile = provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
            let types = provider.registeredTypeIdentifiers
            let onlyText = types.allSatisfy { UTType($0)?.conforms(to: .text) ?? false }

            if !isFile, provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                group.enter()
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    if let url = url {
                        lock.lock()
                        texts.append((index, url.absoluteString))
                        lock.unlock()
                    }
                    group.leave()
                }
            } else if !isFile, onlyText, provider.canLoadObject(ofClass: String.self) {
                group.enter()
                _ = provider.loadObject(ofClass: String.self) { text, _ in
                    if let text = text, !text.isEmpty {
                        lock.lock()
                        texts.append((index, text))
                        lock.unlock()
                    }
                    group.leave()
                }
            } else {
                let type = types.first { $0 != UTType.fileURL.identifier && $0 != UTType.url.identifier } ?? UTType.data.identifier
                group.enter()
                let dir = workDir.appendingPathComponent("\(index)")
                provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                    defer { group.leave() }
                    // The file only exists until this block returns, so keep our own copy.
                    guard let url = url else { return }
                    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    var name = url.lastPathComponent
                    if let suggested = provider.suggestedName, !suggested.isEmpty {
                        let ext = url.pathExtension
                        name = ext.isEmpty || suggested.lowercased().hasSuffix("." + ext.lowercased()) ? suggested : "\(suggested).\(ext)"
                    }
                    let dest = dir.appendingPathComponent(name)
                    do {
                        do { try FileManager.default.linkItem(at: url, to: dest) } catch { try FileManager.default.copyItem(at: url, to: dest) }
                        lock.lock()
                        files.append((index, dest))
                        lock.unlock()
                    } catch {}
                }
            }
        }

        group.notify(queue: .main) { [self] in
            if !files.isEmpty {
                let sources = files.sorted { $0.0 < $1.0 }.map {
                    FileSource(url: $0.1, mime: mimeType(name: $0.1.lastPathComponent)) as Source
                }
                model.payload = Payload(sources: sources)
            } else if !texts.isEmpty {
                model.payload = Payload(text: texts.sorted { $0.0 < $1.0 }.map { $0.1 }.joined(separator: "\n"))
            } else {
                model.problem = "Beam can't read what was shared."
            }
        }
    }
}
