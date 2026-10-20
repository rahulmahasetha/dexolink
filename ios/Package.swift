// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DexoLinkiOS",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "DexoLinkiOS", targets: ["DexoLinkiOS"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "DexoLinkiOS",
            dependencies: [],
            path: "Sources/DexoLinkiOS"
        )
    ]
)
