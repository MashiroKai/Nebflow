package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, parser}

import java.net.http.{HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}

/**
 * First-party Logto transport primitives + the device-registration call —
 * the pieces of the retired RFC 8628 device-flow client that the SURVIVING
 * login paths still speak (统一登录批, 台账 #924, 2026-10-26):
 *
 *   · `Send` / `Request` / `jdkSend` / `formEncode` — the injected-transport
 *     vocabulary shared by [[LogtoAuthCode]] (browser PKCE, 路径①),
 *     [[LogtoSilentRelogin]] and [[LogtoPasswordFlow]] (headless password
 *     login, 路径②);
 *   · `register` / `registerRequest` / `RegisterRejection` — the
 *     provider-token → long-lived device-credential exchange against the
 *     neblink-server's `/api/device/register`, the CONVERGENCE POINT both
 *     login paths land on (browser PKCE callback and headless login feed
 *     their access token here; downstream persist/hot-swap is
 *     `completeDeviceEnrollment`, unchanged).
 *
 * RETIRED with the device flow (2026-10-26): `startRequest` / `pollRequest`
 * / `parseStartResponse` / `PollOutcome` / `classifyPoll` / `start` /
 * `pollOnce`, the gateway `/neblink/device-flow` routes and the CLI
 * `--device-code` leg. Issued legacy codes expire naturally; nothing calls
 * the relay's `/api/device/code` + `/api/device/token` family from this repo
 * any more (the relay-side retirement is a link-server batch — 台账 #924).
 *
 * HTTP is injected (`Send`) so every protocol decision stays unit-tested
 * without network; `jdkSend` is the production transport (same client
 * settings as the gateway's proxyPost: HTTP/1.1, no system proxy).
 */
object LogtoTransport:

  /**
   * One POST request; `bearer` adds an Authorization header when present.
   * `method` defaults to POST (every historical call site); the Experience
   * dance ([[LogtoPasswordFlow]]) also speaks GET/PUT on this same shape.
   */
  final case class Request(
    url: String,
    contentType: String,
    body: String,
    bearer: Option[String] = None,
    method: String = "POST"
  )

  /** Injectable transport: request -> (status, body). */
  type Send = Request => IO[(Int, String)]

  def formEncode(pairs: (String, String)*): String =
    pairs
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
      }
      .mkString("&")

  /**
   * Non-2xx register outcome, STRUCTURED (单设备单账号批): the server's
   * stable `reason` (409 machine-gate: `device_bound_other_account` /
   * `device_already_logged_in` — literals pinned verbatim across repos, the
   * `binding_revoked` discipline) rides along so callers classify the
   * refusal instead of string-matching free text. `message` is the old
   * free-string face (`error` field, or "HTTP <status>").
   */
  final case class RegisterRejection(
    status: Int,
    reason: Option[String],
    message: String
  )

  /**
   * Exchange a provider access token for a long-lived device credential —
   * the registration half of BOTH login paths (browser PKCE callback and
   * headless password login; formerly the device-flow poll's success leg).
   * The response is the server's EnrollResponse JSON (deviceToken /
   * networkId / avatarUrl / githubUsername); callers complete via
   * `completeDeviceEnrollment` (persist + config switch + hot-swap).
   *
   * Optional `machineFingerprint` rides along with the SAME source/moment as
   * the deviceId derivation (DeviceIdentity.machineFingerprint). None ⇒ the
   * key is ABSENT from the payload (not null, not empty) — the server
   * fail-opens on absence with a WARN.
   */
  def registerRequest(
    serverUrl: String,
    accessToken: String,
    deviceId: String,
    deviceName: String,
    platform: String,
    machineFingerprint: Option[String] = None
  ): Request =
    Request(
      url = s"${serverUrl.stripSuffix("/")}${Protocol.DeviceApi.register}",
      contentType = "application/json",
      body = machineFingerprint
        .fold(
          Json.obj(
            "deviceId" -> deviceId.asJson,
            "deviceName" -> deviceName.asJson,
            "platform" -> platform.asJson
          )
        )(fp =>
          Json.obj(
            "deviceId" -> deviceId.asJson,
            "deviceName" -> deviceName.asJson,
            "platform" -> platform.asJson,
            "machineFingerprint" -> fp.asJson
          )
        )
        .noSpaces,
      bearer = Some(accessToken)
    )

  def register(send: Send)(
    serverUrl: String,
    accessToken: String,
    deviceId: String,
    deviceName: String,
    platform: String,
    machineFingerprint: Option[String] = None
  ): IO[Either[RegisterRejection, Json]] =
    send(registerRequest(serverUrl, accessToken, deviceId, deviceName, platform, machineFingerprint))
      .flatMap { case (status, body) =>
        IO.pure(
          if status == 200 then
            parser
              .parse(body)
              .left
              .map(err => RegisterRejection(status, None, Option(err.getMessage).getOrElse(err.toString)))
          else
            val parsed = parser.parse(body).toOption
            val reason = parsed.flatMap(_.hcursor.downField("reason").as[String].toOption)
            Left(RegisterRejection(status, reason, extractError(status, body)))
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
   * as RestApiRoutes.proxyPost. One memoized instance (D5, 2026-09-13).
   * Transport failures surface as (0, errorMessage) so callers degrade to
   * error responses instead of unhandled IO failures.
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

end LogtoTransport
