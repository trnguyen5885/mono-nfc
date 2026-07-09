export type NFCDataGroupName =
  'IMAGE' | 'DG1' | 'DG2' | 'DG13' | 'DG14' | 'SOD';

/** Lỗi trả về từ native NFC SDK. */
export type NFCErrorEvent = {
  /** Mã lỗi ổn định từ native. */
  code: string;
  /** Thông điệp lỗi hiển thị cho người dùng. */
  message: string;
};

/** Sự kiện tiến trình quét NFC. */
export type NFCProgressEvent =
  | {
      /** Phần trăm hoàn thành (0-100). */
      progress: number;
      /** Mô tả bước đang thực hiện. */
      message: string;
      /** Không có lỗi native ở event progress thường. */
      error?: undefined;
    }
  | {
      /** Lỗi native nếu phiên quét thất bại hoặc cần người dùng thử lại. */
      error: NFCErrorEvent;
      /** Event lỗi không kèm progress. */
      progress?: undefined;
      /** Event lỗi không kèm message top-level. */
      message?: undefined;
    };

export type NFCSubscription = {
  remove(): void;
};

/** Kết quả metadata nhẹ sau khi đọc CCCD qua NFC. */
export type NFCScanResult = {
  /** Số CCCD. */
  citizenId?: string;
  /** Họ và tên. */
  fullName?: string;
  /** Ngày sinh (DD/MM/YYYY). */
  dob?: string;
  /** Giới tính (Nam/Nữ/Khác). */
  gender?: string;
  /** Quốc tịch. */
  nationality?: string;
  /** Địa chỉ thường trú. */
  permanentAddress?: string;
  /** Ngày cấp (DD/MM/YYYY). */
  issueDate?: string;
  /** Nơi cấp. */
  issuePlace?: string;
  /** Ngày hết hạn (DD/MM/YYYY). */
  expireDate?: string;
  /** URI file cache của ảnh chip, nếu native đã ghi ra cache. */
  chipImageUri?: string;
  /** MIME type gợi ý cho ảnh chip. */
  chipImageMimeType?: string;
  /** Kích thước ảnh chip theo byte. */
  imageFromChipSize: number;
  /** Kích thước DG1 theo byte. */
  dg1Size: number;
  /** Kích thước DG2 theo byte. */
  dg2Size: number;
  /** Kích thước DG13 theo byte. */
  dg13Size: number;
  /** Kích thước DG14 theo byte. */
  dg14Size: number;
  /** Kích thước SOD theo byte. */
  sodSize: number;
};
