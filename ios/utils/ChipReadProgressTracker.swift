import Foundation

final class ChipReadProgressTracker {
  private struct Allocation {
    let start: Int
    let width: Int
  }

  private let authenticationStart = 10
  private let authenticationWidth = 15
  private let dataGroupStart = 25
  private let maxProgressBeforeSuccess = 95
  private let activeAuthenticationProgress = 97
  private let dataGroupWidths: [DataGroupId: Int] = [
    .COM: 5,
    .DG14: 5,
    .SOD: 5,
    .DG1: 5,
    .DG2: 40,
    .DG13: 10,
  ]

  private var lastProgress: Int
  private var nextDataGroupStart: Int
  private var allocations: [DataGroupId: Allocation] = [:]

  var currentProgress: Int {
    lastProgress
  }

  init(
    initialProgress: Int = 10,
    skipCA: Bool,
    requestedTags: [DataGroupId]
  ) {
    lastProgress = initialProgress
    nextDataGroupStart = dataGroupStart
    preallocateDataGroups(skipCA: skipCA, requestedTags: requestedTags)
  }

  func progressForAuthentication(_ progress: Int) -> Int {
    let mappedProgress = authenticationStart + (clamp(progress) * authenticationWidth / 100)
    return record(mappedProgress)
  }

  func progressForActiveAuthentication() -> Int {
    record(activeAuthenticationProgress)
  }

  func progressForDataGroup(_ dataGroupId: DataGroupId, progress: Int) -> Int {
    let allocation = allocation(for: dataGroupId)
    let mappedProgress = allocation.start + (clamp(progress) * allocation.width / 100)
    return record(mappedProgress)
  }

  private func preallocateDataGroups(skipCA: Bool, requestedTags: [DataGroupId]) {
    let requestedTagSet = Set(requestedTags)
    let plannedOrder = plannedDataGroupOrder(skipCA: skipCA)
      .filter { requestedTagSet.contains($0) }

    for dataGroupId in plannedOrder {
      _ = allocation(for: dataGroupId)
    }
  }

  private func plannedDataGroupOrder(skipCA: Bool) -> [DataGroupId] {
    var order: [DataGroupId] = [.COM]
    if !skipCA {
      order.append(.DG14)
    }
    order.append(contentsOf: [.SOD, .DG1, .DG2, .DG13])
    return order
  }

  private func allocation(for dataGroupId: DataGroupId) -> Allocation {
    if let existing = allocations[dataGroupId] {
      return existing
    }

    let requestedWidth = dataGroupWidths[dataGroupId] ?? 5
    let remainingWidth = max(0, maxProgressBeforeSuccess - nextDataGroupStart)
    let effectiveWidth = min(requestedWidth, remainingWidth)
    let allocation = Allocation(
      start: nextDataGroupStart,
      width: effectiveWidth
    )

    allocations[dataGroupId] = allocation
    nextDataGroupStart = min(
      maxProgressBeforeSuccess,
      nextDataGroupStart + effectiveWidth
    )

    return allocation
  }

  private func record(_ progress: Int) -> Int {
    lastProgress = min(
      99,
      max(lastProgress, progress)
    )
    return lastProgress
  }

  private func clamp(_ progress: Int) -> Int {
    min(100, max(0, progress))
  }
}
