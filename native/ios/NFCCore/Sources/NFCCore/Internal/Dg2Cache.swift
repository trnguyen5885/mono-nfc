import CryptoKit
import Foundation

struct CachedDg2 {
  let dg2Data: Data
  let imageData: Data
  let mimeType: String
}

/** One-entry, short-lived in-memory cache for the DG2 portrait. */
enum Dg2Cache {
  private static let ttl: TimeInterval = 5 * 60
  private static let lock = NSLock()
  private static var entry: Entry?

  private struct Entry {
    let fingerprint: String
    let value: CachedDg2
    let createdAt: Date
  }

  static func get(_ fingerprint: String) -> CachedDg2? {
    lock.lock()
    defer { lock.unlock() }

    guard let current = entry, current.fingerprint == fingerprint else {
      return nil
    }

    guard Date().timeIntervalSince(current.createdAt) <= ttl else {
      entry = nil
      return nil
    }

    return current.value
  }

  static func put(_ fingerprint: String, value: CachedDg2) {
    lock.lock()
    entry = Entry(fingerprint: fingerprint, value: value, createdAt: Date())
    lock.unlock()
  }

  static func clear() {
    lock.lock()
    entry = nil
    lock.unlock()
  }

  static func fingerprint(dg1: Data, sod: Data) -> String? {
    guard !dg1.isEmpty, !sod.isEmpty else { return nil }

    var input = Data()
    appendLengthPrefixed(dg1, to: &input)
    appendLengthPrefixed(sod, to: &input)

    return SHA256.hash(data: input)
      .map { String(format: "%02x", $0) }
      .joined()
  }

  private static func appendLengthPrefixed(_ data: Data, to output: inout Data) {
    var length = UInt64(data.count).bigEndian
    withUnsafeBytes(of: &length) { output.append(contentsOf: $0) }
    output.append(data)
  }
}
