package nebflow.core.jev

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * The typed outcome of an allocation attempt (Face C / P2).
 *
 * "Fall back" is a first-class result, not an exception: the adjudicated #3
 * posture is fail-OPEN (a JeV timeout/failure falls back to the dispatcher's
 * default mount, logs a WARN and leaves a visibility event) so that dispatch
 * NEVER wedges. Modelling the fallback as a value makes "never blocked" true
 * by construction — there is no error channel through which a failure could
 * escape into the dispatch path.
 */
enum JevAllocation:
  /** The face produced a capability set (which may legitimately be empty). */
  case Allocated(capabilities: List[String])

  /**
   * The face did not produce a set, and the caller must use the legacy path.
   *
   * `reason` is the classified diagnostic; `kind` distinguishes the causes so
   * a caller can tell "not even switched on" (no event needed) from "switched
   * on but failed" (an event IS needed — a silent fallback is what the
   * adjudication forbids).
   */
  case FellBack(kind: JevFallbackKind, reason: String)

/** Why an allocation attempt fell back. */
enum JevFallbackKind:
  case Off
  case Timeout
  case Failure

  def wire: String = this match
    case Off => "off"
    case Timeout => "timeout"
    case Failure => "failure"

/**
 * Fail-open fallback mechanics (Face C / P2).
 *
 * ==The rule this file enforces==
 * A fallback is NEVER silent. Two channels always fire together:
 *   ① a WARN log line, so an operator grepping logs sees it;
 *   ② an append-only visibility event on disk, so the fallback is auditable
 *      after the fact.
 *
 * The warning in card §C.2.3 is explicit that a HALF capability face is harder
 * to audit than a zero one; the event is what makes either case reconstructible.
 *
 * ==The OFF case is deliberately eventless==
 * When the face is not applied at all (toggle off / unconfigured), that is the
 * NORMAL state of a system that has not enabled the feature — writing an event
 * on every dispatch of every existing installation would be pure noise and
 * would make the event stream useless for detecting real fallbacks. So `Off`
 * logs (at info level, once per call) and emits no event; `Timeout`/`Failure`
 * emit both.
 */
object JevFallback:

  private val logger = NebflowLogger.forName("nebflow.jev")

  /** Append-only event log (same directory convention as the plugin-dispatch
    * audit, `PluginDispatchPolicy.audit`). */
  private def eventPath: os.Path = PathUtil.dataRoot / "logs" / "jev-allocation.jsonl"

  /**
   * Record a fallback and return the value the caller should use.
   *
   * `subject` names the object being dispatched (a node name, a kernel id) so
   * the event is actionable; it must never carry a credential or a task body.
   */
  def record(kind: JevFallbackKind, subject: String, reason: String): IO[JevAllocation] =
    val event = kind.wire
    kind match
      case JevFallbackKind.Off =>
        IO(
          logger.infoSync(
            s"jev allocation not applied for '$subject' ($reason) — dispatcher default mount used"
          )
        ).as(JevAllocation.FellBack(kind, reason))
      case JevFallbackKind.Timeout | JevFallbackKind.Failure =>
        val warn =
          IO(
            logger.warnSync(
              s"jev allocation $event for '$subject' ($reason) — falling back to the dispatcher default mount " +
                "so dispatch never blocks"
            )
          )
        (warn *> appendEvent(event, subject, reason)).as(JevAllocation.FellBack(kind, reason))

  /**
   * Append one visibility event.
   *
   * A failed append must never fail the dispatch: the allocation already
   * degraded to the legacy path, and losing an audit line is strictly better
   * than blocking the caller. The failure is itself logged.
   */
  private def appendEvent(
    event: String,
    subject: String,
    reason: String,
    capabilities: Option[List[String]] = None
  ): IO[Unit] =
    IO.blocking {
      val fields = List(
        "ts" -> System.currentTimeMillis().toString,
        "event" -> event,
        "subject" -> subject,
        "reason" -> reason
      ) ++ capabilities.toList.flatMap(cs => List("count" -> cs.size.toString, "capabilities" -> cs.mkString(",")))
      val line = fields.map { case (k, v) => s""""$k":${Json.fromString(v).noSpaces}""" }.mkString("{", ",", "}")
      os.write.append(eventPath, line + "\n", createFolders = true)
    }.void.handleErrorWith(e =>
      IO(logger.warnSync(s"jev allocation event append failed ($event/$subject): ${e.getClass.getSimpleName}"))
    )

  /**
   * Record a SUCCESSFUL allocation too.
   *
   * Success is only written when the face actually ran — that is what makes
   * "the allocation action happened" auditable, which the #8 adjudication
   * names as the real risk signal once allocation authority moves off the
   * NodeEdit parameter.
   */
  def recordSuccess(subject: String, capabilities: List[String]): IO[Unit] =
    appendEvent("allocated", subject, "ok", Some(capabilities))
