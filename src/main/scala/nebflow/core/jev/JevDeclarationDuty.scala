package nebflow.core.jev

/**
 * Declaration-duty semantics (JeV integration Face B① / P3, per the #8
 * adjudication).
 *
 * ==The semantic change, stated once==
 * Today the duty rides on a PARAMETER: `NodeEdit` refuses a create whose
 * `plugins` key is absent (`NODE_PLUGINS_UNDECLARED`). The adjudication moves
 * the obligation to the ALLOCATING PARTY and re-points the gate from "the key
 * exists" to "an allocation ACTION happened".
 *
 * The reason is a mismatch between the risk and the probe. Once allocation
 * authority is up-collected, "the keys is missing" stops being the risk
 * signal — the risk becomes "nobody allocated at all". A key-presence check
 * cannot see that, because an absent allocation and an allocated-empty set
 * both surface as "no names".
 *
 * ==Why this file contains only the DECISION, not the refusal==
 * The refusal is expressed through a model-visible error string
 * (`NodeEditTool.scala:786`), and ALL model-visible text is gated on the
 * author's word-by-word review (§16). So this batch lands the semantics and
 * leaves the wording — and therefore the wiring — to the §16 package. This
 * module is the part that can land without crossing that line.
 */
enum JevDeclarationDuty:

  /**
   * The allocating party ran and produced a set (possibly empty).
   *
   * 🔴 An EMPTY set here is a SATISFIED duty, not a gap. `plugins=[]` is
   * already the explicit "no capability face" value, and both
   * `prepareNodePlugins:440` and `dispatchFaceCheck:837` treat the empty set
   * as a legal terminal state with zero cost.
   */
  case Allocated(empty: Boolean)

  /** Nobody allocated: the allocation path itself was absent. */
  case AllocationMissing

  /** The allocation failed and fell back; the dispatcher default mount applies. */
  case AllocatedViaFallback(reason: String)

object JevDeclarationDuty:

  /**
   * Decide the duty from the allocation outcome and whether an allocation was
   * even attempted.
   *
   * @param allocated
   *   `true` when the allocation path ran to a verdict (including an empty
   *   one); `false` when no allocation action occurred at all.
   * @param fallbackReason
   *   the classified reason when the allocation fell back; `None` when it
   *   completed normally.
   */
  def evaluate(
    allocated: Boolean,
    capabilities: List[String],
    fallbackReason: Option[String]
  ): JevDeclarationDuty =
    if !allocated then JevDeclarationDuty.AllocationMissing
    else
      fallbackReason match
        case Some(reason) => JevDeclarationDuty.AllocatedViaFallback(reason)
        case None => JevDeclarationDuty.Allocated(empty = capabilities.isEmpty)

  /**
   * Does this duty state count as satisfied?
   *
   * Both allocated shapes are satisfied: an empty allocation is explicit
   * emptiness, and a fallback still means the allocation action happened (the
   * dispatcher default mount is the adjudicated fail-open outcome, not a
   * missing declaration). Only an ABSENT allocation path fails the duty —
   * which is exactly the risk the moved obligation is meant to catch.
   */
  def isSatisfied(duty: JevDeclarationDuty): Boolean = duty match
    case JevDeclarationDuty.AllocationMissing => false
    case _ => true

/**
 * The pre-JeV (legacy) duty semantic, kept so the shadow can compare them.
 *
 * Kept as a named function rather than an inline condition because its whole
 * purpose is to be DIFFED against [[JevDeclarationDuty]] — an inline `if`
 * would make the comparison invisible.
 */
object JevLegacyDeclarationDuty:

  /**
   * The legacy rule: the key must be present on create.
   *
   * @param keyProvided
   *   whether the caller supplied the `plugins` key at all (a JSON null does
   *   not count — that is the "omitted" form).
   */
  def isSatisfied(keyProvided: Boolean): Boolean = keyProvided
