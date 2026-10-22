// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OrganizerCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "OrganizerCore", targets: ["OrganizerCore"]), .executable(name: "OrganizerProbe", targets: ["OrganizerProbe"])],
    targets: [
        .systemLibrary(name: "CSQLite", pkgConfig: "sqlite3"),
        .systemLibrary(name: "CZlib", pkgConfig: "zlib"),
        .target(name: "OrganizerCore", dependencies: ["CSQLite", "CZlib"]),
        .executableTarget(name: "OrganizerProbe", dependencies: ["OrganizerCore"]),
        .testTarget(name: "OrganizerCoreTests", dependencies: ["OrganizerCore"], resources: [.copy("Fixtures")])
    ]
)
