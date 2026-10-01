package nebflow.llm.decision

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*

/**
 * Decision-provider face (JeV integration, Face A / P1-A1).
 *
 * ==Why an independent file and NOT `ProviderAdapter` (P0 #1, adjudicated)==
 * `nebflow.llm.ProviderAdapter` (`llm/adapter.scala:49`) is a CHAT-shaped trait:
 * `sendMessage` / `sendMessageStream` over `SendMessageParams` (messages + tools
 * + thinking). The JeV wire protocol is a STRUCTURED QUESTION-ANSWER shape:
 * `POST /v1/systemone` with exactly three top-level fields `state` / `model` /
 * `questions`, answering `answers.<qid>` per question. The two are not
 * isomorphic at the type level, so folding one into the other is a type-level
 * mismatch, not a style preference. The official evidence (four pages, one
 * endpoint, a 60-entry sitemap with no chat face) refutes the "JeV offers a
 * chat compatibility layer" precondition that extending `LlmProtocol` would
 * need. => new file, `LlmProtocol` and the `providers` validation whitelist
 * untouched (zero behaviour drift on the model-selection chain).
 *
 * ==Shape precedent==
 * `llm/SearchProviderResolver.swift`'s standalone search leg (`:367`
 * `executeStandaloneSearch`): direct sttp call + its own credential + a fixed
 * read timeout + HTTP-status error classification + an INDEPENDENT health
 * channel. This file mirrors that shape deliberately.
 *
 * ==Canonical internal question form (P0 #2, adjudicated)==
 * The INTERNAL representation is the JeV normative one and nothing else:
 *   - `Choice.criteria` = a MAP `option -> description` (value may be absent);
 *   - `Score.criteria`  = an ORDERED array (low -> high).
 * There is deliberately NO "array -> map" conversion position: the premise
 * that JeV takes a string array was refuted by the on-disk evidence
 * (`score_one.mjs:40-41` builds a dict; `selfcheck-request.json` is a dict with
 * a `null` value; the official `api` / `primitives` / `primitives/choice` pages
 * all say "as a map"). On the JeV side an array is legal only as a Score
 * `criteria` or as an ENTRY VALUE — never as the choice container.
 */

/** Question type discriminator of the JeV wire protocol. */
enum DecisionQuestionType:
  case Score, Choice, Noul

  /** Wire spelling — used both in the request question object and in the
    * answer object's `type` field. */
  def wire: String = this match
    case Score => "score"
    case Choice => "choice"
    case Noul => "noul"

object DecisionQuestionType:
  def fromWire(s: String): Option[DecisionQuestionType] = s match
    case "score" => Some(Score)
    case "choice" => Some(Choice)
    case "noul" => Some(Noul)
    case _ => None

/** One question of a decision request (canonical form). */
sealed trait DecisionQuestion extends Product with Serializable:
  def id: String
  def instructions: String
  def qtype: DecisionQuestionType

object DecisionQuestion:

  /**
   * A choice question: pick exactly one label.
   *
   * `criteria` is a MAP — `None` as the value is the "label with no
   * description" case (the on-disk `selfcheck-request.json` shows a real
   * `"calm": null` entry answered HTTP 200, so it is a legal JeV input).
   */
  final case class Choice(
    id: String,
    instructions: String,
    criteria: Map[String, Option[String]]
  ) extends DecisionQuestion:
    def qtype: DecisionQuestionType = DecisionQuestionType.Choice

  /** A score question: pick one position on an ORDERED list (low -> high). */
  final case class Score(
    id: String,
    instructions: String,
    criteria: List[String]
  ) extends DecisionQuestion:
    def qtype: DecisionQuestionType = DecisionQuestionType.Score

  /**
   * A noul question: one yes/no proposition answered on a 0..1 scale.
   *
   * The answer carries a scalar and NOTHING else — there is no separate
   * confidence for a noul (official wording), and no `probabilities` map.
   */
  final case class Noul(
    id: String,
    instructions: String
  ) extends DecisionQuestion:
    def qtype: DecisionQuestionType = DecisionQuestionType.Noul

/** One answer of a decision response (canonical form). */
sealed trait DecisionAnswer extends Product with Serializable:
  def qtype: DecisionQuestionType

object DecisionAnswer:

  /** Score answer. `legend` echoes the criteria positions the model saw. */
  final case class Score(
    score: Int,
    confidence: Option[Double],
    legend: Map[String, String],
    probabilities: Map[String, Double]
  ) extends DecisionAnswer:
    def qtype: DecisionQuestionType = DecisionQuestionType.Score

  /** Choice answer. `probabilities` is the per-label distribution. */
  final case class Choice(
    choice: String,
    confidence: Option[Double],
    probabilities: Map[String, Double]
  ) extends DecisionAnswer:
    def qtype: DecisionQuestionType = DecisionQuestionType.Choice

  /**
   * Noul answer: ONE scalar on 0..1.
   *
   * Deliberately carries no `confidence` and no `probabilities` field — the
   * official wording is "There is no separate confidence value for a Noul",
   * and the on-disk `selfcheck-response.json` shows the noul answer object
   * holding exactly `type` + `noul`. Because the field does not exist on this
   * variant, no code path CAN read it by mistake.
   */
  final case class Noul(noul: Double) extends DecisionAnswer:
    def qtype: DecisionQuestionType = DecisionQuestionType.Noul

  /**
   * P(yes) of a CHOICE-typed `{yes, no}` question — `probabilities["yes"]`.
   *
   * This is the methodology-mainline read (P0 #7 adjudicated): the 21x20 round
   * evidence answered `need_<p>` with `type: "choice"` and
   * `criteria: {yes, no}`, so P(yes) comes from the CHOICE answer's
   * distribution — never from a noul. Returns `None` for every non-choice
   * answer by construction, so "read the wrong key" is not expressible.
   */
  def pYes(answer: DecisionAnswer): Option[Double] = answer match
    case c: Choice => c.probabilities.get("yes")
    case _ => None

  /**
   * P(yes) of a NOUL-typed question — the scalar itself (P0 #7(b)).
   *
   * Separate entry point on purpose: the two question types have DIFFERENT
   * field reads, and collapsing them into one "pYes" is exactly the mistake
   * the adjudication registered as a form error.
   */
  def noulYes(answer: DecisionAnswer): Option[Double] = answer match
    case n: Noul => Some(n.noul)
    case _ => None

  /**
   * The decision threshold, taken from the official wording ("Use 0.5 when
   * yes and no are equally easy to act on") — a given value, not an
   * arbitrary one.
   */
  val YesThreshold: Double = 0.5

