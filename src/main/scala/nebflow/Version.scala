package nebflow

/**
 * Version format: Major.Minor.EditCounter
 *  - Major: breaking changes
 *  - Minor: feature additions
 *  - EditCounter: incremented on every code edit session
 */
object Version:
  val major = 1
  val minor = 3
  val edit = 60

  val string: String = f"$major.$minor%02d.$edit%03d"
end Version
