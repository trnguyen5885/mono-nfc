package com.margelo.nitro.nitronfc.utils

import java.nio.charset.Charset
import java.text.Normalizer
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Dg13ParsedData(
        val fullName: String = "",
        val gender: String = "",
        val permanentAddress: String = "",
        val issueDate: String = "",
        val issuePlace: String = "",
        val expireDate: String = "",
        val fieldMap: Map<Int, List<String>> = emptyMap(),
)

private data class Asn1Node(
        val tagClass: Int,
        val tagNumber: Int,
        val constructed: Boolean,
        val valueOffset: Int,
        val valueLength: Int,
        val nextOffset: Int,
)

object Dg13Parser {
  private const val TAG_CLASS_UNIVERSAL = 0
  private const val TAG_CLASS_APPLICATION = 1

  fun parse(
          dg13Bytes: ByteArray,
          fallbackIssuePlace: String = "",
  ): Dg13ParsedData {
    if (dg13Bytes.isEmpty()) {
      return Dg13ParsedData(issuePlace = fallbackIssuePlace)
    }

    val fields =
            parseStructuredFields(dg13Bytes)
                    ?: return Dg13ParsedData(issuePlace = fallbackIssuePlace)

    val candidates = fields.values.flatten().distinct()

    return Dg13ParsedData(
            fullName = findFullName(fields),
            gender = normalizeGender(fields[0x04]?.firstOrNull().orEmpty()),
            permanentAddress = fields[0x09]?.firstOrNull().orEmpty(),
            issueDate = normalizeDate(fields[0x0B]?.firstOrNull().orEmpty()),
            issuePlace = findIssuePlace(candidates) ?: fallbackIssuePlace,
            expireDate = normalizeDate(fields[0x0C]?.firstOrNull().orEmpty()),
            fieldMap = fields,
    )
  }

  fun normalizeGender(raw: String): String {
    return when (normalizeForSearch(raw)) {
      "M", "MALE", "NAM" -> "Nam"
      "F", "FEMALE", "NU" -> "Nữ"
      else -> raw.trim()
    }
  }

  private fun parseStructuredFields(bytes: ByteArray): Map<Int, List<String>>? {
    val root = parseNode(bytes, 0) ?: return null
    if (root.tagClass != TAG_CLASS_APPLICATION || root.tagNumber != 13) {
      return null
    }

    val rootChildren = parseChildren(bytes, root)
    val sequenceNode = rootChildren.firstOrNull() ?: return null
    val sequenceChildren = parseChildren(bytes, sequenceNode)
    val setNode =
            sequenceChildren.firstOrNull {
              it.tagClass == TAG_CLASS_UNIVERSAL && it.tagNumber == 17
            }
                    ?: return null

    val fieldNodes = parseChildren(bytes, setNode)
    if (fieldNodes.isEmpty()) {
      return null
    }

    val result = linkedMapOf<Int, List<String>>()
    fieldNodes.forEach { fieldNode ->
      if (!(fieldNode.tagClass == TAG_CLASS_UNIVERSAL && fieldNode.tagNumber == 16)) {
        return@forEach
      }

      val parts = parseChildren(bytes, fieldNode)
      val indexNode =
              parts.firstOrNull { it.tagClass == TAG_CLASS_UNIVERSAL && it.tagNumber == 2 }
                      ?: return@forEach

      val index = parseInteger(bytes, indexNode) ?: return@forEach
      val values =
              parts.drop(1).flatMap { extractStrings(bytes, it) }.map(::cleanValue).filter {
                it.isNotBlank()
              }

      if (values.isNotEmpty()) {
        result[index] = values
      }
    }

    return result.takeIf { it.isNotEmpty() }
  }

  private fun parseChildren(bytes: ByteArray, parent: Asn1Node): List<Asn1Node> {
    if (!parent.constructed) {
      return emptyList()
    }

    val children = mutableListOf<Asn1Node>()
    var offset = parent.valueOffset
    val end = parent.valueOffset + parent.valueLength

    while (offset < end) {
      val child = parseNode(bytes, offset) ?: break
      if (child.nextOffset > end) {
        break
      }
      children.add(child)
      offset = child.nextOffset
    }

    return children
  }

