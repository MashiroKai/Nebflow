package nebflow.cli

import io.circe.Json
import io.circe.parser.*
import io.circe.syntax.*
import nebflow.shared.PathUtil

/**
 * `<dataRoot>/.pid.meta` — the ownership sidecar of `<dataRoot>/.pid`
 * (phase 2 daemon plan §2.2).
 *
 * `.pid` says WHICH process owns the instance; `.pid.meta` says WHO started
 * it (launcher / restart-script / sbt / unknown) and in which mode (daemon /
 * foreground). `stand stop` reads it to decide whether the CLI is allowed
 * to manage that process: a `launcher`-started daemon is fully managed,
 * while `sbt`/`restart-script` instances belong to their own supervisors and
 * are refused by default.
 *
 * JSON shape (only `startedBy` is required — the host-script patch writes a
 * one-line file with exactly that field, and readers must tolerate it):
 * {{{
 * {"startedBy":"launcher","mode":"daemon","startedAt":"…","pid":123,
 *  "cmdlineSha256":"<64-hex>","cmdline":"<truncated to 200 chars>"}
 * }}}
 *
 * `cmdlineSha256` is computed BEFORE truncation; the truncation limit reuses
 * [[nebflow.gateway.StaleProcessGuard.CmdlineDetailLimit]] so both faces show
 * the same slice of the command line.
 *
 * Write rule = writeFresh: a writer may (re)write the meta file only when it
 * is absent, or the `.pid` file is absent, or the pid it names is dead, or
 * that pid is the writer's own process (self-exemption — `bootGateway`
 * writes `.pid` for itself, and a rule without the exemption would then
 * treat its own fresh pid as a foreign live instance and never write meta).
 * A live, foreign pid means another instance owns the root: no-clobber.
 * This closes the crash-leftover misattribution in both directions (a
 * leftover `sbt` meta must not refuse a later daemon start; a leftover
 * `launcher` meta must not authorize killing a later sbt foreground run).
 *
 * Backup face: intentionally NOT included in any backup include list
 * (service/NebflowBackup include lists name user data only) — a pid meta is
 * per-boot runtime state.
 */
object PidMeta:

  /** Values for `startedBy`. */
  val StartedByLauncher: String = "launcher"
  val StartedByRestartScript: String = "restart-script"
  val StartedBySbt: String = "sbt"
  val StartedByUnknown: String = "unknown"

  val ModeDaemon: String = "daemon"
  val ModeForeground: String = "foreground"

  def metaFile: os.Path = PathUtil.dataRoot / ".pid.meta"

  final case class Meta(
    startedBy: String,
    mode: Option[String],
    startedAt: Option[String],
    pid: Option[Long],
    cmdlineSha256: Option[String],
    cmdline: Option[String]
  )

  /**
   * Read the meta file. None when absent or unparsable — callers map None to
   * `unknown` ownership (conservative: the default-refuse path in stop).
   */
  def read(): Option[Meta] =
    if !os.exists(metaFile) then None
    else
      parse(os.read(metaFile)).toOption
        .filter(_.isObject)
        .flatMap(decode)

  /** Decode a parsed JSON object; tolerates every missing optional field. */
  private[cli] def decode(json: Json): Option[Meta] =
    val c = json.hcursor
    for startedBy <- c.get[String]("startedBy").toOption
    yield Meta(
      startedBy,
      c.get[String]("mode").toOption,
      c.get[String]("startedAt").toOption,
      c.get[Long]("pid").toOption,
      c.get[String]("cmdlineSha256").toOption,
      c.get[String]("cmdline").toOption
    )

  private[cli] def encode(m: Meta): String =
    Json
      .obj(
        "startedBy" -> m.startedBy.asJson,
        "mode" -> m.mode.asJson,
        "startedAt" -> m.startedAt.asJson,
        "pid" -> m.pid.asJson,
        "cmdlineSha256" -> m.cmdlineSha256.asJson,
        "cmdline" -> m.cmdline.asJson
      )
      .spaces2
      .trim + "\n"

  // ── writeFresh rule ────────────────────────────────────────────────────

  /**
   * Pure writeFresh predicate (spec-driven): writable ⇔ meta absent ∨ `.pid`
   * absent ∨ that pid dead ∨ that pid == current (self-exemption).
   * no-clobber ⇔ meta present ∧ `.pid` present ∧ live ∧ foreign.
   */
  private[cli] def writeFreshAllowed(
    metaExists: Boolean,
    pidOpt: Option[Long],
    pidAlive: Boolean,
    currentPid: Long
  ): Boolean =
    !metaExists ||
      pidOpt.isEmpty ||
      !pidAlive ||
      pidOpt.contains(currentPid)

  /**
   * Write meta for the CURRENT process (reads own pid + command line),
   * enforcing the writeFresh rule against the on-disk state. No-op when the
   * rule says no-clobber (a live foreign pid owns the root).
   */
  def writeFresh(startedBy: String, mode: String): Unit =
    val currentPid = java.lang.ProcessHandle.current().pid()
    writeFreshAs(startedBy, mode, Some(currentPid), selfCommandLine(), System.currentTimeMillis())

  /**
   * Write meta for an arbitrary pid (the daemon parent writes it for the
   * child before the child boots). Same writeFresh rule.
   */
  def writeFreshFor(startedBy: String, mode: String, pid: Long): Unit =
    writeFreshAs(startedBy, mode, Some(pid), None, System.currentTimeMillis())

  private[cli] def writeFreshAs(
    startedBy: String,
    mode: String,
    pid: Option[Long],
    cmdline: Option[String],
    nowMs: Long
  ): Unit =
    val currentPid = java.lang.ProcessHandle.current().pid()
    val pidOpt = ProcessManager.readPid()
    val allowed =
      writeFreshAllowed(os.exists(metaFile), pidOpt, pidOpt.exists(ProcessManager.isRunning), currentPid)
    if allowed then
      val fullCmd = cmdline.filter(_.trim.nonEmpty)
      val m = Meta(
        startedBy,
        Some(mode),
        Some(java.time.Instant.ofEpochMilli(nowMs).toString),
        pid,
        fullCmd.map(sha256Hex),
        fullCmd.map(c =>
          nebflow.gateway.StaleProcessGuard.truncate(c, nebflow.gateway.StaleProcessGuard.CmdlineDetailLimit)
        )
      )
      os.write.over(metaFile, encode(m), createFolders = true)

  end writeFreshAs

  /** Delete the meta file if present (paired with `ProcessManager.removePid`). */
  def remove(): Unit =
    if os.exists(metaFile) then os.remove(metaFile)

  private def selfCommandLine(): Option[String] =
    try
      import scala.jdk.OptionConverters.*
      java.lang.ProcessHandle.current().info.commandLine.toScala.map(_.trim).filter(_.nonEmpty)
    catch case _: Exception => None

  private def sha256Hex(s: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.digest(s.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString

end PidMeta
