package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.syntax.*
import io.circe.{Json, parser}

import java.net.URI
import java.net.URLDecoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.jdk.CollectionConverters.*

/**
  * Logto Experience API client for the SELF-DRAWN login batch (2026-09-09
  * design `20260909_login-selfdrawn-design.md` §2.2, plan A): the gateway
  * acts as a BFF that HOLDS the Logto interaction server-side (cookie jar
  * carrying `_interaction`/`_interaction.sig`, probed live 2026-09-09 §1.3)
  * and drives the Experience API itself — the browser only talks to the
  * local gateway, rendering zero auth-domain pages.
  *
  * Pure builders (request shapes for every route in the §2.2 table, verified
  * against the DEPLOYED hosted bundle — not docs) + transport-injected flow
  * steps, mirroring `LogtoAuthCode`/`LogtoDeviceFlow` so every protocol
  * decision is unit-tested without network. `jdkSend` is the production
  * transport; redirects are NEVER followed (the 303 interception after
  * `/oidc/auth` IS the session-opening mechanism).
  *
  * 待锁语义 (design §2.3, QA locks with a real account; does not block the
  * batch): consent exact timing (submit → direct callback vs /consent hop),
  * social callback query → connectorData mapping (`connectorDataFromQuery`
  * is the single adjust-once seam), error-code census.
  */