  private fun parseNode(bytes: ByteArray, offset: Int): Asn1Node? {
    if (offset >= bytes.size) {
      return null
    }

    var cursor = offset
    val firstTagByte = bytes[cursor].toInt() and 0xFF
    cursor += 1

    val tagClass = (firstTagByte ushr 6) and 0x03
    val constructed = (firstTagByte and 0x20) != 0
    var tagNumber = firstTagByte and 0x1F

    if (tagNumber == 0x1F) {
      tagNumber = 0
      while (cursor < bytes.size) {
        val next = bytes[cursor].toInt() and 0xFF
        cursor += 1
        tagNumber = (tagNumber shl 7) or (next and 0x7F)
        if ((next and 0x80) == 0) {
          break
        }
      }
    }

    if (cursor >= bytes.size) {
      return null
    }

    val lengthByte = bytes[cursor].toInt() and 0xFF
    cursor += 1

    val valueLength =
            if ((lengthByte and 0x80) == 0) {
              lengthByte
            } else {
              val count = lengthByte and 0x7F
              if (count == 0 || count > 4 || cursor + count > bytes.size) {
                return null
              }

              var length = 0
              repeat(count) { length = (length shl 8) or (bytes[cursor + it].toInt() and 0xFF) }
              cursor += count
              length
            }

    val nextOffset = cursor + valueLength
    if (nextOffset > bytes.size) {
      return null
    }

    return Asn1Node(
            tagClass = tagClass,
            tagNumber = tagNumber,
            constructed = constructed,
            valueOffset = cursor,
            valueLength = valueLength,
            nextOffset = nextOffset,
    )
  }

  private fun parseInteger(bytes: ByteArray, node: Asn1Node): Int? {
    if (node.tagClass != TAG_CLASS_UNIVERSAL || node.tagNumber != 2) {
      return null
    }

    var result = 0
    for (index in 0 until node.valueLength) {
      result = (result shl 8) or (bytes[node.valueOffset + index].toInt() and 0xFF)
    }
    return result
  }

  private fun extractStrings(bytes: ByteArray, node: Asn1Node): List<String> {
    if (node.constructed) {
      return parseChildren(bytes, node).flatMap { child -> extractStrings(bytes, child) }
    }

    if (node.tagClass != TAG_CLASS_UNIVERSAL) {
      return emptyList()
    }

    val value = bytes.copyOfRange(node.valueOffset, node.nextOffset)
    val decoded =
            when (node.tagNumber) {
              12, 19, 20, 22 -> decodeString(value, Charsets.UTF_8)
              30 -> decodeString(value, Charsets.UTF_16BE)
              else -> ""
            }

    return decoded.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
  }

  private fun decodeString(value: ByteArray, charset: Charset): String {
    return value.toString(charset).trim()
  }

  private fun cleanValue(raw: String): String {
    return raw.replace(Regex("""\s+"""), " ").trim()
  }

  private fun normalizeForSearch(raw: String): String {
    return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .uppercase(Locale.ROOT)
            .trim()
  }

  private fun findFullName(fields: Map<Int, List<String>>): String {
    val preferredIndexes = listOf(0x02, 0x03, 0x01)

    return preferredIndexes
            .flatMap { fields[it].orEmpty() }
            .map(::cleanValue)
            .firstOrNull(::isLikelyFullName)
            .orEmpty()
  }

  private fun isLikelyFullName(value: String): Boolean {
    val clean = value.trim()
    if (clean.length < 5) return false
    if (!clean.any { it.isLetter() }) return false
    if (clean.all { it.isDigit() || it.isWhitespace() }) return false
    if (tryParseDate(clean) != null) return false

    val normalized = normalizeForSearch(clean)
    if (normalized in setOf("NAM", "NU", "MALE", "FEMALE", "VIET NAM", "VNM")) {
      return false
    }

    return !looksLikeAddressOrIssuer(normalized)
  }

  private fun looksLikeAddressOrIssuer(normalized: String): Boolean {
    return listOf(
                    "CONG AN",
                    "CANH SAT",
                    "CUC TRUONG",
                    "TINH ",
                    "THANH PHO",
                    "HUYEN ",
                    "QUAN ",
                    "PHUONG ",
                    "XA ",
                    "THI TRAN",
                    "DUONG ",
                    "THON ",
                    "AP ",
            )
            .any(normalized::contains)
  }

  private fun findIssuePlace(candidates: List<String>): String? {
    return candidates.firstOrNull { candidate ->
      val normalized = normalizeForSearch(candidate)
      normalized.contains("CONG AN") || normalized.contains("CANH SAT")
    }
  }

  private fun normalizeDate(raw: String): String {
    val parsed = tryParseDate(raw) ?: return raw.trim()
    return OUTPUT_DATE_FORMAT.format(parsed)
  }

  private fun tryParseDate(raw: String): Date? {
    val clean = raw.trim()
    for (pattern in DATE_PATTERNS) {
      try {
        return SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(clean)
      } catch (_: ParseException) {}
    }

    return null
  }

  private val OUTPUT_DATE_FORMAT =
          SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply { isLenient = false }

  private val DATE_PATTERNS =
          listOf(
                  "dd/MM/yyyy",
                  "yyyy-MM-dd",
                  "ddMMyyyy",
                  "yyyyMMdd",
          )
}
