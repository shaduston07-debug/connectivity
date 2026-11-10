// swift-tools-version: 5.9

// The transfer engine shared by the iOS app and its share extension, as a package so it can be
// built and tested on a Mac or Linux without Xcode. The app itself is built from project.yml.

import PackageDescription

let package = Package(
    name: "BeamCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "BeamCore", targets: ["BeamCore"]),
        .executable(name: "beam-cli", targets: ["beam-cli"]),
    ],
    dependencies: [
        // CryptoKit is built in on Apple platforms. This is the same API for Linux.
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0"),
    ],
    targets: [
        .target(
            name: "BeamCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux])),
            ],
            path: "Core"
        ),
        .executableTarget(name: "beam-cli", dependencies: ["BeamCore"], path: "Tools/beam-cli"),
    ]
)
