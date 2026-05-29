package nebflow

/**
 * Version format: Major.Minor.EditCounter
 *  - Major: breaking changes
 *  - Minor: feature additions
 *  - EditCounter: incremented on every code edit session
 */
object Version:
  val major = 1
  val minor = 0
  val edit = 4

  val string: String = f"$major.$minor%02d.$edit%03d"
end Version
