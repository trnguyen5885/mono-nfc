// swift-tools-version:5.9
import PackageDescription

let package = Package(
  name: "NFCCore",
  platforms: [.iOS(.v15), .macOS(.v11)],
  products: [
    .library(name: "NFCCore", targets: ["NFCCore"]),
  ],
  dependencies: [
    .package(
      url: "https://github.com/krzyzanowskim/OpenSSL.git",
      .upToNextMinor(from: "1.1.2300")
    ),
  ],
  targets: [
    .target(
      name: "NFCPassportReader",
      dependencies: ["OpenSSL"],
      path: "Sources/NFCPassportReader",
      resources: [.process("Resources")]
    ),
    .target(
      name: "NFCCore",
      dependencies: ["NFCPassportReader"],
      path: "Sources/NFCCore",
      swiftSettings: [.define("NFC_CORE_BINARY_BUILD")]
    ),
    .testTarget(
      name: "NFCCoreTests",
      dependencies: ["NFCCore"],
      path: "Tests/NFCCoreTests"
    ),
  ]
)
