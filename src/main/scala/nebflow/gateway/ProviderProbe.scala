/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.neblink.OutboundHttpClients

private[gateway] object ProviderProbe:

  /**
   * One model-list probe outcome. `NoEndpoint` is the only verdict that lets
   * the caller move on to the next declared candidate; `Failed` already carries
   * a user-facing message.
   */
  private[gateway] enum ModelsProbe:
    case Found(models: List[Json])
    case NoEndpoint(detail: String)
    case Failed(detail: String)

  /**
   * GET every model-list endpoint declared for one provider face, in probe
   * order (`ModelListFaces`), and extract the model entries (both
   * OpenAI-compatible and Anthropic reply `{"data":[{"id":..}]}`, normalized
   * with empty ids removed and duplicates collapsed). Each entry is `{id}` plus
   * `contextLength` when the provider reports one (OpenRouter
   * `context_length`, others `context_window`) — absent/unparsable means the
   * field is simply omitted.
   *
   * Only a "no such endpoint" verdict advances to the next declared candidate
   * (`ModelsProbe.NoEndpoint`: HTTP 404, or a 2xx body carrying the provider's
   * own 404 envelope — measured on zhipu, whose gateway answers `HTTP 200` with
   * `{"code":500,"msg":"404 NOT_FOUND"}`). Every other failure is final, so a
   * later candidate can never mask a real error. Uses the same JDK
   * HttpClient posture as the LLM adapters (`OutboundHttpClients.Policy.SystemProxy10s`:
   * HTTP/1.1 forced, system proxy honored) — a probe must see the same network
   * path real completions take.
   *
   * D1 — why HTTP/1.1 here, and what would flip it (this peer is NOT the
   * neblink Caddy face, so the two must not share a conclusion):
   *   · Evidence: **this call faces a different reverse proxy** — the provider
   *     API gateway, the same family as the nginx/one-api endpoint that
   *     produced the original 2026-08-11 `bad_record_mac` incident, so its
   *     trap evidence is *closer* to the original finding than neblink's. What
   *     we actually have measured here is nothing: the incident's alert text
   *     and frequency were never retained, and no reading on this path since.
   *     The 2026-09-12 probe covered neblink/Caddy only ⇒ the neblink
   *     14/14-green result says nothing about this peer.
   *   · Judge-red: a reproduced `bad_record_mac` / TLS alert on this path, or a
   *     GOAWAY / closed-reset bucket here in the outbound-failure counters, or
   *     an h2-vs-h1 same-window comparison (n ≥ 100/arm) showing h2 p95 >
   *     h1 p95 × 1.2. Do NOT relax this pin as part of a neblink-Caddy review.
   */
  private[gateway] def fetchProviderModels(
    modelsUrls: List[String],
    apiKey: String,
    protocol: String
  ): IO[Either[String, List[Json]]] =
    IO.blocking {
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.SystemProxy10s)

      // Declared candidates, probed in order; only a missing endpoint advances.
      def probe(urls: List[String], missing: Option[String]): Either[String, List[Json]] =
        urls match
          case Nil =>
            val tried = missing.map(d => s" — tried $d").getOrElse("")
            Left(s"No model-list endpoint found for this provider$tried: enter model ids manually")
          case url :: rest =>
            probeModelList(client, url, apiKey, protocol) match
              case ModelsProbe.Found(models) => Right(models)
              case ModelsProbe.NoEndpoint(detail) => probe(rest, Some(s"$url -> $detail"))
              case ModelsProbe.Failed(detail) => Left(detail)

      probe(modelsUrls, None)
    }.handleErrorWith(e => IO.pure(Left(unreachable(e))))

  /**
   * GET one declared endpoint with protocol-specific auth headers and classify
   * the reply. A 2xx body that still carries the provider's own error envelope
   * counts as a failure: the zhipu gateway answers `HTTP 200` with
   * `{"code":500,"msg":"404 NOT_FOUND",...}` on an endpoint it does not serve,
   * and reporting that as "no models" hid the real 404 from the dialog.
   */
  private[gateway] def probeModelList(
    client: java.net.http.HttpClient,
    url: String,
    apiKey: String,
    protocol: String
  ): ModelsProbe =
    try
      val reqBuilder = java.net.http.HttpRequest
        .newBuilder()
        .uri(java.net.URI.create(url))
        .timeout(java.time.Duration.ofSeconds(15))
        .GET()
      if protocol == "openai" then
        if apiKey.nonEmpty then reqBuilder.header("Authorization", s"Bearer $apiKey")
      else
        // anthropic
        reqBuilder.header("x-api-key", apiKey)
        reqBuilder.header("anthropic-version", "2023-06-01")
      val response = client.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      val status = response.statusCode()
      val body = response.body()
      val parsed = parser.parse(body)
      val detail = parsed.toOption.flatMap(errorDetail).getOrElse(body.take(200))
      if status == 404 || parsed.toOption.exists(saysNoEndpoint) then ModelsProbe.NoEndpoint(detail)
      else if status >= 200 && status < 300 then
        parsed match
          case Left(err) => ModelsProbe.Failed(s"Invalid JSON from provider: ${err.message}")
          case Right(json) =>
            val models = extractModels(json)
            if models.nonEmpty then ModelsProbe.Found(models)
            else if isErrorEnvelope(json) then
              ModelsProbe.Failed(s"Provider returned HTTP $status with an error body: $detail")
            else ModelsProbe.Failed("Provider returned no models")
      else ModelsProbe.Failed(s"Provider returned HTTP $status: $detail")
    catch case e: Exception => ModelsProbe.Failed(unreachable(e))
    end try

  end probeModelList

  /**
   * Extract `data[].id` from a provider reply (both OpenAI-compatible and
   * Anthropic replies) with empty ids dropped and duplicates collapsed (first
   * occurrence wins). Each entry is `{id}` plus `contextLength` when the
   * provider reports one (OpenRouter `context_length`, others
   * `context_window`).
   *
   * 案② B4（`chain-llmstall-fix`，2026-09-21）——**本函数是「per-model 真值上界」的
   * 唯一来源，零新增解析**：抽出的 `contextLength` 经 `/api/provider/models` 到前端
   * （`sidebar.js` 的 `providerModelChoices` / `contextLengthFor`），由
   * `fillContextIfEmpty` 写回 `llm.providers.*.models[].modelMaxContext`
   * （`ModelConfig.modelMaxContext`），再由后端取数单点
   * `ProviderRegistry.effectiveContextWindow` 参与 `min(configured, modelMaxContext)`。
   * ⇒ 此处**不需要也不允许**新增第二份解析 / 第二个真值口：语义修正在消费端
   * （前后端两处），真值口保持本单点。判定面：`check-provider-modellist.mjs`
   * （AGENTS §13）钉的正是本函数所在的面。
   */
  private[gateway] def extractModels(json: Json): List[Json] =
    val entries = json.hcursor
      .downField("data")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(j => j.hcursor.downField("id").as[String].toOption.map(_.trim).filter(_.nonEmpty).map(id => (id, j)))
    // distinct by id, first occurrence wins
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    entries.collect {
      case (id, raw) if seen.add(id) =>
        val ctx = List("context_length", "context_window")
          .flatMap(k => raw.hcursor.downField(k).as[Long].toOption)
          .headOption
        ctx match
          case Some(n) => Json.obj("id" -> id.asJson, "contextLength" -> n.asJson)
          case None => Json.obj("id" -> id.asJson)
    }

  end extractModels

  /**
   * Provider-side error text, when the reply carries one (`error.message`,
   * `error` as a string, `msg`, or `message`).
   */
  private[gateway] def errorDetail(json: Json): Option[String] =
    val c = json.hcursor
    List(
      c.downField("error").downField("message").as[String].toOption,
      c.downField("error").as[String].toOption,
      c.downField("msg").as[String].toOption,
      c.downField("message").as[String].toOption
    ).flatten.map(_.trim).find(_.nonEmpty)

  /**
   * A reply that is an error even under a 2xx status: `{"error":…}`,
   * `{"success":false}`, or the `{"code":…,"msg":…}` envelope a gateway uses to
   * carry an HTTP-level error code. Such a body must never be reported as
   * "no models".
   */
  private[gateway] def isErrorEnvelope(json: Json): Boolean =
    val c = json.hcursor
    c.downField("error").focus.isDefined ||
    c.downField("success").as[Boolean].toOption.contains(false) ||
    (c.downField("code").focus.isDefined && c.downField("msg").focus.isDefined)

  /**
   * The reply says the endpoint does not exist (a 2xx carrier of
   * `404 NOT_FOUND`, or a `resource_not_found_error`) — the only verdict that
   * lets `fetchProviderModels` try the face's next declared candidate.
   */
  private[gateway] def saysNoEndpoint(json: Json): Boolean =
    val c = json.hcursor
    val code = c
      .downField("code")
      .as[Int]
      .toOption
      .orElse(c.downField("code").as[String].toOption.flatMap(_.trim.toIntOption))
    val text = List(
      c.downField("msg").as[String].toOption,
      c.downField("message").as[String].toOption,
      c.downField("error").downField("message").as[String].toOption,
      c.downField("error").downField("type").as[String].toOption
    ).flatten.mkString(" ").toLowerCase
    code.contains(404) || text.contains("not_found") || text.contains("not found")

  /**
   * Transport-level failure text: a provider whose declared endpoints answer
   * nothing at all (proxy, DNS, TLS, timeout) is reported through this branch,
   * never as a silent empty list.
   */
  private[gateway] def unreachable(e: Throwable): String =
    s"Provider unreachable: ${Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)}"

end ProviderProbe
