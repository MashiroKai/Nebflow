package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevAllocatorPort, JevFallback, JevFallbackKind}
import nebflow.shared.NebflowLogger

/**
 * JeV allocator: task text + capability catalog -> capability set
 * (JeV integration Face B/C, P2 + P3 method transfer).
 *
 * Implements the `core.jev.JevAllocatorPort` seam, so the core call sites
 * depend only on the port and never on a decision-provider type.
 *
 * ==Methodology (P0 #7 / #2, transferred verbatim from the jev-test2 run)==
 * For each catalog entry TWO questions are asked in ONE batched call
 * (`setscore_one.mjs` is the transferred source):
 *
 *   - `need_<name>` — a CHOICE question with the fixed `{yes, no}` criteria:
 *     "is this capability required for the task's primary purpose?".
 *   - `fit_<name>` — a SCORE question over `["poor", "partial", "good"]`.
 *
 * The membership rule is the R2 main rule, unchanged:
 *
 * {{{ J_primary = { p | need_<p>.probabilities["yes"] >= 0.5 } }}}
 *
 * ==The two documented traps, both avoided==
 *   ① `fit >= 1.0` is NOT a membership threshold. It was measured to collapse
 *      exactness to 5.0% when used as one; it stays a sensitivity check.
 *   ② P(yes) is read from the CHOICE answer's `probabilities["yes"]`, never
 *      from a noul — the two are different field reads (see
 *      `DecisionAnswer.pYes` / `noulYes`), and the mainline evidence used the
 *      choice form in every one of the 21x20 rounds.
 *
 * ==Fail-open (P0 #3, adjudicated)==
 * Every failure path returns [[JevAllocation.FellBack]] after recording a WARN
 * plus a visibility event. Nothing throws out of `allocate`: dispatch must
 * never block on this face.
 */
final class JevAllocator(
  provider: DecisionProvider[IO],
  timeoutMs: Long,
  /** Maximum characters of task text handed over as `state` (the measured
    * brief cap; the live run never approached the provider's own limit). */
  stateCapChars: Int = JevAllocator.DefaultStateCapChars
) extends JevAllocatorPort:

  private val logger = NebflowLogger.forName("nebflow.jev.alloc")

  /** Choice criteria of a `need_` question — the fixed yes/no pair. */
  private val NeedCriteria: Map[String, Option[String]] =
    Map("yes" -> Some("required for this task's primary purpose"), "no" -> Some("not required"))

  def allocate(taskText: String, catalog: List[CatalogEntry]): IO[JevAllocation] =
    if catalog.isEmpty then
      // An empty catalog is a legitimate "no capability face installed" state,
      // not a failure: the caller gets the empty set and dispatch proceeds.
      JevFallback.recordSuccess("catalog:empty", Nil).as(JevAllocation.Allocated(Nil))
    else
      val state = if taskText.length > stateCapChars then taskText.take(stateCapChars) else taskText
      val req = DecisionRequest(state = state, questions = questionsFor(catalog))
      provider
        .predict(req)
        .map {
          case Left(err) => Left(err)
          case Right(resp) => Right(select(resp, catalog))
        }
        .flatMap {
          case Left(err) =>
            // Classified from the TYPED error, not from its message text:
            // string-matching the diagnostic would silently mis-classify the
            // moment a detail string is reworded.
            val kind = if err.isTimeout then JevFallbackKind.Timeout else JevFallbackKind.Failure
            JevFallback.record(kind, "allocation", err.message)
          case Right(selected) =>
            JevFallback.recordSuccess("allocation", selected).as(JevAllocation.Allocated(selected))
        }
        .handleErrorWith { e =>
          // Belt and braces: even a defect outside the provider's own error
          // channel must land on the fallback, never escape to the caller.
          JevFallback.record(JevFallbackKind.Failure, "allocation", e.getClass.getSimpleName)
        }

  /** Build the batched question set: one `need_` + one `fit_` per entry. */
  private[decision] def questionsFor(catalog: List[CatalogEntry]): List[DecisionQuestion] =
    catalog.flatMap { entry =>
      List[DecisionQuestion](
        DecisionQuestion.Choice(
          id = s"need_${entry.name}",
          instructions =
            s"Does executing this task's primary purpose REQUIRE the '${entry.name}' capability package — " +
              "i.e., the task cannot be properly executed without the capability it provides? " +
              "Judge this one package independently, by the task text alone; ignore boilerplate.",
          criteria = NeedCriteria
        ),
        DecisionQuestion.Score(
          id = s"fit_${entry.name}",
          instructions =
            s"How well does the '${entry.name}' capability package fit this task's primary purpose, " +
              "by the task text alone?",
          criteria = List("poor", "partial", "good")
        )
      )
    }

  /**
   * Apply the R2 membership rule to a response.
   *
   * A `need_` question with NO usable answer is treated as "not needed" — the
   * conservative direction for a face whose output grants capabilities. The
   * miss is logged so a systematically missing answer is visible rather than
   * silently shrinking the set.
   */
  private[decision] def select(resp: DecisionResponse, catalog: List[CatalogEntry]): List[String] =
    catalog.filter { entry =>
      val qid = s"need_${entry.name}"
      resp.answers.get(qid) match
        case Some(a) =>
          DecisionAnswer.pYes(a) match
            case Some(p) => p >= DecisionAnswer.YesThreshold
            case None =>
              logger.warnSync(s"need question '$qid' answered without a probabilities[yes] read — treated as not required")
              false
        case None =>
          logger.warnSync(s"need question '$qid' has no answer in the response — treated as not required")
          false
    }.map(_.name)

object JevAllocator:

  /**
   * `state` cap. The transferred scorer caps the brief at 12000 characters,
   * and the measured brief never reached the provider's own limit under it.
   */
  val DefaultStateCapChars: Int = 12000

  def apply(provider: DecisionProvider[IO], timeoutMs: Long): JevAllocator =
    new JevAllocator(provider, timeoutMs)

  /**
   * Build an allocator from the live config, or `None` when the face is off.
   *
   * This is the single place the port implementation is obtained, so call
   * sites in `core` never construct a provider themselves.
   */
  def fromConfig(
    health: Option[DecisionHealthPort],
    configPath: Option[String] = None
  ): Option[JevAllocator] =
    for
      settings <- JevSettings.fromConfig(health, configPath)
      provider <- DecisionProviders.build(settings)
    yield new JevAllocator(provider, settings.timeoutMs)
