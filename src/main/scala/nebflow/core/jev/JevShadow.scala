package nebflow.core.jev

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Offline shadow evaluation of the allocation point (Face B① / P3).
 *
 * ==What a shadow is and why it is the right shape for this batch==
 * The node-creation allocation point is where the highest-risk change lands
 * (it decides which capabilities a node gets). Running it for real in this
 * batch would mean wiring a live provider call into the create path — which
 * cannot happen here, because (a) the refusal wording is a model-visible §16
 * face, and (b) the allocation point's real trigger needs the P4 trigger face.
 *
 * So the shadow computes the allocation decision and RECORDS the comparison
 * against the legacy decision, without changing what the engine does. The
 * result is evidence: how often the JeV set would differ from what the
 * dispatcher/author declared, which is exactly the number that has to be known
 * before the allocation point is armed for real.
 *
 * ==The ON/OFF switch is per-record, not per-process==
 * A shadow is only useful if both arms are computed for the SAME input, so the
 * evaluator always computes both and records whether they agree — the "ON"
 * column is the JeV verdict, the "OFF" column is the legacy one.
 */
final case class JevShadowRecord(
  nodeName: String,
  /** What the legacy path would decide (key present / absent). */
  legacySatisfied: Boolean,
  /** What the JeV duty semantics would decide. */
  jevSatisfied: Boolean,
  /** The set the allocation produced (empty when it did not run). */
  jevCapabilities: List[String],
  /** The set the caller declared (the legacy input). */
  declaredCapabilities: List[String],
  /** True when the two arms disagree — the signal worth counting. */
  divergent: Boolean,
  /** Why the JeV arm fell back, when it did. */
  fallbackReason: Option[String]
)

object JevShadowRecord:
  given io.circe.Encoder[JevShadowRecord] = io.circe.generic.semiauto.deriveEncoder

/**
 * The shadow evaluator.
 *
 * Pure with respect to the engine: it reads nothing the engine depends on,
 * returns nothing the engine consumes, and its only effect is an append to its
 * own evidence file. A failure anywhere in it is caught and logged — a shadow
 * must NEVER be able to break a create.
 */
final class JevShadowEvaluator(
  allocator: Option[JevAllocatorPort],
  enabled: Boolean
):

  private val logger = NebflowLogger.forName("nebflow.jev.shadow")

  private def shadowPath: os.Path = PathUtil.dataRoot / "logs" / "jev-shadow.jsonl"

  /**
   * Evaluate one create/edit decision in both arms.
   *
   * @param nodeName
   *   the node being created/edited (the subject of the record).
   * @param taskText
   *   the task text that would be the allocation input.
   * @param catalog
   *   the capability catalog the allocation would choose from.
   * @param declaredCapabilities
   *   what the caller declared (`plugins`).
   * @param keyProvided
   *   whether the caller provided the `plugins` key at all.
   */
  def evaluate(
    nodeName: String,
    taskText: String,
    catalog: List[CatalogEntry],
    declaredCapabilities: List[String],
    keyProvided: Boolean
  ): IO[Option[JevShadowRecord]] =
    if !enabled then IO.pure(None)
    else
      allocator match
        case None =>
          // The gate is on but no allocator could be built (bad provider id,
          // etc.). Recording the legacy arm alone would be misleading, so the
          // shadow simply does not run and says so once per call.
          IO(
            logger.infoSync(s"jev shadow skipped for '$nodeName' — no allocator available")
          ).as(None)
        case Some(a) =>
          a.allocate(taskText, catalog)
            .flatMap { allocation =>
              val (jevSet, fallback) = allocation match
                case JevAllocation.Allocated(caps) => (caps, None)
                case JevAllocation.FellBack(kind, reason) => (Nil, Some(s"${kind.wire}: $reason"))
              // Did the allocation ACTION run? This is the probe the #8
              // adjudication moved the duty onto, so it asks "was an attempt
              // made", NOT "did the attempt succeed". A fell-back allocation
              // was attempted and returned a verdict, and the dispatcher
              // default mount is its adjudicated outcome — so it counts as
              // run. Only the OFF kind means no action at all: there the face
              // was never applied, which is the genuinely absent path.
              // Reading a timeout/failure as "nobody allocated" would mark the
              // most common failure mode as a duty violation and would make
              // `AllocatedViaFallback` unreachable on this, its only path.
              val allocatedRan = allocation match
                case JevAllocation.Allocated(_) => true
                case JevAllocation.FellBack(JevFallbackKind.Off, _) => false
                case JevAllocation.FellBack(JevFallbackKind.Timeout, _) => true
                case JevAllocation.FellBack(JevFallbackKind.Failure, _) => true
              val duty = JevDeclarationDuty.evaluate(allocatedRan, jevSet, fallback)
              val rec = JevShadowRecord(
                nodeName = nodeName,
                legacySatisfied = JevLegacyDeclarationDuty.isSatisfied(keyProvided),
                jevSatisfied = JevDeclarationDuty.isSatisfied(duty),
                jevCapabilities = jevSet,
                declaredCapabilities = declaredCapabilities,
                // Two distinct criteria, deliberately kept apart:
                //   ① the two DUTY VERDICTS disagree (the #8 semantic shift —
                //      a node that declared nothing but WAS allocated);
                //   ② the produced SET differs from the declared one (what
                //      arming the point would actually change).
                // On a fallback the produced set is empty by construction (the
                // face produced nothing), so ② fires against a NON-empty
                // declaration. That is recorded rather than filtered:
                // `fallbackReason` is what tells a degraded run apart from a
                // genuine replacement, so the divergence count can be read
                // both ways without re-running the shadow.
                divergent = JevLegacyDeclarationDuty.isSatisfied(keyProvided) != JevDeclarationDuty.isSatisfied(duty) ||
                  jevSet.sorted != declaredCapabilities.sorted,
                fallbackReason = fallback
              )
              append(rec).as(Some(rec): Option[JevShadowRecord])
            }
            .handleErrorWith { e =>
              // A shadow defect must not break a create.
              IO(logger.warnSync(s"jev shadow failed for '$nodeName' (${e.getClass.getSimpleName}) — ignored")).as(None)
            }

  /** Append one record; a failed append is logged and dropped, never raised. */
  private def append(rec: JevShadowRecord): IO[Unit] =
    IO.blocking {
      os.write.append(shadowPath, rec.asJson.noSpaces + "\n", createFolders = true)
    }.void.handleErrorWith(e =>
      IO(logger.warnSync(s"jev shadow append failed (${rec.nodeName}): ${e.getClass.getSimpleName}"))
    )

  /** Read back the records (evidence for the offline evaluation report). */
  def readAll: IO[List[Json]] =
    IO.blocking {
      if !os.exists(shadowPath) then Nil
      else
        os.read(shadowPath)
          .linesIterator
          .filter(_.trim.nonEmpty)
          .flatMap(l => io.circe.parser.parse(l).toOption)
          .toList
    }

