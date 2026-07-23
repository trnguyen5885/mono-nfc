import Foundation

enum ChipReadDisplayUtils {
  static func getDisplayString(
    from message: NFCViewDisplayMessage,
    tracker: ChipReadProgressTracker
  ) -> String {
    switch message {
    case .requestPresentPassport:
      return "Place the back of the citizen ID card near the top of the iPhone and keep it still."
    case .authenticatingWithPassport:
      return "Unlocking the chip with CAN... \(tracker.currentProgress)%"
    case .activeAuthentication:
      return "Authenticating the chip... \(tracker.currentProgress)%"
    case .readingDataGroupProgress:
      return "Reading data from the chip... \(tracker.currentProgress)%"
    case .successfulRead:
      return "Read successfully!"
    case .error(let error):
      return ChipReadErrorMapper.localizedReaderErrorMessage(error)
    default:
      return "Please keep the citizen ID card still"
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
      progressListener(overallProgress, "Unlocking the citizen ID chip...")
    case .activeAuthentication:
      let overallProgress = tracker.progressForActiveAuthentication()
      progressListener(overallProgress, "Authenticating the chip...")
    case .readingDataGroupProgress(let dg, let progress):
      let overallProgress = tracker.progressForDataGroup(dg, progress: progress)
      progressListener(overallProgress, "Reading data from the chip...")
    default:
      break
    }
  }
}
