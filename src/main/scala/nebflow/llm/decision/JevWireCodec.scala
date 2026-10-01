package nebflow.llm.decision

import io.circe.{Json, JsonObject}
import io.circe.syntax.*

/**
 * JeV wire codec (Face A / P1-A1 adaptation layer).
 *
 * Renders the canonical internal question form into the JeV request body and
 * parses the JeV response body back into canonical answers. Kept separate from
 * the providers so the wire contract is pinned by a unit test WITHOUT any
 * network: both `TypesafeJevProvider` and `LayaProvider` go through it, and a
 * shape regression fails the spec rather than a live call.
 *
 * ==Wire contract (pinned from on-disk evidence)==
 * Request (`selfcheck-request.json`, HTTP 200 on the real endpoint):
 * {{{
 * { "state": "...",
 *   "questions": { "q1": { "type": "noul",   "instructions": "..." },
 *                  "q2": { "type": "choice", "instructions": "...",
 *                          "criteria": { "calm": null, "angry": "hostile" } },
 *                  "q3": { "type": "score",  "instructions": "...",
 *                          "criteria": ["can wait", "today"] } } }
 * }}}
 * Response (`selfcheck-response.json`, HTTP 200, `model: jev-1.13.0`):
 * {{{
 * { "model": "...",
 *   "answers": { "q2": { "type": "choice", "choice": "frustrated",
 *                        "confidence": 0.98,
 *                        "probabilities": { "calm": 0, ... } },
 *                "q3": { "type": "score", "score": 2, "confidence": 1,
 *                        "legend": { "0": "can wait", ... },
 *                        "probabilities": { "0": 0, ... } },
 *                "q1": { "type": "noul", "noul": 0.98 } },
 *   "usage": { "input_tokens": 394, "output_tokens": 77 } }
 * }}}
 *
 * The `model` top-level field of the request is added by the provider from its
 * config (the captured sample has no `model` key because the local test server
 * held the key/model itself).
 */
object JevWireCodec:

  /** Render the request body exactly as the endpoint expects it. */
  def requestBody(req: DecisionRequest, model: String): Json =
    val questions = Json.fromFields(
      req.questions.map { q =>
        q.id -> questionJson(q)
      }
    )
    val fields = List[Option[(String, Json)]](
      Some("state" -> req.state.asJson),
      Some("model" -> model.asJson),
      Some("questions" -> questions)
    )
    Json.fromFields(fields.flatten)

  /** One question object; omits `criteria` for noul (the endpoint has none). */
  private def questionJson(q: DecisionQuestion): Json =
    val base = JsonObject(
      "type" -> q.qtype.wire.asJson,
      "instructions" -> q.instructions.asJson
    )
    val withCriteria = q match
      case c: DecisionQuestion.Choice =>
        // Canonical form IS a map; a null-valued entry is rendered as JSON
        // null, which is the shape the endpoint answers 200 on.
        base.add(
          "criteria",
          Json.fromFields(c.criteria.map { case (k, v) => k -> v.map(_.asJson).getOrElse(Json.Null) })
        )
      case s: DecisionQuestion.Score =>
        base.add("criteria", s.criteria.asJson)
      case _: DecisionQuestion.Noul => base
    Json.fromJsonObject(withCriteria)

  /**
   * Parse a response body into canonical answers.
   *
   * Returns `Left(Malformed)` with a specific reason when the envelope or a
   * single answer is unusable — a value read from the wrong place must fail
   * loudly here rather than travel on as a silent empty answer.
   */
  def parseResponse(body: String): Either[DecisionError, DecisionResponse] =
    io.circe.parser.parse(body) match
      case Left(err) =>
        Left(DecisionError.Malformed(s"response is not JSON (${err.message})"))
      case Right(json) =>
        val c = json.hcursor
        val model = c.downField("model").as[String].toOption
        val answersJson = c.downField("answers").focus.flatMap(_.asObject)
        answersJson match
          case None => Left(DecisionError.Malformed("response has no 'answers' object"))
          case Some(obj) =>
            val parsed = obj.toList.map { case (qid, aJson) => qid -> parseAnswer(aJson) }
            parsed.collectFirst { case (qid, Left(e)) => qid -> e } match
              case Some((qid, err)) =>
                Left(DecisionError.Malformed(s"answer '$qid': ${err.message}"))
              case None =>
                val answers = parsed.collect { case (qid, Right(a)) => qid -> a }.toMap
                Right(DecisionResponse(model, answers, parseUsage(json)))

  private def parseAnswer(j: Json): Either[DecisionError, DecisionAnswer] =
    val c = j.hcursor
    c.downField("type").as[String].toOption.flatMap(DecisionQuestionType.fromWire) match
      case None =>
        Left(DecisionError.Malformed("answer has no recognised 'type'"))
      case Some(DecisionQuestionType.Choice) =>
        c.downField("choice").as[String].toOption match
          case None => Left(DecisionError.Malformed("choice answer has no 'choice' label"))
          case Some(label) =>
            Right(
              DecisionAnswer.Choice(
                choice = label,
                confidence = c.downField("confidence").as[Double].toOption,
                probabilities = doubleMap(c.downField("probabilities").focus)
              )
            )
      case Some(DecisionQuestionType.Score) =>
        // The score position is lenient about representation: JeV echoes an
        // integer in the captured sample, but a JSON float of the same value
        // must not be discarded as malformed.
        c.downField("score").focus.flatMap(DecisionProvider.uintOf) match
          case None => Left(DecisionError.Malformed("score answer has no numeric 'score'"))
          case Some(pos) =>
            Right(
              DecisionAnswer.Score(
                score = pos.toInt,
                confidence = c.downField("confidence").as[Double].toOption,
                legend = c.downField("legend").focus.flatMap(_.asObject).map(stringMap).getOrElse(Map.empty),
                probabilities = doubleMap(c.downField("probabilities").focus)
              )
            )
      case Some(DecisionQuestionType.Noul) =>
        c.downField("noul").as[Double].toOption match
          case None => Left(DecisionError.Malformed("noul answer has no numeric 'noul'"))
          case Some(v) => Right(DecisionAnswer.Noul(v))

  private def parseUsage(json: Json): Option[DecisionUsage] =
    val c = json.hcursor.downField("usage")
    for
      i <- c.downField("input_tokens").focus.flatMap(DecisionProvider.uintOf)
      o <- c.downField("output_tokens").focus.flatMap(DecisionProvider.uintOf)
    yield DecisionUsage(i, o)

  private def doubleMap(j: Option[Json]): Map[String, Double] =
    j.flatMap(_.asObject)
      .map(_.toMap.flatMap { case (k, v) => v.asNumber.map(n => k -> n.toDouble) })
      .getOrElse(Map.empty)

  private def stringMap(o: JsonObject): Map[String, String] =
    o.toMap.flatMap { case (k, v) => v.asString.map(k -> _) }

end JevWireCodec
