import Foundation

enum MrzUtils {
  static func formatDate(_ value: String) -> String {
    let clean = value
      .replacingOccurrences(of: "<", with: "")
      .replacingOccurrences(of: ">", with: "")
      .trimmingCharacters(in: .whitespacesAndNewlines)

    if clean.count == 6 {
      let yy = String(clean.prefix(2))
      let mm = String(clean.dropFirst(2).prefix(2))
      let dd = String(clean.suffix(2))

      let currentYear = Calendar.current.component(.year, from: Date())
      let currentYearShort = currentYear % 100
      let year = (Int(yy) ?? 0) > currentYearShort ? "19\(yy)" : "20\(yy)"

      return "\(dd)/\(mm)/\(year)"
    }

    if clean.count == 8 {
      let yyyy = String(clean.prefix(4))
      let mm = String(clean.dropFirst(4).prefix(2))
      let dd = String(clean.suffix(2))
      return "\(dd)/\(mm)/\(yyyy)"
    }

    return clean
  }

  static func normalizeName(
    firstName: String,
    lastName: String
  ) -> String {
    "\(firstName) \(lastName)"
      .replacingOccurrences(of: "<", with: " ")
      .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }

  static func normalizeNationality(_ raw: String) -> String {
    if raw.uppercased() == "VNM" || raw.isEmpty {
      return "Việt Nam"
    }

    return raw
  }
}
