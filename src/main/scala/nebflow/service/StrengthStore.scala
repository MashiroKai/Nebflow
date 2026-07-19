package nebflow.service

import nebflow.shared.*
import nebflow.core.PathUtil
import io.circe.syntax.*
import io.circe.generic.semiauto.*

/**
 * Unified strength tracking for skills and memory detail files.
 *
 * Both skills and long memory entries (those with →id) share the same
 * Ebbinghaus-based forgetting mechanism:
 *
 *   - Created at MAX_STRENGTH (100)
 *   - Each read of the detail/SKILL.md file: strength += READ_INCREMENT (30), capped at MAX
 *   - Decay: strength *= e^(-ΔD / HALF_LIFE), where ΔD = delegate/flow events since last activity
 *   - Below THRESHOLD (25): excluded from catalog / memory block
 *
 * Decay is based on delegate/flow event count, NOT wall-clock time.
 * When Nebflow is closed, no events occur, so no decay.
 */
object StrengthStore:

  case class Entry(strength: Double, readCount: Int, lastActiveDelegate: Int)

  object Entry:
    given io.circe.Codec[Entry] = deriveCodec

  val MaxStrength: Double = 100.0
  val ReadIncrement: Double = 30.0
  val HalfLife: Double = 20.0 // delegate/flow events for strength to halve
  val Threshold: Double = 25.0 // minimum effective strength to stay in catalog

  private var statsCache: Map[String, Entry] = Map.empty
  private var statsCacheLoaded: Boolean = false
  private var statsCacheMtime: Long = -1

  private def statsPath = PathUtil.dataRoot / ".stats.json"

  private def loadStats(): Map[String, Entry] =
    val mtime = if os.exists(statsPath) then os.mtime(statsPath) else -1
    if statsCacheLoaded && mtime == statsCacheMtime then statsCache
    else
      statsCache =
        if !os.exists(statsPath) then Map.empty
        else
          try
            val raw = os.read(statsPath)
            io.circe.parser.decode[Map[String, Entry]](raw).getOrElse(Map.empty)
          catch case _: Exception => Map.empty
      statsCacheLoaded = true
      statsCacheMtime = mtime
      statsCache

  private def saveStats(stats: Map[String, Entry]): Unit =
    os.write.over(statsPath, stats.asJson.noSpaces, createFolders = true)
    statsCache = stats
    statsCacheLoaded = true
    statsCacheMtime = os.mtime(statsPath)

  /**
   * Compute the effective strength after decay since last activity.
   * No file I/O — pure computation.
   */
  def effectiveStrength(entry: Entry, currentDelegate: Int): Double =
    val deltaD = (currentDelegate - entry.lastActiveDelegate).max(0)
    entry.strength * math.exp(-deltaD.toDouble / HalfLife)

  /**
   * Get or create an entry for a key. New entries start at MAX_STRENGTH
   * with the current delegate count as their lastActiveDelegate.
   */
  def getOrCreate(key: String, currentDelegate: Int): Entry =
    val stats = loadStats()
    stats.getOrElse(key, Entry(MaxStrength, 0, currentDelegate))

  /** Record a read event: apply decay, add increment, cap at MAX_STRENGTH. */
  def recordRead(key: String, currentDelegate: Int): Unit =
    val stats = loadStats()
    val entry = stats.getOrElse(key, Entry(MaxStrength, 0, currentDelegate))
    val decayed = effectiveStrength(entry, currentDelegate)
    val updated = Entry(
      strength = math.min(MaxStrength, decayed + ReadIncrement),
      readCount = entry.readCount + 1,
      lastActiveDelegate = currentDelegate
    )
    saveStats(stats + (key -> updated))

  /**
   * Check whether a key should be included in the catalog / memory block.
   * Returns true if effective strength ≥ THRESHOLD.
   */
  def shouldInclude(key: String, currentDelegate: Int): Boolean =
    val entry = getOrCreate(key, currentDelegate)
    effectiveStrength(entry, currentDelegate) >= Threshold

  /**
   * Detect if a file path is a SKILL.md or memory detail file,
   * and record the read if so. Called after a successful Read tool execution.
   */
  def recordReadIfTracked(filePath: String, currentDelegate: Int): Unit =
    // SKILL.md: ~/.nebflow/skills/<name>/SKILL.md
    if filePath.contains("/skills/") && (filePath.endsWith("/SKILL.md") || filePath.endsWith("/skill.md")) then
      val parts = filePath.split("/skills/")
      if parts.length > 1 then
        val skillName = parts(1).split("/").headOption.getOrElse("")
        if skillName.nonEmpty then recordRead(s"skills.$skillName", currentDelegate)
    // Memory detail: ~/.nebflow/memory/<id>.md
    else if filePath.contains("/memory/") && filePath.endsWith(".md") then
      val fileName = filePath.split("/").last.replace(".md", "")
      if fileName.matches("[a-zA-Z0-9]{4,12}") then recordRead(s"memory.$fileName", currentDelegate)

end StrengthStore
