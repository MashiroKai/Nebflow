package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{
  AgentModelConfig,
  ContentBlock,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  ToolCall,
  TokenUsage
}

/**
 * OpenAI wire-format shim for the EXISTING inbound chat face (batch B2,
 * "立项（最小形态）"; author ruling 2026-09-24).
 *
 * Scope = exactly three wire differences between the in-tree inbound face
 * (`ChatRoutes.routes`, `POST /api/v1/chat`) and the OpenAI shape:
 *   1. path name — the alias lives in the same `ChatRoutes` (see `routes`);
 *   2. request body — OpenAI has no `sessionId`/`agentId`;
 *   3. response body — in-tree `LlmResponse` vs OpenAI `choices[]`.
 * Nothing else: no `/v1/models`, no SSE semantics, no second auth face, no new
 * dependency. Everything here is pure Json ⇄ case-class conversion so it is
 * unit-testable without a live gateway.
 *
 * Disciple applied throughout — **faithful or refuse, never silently drop**.
 * A field that cannot be carried into the in-tree request is refused with an
 * explicit `400` naming the offending parameter, instead of being ignored;
 * a field whose omission cannot change the response shape the caller parses
 * (pure sampling knobs) is accepted and declared not-carried (see
 * `ignoredFields`). Unknown fields are ignored for forward compatibility
 * (declared).
 *
 * 🔴 Identity (`session_id` / `agent_id`) is the batch's load-bearing design
 * point: the in-tree face REQUIRES both, OpenAI's format has neither, and the
 * shim is forbidden from inventing a placeholder identity (same-family
 * prohibition as `NodeTools.scala:3512-3521`). The conservative form is
 * implemented: both must be supplied by the caller as Nebflow extension fields
 * on the request body; absent/empty ⇒ explicit 400. See the batch report's
 * "sessionId/agentId disposition" section for the alternatives and their cost.
 */
