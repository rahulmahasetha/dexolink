// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MacDroid",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "MacDroidApp", targets: ["MacDroidApp"]),
        .library(name: "Core", targets: ["Core"]),
        .library(name: "Networking", targets: ["Networking"]),
        .library(name: "Crypto", targets: ["Crypto"]),
        .library(name: "UI", targets: ["UI"]),
        .library(name: "Utilities", targets: ["Utilities"])
    ],
    dependencies: [
        // Dependencies will be added as needed
    ],
    targets: [
        .executableTarget(
            name: "MacDroidApp",
            dependencies: ["Core", "Networking", "Crypto", "UI", "Utilities"],
            path: "Sources/MacDroidApp"
        ),
        .target(
            name: "Core",
            dependencies: ["Networking", "Crypto", "Utilities"],
            path: "Sources/Core"
        ),
        .target(
            name: "Networking",
            dependencies: ["Crypto", "Utilities"],
            path: "Sources/Networking"
        ),
        .target(
            name: "Crypto",
            dependencies: ["Utilities"],
            path: "Sources/Crypto"
        ),
        .target(
            name: "UI",
            dependencies: ["Core", "Utilities"],
            path: "Sources/UI"
        ),
        .target(
            name: "Utilities",
            dependencies: [],
            path: "Sources/Utilities"
        ),
        .testTarget(
            name: "CoreTests",
            dependencies: ["Core"],
            path: "Tests/CoreTests"
        ),
        .testTarget(
            name: "NetworkingTests",
            dependencies: ["Networking"],
            path: "Tests/NetworkingTests"
        )
    ]
)