/**
 * Declarative stub for the REAL wiring (Face B① / P3).
 *
 * 🔴 This is deliberately a stub, and deliberately declarative.
 *
 * Wiring the allocation point for real means two things this batch cannot do:
 *   ① the refusal path speaks through a model-visible error string (§16 gate —
 *      the author reviews such text word by word before it lands);
 *   ② the trigger face it hangs off is P4 (kernel/delegate allocation), which
 *      this batch explicitly does not build.
 *
 * Rather than leave an implicit gap, the stub declares the exact
 * preconditions that must hold, so a later batch arms it by satisfying a
 * written list instead of re-deriving the constraints. The stub NEVER changes
 * the caller's behaviour: every method is an observation only.
 */
object JevAllocationStub:

  private val logger = NebflowLogger.forName("nebflow.jev.stub")

  /**
   * The preconditions a later batch must satisfy before this point is armed.
   *
   * Kept as data so it can be asserted in a spec and rendered in the report —
   * a comment would drift silently, a list cannot.
   */
  val ArmingPreconditions: List[String] = List(
    "§16: the declaration-gate refusal wording (model-visible) has had the author's word-by-word review",
    "P4: the kernel/delegate trigger face exists to carry the allocation at trigger time",
    "P4: the blocking-delegate budget (R6 timeout input) is in place so a blocking call is respect-judged",
    "The shadow's divergence count has been read and the replacement rate accepted on evidence"
  )

  /** True while the point is not armed. Always true in this batch. */
  def isShadowOnly: Boolean = true

  /**
   * Observe (never change) what a real arming WOULD do for this input.
   *
   * The returned value is for logging/evidence only; callers must not branch
   * on it. Its signature deliberately returns `Unit`-like advice rather than a
   * capability set, so it cannot be mistaken for an authority.
   */
  def observe(nodeName: String, wouldAllocate: Boolean): IO[Unit] =
    IO {
      if wouldAllocate then
        logger.infoSync(s"jev allocation point is SHADOW-ONLY for '$nodeName' — the declared set is used unchanged")
      else
        logger.infoSync(s"jev allocation point not applicable for '$nodeName' (face off)")
    }.void
