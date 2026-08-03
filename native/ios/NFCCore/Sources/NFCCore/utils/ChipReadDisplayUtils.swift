import Foundation

#if SWIFT_PACKAGE
import NFCPassportReader
#endif

enum ChipReadDisplayUtils {
  static func getDisplayString(
    from message: NFCViewDisplayMessage,
    tracker: ChipReadProgressTracker,
    language: String = "en"
  ) -> String {
    let isVietnamese = language.lowercased() == "vi"
    switch message {
    case .requestPresentPassport:
      return isVietnamese
        ? "Đặt mặt lưng căn cước công dân gần phía trên iPhone và giữ yên."
        : "Place the back of the citizen ID card near the top of the iPhone and keep it still."
    case .authenticatingWithPassport:
      return isVietnamese
        ? "Đang mở khóa chip bằng CAN... \(tracker.currentProgress)%"
        : "Unlocking the chip with CAN... \(tracker.currentProgress)%"
    case .activeAuthentication:
      return isVietnamese
        ? "Đang xác thực chip... \(tracker.currentProgress)%"
        : "Authenticating the chip... \(tracker.currentProgress)%"
    case .readingDataGroupProgress:
      return isVietnamese
        ? "Đang đọc dữ liệu từ chip... \(tracker.currentProgress)%"
        : "Reading data from the chip... \(tracker.currentProgress)%"
    case .successfulRead:
      return isVietnamese ? "Đọc thành công!" : "Read successfully!"
    case .error(let error):
      return ChipReadErrorMapper.localizedReaderErrorMessage(error, language: language)
    default:
      return isVietnamese ? "Vui lòng giữ căn cước công dân yên" : "Please keep the citizen ID card still"
    }
  }

  static func handleInternalProgress(
    _ message: NFCViewDisplayMessage,
    tracker: ChipReadProgressTracker,
    progressListener: ChipReadProgressListener,
    language: String = "en"
  ) {
    let isVietnamese = language.lowercased() == "vi"
    switch message {
    case .authenticatingWithPassport(let progress):
      let overallProgress = tracker.progressForAuthentication(progress)
      progressListener(
        overallProgress,
        isVietnamese ? "Đang mở khóa chip căn cước công dân..." : "Unlocking the citizen ID chip..."
      )
    case .activeAuthentication:
      let overallProgress = tracker.progressForActiveAuthentication()
      progressListener(
        overallProgress,
        isVietnamese ? "Đang xác thực chip..." : "Authenticating the chip..."
      )
    case .readingDataGroupProgress(let dg, let progress):
      let overallProgress = tracker.progressForDataGroup(dg, progress: progress)
      progressListener(
        overallProgress,
        isVietnamese ? "Đang đọc dữ liệu từ chip..." : "Reading data from the chip..."
      )
    default:
      break
    }
  }
}