object OpenAiChatShim:

  /**
   * A refused request. `status` is 400 for every wire-shape refusal (this face
   * introduces no new status vocabulary); `param` mirrors the OpenAI error
   * envelope's field pointer, `code` its machine-readable tag.
   */
  final case class Rejected(
    param: Option[String],
    code: String,
    message: String
  ):
    def status: Int = 400

  /**
   * Pure sampling knobs: accepted and deliberately NOT carried into the
   * in-tree request. This set is closed by a rule, not by taste — every member
   * is a field whose omission cannot change the SHAPE of the response the
   * caller parses (`messages[]` role/content/image). Anything that could
   * change that shape (stream / n>1 / response_format / logprobs / tools /
   * tool round-trip) is refused instead, never dropped.
   *
   * `stream` and `n` appear here because they are accepted only at their
   * no-op values (`stream:false`, `n:1` = exactly this face's behaviour);
   * `guardUnsupported` refuses every other value. Membership therefore reads
   * as "when accepted, this field was not carried" — which is true.
   */
  val ignoredFields: Set[String] = Set(
    "temperature",
    "top_p",
    "max_tokens",
    "max_completion_tokens",
    "stop",
    "presence_penalty",
    "frequency_penalty",
    "seed",
    "user",
    "metadata",
    "stream_options",
    "service_tier",
    "parallel_tool_calls",
    "store",
    "stream",
    "n"
  )

  // ── request: OpenAI body → in-tree LlmRequest ────────────────────────────

  /**
   * Convert an OpenAI `chat.completions` request body into the in-tree
   * [[LlmRequest]]. Left = refused (explicit, actionable, no silent drop).
   */
  def toLlmRequest(body: Json): Either[Rejected, LlmRequest] =
    val cur = body.hcursor
    for
      modelRef <- readModel(cur)
      messages <- readMessages(cur)
      sessionId <- readExtension(cur, "session_id")
      agentId <- readExtension(cur, "agent_id")
      _ <- guardUnsupported(cur)
    yield LlmRequest(
      messages = messages,
      sessionId = sessionId,
      agentId = agentId,
      tools = None,
      // `model` IS carried — as the request's own agent-model config, which is
      // the in-tree carrier for "which model should serve this request"
      // (`LlmRequest.agentModel`, protocol.scala:109 → consumed at
      // `registry.getCandidatesForAgent`, registry.scala:189, reached from
      // interface.scala:461 / :568). Setting it here means a caller-supplied
      // model actually decides the candidate chain instead of being dropped
      // after validation. With no `model` on the wire this field stays `None`
      // and the global default preset serves the request, exactly as before.
      agentModel = Some(AgentModelConfig(preferred = Some(modelRef), fallbacks = Nil))
    )

    end for

  end toLlmRequest

  /**
   * `model` is required by the OpenAI contract and is **carried**, not ignored:
   * [[toLlmRequest]] puts it into `LlmRequest.agentModel`, the in-tree carrier
   * for "which model serves this request" (the same field the in-tree agents
   * populate — `AgentCore.scala:946`), so the registry builds the candidate
   * chain from it (`registry.scala:189`) instead of the global preset. The form
   * accepted is the in-tree model ref `<provider>/<model>`; a bare OpenAI model
   * name cannot be resolved without inventing a mapping ⇒ refused (declared, see
   * the batch report's field table), never silently substituted.
   */
  private def readModel(cur: io.circe.ACursor): Either[Rejected, String] =
    cur.downField("model").as[String] match
      case Right(m) if m.trim.nonEmpty =>
        val t = m.trim
        val parts = t.split('/')
        if parts.length == 2 && parts.forall(_.nonEmpty) then Right(t)
        else Left(modelFormError)
      case _ => Left(modelFormError)

  private val modelFormError = Rejected(
    param = Some("model"),
    code = "invalid_model",
    message = "model must be a non-empty string in the form <provider>/<model> " +
      "(this face resolves models against the gateway's own provider catalog)"
  )

  /**
   * `session_id` / `agent_id`: Nebflow extensions to the OpenAI body (OpenAI's
   * format carries no session/agent identity). Required — see the object doc.
   */
  private def readExtension(
    cur: io.circe.ACursor,
    field: String
  ): Either[Rejected, String] =
    cur.downField(field).as[Option[String]] match
      case Right(Some(v)) if v.trim.nonEmpty => Right(v.trim)
      case _ =>
        Left(
          Rejected(
            param = Some(field),
            code = "missing_identity",
            message = s"$field is required: this face carries no session identity of its own, " +
              "and inventing a placeholder identity is not allowed. " +
              "Send it as a top-level field alongside the OpenAI body fields."
          )
        )

  /**
   * Fields this face refuses: each one would either change the response shape
   * or require the full tool round-trip, so silently dropping it would change
   * the caller's contract.
   */
  private def guardUnsupported(cur: io.circe.ACursor): Either[Rejected, Unit] =
    val checks: List[(String, String, String)] = List(
      (
        "stream",
        "unsupported_stream",
        "stream=true is not supported by this face; use POST /api/v1/chat/stream"
      ),
      (
        "response_format",
        "unsupported_response_format",
        "response_format is not supported by this face"
      ),
      ("logprobs", "unsupported_logprobs", "logprobs is not supported by this face"),
      (
        "tools",
        "unsupported_tools",
        "tools are not supported by this face; use POST /api/v1/chat"
      ),
      (
        "tool_choice",
        "unsupported_tools",
        "tool_choice is not supported by this face; use POST /api/v1/chat"
      ),
      (
        "functions",
        "unsupported_tools",
        "the legacy functions field is not supported by this face; use POST /api/v1/chat"
      ),
      (
        "function_call",
        "unsupported_tools",
        "the legacy function_call field is not supported by this face; use POST /api/v1/chat"
      )
    )
    checks.collectFirst {
      case (field, code, msg) if isMeaningful(cur.downField(field).focus) =>
        Rejected(Some(field), code, msg)
    } match
      case Some(r) => Left(r)
      case None =>
        // n: one choice is all this face can produce.
        cur.downField("n").as[Option[Int]] match
          case Right(Some(n)) if n != 1 =>
            Left(
              Rejected(
                Some("n"),
                "unsupported_n",
                "n must be 1: this face returns a single choice"
              )
            )
          case _ => Right(())

    end match

  end guardUnsupported

  /**
   * `present` includes `false`, `""` and `{}` — a caller that sends
   * `stream: false` is stating a preference this face cannot honor as written
   * for the *streaming* fields, but `false` on `stream` means "not streaming",
   * which is exactly this face. Handled by the per-field test below.
   */
  private def isMeaningful(focus: Option[Json]): Boolean =
    focus.exists {
      case Json.Null => false
      case j if j.isBoolean => j.asBoolean.contains(true)
      case j if j.isArray => j.asArray.exists(_.nonEmpty)
      case j if j.isObject => j.asObject.exists(_.nonEmpty)
      case j if j.isString => j.asString.exists(_.nonEmpty)
      case _ => true
    }

  /**
   * `messages` → in-tree `List[Message]`. Accepts the OpenAI content forms
   * (plain string, or an array of `text` / `image_url` parts). Roles are the
   * three the in-tree `MessageRole` models; OpenAI's `role:"tool"` and
   * assistant `tool_calls` have no in-tree counterpart that this face carries
   * ⇒ refused, not dropped.
   */
  private def readMessages(cur: io.circe.ACursor): Either[Rejected, List[Message]] =
    cur.downField("messages").as[Option[List[Json]]] match
      case Right(Some(msgs)) if msgs.nonEmpty =>
        msgs.zipWithIndex.foldLeft[Either[Rejected, List[Message]]](Right(Nil)) { case (acc, (m, idx)) =>
          for
            done <- acc
            one <- readMessage(m, idx)
          yield done :+ one
        }
      case _ =>
        Left(
          Rejected(
            Some("messages"),
            "invalid_messages",
            "messages must be a non-empty array of {role, content} objects"
          )
        )

  private def readMessage(json: Json, idx: Int): Either[Rejected, Message] =
    val c = json.hcursor
    c.downField("role").as[String] match
      case Left(_) =>
        Left(
          Rejected(
            Some(s"messages[$idx].role"),
            "invalid_role",
            s"messages[$idx].role must be one of: system, user, assistant"
          )
        )
      case Right(rawRole) =>
        MessageRole.fromString(rawRole.trim.toLowerCase) match
          case None =>
            Left(
              Rejected(
                Some(s"messages[$idx].role"),
                "invalid_role",
                s"unsupported message role '$rawRole' at messages[$idx]; " +
                  "this face accepts system, user, assistant"
              )
            )
          case Some(role) =>
            if isMeaningful(c.downField("tool_calls").focus) then
              Left(
                Rejected(
                  Some(s"messages[$idx].tool_calls"),
                  "unsupported_tool_roundtrip",
                  s"assistant tool_calls at messages[$idx] are not supported by this face " +
                    "(the tool round-trip is out of this face's scope)"
                )
              )
            else readContent(c, role, idx)

    end match

  end readMessage

  private def readContent(
    c: io.circe.ACursor,
    role: MessageRole,
    idx: Int
  ): Either[Rejected, Message] =
    c.downField("content").focus match
      case None | Some(Json.Null) =>
        // OpenAI allows null content on an assistant turn that carried only
        // tool calls — those are refused above, so a plain null is an empty turn.
        Right(Message(role, Left("")))
      case Some(j) if j.isString =>
        Right(Message(role, Left(j.asString.getOrElse(""))))
      case Some(j) if j.isArray =>
        val parts = j.asArray.getOrElse(Vector.empty)
        parts.zipWithIndex
          .foldLeft[Either[Rejected, List[ContentBlock]]](Right(Nil)) { case (acc, (p, pi)) =>
            for
              done <- acc
              one <- readPart(p, idx, pi)
            yield done :+ one
          }
          .map(blocks => Message(role, Right(blocks)))
      case Some(_) =>
        Left(
          Rejected(
            Some(s"messages[$idx].content"),
            "invalid_content",
            s"messages[$idx].content must be a string or an array of content parts"
          )
        )

  private def readPart(json: Json, idx: Int, pi: Int): Either[Rejected, ContentBlock] =
    val c = json.hcursor
    c.downField("type").as[String] match
      case Right("text") =>
        c.downField("text").as[String] match
          case Right(t) => Right(ContentBlock.Text(t))
          case Left(_) =>
            Left(
              Rejected(
                Some(s"messages[$idx].content[$pi].text"),
                "invalid_part",
                s"messages[$idx].content[$pi].text must be a string"
              )
            )
      case Right("image_url") =>
        c.downField("image_url").downField("url").as[String] match
          case Right(url) => parseDataUrl(url, idx, pi)
          case Left(_) =>
            Left(
              Rejected(
                Some(s"messages[$idx].content[$pi].image_url.url"),
                "invalid_part",
                s"messages[$idx].content[$pi].image_url.url must be a string"
              )
            )
      case Right(other) =>
        Left(
          Rejected(
            Some(s"messages[$idx].content[$pi].type"),
            "invalid_part",
            s"unsupported content part type '$other'; this face accepts text, image_url"
          )
        )
      case Left(_) =>
        Left(
          Rejected(
            Some(s"messages[$idx].content[$pi].type"),
            "invalid_part",
            s"messages[$idx].content[$pi].type is required (text | image_url)"
          )
        )

    end match

  end readPart

  /**
   * The in-tree image block carries base64 (`ContentBlock.Image(data,
   * mediaType)`, rendered outbound as `data:<mediaType>;base64,<data>` —
   * `OpenAiAdapter.scala:144`). A remote image URL is therefore NOT carryable:
   * fetching it would be a new network face. Refused, not dropped.
   */
  private def parseDataUrl(url: String, idx: Int, pi: Int): Either[Rejected, ContentBlock] =
    val prefix = "data:"
    val marker = ";base64,"
    val at = url.indexOf(marker)
    if url.startsWith(prefix) && at > prefix.length then
      val mediaType = url.substring(prefix.length, at)
      val data = url.substring(at + marker.length)
      if data.nonEmpty then Right(ContentBlock.Image(data, mediaType))
      else Left(remoteImageError(idx, pi))
    else Left(remoteImageError(idx, pi))

  private def remoteImageError(idx: Int, pi: Int) = Rejected(
    Some(s"messages[$idx].content[$pi].image_url.url"),
    "unsupported_image_url",
    s"messages[$idx].content[$pi].image_url.url must be an inline data: URL " +
      "(data:<mediaType>;base64,<payload>); remote image URLs are not fetched by this face"
  )

  // ── response: in-tree LlmResponse → OpenAI body ──────────────────────────

  /**
   * Convert the in-tree [[LlmResponse]] into an OpenAI `chat.completion`
   * body. `model` reports what ACTUALLY served the request (`meta.providerId` /
   * `meta.model`), so a substituted candidate is always visible to the caller.
   *
   * Deliberately not carried (no OpenAI counterpart in the completion object;
   * declared, not silently lost): `searchInfo` (a provider-native extension)
   * and the `meta` diagnostics (duration / fallback chain / context window).
   */
  def toOpenAiResponse(resp: LlmResponse): Json =
    val toolCalls = resp.toolCalls.map(toolCallJson)
    val content: Json =
      if toolCalls.nonEmpty && resp.reply.isEmpty then Json.Null else resp.reply.asJson
    val messageBase = Json.obj(
      "role" -> "assistant".asJson,
      "content" -> content
    )
    val message =
      if toolCalls.nonEmpty then messageBase.deepMerge(Json.obj("tool_calls" -> Json.fromValues(toolCalls)))
      else messageBase
    val choice = Json.obj(
      "index" -> 0.asJson,
      "message" -> message,
      "finish_reason" -> (if toolCalls.nonEmpty then "tool_calls" else "stop").asJson
    )
    val base = Json.obj(
      "id" -> s"chatcmpl-${java.util.UUID.randomUUID().toString.replace("-", "")}".asJson,
      "object" -> "chat.completion".asJson,
      "created" -> (System.currentTimeMillis() / 1000L).asJson,
      "model" -> s"${resp.meta.providerId}/${resp.meta.model}".asJson,
      "choices" -> Json.arr(choice)
    )
    resp.usage.map(usageJson) match
      case Some(u) => base.deepMerge(Json.obj("usage" -> u))
      case None => base

  end toOpenAiResponse

  /**
   * Same rendering as the outbound OpenAI adapter (`OpenAiAdapter.scala:117`):
   * `arguments` is the compact JSON string of the tool input.
   */
  private def toolCallJson(tc: ToolCall): Json =
    Json.obj(
      "id" -> tc.id.asJson,
      "type" -> "function".asJson,
      "function" -> Json.obj(
        "name" -> tc.name.asJson,
        "arguments" -> Json.fromJsonObject(tc.input).noSpaces.asJson
      )
    )

  private def usageJson(u: TokenUsage): Json =
    val total = u.inputTokens + u.outputTokens
    val base = Json.obj(
      "prompt_tokens" -> u.inputTokens.asJson,
      "completion_tokens" -> u.outputTokens.asJson,
      "total_tokens" -> total.asJson
    )
    u.cacheReadTokens match
      case Some(c) =>
        base.deepMerge(
          Json.obj("prompt_tokens_details" -> Json.obj("cached_tokens" -> c.asJson))
        )
      case None => base

  /**
   * OpenAI-shaped error envelope. `status` is always 400 for this face's
   * refusals; the JSON body carries the field pointer and the machine tag.
   */
  def errorEnvelope(r: Rejected): Json =
    Json.obj(
      "error" -> Json.obj(
        "message" -> r.message.asJson,
        "type" -> "invalid_request_error".asJson,
        "param" -> r.param.asJson,
        "code" -> r.code.asJson
      )
    )

  /** A non-JSON body is refused with the same envelope. */
  val malformedBody: Rejected = Rejected(
    param = None,
    code = "invalid_json",
    message = "request body is not valid JSON"
  )
end OpenAiChatShim
