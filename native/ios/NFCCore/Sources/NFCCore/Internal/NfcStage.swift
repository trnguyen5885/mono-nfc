/** Stable internal names for scan phases; they do not alter protocol execution. */
enum NfcStage: String {
  case validate
  case initialize
  case authenticate
  case readDataGroups
  case mapResult
  case completed
}