/** Token usage of one decision call (JeV returns `input_tokens`/`output_tokens`). */
final case class DecisionUsage(inputTokens: Long, outputTokens: Long)

/** One decision response. `model` echoes the serving model (e.g. `jev-1.13.0`). */
final case class DecisionResponse(
  model: Option[String],
  answers: Map[String, DecisionAnswer],
  usage: Option[DecisionUsage]
)

/**
 * A decision request.
 *
 * `state` is the model-visible task text; `questions` the typed proposition
 * set. `model` is a provider-level default applied when the config does not
 * name one.
 */
final case class DecisionRequest(
  state: String,
  questions: List[DecisionQuestion],
  model: Option[String] = None
)

/**
 * Classified failure of a decision call.
 *
 * Mirrors `SearchProviderResolver.classifyStandaloneError` (401/403 auth,
 * 429 quota/rate, 5xx server, timeout explicit, else transport) so the
 * fail-open fallback in `core.jev` can branch on a typed reason instead of
 * string-matching. Every variant names the provider message it came from —
 * the upstream diagnostic is never swallowed.
 */
enum DecisionError:
  case Auth(detail: String)
  case Quota(detail: String)
  case Server(detail: String)
  case Timeout(detail: String)
  case Malformed(detail: String)
  case Transport(detail: String)

  /**
   * Is this failure a deadline expiry?
   *
   * A typed predicate rather than a substring search on [[message]]: the
   * allocator branches on it to pick its fallback kind, and matching prose is
   * the kind of coupling that breaks the moment a detail string changes.
   */
  def isTimeout: Boolean = this match
    case Timeout(_) => true
    case _ => false

  /** Diagnostic text (credential-free by construction — see the providers). */
  def message: String = this match
    case Auth(d) => s"decision API auth failed: $d"
    case Quota(d) => s"decision API quota/rate limited: $d"
    case Server(d) => s"decision API server error: $d"
    case Timeout(d) => s"decision API timeout: $d"
    case Malformed(d) => s"decision API malformed response: $d"
    case Transport(d) => s"decision API transport error: $d"

/**
 * The decision-provider trait (P0 #1: independent of `ProviderAdapter`).
 *
 * One method carrying the question types, rather than three (`score` /
 * `choice` / `noul`): the wire call is ONE batched request whatever the mix,
 * so a per-type method set would either force three round trips or lie about
 * the transport. The type discriminator travels on each question.
 */
trait DecisionProvider[F[_]]:
  /** Stable provider id, used in diagnostics and the health channel. */
  def id: String

  /** Execute one batched decision request. */
  def predict(req: DecisionRequest): F[Either[DecisionError, DecisionResponse]]

/**
 * Provider ids and shared constants of the decision face.
 *
 * `timeoutMs` axis warning (P0 #6): this timeout is the ALLOCATION CALL
 * deadline — how long the caller waits for a decision before falling back.
 * It is a DIFFERENT axis from the R6 tool-authorization budget
 * (`Delegate.timeout` -> `Defaults.declaredToolTimeoutMs`), which is the
 * blocking-tool judgement window. Two different questions; do not conflate.
 */
object DecisionProvider:

  /** Official hosted JeV. */
  val TypesafeJev: String = "typesafe-jev"

  /** Self-hosted open-source LAYA (`laya-serve`). */
  val Laya: String = "laya"

  val AllIds: List[String] = List(TypesafeJev, Laya)

  /** Official endpoint (the only endpoint every JeV model shares). */
  val DefaultTypesafeEndpoint: String = "https://api.typesafe.ai/v1/systemone"

  /** Self-hosted loopback default. */
  val DefaultLayaEndpoint: String = "http://127.0.0.1:8080/predict"

  val DefaultModel: String = "jev-latest"

  /**
   * Allocation-call deadline default. Mirrors the standalone-search leg's
   * fixed timeout (`StandaloneSearchConfig.TimeoutMs = 15_000`) rather than
   * inventing a number: the measured decision latency median is ~0.9s, so 15s
   * is a generous cap that still bounds dispatch latency.
   */
  val DefaultTimeoutMs: Long = 15_000L

  /** Capacity threshold above which LAYA precision degrades (card §A.2.4 ②). */
  val LayaChoiceHighBaseThreshold: Int = 20

  /** Read a JSON number as a long, tolerating an integral float encoding
    * (`2.0` must not be discarded as "not a number"). */
  private[decision] def uintOf(j: Json): Option[Long] =
    j.asNumber.flatMap { n =>
      n.toLong.orElse {
        val d = n.toDouble
        if d.isWhole && !d.isNaN && d >= Long.MinValue.toDouble && d <= Long.MaxValue.toDouble then Some(d.toLong)
        else None
      }
    }

  private[decision] def objOf(j: Json): Option[JsonObject] = j.asObject
end DecisionProvider
