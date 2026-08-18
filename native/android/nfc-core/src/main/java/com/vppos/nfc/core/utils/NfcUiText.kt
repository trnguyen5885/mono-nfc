package com.vppos.nfc.core.utils

/** User-facing strings for the native NFC reader. */
class NfcUiText(language: String) {
  private val isVietnamese = language.equals("vi", ignoreCase = true)

  val readyToScan: String
    get() = if (isVietnamese) "Sẵn sàng quét" else "Ready to scan"

  val guideDefault: String
    get() =
            if (isVietnamese) {
              "Đặt CCCD áp sát mặt lưng điện thoại và giữ yên"
            } else {
              "Place the citizen ID card against the back of the phone"
            }

  val cancel: String
    get() = if (isVietnamese) "Hủy" else "Cancel"

  val retry: String
    get() = if (isVietnamese) "Thử lại" else "Retry"

  val openingNativeScreen: String
    get() = if (isVietnamese) "Đang mở màn hình NFC..." else "Opening the native NFC screen..."

  val connecting: String
    get() = if (isVietnamese) "Đang kết nối với chip..." else "Connecting to the chip..."

  val authenticating: String
    get() = if (isVietnamese) "Đang xác thực chip..." else "Authenticating the chip..."

  val readingData: String
    get() = if (isVietnamese) "Đang đọc dữ liệu từ chip..." else "Reading data from the chip..."

  val cachedImage: String
    get() =
            if (isVietnamese) "Đang sử dụng ảnh chip đã lưu..."
            else "Using the cached chip image..."

  val completed: String
    get() = if (isVietnamese) "Đọc NFC thành công" else "NFC read completed successfully"
}
