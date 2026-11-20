package nebflow.shared

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

/**
 * Boot + periodic driver for the worktree temporary-artifact TTL.
 *
 * Author ruling 2026-11-16 (followed up 2026-11-19 「提示词和机制都要落实」):
 * worktree delivery/evidence artifacts live in `<project>/.stand/tmp/<wt>/` and
 * carry a **seven-day TTL**. The prompt side lands the rule in every dispatch
 * brief ([[Defaults.DeliveryLandingLine]]); this object is the mechanism side.
 *
 * Why the engine shells out instead of re-implementing the clean: the TTL
 * semantics (which directories are eligible, the uncommitted-retention guard,
 * the audit report) already live in `scripts/stand-wt.py clean`, and the gate
 * side lives in `scripts/check-delivery.py`. A Scala re-implementation would be
 * a second ruler for the same rule. This leg therefore only decides **when** the
 * cleaner runs — exactly the shape [[EvidenceTempSweep]] has for
 * [[EvidenceTemp.sweep]].
 *
 * Scope (deliberately narrow): every worktree directory under
 * `<project>/.stand/tmp/`, `--ttl-days 7`, and
 * **never** `--force` — so the cleaner's own guard ("a directory holding
 * uncommitted artifacts is retained, `WARNING retained …`") keeps protecting
 * unreviewed work verbatim. Directories whose artifacts were admitted to the
 * repository (or that are empty) past the TTL are actually deleted, and the
 * cleaner writes its `worktree-audit/<stamp>/clean-<wt>.txt` record.
 *
 * Fail-safe: a missing script, a missing `<project>/.stand/tmp` root (e.g. an
 * installed instance whose cwd is the data root) or a non-zero cleaner exit
 * warns and never breaks the boot path or the periodic loop.
 */
object WorkspaceTmpSweep:

  private val logger = NebflowLogger.forName("nebflow.shared.workspace-tmp")

  /** Project-root-relative script that owns the clean semantics (single source). */
  val ScriptRelPath: String = "scripts/stand-wt.py"

  /** TTL window handed to the cleaner (author ruling: seven days). */
  val TtlDays: Int = 7

  /** Scratch root swept, relative to the project (primary) checkout. */
  val TmpRelPath: String = ".stand/tmp"

  /** Interpreter used for the cleaner; overridable for hosts without `python3` on PATH. */
  private def python: String = sys.env.getOrElse("PYTHON", "python3")

  /**
   * Run one sweep. `root` defaults to the process working directory — the
   * project checkout the engine was started from (same anchor `NfPathPolicy`
   * uses for the workspace `.nebflow`).
   */
  def sweepOnce(root: os.Path = os.pwd): IO[Unit] =
    IO.blocking {
      val script = root / os.SubPath(ScriptRelPath)
      val tmp = root / os.SubPath(TmpRelPath)
      if !os.exists(script) || !os.exists(tmp) then None
      else
        val res = os
          .proc(
            python,
            "-B",
            script.toString,
            "--root",
            root.toString,
            "clean",
            "--apply",
            "--ttl-days",
            TtlDays.toString
          )
          .call(cwd = root, check = false, mergeErrIntoOut = true)
        Some((res.exitCode, res.out.text()))
      end if
    }.attempt
      .flatMap {
        case Left(e) =>
          logger.warn(s"[workspace-tmp] TTL sweep skipped: ${e.getMessage}")
        case Right(None) =>
          IO.unit
        case Right(Some((code, out))) =>
          val removed = out.linesIterator.count(_.startsWith("DELETE "))
          val summary = s"[workspace-tmp] TTL sweep (ttl=${TtlDays}d): removed $removed expired artifact dir(ies)"
          if code == 0 then logger.info(summary)
          else
            logger.warn(
              s"$summary — cleaner exit $code: " +
                out.linesIterator.toList.takeRight(3).mkString(" / ")
            )
      }

  /**
   * Periodic re-arm: the first sweep fires after one full `interval`, then every
   * `interval`. A long-lived gateway would otherwise only enforce the TTL at
   * boot (`[[sweepOnce]]` in `GatewayMain`), so expired directories would pile
   * up until the next restart. Failures are absorbed inside [[sweepOnce]].
   */
  def launch(interval: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(interval) *> sweepOnce() *> IO.defer(loop)
    loop

end WorkspaceTmpSweep
