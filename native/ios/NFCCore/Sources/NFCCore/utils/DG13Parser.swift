import Foundation

struct Dg13ParsedData {
  var fullName: String = ""
  var gender: String = ""
  var permanentAddress: String = ""
  var issueDate: String = ""
  var issuePlace: String = ""
  var expireDate: String = ""
  var fieldMap: [Int: [String]] = [:]
}

private struct ASN1Node {
  let tagClass: Int
  let tagNumber: Int
  let constructed: Bool
  let valueOffset: Int
  let valueLength: Int
  let nextOffset: Int
}

enum Dg13Parser {
  private static let tagClassUniversal = 0
  private static let tagClassApplication = 1
  private static let outputDateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "dd/MM/yyyy"
    return formatter
  }()
  private static let inputDatePatterns = [
    "dd/MM/yyyy",
    "yyyy-MM-dd",
    "ddMMyyyy",
    "yyyyMMdd",
  ]

  static func parse(
    _ data: Data,
    fallbackIssuePlace: String = ""
  ) -> Dg13ParsedData {
    let bytes = [UInt8](data)
    guard !bytes.isEmpty,
          let fields = parseStructuredFields(bytes)
    else {
      return Dg13ParsedData(issuePlace: fallbackIssuePlace)
    }

    let candidates = Array(Set(fields.values.flatMap { $0 }))

    return Dg13ParsedData(
      fullName: normalizeFullName(fields[0x02]?.first ?? ""),
      gender: normalizeGender(fields[0x04]?.first ?? ""),
      permanentAddress: fields[0x09]?.first ?? "",
      issueDate: normalizeDate(fields[0x0B]?.first ?? ""),
      issuePlace: findIssuePlace(candidates) ?? fallbackIssuePlace,
      expireDate: normalizeDate(fields[0x0C]?.first ?? ""),
      fieldMap: fields
    )
  }

  static func normalizeGender(_ raw: String) -> String {
    switch normalizeForSearch(raw) {
    case "M", "MALE", "NAM":
      return "Male"
    case "F", "FEMALE", "NU":
      return "Female"
    default:
      return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }
  }

  static func normalizeFullName(_ raw: String) -> String {
    raw
      .replacingOccurrences(of: "<", with: " ")
      .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private static func parseStructuredFields(_ bytes: [UInt8]) -> [Int: [String]]? {
    guard let root = parseNode(bytes, offset: 0),
          root.tagClass == tagClassApplication,
          root.tagNumber == 13
    else {
      return nil
    }

    let rootChildren = parseChildren(bytes, parent: root)
    guard let sequenceNode = rootChildren.first else {
      return nil
    }

    let sequenceChildren = parseChildren(bytes, parent: sequenceNode)
    guard let setNode = sequenceChildren.first(where: {
      $0.tagClass == tagClassUniversal && $0.tagNumber == 17
    }) else {
      return nil
    }

    let fieldNodes = parseChildren(bytes, parent: setNode)
    guard !fieldNodes.isEmpty else {
      return nil
    }

    var result: [Int: [String]] = [:]
    for fieldNode in fieldNodes {
      guard fieldNode.tagClass == tagClassUniversal,
            fieldNode.tagNumber == 16
      else {
        continue
      }

      let parts = parseChildren(bytes, parent: fieldNode)
      guard let indexNode = parts.first(where: {
        $0.tagClass == tagClassUniversal && $0.tagNumber == 2
      }),
      let index = parseInteger(bytes, node: indexNode)
      else {
        continue
      }

      let values = parts.dropFirst()
        .flatMap { extractStrings(bytes, node: $0) }
        .map(cleanValue)
        .filter { !$0.isEmpty }

      if !values.isEmpty {
        result[index] = values
      }
    }

    return result.isEmpty ? nil : result
  }

  private static func parseChildren(_ bytes: [UInt8], parent: ASN1Node) -> [ASN1Node] {
    guard parent.constructed else {
      return []
    }

    var children: [ASN1Node] = []
    var offset = parent.valueOffset
    let end = parent.valueOffset + parent.valueLength

    while offset < end {
      guard let child = parseNode(bytes, offset: offset),
            child.nextOffset <= end
      else {
        break
      }

      children.append(child)
      offset = child.nextOffset
    }

    return children
  }

  private static func parseNode(_ bytes: [UInt8], offset: Int) -> ASN1Node? {
    guard offset < bytes.count else {
      return nil
    }

    var cursor = offset
    let firstTagByte = Int(bytes[cursor])
    cursor += 1

    let tagClass = (firstTagByte >> 6) & 0x03
    let constructed = (firstTagByte & 0x20) != 0
    var tagNumber = firstTagByte & 0x1F

    if tagNumber == 0x1F {
      tagNumber = 0
      while cursor < bytes.count {
        let next = Int(bytes[cursor])
        cursor += 1
        tagNumber = (tagNumber << 7) | (next & 0x7F)
        if (next & 0x80) == 0 {
          break
        }
      }
    }

    guard cursor < bytes.count else {
      return nil
    }

    let lengthByte = Int(bytes[cursor])
    cursor += 1

    let valueLength: Int
    if (lengthByte & 0x80) == 0 {
      valueLength = lengthByte
    } else {
      let count = lengthByte & 0x7F
      guard count > 0, count <= 4, cursor + count <= bytes.count else {
        return nil
      }

      var length = 0
      for index in 0..<count {
        length = (length << 8) | Int(bytes[cursor + index])
      }
      cursor += count
      valueLength = length
    }

    let nextOffset = cursor + valueLength
    guard nextOffset <= bytes.count else {
      return nil
    }

    return ASN1Node(
      tagClass: tagClass,
      tagNumber: tagNumber,
      constructed: constructed,
      valueOffset: cursor,
      valueLength: valueLength,
      nextOffset: nextOffset
    )
  }

  private static func parseInteger(_ bytes: [UInt8], node: ASN1Node) -> Int? {
    guard node.tagClass == tagClassUniversal, node.tagNumber == 2 else {
      return nil
    }

    var result = 0
    for index in 0..<node.valueLength {
      result = (result << 8) | Int(bytes[node.valueOffset + index])
    }
    return result
  }

  private static func extractStrings(_ bytes: [UInt8], node: ASN1Node) -> [String] {
    if node.constructed {
      return parseChildren(bytes, parent: node).flatMap { child in
        extractStrings(bytes, node: child)
      }
    }

    guard node.tagClass == tagClassUniversal else {
      return []
    }

    let data = Data(bytes[node.valueOffset..<node.nextOffset])
    let decoded: String
    switch node.tagNumber {
    case 12, 19, 20, 22:
      decoded = String(data: data, encoding: .utf8) ?? ""
    case 30:
      decoded = String(data: data, encoding: .utf16BigEndian) ?? ""
    default:
      decoded = ""
    }

    let clean = cleanValue(decoded)
    return clean.isEmpty ? [] : [clean]
  }

  private static func cleanValue(_ raw: String) -> String {
    raw
      .components(separatedBy: .whitespacesAndNewlines)
      .filter { !$0.isEmpty }
      .joined(separator: " ")
  }

  private static func normalizeForSearch(_ raw: String) -> String {
    raw
      .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "vi_VN"))
      .uppercased()
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private static func findIssuePlace(_ candidates: [String]) -> String? {
    candidates.first { candidate in
      let normalized = normalizeForSearch(candidate)
      return normalized.contains("CONG AN") || normalized.contains("CANH SAT")
    }
  }

  private static func normalizeDate(_ raw: String) -> String {
    guard let parsed = tryParseDate(raw) else {
      return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    return outputDateFormatter.string(from: parsed)
  }

  private static func tryParseDate(_ raw: String) -> Date? {
    let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    for pattern in inputDatePatterns {
      let formatter = DateFormatter()
      formatter.locale = Locale(identifier: "en_US_POSIX")
      formatter.dateFormat = pattern
      if let date = formatter.date(from: clean) {
        return date
      }
    }

    return nil
  }
}
