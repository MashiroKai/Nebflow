package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, parser}

import java.net.{URI, URLEncoder}
import java.net.http.{HttpRequest, HttpResponse}

/**
 * RFC 8628 device-flow client against an external OIDC provider (Logto).
 * Stage 1 of the Logto migration (2026-08-18): when a provider is
 * configured, the flow start/poll move from neblink-server's self-hosted
 * `/api/device/code` + `/api/device/token` to the provider's standard
 * `/oidc/device/auth` + `/oidc/token`, and the resulting access token is
 * exchanged for the same long-lived device credential via the server's new
 * `/api/device/register`. Downstream persist/hot-swap logic is unchanged.
 *
 * HTTP is injected (`Send`) so every protocol decision below is unit-tested
 * without network; `jdkSend` is the production transport (same client
 * settings as the gateway's proxyPost: HTTP/1.1, no system proxy).
 */
object LogtoDeviceFlow:

  /** One POST request; `bearer` adds an Authorization header when present. */
  final case class Request(
    url: String,
    contentType: String,
    body: String,
    bearer: Option[String] = None
  )

  /** Injectable transport: request -> (status, body). */
  type Send = Request => IO[(Int, String)]

  // ── request builders (pure) ─────────────────────────────────────────────

  def startRequest(endpoint: String, clientId: String): Request =
    Request(
      url = s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.deviceAuth}",
      contentType = "application/x-www-form-urlencoded",
      body = formEncode(
        "client_id" -> clientId,
        "scope" -> "openid offline_access"
      )
    )

  def pollRequest(endpoint: String, clientId: String, deviceCode: String): Request =
    Request(
      url = s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.token}",
      contentType = "application/x-www-form-urlencoded",
      body = formEncode(
        "grant_type" -> "urn:ietf:params:oauth:grant-type:device_code",
        "device_code" -> deviceCode,
        "client_id" -> clientId
      )
    )

  def registerRequest(
    serverUrl: String,
    accessToken: String,
    deviceId: String,
    deviceName: String,
    platform: String
  ): Request =
    Request(
      url = s"${serverUrl.stripSuffix("/")}${Protocol.DeviceApi.register}",
      contentType = "application/json",
      body = Json
        .obj(
          "deviceId" -> deviceId.asJson,
          "deviceName" -> deviceName.asJson,
          "platform" -> platform.asJson
        )
        .noSpaces,
      bearer = Some(accessToken)
    )

  def formEncode(pairs: (String, String)*): String =
    pairs
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
      }
      .mkString("&")

  // ── response mapping (pure) ─────────────────────────────────────────────

  /**
   * Map the provider's device-authorization response onto the frontend
   * contract the gateway has always served (`deviceCode`/`userCode`/
   * `verificationUri`/`interval`/`expiresIn`, camelCase) — the web UI is
   * unchanged. Prefers `verification_uri_complete` (Logto's pre-filled
   * confirm URL) over the plain `verification_uri`.
   */
  def parseStartResponse(body: String): Either[String, Json] =
    for
      json <- parser.parse(body).left.map(_.message)
      c = json.hcursor
      deviceCode <- c.downField("device_code").as[String].left.map(_.message)
      userCode <- c.downField("user_code").as[String].left.map(_.message)
    yield
      val verification = c
        .downField("verification_uri_complete")
        .as[String]
        .orElse(c.downField("verification_uri").as[String])
        .getOrElse("")
      Json.obj(
        "deviceCode" -> deviceCode.asJson,
        "userCode" -> userCode.asJson,
        "verificationUri" -> verification.asJson,
        "interval" -> c.downField("interval").as[Option[Int]].toOption.flatten.getOrElse(5).asJson,
        "expiresIn" -> c.downField("expires_in").as[Option[Int]].toOption.flatten.getOrElse(900).asJson
      )

  sealed trait PollOutcome

  object PollOutcome:
    /** Token granted — carry the provider access token for registration. */
    final case class Success(accessToken: String) extends PollOutcome

    /**
     * Keep polling (`authorization_pending`; `slow_down` is normalized here
     * so the frontend's pending-only error contract keeps working).
     */
    final case class Pending(error: String) extends PollOutcome

    /** Terminal failure (`expired_token`, `access_denied`, ...). */
    final case class Failed(error: String) extends PollOutcome
  end PollOutcome

  def classifyPoll(status: Int, body: String): PollOutcome =
    if status == 200 then
      parser
        .parse(body)
        .toOption
        .flatMap(_.hcursor.downField("access_token").as[String].toOption)
        .map(PollOutcome.Success(_))
        .getOrElse(PollOutcome.Failed("token response missing access_token"))
    else
      val error = parser
        .parse(body)
        .toOption
        .flatMap(_.hcursor.downField("error").as[String].toOption)
      error match
        case Some("authorization_pending") | Some("slow_down") =>
          PollOutcome.Pending("authorization_pending")
        case Some(e) => PollOutcome.Failed(e)
        // status 0 = transport failure; the body IS the error message.
        case None if status == 0 => PollOutcome.Failed(body)
        case None => PollOutcome.Failed(s"HTTP $status")
  end classifyPoll

  // ── flow steps (IO, transport-injected) ─────────────────────────────────

  /** Start the device flow; returns the frontend-contract JSON. */
  def start(send: Send)(endpoint: String, clientId: String): IO[Either[String, Json]] =
    send(startRequest(endpoint, clientId)).flatMap { case (status, body) =>
      IO.pure(
        if status == 200 then parseStartResponse(body)
        else Left(extractError(status, body))
      )
    }

  /** One poll against the provider's token endpoint. */
  def pollOnce(send: Send)(endpoint: String, clientId: String, deviceCode: String): IO[PollOutcome] =
    send(pollRequest(endpoint, clientId, deviceCode)).map { case (status, body) =>
      classifyPoll(status, body)
    }

  /**
   * Exchange the provider access token for a long-lived device credential.
   * The response is the server's EnrollResponse JSON (deviceToken /
   * networkId / avatarUrl / githubUsername) — the exact shape the legacy
   * flow returned, so the caller's persist/hot-swap path is shared.
   */
  def register(send: Send)(
    serverUrl: String,
    accessToken: String,
    deviceId: String,
    deviceName: String,
    platform: String
  ): IO[Either[String, Json]] =
    send(registerRequest(serverUrl, accessToken, deviceId, deviceName, platform)).flatMap { case (status, body) =>
      IO.pure(
        if status == 200 then parser.parse(body).left.map(_.message)
        else Left(extractError(status, body))
      )
    }

  private def extractError(status: Int, body: String): String =
    if status == 0 then body // transport failure: body carries the message
    else
      parser
        .parse(body)
        .toOption
        .flatMap(_.hcursor.downField("error").as[String].toOption)
        .getOrElse(s"HTTP $status")

  // ── production transport ────────────────────────────────────────────────

  /**
   * JDK-backed Send on the shared `OutboundHttpClients.Policy.Direct15s`
   * client (HTTP/1.1, system proxy bypassed, 15 s connect) — the same policy
   * as RestApiRoutes.proxyPost. Previously this built a fresh `HttpClient` on
   * every device-flow poll tick; it now reuses one memoized instance (D5,
   * 2026-09-13). Transport failures surface as (0, errorMessage) so callers
   * degrade to error responses instead of unhandled IO failures.
   *
   * D1 — why HTTP/1.1 here, and what would flip it:
   *   · Evidence: **none on this peer.** Logto (`auth.*`) is fronted by an
   *     unknown reverse proxy — whether it is the same Caddy as neblink is
   *     未证, and no TLS/ALPN reading has ever been taken on it. The
   *     "HTTP/2 reuse + TLS 1.3 resumption clash with the reverse proxy"
   *     rationale on the old comment was carried over from RestApiRoutes
   *     (commit 4c0f618f), not measured here. The 2026-09-12 Caddy probe
   *     (14/14 HTTP_2 200, 0 TLS alerts) does not transfer: different peer,
   *     and "green in one local window" is not evidence a trap is absent.
   *   · Judge-red: a reproduced TLS alert / bad_record_mac against this peer,
   *     or a GOAWAY / closed-reset bucket on the token exchange (a
   *     non-idempotent POST) in the outbound-failure counters.
   */
  val jdkSend: Send = req =>
    IO.blocking {
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
      val builder = HttpRequest
        .newBuilder()
        .uri(URI.create(req.url))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", req.contentType)
        .POST(HttpRequest.BodyPublishers.ofString(req.body))
      req.bearer.foreach(t => builder.header("Authorization", s"Bearer $t"))
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      (response.statusCode(), response.body())
    }.handleErrorWith(e => IO.pure((0, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))))

end LogtoDeviceFlow
