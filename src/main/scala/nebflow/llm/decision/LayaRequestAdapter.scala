package nebflow.llm.decision

import nebflow.shared.NebflowLogger

/**
 * LAYA-side adaptation layer (Face A / P1-A1, P0 #2 adjudicated).
 *
 * The canonical internal form IS the JeV normative form, so the common body
 * needs no conversion. What remains are the THREE LAYA-specific differences
 * the adjudication listed — and each is handled here rather than by inventing
 * an "array -> map" conversion position (that premise was refuted; an array is
 * legal on the JeV side only as a Score criteria or as an entry VALUE).
 *
 *   ① **null label** — official wording: "A null or duplicate `choice` label
 *      ... [is] refused". The canonical form permits an absent description
 *      (the on-disk JeV sample has a real `"calm": null` entry), so a null
 *      value is DOWNGRADED for LAYA: the entry keeps its key and carries an
 *      empty description instead of a JSON null.
 *   ② **duplicate label** — also refused by LAYA. Structurally impossible on
 *      canonical input: criteria is a `Map`, so keys are unique by
 *      construction. The invariant is asserted, not re-checked defensively.
 *   ③ **high-cardinality choice** — LAYA precision degrades above ~20 options
 *      (0.425 vs JeV 0.870 at high cardinality), so a choice question over the
 *      threshold produces a WARNING naming the question id and the count. A
 *      warning only: refusing or re-shaping the question is a product decision,
 *      and silently dropping options would corrupt the answer distribution.
 */
object LayaRequestAdapter:

  private val logger = NebflowLogger.forName("nebflow.llm.decision.laya")

  /**
   * Render the LAYA request body.
   *
   * `min_confidence`, when configured, is passed through as an abstention
   * floor so a low-confidence answer comes back as an abstention rather than
   * as a confident-looking label.
   */
  def requestBody(req: DecisionRequest, model: String, minConfidence: Option[Double]): io.circe.Json =
    val adapted = DecisionRequest(
      state = req.state,
      questions = req.questions.map(adaptQuestion),
      model = Some(model)
    )
    val base = JevWireCodec.requestBody(adapted, model)
    minConfidence match
      case None => base
      case Some(mc) =>
        // `Json.fromDouble` is `Option` because NaN/Infinity have no JSON
        // form. Drop the key in that case rather than emitting `null`, which
        // would read as an explicit "no floor".
        io.circe.Json
          .fromDouble(mc)
          .map(j => base.asObject.map(o => io.circe.Json.fromJsonObject(o.add("min_confidence", j))).getOrElse(base))
          .getOrElse(base)

  /** Apply the per-question LAYA adaptations; emits the capacity warning. */
  private[decision] def adaptQuestion(q: DecisionQuestion): DecisionQuestion =
    q match
      case c: DecisionQuestion.Choice =>
        if c.criteria.size > DecisionProvider.LayaChoiceHighBaseThreshold then
          logger.warnSync(
            s"choice question '${c.id}' has ${c.criteria.size} options " +
              s"(> ${DecisionProvider.LayaChoiceHighBaseThreshold}); LAYA precision degrades at high cardinality"
          )
        // ① null -> empty description. The key is preserved (dropping it would
        //    silently shrink the option set and skew the distribution).
        DecisionQuestion.Choice(
          id = c.id,
          instructions = c.instructions,
          criteria = c.criteria.map { case (k, v) => k -> Some(v.getOrElse("")) }
        )
      // ② Score criteria is already an ordered array in canonical form; LAYA's
      //    documented shape for score is exactly that array. Nothing to adapt.
      // ③ Noul carries no criteria on either side.
      case other => other

  /**
   * Assert the canonical-form invariant the LAYA adapter relies on.
   *
   * Kept as an explicit named predicate so the reasoning is testable instead
   * of living only in a comment: a `Map` cannot hold duplicate keys, which is
   * what makes the "duplicate label" refusal unreachable from this path.
   */
  private[decision] def hasNoDuplicateLabels(q: DecisionQuestion): Boolean =
    q match
      case c: DecisionQuestion.Choice => c.criteria.keySet.size == c.criteria.size
      case _ => true
