package com.margelo.nitro.nitronfc.utils

import java.util.Calendar
import java.util.Locale

/**
 * Utility functions xử lý dữ liệu MRZ (Machine Readable Zone) từ DG1.
 */
object MrzUtils {

  /**
   * Format ngày sinh từ MRZ (YYMMDD) sang DD/MM/YYYY.
   * Sử dụng năm hiện tại làm ngưỡng xác định thế kỷ (19xx vs 20xx).
   */
  fun formatBirthDate(value: String): String {
    if (value.length != 6) return value

    val yy = value.substring(0, 2)
    val mm = value.substring(2, 4)
    val dd = value.substring(4, 6)
    val currentYearShort = Calendar.getInstance().get(Calendar.YEAR) % 100
    val year = if (yy.toIntOrNull() ?: 0 > currentYearShort) "19$yy" else "20$yy"

    return "$dd/$mm/$year"
  }

  /**
   * Format ngày hết hạn từ MRZ (YYMMDD) sang DD/MM/20YY.
   * CCCD luôn hết hạn trong thế kỷ 21.
   */
  fun formatExpireDate(value: String): String {
    if (value.length != 6) return value

    val yy = value.substring(0, 2)
    val mm = value.substring(2, 4)
    val dd = value.substring(4, 6)

    return "$dd/$mm/20$yy"
  }

  /**
   * Ghép primaryIdentifier + secondaryIdentifier từ MRZ thành họ tên.
   * Thay ký tự '<' bằng khoảng trắng và chuẩn hóa.
   */
  fun normalizeName(primary: String?, secondary: String?): String {
    return listOf(primary, secondary)
      .filterNotNull()
      .joinToString(" ")
      .replace("<", " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  /**
   * Chuẩn hóa giới tính từ MRZ sang tiếng Việt.
   */
  fun normalizeGender(raw: String): String {
    return when (raw.trim().uppercase(Locale.ROOT)) {
      "M", "MALE", "NAM" -> "Nam"
      "F", "FEMALE", "NU" -> "Nữ"
      else -> "Khác"
    }
  }

  /**
   * Chuẩn hóa quốc tịch từ mã ISO 3166 sang tiếng Việt.
   */
  fun normalizeNationality(raw: String?): String {
    return when (raw?.uppercase(Locale.ROOT)) {
      "VNM" -> "Việt Nam"
      null, "" -> "Việt Nam"
      else -> raw
    }
  }
}
