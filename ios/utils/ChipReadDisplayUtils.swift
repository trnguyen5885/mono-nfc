import Foundation

enum ChipReadDisplayUtils {
  static func getDisplayString(
    from message: NFCViewDisplayMessage,
    tracker: ChipReadProgressTracker
  ) -> String {
    switch message {
    case .requestPresentPassport:
      return "Đưa mặt sau CCCD sát đỉnh iPhone và giữ cố định."
    case .authenticatingWithPassport:
      return "Đang mở khóa chip bằng CAN... \(tracker.currentProgress)%"
    case .activeAuthentication:
      return "Đang xác thực chip... \(tracker.currentProgress)%"
    case .readingDataGroupProgress:
      return "Đang đọc dữ liệu từ chip... \(tracker.currentProgress)%"
    case .successfulRead:
      return "Đọc thành công!"
    case .error(let error):
      return ChipReadErrorMapper.localizedReaderErrorMessage(error)
    default:
      return "Vui lòng giữ CCCD cố định"
    }
  }

  static func handleInternalProgress(
    _ message: NFCViewDisplayMessage,
    tracker: ChipReadProgressTracker,
    progressListener: ChipReadProgressListener
  ) {
    switch message {
    case .authenticatingWithPassport(let progress):
      let overallProgress = tracker.progressForAuthentication(progress)
      progressListener(overallProgress, "Đang mở khóa chip CCCD...")
    case .activeAuthentication:
      let overallProgress = tracker.progressForActiveAuthentication()
      progressListener(overallProgress, "Đang xác thực chip...")
    case .readingDataGroupProgress(let dg, let progress):
      let overallProgress = tracker.progressForDataGroup(dg, progress: progress)
      progressListener(overallProgress, "Đang đọc dữ liệu từ chip...")
    default:
      break
    }
  }
}