object LogtoExperience:

  /** One Experience/interaction request. `cookie` is filled by `run` from
    * the jar (builders leave it empty); `body` is the pre-encoded JSON. */
  final case class ExperienceRequest(
    method: String,
    url: String,
    body: Option[String] = None,
    contentType: String = "application/json",
    cookie: Option[String] = None
  )

  /** Raw transport answer: status + body + the redirect interception
    * surfaces (Set-Cookie headers, Location) the jar/flow need. */
  final case class ExperienceResponse(
    status: Int,
    body: String,
    setCookies: List[String] = Nil,
    location: Option[String] = None
  )

  /** Injectable transport: request -> response. Same shape idea as
    * `LogtoDeviceFlow.Send`, richer because cookies/redirects are the
    * protocol here. */
  type Send = ExperienceRequest => IO[ExperienceResponse]

  /** Logto Experience error (non-2xx). Logto answers `{code, message}`
    * (probed: 422 `session.invalid_credentials`, 400 `guard.invalid_input`,
    * 404 `session.identifier_not_found`); the consent-branch detection keys
    * on the code. `describe` is the human-facing one-liner. */
  final case class ExperienceError(status: Int, code: Option[String], message: Option[String]):
    def describe: String =
      if status == 0 then message.getOrElse("transport failure")
      else s"${code.getOrElse(s"HTTP $status")}${message.fold("")(m => s": $m")}"
    /** Submit answered with "MFA verification required" — the wizard drives
      * POST mfa/totp then re-submits; NOT terminal. */
    def isMfaRequired: Boolean = code.exists(_.toLowerCase.contains("mfa"))
    /** Submit answered with a consent requirement — `submitWithConsent`
      * resolves it inline. */
    def isConsentRequired: Boolean = code.exists(_.toLowerCase.contains("consent"))
  end ExperienceError

  // ── cookie jar (server-held interaction session) ────────────────────────

  /** Gateway-held Logto cookies. Live probe §1.3: `/oidc/auth` answers 303
    * with `Set-Cookie: _interaction=...; HttpOnly` + the signed pair — the
    * interaction lives entirely in these cookies, so keeping them server-side
    * keeps the browser out of the auth domain. Absorb-on-response,
    * attach-on-request via `run`. */
  final class CookieJar private[LogtoExperience] (store: Ref[IO, Map[String, String]]):
    /** Merge raw Set-Cookie header values into the jar. */
    def absorb(setCookies: List[String]): IO[Unit] =
      store.update(m => setCookies.flatMap(parseSetCookie).foldLeft(m)(_ + _))
    /** The `Cookie:` header value; None while the jar is empty. */
    def cookieHeader: IO[Option[String]] =
      store.get.map(m => if m.isEmpty then None else Some(m.map((k, v) => s"$k=$v").mkString("; ")))
    def clear: IO[Unit] = store.set(Map.empty)
    /** Test/diagnostic view. */
    def snapshot: IO[Map[String, String]] = store.get

  object CookieJar:
    def make: IO[CookieJar] = Ref.of[IO, Map[String, String]](Map.empty).map(new CookieJar(_))
    /** Pure-context construction (routes class bodies, no IO context there). */
    def unsafe: CookieJar = new CookieJar(Ref.unsafe[IO, Map[String, String]](Map.empty))
  end CookieJar

  /** Parse `name=value; Path=/; HttpOnly` to `(name, value)`. A header with
    * no `=` (deletion form) maps to an empty value; garbage returns None. */
  def parseSetCookie(header: String): Option[(String, String)] =
    Option(header).map(_.trim).filter(_.nonEmpty).flatMap { h =>
      h.split(";").headOption.getOrElse("").trim.split("=", 2).toList match
        case name :: value :: _ if name.trim.nonEmpty => Some(name.trim -> value.trim)
        case name :: Nil if name.trim.nonEmpty        => Some(name.trim -> "")
        case _                                        => None
    }

  // ── request builders (pure; the §2.2 route table) ────────────────────────

  private val ApiExperience = "/api/experience"
  private val ApiConsent = "/api/interaction/consent"

  private def req(method: String, endpoint: String, path: String, body: Option[Json] = None): ExperienceRequest =
    ExperienceRequest(
      method = method,
      url = s"${endpoint.stripSuffix("/")}$path",
      body = body.map(_.noSpaces)
    )

  /** Interaction OPEN: the plain `/oidc/auth` GET — sent with redirects
    * never-followed so the 303 (`Location: /sign-in?...` + interaction
    * cookies) is intercepted by the gateway. `prompt` stays "consent" (the
    * refresh-token invariant, `LogtoAuthCode.authorizeUrl`). */
  def interactionStart(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    codeChallenge: String,
    state: String,
    prompt: String = "consent"
  ): ExperienceRequest =
    ExperienceRequest(
      method = "GET",
      url = LogtoAuthCode.authorizeUrl(endpoint, clientId, redirectUri, codeChallenge, state, prompt)
    )

  /** Set the interaction event (SignIn / Register / ForgotPassword). */
  def interactionPut(endpoint: String, interactionEvent: String): ExperienceRequest =
    req("PUT", endpoint, ApiExperience, Some(Json.obj("interactionEvent" -> interactionEvent.asJson)))

  /** Password verification (sign-in). */
  def passwordVerify(endpoint: String, identifierType: String, identifierValue: String, password: String): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/verification/password", Some(Json.obj(
      "identifier" -> Json.obj("type" -> identifierType.asJson, "value" -> identifierValue.asJson),
      "password" -> password.asJson
    )))

  /** Send an email verification code (sign-in code mode / registration /
    * forgot-password). */
  def verificationCodeSend(
    endpoint: String,
    interactionEvent: String,
    identifierType: String,
    identifierValue: String
  ): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/verification/verification-code", Some(Json.obj(
      "interactionEvent" -> interactionEvent.asJson,
      "identifier" -> Json.obj("type" -> identifierType.asJson, "value" -> identifierValue.asJson)
    )))

  /** Verify the code earlier sent — returns the (refreshed) verificationId. */
  def verificationCodeVerify(endpoint: String, verificationId: String, code: String): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/verification/verification-code/verify", Some(Json.obj(
      "verificationId" -> verificationId.asJson,
      "code" -> code.asJson
    )))

  /** Social branch, step 1: the IdP authorization URL. `state` doubles as
    * the CSRF token echoed back on `/auth/social-callback`; `redirectUri`
    * is where Logto's `/callback/social/:target` 303s the browser (the
    * gateway's local social-callback route). */
  def socialAuthorizationUri(endpoint: String, target: String, state: String, redirectUri: String): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/verification/social/$target/authorization-uri", Some(Json.obj(
      "state" -> state.asJson,
      "redirectUri" -> redirectUri.asJson
    )))

  /** Social branch, final step: hand the callback payload (connectorData)
    * back to Logto in exchange for a verificationId. */
  def socialVerify(endpoint: String, target: String, connectorData: Json): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/verification/social/$target/verify", Some(connectorData))

  /** Bind a verified identity to the interaction (sign-in or register). */
  def identification(endpoint: String, verificationId: String, linkSocialIdentity: Boolean = false): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/identification", Some(
      if linkSocialIdentity then
        Json.obj("verificationId" -> verificationId.asJson, "linkSocialIdentity" -> true.asJson)
      else Json.obj("verificationId" -> verificationId.asJson)
    ))

  /** Registration / password-change profile fields (username / password /
    * email). Payload passes through verbatim — the wizard owns the shape. */
  def profile(endpoint: String, payload: Json): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/profile", Some(payload))

  /** Finish the interaction; answers `{redirectTo}` (the loopback callback
    * with code+state, or a /consent hop — resolved by `submitWithConsent`). */
  def submit(endpoint: String): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/submit", Some(Json.obj()))

  /** MFA sign-in step: verify the TOTP code. */
  def totpVerify(endpoint: String, code: String, verificationId: Option[String]): ExperienceRequest =
    val base = Json.obj("code" -> code.asJson)
    req("POST", endpoint, s"$ApiExperience/verification/totp/verify", Some(
      verificationId.fold(base)(v => base.deepMerge(Json.obj("verificationId" -> v.asJson)))
    ))

  /** MFA enrollment (bind TOTP after a verified code). */
  def mfaBind(endpoint: String, verificationId: String): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/profile/mfa", Some(Json.obj(
      "type" -> "totp".asJson,
      "verificationId" -> verificationId.asJson
    )))

  /** MFA skip (user declined enrollment; NoPrompt policy accounts). */
  def mfaSkipped(endpoint: String): ExperienceRequest =
    req("POST", endpoint, s"$ApiExperience/profile/mfa-skipped", Some(Json.obj()))

  /** Consent info (scope list) for the wizard's consent screen. */
  def consentInfo(endpoint: String): ExperienceRequest =
    req("GET", endpoint, ApiConsent)

  /** Consent approval. */
  def consentApprove(endpoint: String, organizationIds: Option[List[String]]): ExperienceRequest =
    val body = organizationIds.fold(Json.obj())(ids => Json.obj("organizationIds" -> ids.asJson))
    req("POST", endpoint, ApiConsent, Some(body))

  // ── response mapping (pure) ──────────────────────────────────────────────

  /** `{verificationId}` extractor (every verification step answers it). */
  def verificationIdOf(json: Json): Option[String] =
    json.hcursor.downField("verificationId").as[String].toOption

  /** `{redirectTo}` extractor (submit / consent approve). */
  def redirectToOf(json: Json): Option[String] =
    json.hcursor.downField("redirectTo").as[String].toOption

  /** The consent hop: Logto points `redirectTo` at its own `/consent` path
    * when consent must be granted before the code is cut. */
  def isConsentRedirect(url: String): Boolean =
    scala.util.Try(URI.create(url)).toOption
      .flatMap(uri => Option(uri.getPath))
      .exists(_.endsWith("/consent"))

  /** Parse a non-2xx Experience answer. Understands both `{code,message}`
    * (Experience API) and `{error,error_description}` (OAuth endpoints).
    * Status 0 is the transport-failure convention (mirrors
    * `LogtoDeviceFlow`): the body IS the message. */
  def parseError(status: Int, body: String): ExperienceError =
    val json = parser.parse(body).toOption
    ExperienceError(
      status = status,
      code = json.flatMap(_.hcursor.downField("code").as[String].toOption)
        .orElse(json.flatMap(_.hcursor.downField("error").as[String].toOption)),
      message = json.flatMap(_.hcursor.downField("message").as[String].toOption)
        .orElse(json.flatMap(_.hcursor.downField("error_description").as[String].toOption))
        .orElse(Option(body.trim).filter(_ => status == 0 && json.isEmpty && body.trim.nonEmpty))
    )

  /** Pull `code` + `state` out of the final redirectTo (the loopback
    * `/auth/callback?code=...&state=...` the provider built). Both must be
    * present and non-empty — anything else is not a finalizable redirect. */
  def parseRedirectCallback(url: String): Option[(String, String)] =
    scala.util.Try(URI.create(url)).toOption.flatMap { uri =>
      val query = Option(uri.getRawQuery).toList
        .flatMap(_.split("&").toList)
        .flatMap {
          case "" => None
          case kv =>
            kv.split("=", 2) match
              case Array(k)      => Some(URLDecoder.decode(k, "UTF-8") -> "")
              case Array(k, v)   => Some(URLDecoder.decode(k, "UTF-8") -> URLDecoder.decode(v, "UTF-8"))
              case _             => None
        }.toMap
      for
        code <- query.get("code").filter(_.nonEmpty)
        state <- query.get("state").filter(_.nonEmpty)
      yield (code, state)
    }

  /** 待锁 seam (design §2.3 #2): the social-callback query → connectorData
    * mapping. Best-effort v1: every query param becomes a JSON string field
    * (connectors accept string payloads; numeric/boolean coercion and any
    * nesting are adjusted HERE once QA locks the real contract). */
  def connectorDataFromQuery(query: Map[String, String]): Json =
    Json.obj(query.map { case (k, v) => k -> Json.fromString(v) }.toSeq*)

  // ── flow steps (IO, transport-injected) ──────────────────────────────────

  /** Send with the jar: attach the Cookie header, absorb Set-Cookie, map
    * non-2xx to `ExperienceError` and 2xx to parsed JSON (204/empty → `{}`). */
  def run(send: Send)(jar: CookieJar)(request: ExperienceRequest): IO[Either[ExperienceError, Json]] =
    jar.cookieHeader.flatMap(cookie => send(request.copy(cookie = cookie))).flatMap { resp =>
      jar.absorb(resp.setCookies).flatMap { _ =>
        classify(resp) match
          case Right(json) => IO.pure(Right(json))
          case Left(err)   => IO.pure(Left(err))
      }
    }

  /** `run` bound to the production transport — the routes' call shape
    * (tests inject a scripted `Send` into `run` instead). */
  def jdkSendAndRun(jar: CookieJar)(request: ExperienceRequest): IO[Either[ExperienceError, Json]] =
    run(jdkSend)(jar)(request)

  private def classify(resp: ExperienceResponse): Either[ExperienceError, Json] =
    if resp.status >= 200 && resp.status < 300 then
      if resp.body.trim.isEmpty then Right(Json.obj())
      else
        parser.parse(resp.body).toOption
          .toRight(ExperienceError(resp.status, None, Some(s"invalid JSON body: ${resp.body.take(200)}")))
    else Left(parseError(resp.status, resp.body))

  /** submit → consent orchestration (design §2.2): POST submit first; when
    * the answer signals consent (a `redirectTo` pointing at /consent, or an
    * error code mentioning consent) run the GET-info + POST-approve dance
    * and surface the post-consent redirect. Any other error (e.g. MFA
    * required) is returned untouched — the wizard drives those steps. */
  def submitWithConsent(send: Send)(endpoint: String, jar: CookieJar): IO[Either[ExperienceError, String]] =
    def consentFlow: IO[Either[ExperienceError, String]] =
      run(send)(jar)(consentInfo(endpoint)).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(_) =>
          run(send)(jar)(consentApprove(endpoint, None)).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(json) => IO.pure(
              redirectToOf(json).toRight(
                ExperienceError(502, None, Some("consent approve response missing redirectTo"))))
          }
      }
    run(send)(jar)(submit(endpoint)).flatMap {
      case Right(json) =>
        redirectToOf(json) match
          case Some(url) if isConsentRedirect(url) => consentFlow
          case Some(url)                           => IO.pure(Right(url))
          case None => IO.pure(Left(ExperienceError(502, None, Some("submit response missing redirectTo"))))
      case Left(err) if err.isConsentRequired => consentFlow
      case Left(err)                          => IO.pure(Left(err))
    }

  // ── production transport ─────────────────────────────────────────────────

  /** JDK-backed Send. Redirects are NEVER followed (the 303 interception is
    * protocol, not transport noise); HTTP/1.1 + system-proxy bypass + 15s
    * timeouts — same policy as `LogtoDeviceFlow.jdkSend` (Caddy fronting
    * breaks HTTP/2 reuse). Transport failures surface as status 0 with the
    * message in `body`. */
  val jdkSend: Send = request =>
    IO.blocking {
      val client = HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(java.net.ProxySelector.of(null))
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()
      val builder = HttpRequest
        .newBuilder()
        .uri(URI.create(request.url))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", request.contentType)
      request.cookie.foreach(c => builder.header("Cookie", c))
      val publisher = request.body match
        case Some(b) => HttpRequest.BodyPublishers.ofString(b)
        case None    => HttpRequest.BodyPublishers.noBody()
      val response = client.send(builder.method(request.method, publisher).build(), HttpResponse.BodyHandlers.ofString())
      ExperienceResponse(
        status = response.statusCode(),
        body = response.body(),
        setCookies = response.headers().allValues("set-cookie").asScala.toList,
        location = Option(response.headers().firstValue("location").orElse(null))
      )
    }.handleErrorWith(e => IO.pure(ExperienceResponse(0, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))))

end LogtoExperience
