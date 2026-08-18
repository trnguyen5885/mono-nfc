package com.vppos.nfc.core.utils

import java.util.Calendar
import java.util.Locale

/** Utility functions for processing MRZ (Machine Readable Zone) data from DG1. */
object MrzUtils {

  /**
   * Format the date of birth from MRZ (YYMMDD) as DD/MM/YYYY. Use the current year as the threshold
   * for the century (19xx vs 20xx).
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
   * Format the expiration date from MRZ (YYMMDD) as DD/MM/20YY. Citizen ID cards always expire in
   * the 21st century.
   */
  fun formatExpireDate(value: String): String {
    if (value.length != 6) return value

    val yy = value.substring(0, 2)
    val mm = value.substring(2, 4)
    val dd = value.substring(4, 6)

    return "$dd/$mm/20$yy"
  }

  /**
   * Combine the primaryIdentifier and secondaryIdentifier from MRZ into a name. Replace '<' with
   * spaces and normalize whitespace.
   */
  fun normalizeName(primary: String?, secondary: String?): String {
    return listOf(primary, secondary)
            .filterNotNull()
            .joinToString(" ")
            .replace("<", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
  }

  /** Normalize the gender value from MRZ into English. */
  fun normalizeGender(raw: String): String {
    return when (raw.trim().uppercase(Locale.ROOT)) {
      "M", "MALE", "NAM" -> "Male"
      "F", "FEMALE", "NU" -> "Female"
      else -> "Other"
    }
  }

  /** Normalize nationality from an ISO 3166 code into English. */
  fun normalizeNationality(raw: String?): String {
    return when (raw?.uppercase(Locale.ROOT)) {
      "VNM" -> "Việt Nam"
      null, "" -> "Việt Nam"
      else -> raw
    }
  }
}
