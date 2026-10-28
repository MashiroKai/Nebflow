package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}

import java.net.http.{HttpRequest, HttpResponse}
import java.net.{URI, URLDecoder}
import scala.jdk.CollectionConverters.*

import LogtoTransport.Request

/**
 * Headless FIRST-PARTY password login against the deployed Logto tenant —
 * 统一登录路径②（台账 #924，作者裁定 2026-10-26：「无头登录场景，通过 cli 用账号
 * 密码登录，登录之后就自动注册设备到用户的设备列表下，整体路径一样，只是登录方式
 * 不一样」）。
 *
 * Mechanism = the same protocol the hosted sign-in page speaks, driven
 * headless with a cookie jar (NO browser, NO device code):
 *
 *   1. `GET /oidc/auth?...` (AC+PKCE authorize; 303, NOT followed) — mounts
 *      the OIDC interaction (`_logto` / `_interaction` cookies);
 *   2. `PUT /api/experience {"interactionEvent":"SignIn"}` → 204 — opens the
 *      Experience session ON that interaction;
 *   3. `POST /api/experience/verification/password
 *      {"identifier":{"type":...,"value":...},"password":...}` → 200
 *      `{verificationId}` — the password-identifier recognition leg;
 *   4. `POST /api/experience/identification {"verificationId":...}` → 2xx;
 *   5. `POST /api/experience/submit` → 200 `{redirectTo}`;
 *   6. `GET redirectTo` (same jar, 303 NOT followed) → `Location` = the
 *      loopback callback carrying `?code=...&state=...` — the code is taken
 *      from the Location header and exchanged at `/oidc/token` with the PKCE
 *      verifier ([[LogtoAuthCode.parseTokenResponse]], the production-proven
 *      browser-path parser).
 *
 * DIALECT PINNED TO THE DEPLOYED TENANT (shape probes 2026-10-26, zero
 * credentials, reports-kai see 台账 #924): this tenant runs the
 * Experience-API v1-beta shape — `PUT /api/experience` takes
 * `interactionEvent` (the GA `POST /api/experience {method}` returns 405
 * here) and the identifier is `{"type","value"}` (not `{"username"}`).
 * Wrong credentials answer 422 `session.invalid_credentials` (anti-
 * enumeration: no user-existence leak); identification/submit answer
 * structured `session.*` codes.
 *
 * SECURITY CONTRACT (security self-check, 台账 #924):
 *   · the password lives in the request body of step 3 ONLY — it never
 *     enters any [[Failure]] payload, any log line, or any persisted store;
 *   · transport = TLS (auth.nebflow.space) via the shared Direct15s policy,
 *     same as every other Logto conversation this codebase makes;
 *   · the exchange rides first-party code end to end — the CLI hands the
 *     credentials to the LOCAL gateway (loopback), the gateway performs the
 *     dance; no credential ever transits a third-party process beyond the
 *     first-party tenant itself.
 *
 * HTTP is injected: `Send` carries a cookie header in and returns
 * Set-Cookie/Location out (the plain `LogtoTransport.Send` cannot — the
 * Experience session IS cookies); [[jdkSend]] is the production transport.
 * Every protocol decision below is unit-tested against a fake transport.
 */
object LogtoPasswordFlow:

  /**
   * One request WITH the current cookie header; the response carries raw
   * `Set-Cookie` values (merged by [[mergeCookies]]) and the `Location`
   * header (the 303 legs are never auto-followed).
   */
  final case class Resp(
    status: Int,
    setCookies: List[String],
    location: Option[String],
    body: String
  )

  /** Injectable transport: (request, cookie-header) -> response. */
  type Send = (Request, String) => IO[Resp]

  // ── failure model (closed set; NO credential material in any payload) ────

  sealed trait Failure

  object Failure:
    /** Transport-level breakdown (status 0): connection refused, TLS, DNS… */
    final case class Transport(message: String) extends Failure

    /** Provider said the account/password pair is wrong (anti-enumeration face). */
    case object InvalidCredentials extends Failure

    /** Provider throttled the attempt (or the gateway's own limiter fired). */
    case object RateLimited extends Failure

    /**
     * Unexpected protocol shape: a route answered but not with the contract
     * above (schema drift, session reuse, state mismatch). `step` names the
     * dance leg; `code` is the provider's stable error code when one exists.
     */
    final case class Protocol(step: String, code: String, message: String) extends Failure

  end Failure

  // ── pure: identifier derivation + cookie merge + redirect parsing ────────

  /** `kai@x.com` → `email`, anything else → `username` (phone not offered). */
  def identifierTypeOf(account: String): String =
    if account.contains('@') then "email" else "username"

  /**
   * Merge raw `Set-Cookie` values into the request cookie header. First
   * `name=value` segment of each Set-Cookie wins (attributes dropped — the
   * whole dance talks to ONE host, no cross-host scoping needed); a later
   * same-name cookie replaces an earlier one; an EMPTY value (`name=;`)
   * deletes the name. Deterministic order = insertion order of last write.
   */
  def mergeCookies(currentHeader: String, setCookies: List[String]): String =
    val start: Vector[(String, String)] =
      if currentHeader.isBlank then Vector.empty
      else
        currentHeader
          .split("; ")
          .toVector
          .flatMap { pair =>
            pair.indexOf('=') match
              case i if i > 0 => Some(pair.substring(0, i) -> pair.substring(i + 1))
              case _ => None
          }
    val updated = setCookies.foldLeft(start) { (acc, sc) =>
      val first = sc.takeWhile(_ != ';').trim
      first.indexOf('=') match
        case i if i > 0 =>
          val (k, v) = (first.substring(0, i).trim, first.substring(i + 1).trim)
          if v.isEmpty then acc.filterNot(_._1 == k) // deletion cookie
          else acc.filterNot(_._1 == k) :+ ((k, v))
        case _ => acc // malformed Set-Cookie: ignored, never fatal
    }
    updated.map((k, v) => s"$k=$v").mkString("; ")
  end mergeCookies

  /** Parse `code` / `state` from a redirect-Location query (RFC 6749 §4.1.2). */
  def parseRedirectQuery(location: String): Either[String, (String, String)] =
    val idx = location.indexOf('?')
    if idx < 0 then Left("redirect location carries no query")
    else
      val params = location
        .substring(idx + 1)
        .split("&")
        .toList
        .flatMap { pair =>
          pair.indexOf('=') match
            case i if i > 0 =>
              Some(
                URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
                  URLDecoder.decode(pair.substring(i + 1), "UTF-8")
              )
            case _ => None
        }
        .toMap
      for
        code <- params.get("code").filter(_.nonEmpty).toRight("redirect carries no code")
        state <- params.get("state").toRight("redirect carries no state")
      yield (code, state)
    end if
  end parseRedirectQuery

  // ── pure: request builders (dialect pinned to the deployed tenant) ───────

  /**
   * The interaction-starting authorize URL. Same parameter family as the
   * browser path ([[LogtoAuthCode.authorizeUrl]]: AC+PKCE S256,
   * `openid email profile`) MINUS `prompt` — the headless dance has no page
   * to render a consent screen on, and the provider auto-consents a
   * first-party native client on submit (the hosted page's REST dialect).
   * `nonce` rides for replay protection (passed through to the id_token).
   */
  def interactionAuthorizeUrl(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    codeChallenge: String,
    state: String,
    nonce: String
  ): String =
    s"${endpoint.stripSuffix("/")}${Protocol.LogtoOidc.authorize}?" +
      LogtoTransport.formEncode(
        "client_id" -> clientId,
        "redirect_uri" -> redirectUri,
        "response_type" -> "code",
        "scope" -> "openid email profile",
        "code_challenge" -> codeChallenge,
        "code_challenge_method" -> "S256",
        "state" -> state,
        "nonce" -> nonce
      )

  def putExperienceRequest(endpoint: String): Request =
    Request(
      url = s"${endpoint.stripSuffix("/")}/api/experience",
      contentType = "application/json",
      method = "PUT",
      body = Json.obj("interactionEvent" -> "SignIn".asJson).noSpaces
    )

  /**
   * The password-verification leg. 🔴 The password exists ONLY inside this
   * request body — the failure model ([[Failure]]) never carries it.
   */
  def verifyPasswordRequest(
    endpoint: String,
    identifierType: String,
    identifierValue: String,
    password: String
  ): Request =
    Request(
      url = s"${endpoint.stripSuffix("/")}/api/experience/verification/password",
      contentType = "application/json",
      body = Json
        .obj(
          "identifier" -> Json.obj("type" -> identifierType.asJson, "value" -> identifierValue.asJson),
          "password" -> password.asJson
        )
        .noSpaces
    )

  def identifyRequest(endpoint: String, verificationId: String): Request =
    Request(
      url = s"${endpoint.stripSuffix("/")}/api/experience/identification",
      contentType = "application/json",
      body = Json.obj("verificationId" -> verificationId.asJson).noSpaces
    )

  def submitRequest(endpoint: String): Request =
    Request(
      url = s"${endpoint.stripSuffix("/")}/api/experience/submit",
      contentType = "application/json",
      body = "{}"
    )

  /** Token exchange — same builder as the browser path (pure, no cookies). */
  def tokenRequest(endpoint: String, clientId: String, redirectUri: String, code: String, verifier: String): Request =
    LogtoAuthCode.tokenRequest(endpoint, clientId, redirectUri, code, verifier)

  // ── response mapping (pure) ─────────────────────────────────────────────

  /** Map a non-success Experience-API answer onto the closed failure set. */
  def classifyFailure(step: String, status: Int, body: String): Failure =
    if status == 0 then Failure.Transport(body)
    else
      val parsed = parser.parse(body).toOption
      val code = parsed.flatMap(_.hcursor.downField("code").as[String].toOption).getOrElse("")
      val message = parsed
        .flatMap(_.hcursor.downField("message").as[String].toOption)
        .getOrElse(s"HTTP $status")
      if code.contains("invalid_credentials") || code.contains("not_found") then Failure.InvalidCredentials
      else if status == 429 || code.contains("rate") then Failure.RateLimited
      else Failure.Protocol(step, code, message)

  /** verification/password success → `verificationId`. */
  def parseVerification(body: String): Either[Failure, String] =
    parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.downField("verificationId").as[String].toOption)
      .filter(_.nonEmpty)
      .toRight(Failure.Protocol("verification/password", "", "missing verificationId"))

  /** submit success → `redirectTo`. */
  def parseSubmit(body: String): Either[Failure, String] =
    parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.downField("redirectTo").as[String].toOption)
      .filter(_.nonEmpty)
      .toRight(Failure.Protocol("submit", "", "missing redirectTo"))

  // ── the dance (IO, transport-injected) ──────────────────────────────────

  /**
   * Full headless login: interaction → experience session → password
   * verification → identification → submit → code (state-checked) → token
   * exchange. Returns the SAME `TokenResult` the browser PKCE path yields,
   * so the caller's register + persist leg is byte-identical for both paths.
   *
   * Shape note: six sequential legs with cookie threading — written with
   * EXPLICIT braces and early-exit branches (no deep indentation nesting;
   * the first draft's indentation soup died on the parser).
   */
  def signIn(send: Send)(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    identifierType: String,
    identifierValue: String,
    password: String
  ): IO[Either[Failure, LogtoAuthCode.TokenResult]] =
    for
      verifier <- LogtoAuthCode.generateVerifier
      state <- LogtoAuthCode.generateState
      nonce <- LogtoAuthCode.generateState
      result <- dance(
        send,
        DanceInput(
          base = endpoint.stripSuffix("/"),
          clientId = clientId,
          redirectUri = redirectUri,
          identifierType = identifierType,
          identifierValue = identifierValue,
          password = password,
          verifier = verifier,
          state = state,
          nonce = nonce
        )
      )
    yield result
  end signIn

  /** Everything one dance run carries — pure data, keeps the step fns flat. */
  private final case class DanceInput(
    base: String,
    clientId: String,
    redirectUri: String,
    identifierType: String,
    identifierValue: String,
    password: String,
    verifier: String,
    state: String,
    nonce: String
  )

  private def dance(send: Send, in: DanceInput): IO[Either[Failure, LogtoAuthCode.TokenResult]] =
    // ── leg 1: mount the OIDC interaction (303 expected, never followed) ────
    send(
      Request(
        interactionAuthorizeUrl(
          in.base,
          in.clientId,
          in.redirectUri,
          LogtoAuthCode.challengeS256(in.verifier),
          in.state,
          in.nonce
        ),
        contentType = "application/x-www-form-urlencoded",
        body = "",
        method = "GET"
      ),
      ""
    ).flatMap { r1 =>
      val authorized = r1.status / 100 == 3 || r1.status == 200
      if !authorized then IO.pure(Left(classifyFailure("authorize", r1.status, r1.body)))
      else
        val jar1 = mergeCookies("", r1.setCookies)
        // ── leg 2: open the Experience session ON the interaction ──────────
        send(putExperienceRequest(in.base), jar1).flatMap { r2 =>
          if r2.status / 100 != 2 then IO.pure(Left(classifyFailure("experience", r2.status, r2.body)))
          else
            val jar2 = mergeCookies(jar1, r2.setCookies)
            // ── leg 3: password-identifier recognition ─────────────────────
            send(verifyPasswordRequest(in.base, in.identifierType, in.identifierValue, in.password), jar2).flatMap {
              r3 =>
                if r3.status / 100 != 2 then IO.pure(Left(classifyFailure("verification/password", r3.status, r3.body)))
                else
                  parseVerification(r3.body) match
                    case Left(f) => IO.pure(Left(f))
                    case Right(vid) =>
                      val jar3 = mergeCookies(jar2, r3.setCookies)
                      // ── leg 4: bind the verified identity to the session ────
                      send(identifyRequest(in.base, vid), jar3).flatMap { r4 =>
                        if r4.status / 100 != 2 then
                          IO.pure(Left(classifyFailure("identification", r4.status, r4.body)))
                        else
                          val jar4 = mergeCookies(jar3, r4.setCookies)
                          // ── leg 5: submit → redirectTo ─────────────────────
                          send(submitRequest(in.base), jar4).flatMap { r5 =>
                            if r5.status / 100 != 2 then IO.pure(Left(classifyFailure("submit", r5.status, r5.body)))
                            else
                              parseSubmit(r5.body) match
                                case Left(f) => IO.pure(Left(f))
                                case Right(redirectTo) =>
                                  // ── leg 6: follow redirectTo, take code+state ──
                                  send(
                                    Request(
                                      redirectTo,
                                      contentType = "application/x-www-form-urlencoded",
                                      body = "",
                                      method = "GET"
                                    ),
                                    jar4
                                  ).flatMap { r6 =>
                                    r6.location match
                                      case None =>
                                        IO.pure(
                                          Left(Failure.Protocol("redirect", "", s"HTTP ${r6.status} without Location"))
                                        )
                                      case Some(loc) =>
                                        parseRedirectQuery(loc) match
                                          case Left(m) => IO.pure(Left(Failure.Protocol("redirect", "", m)))
                                          case Right((code, st)) if st == in.state =>
                                            exchangeToken(send)(in.base, in.clientId, in.redirectUri, code, in.verifier)
                                          case Right(_) =>
                                            IO.pure(
                                              Left(Failure.Protocol("redirect", "", "state mismatch (possible CSRF)"))
                                            )
                                  }
                          }
                      }
            }
        }
      end if
    }
  end dance

  /** Token exchange on the shared pure parser (no cookies on this leg). */
  private def exchangeToken(send: Send)(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    code: String,
    verifier: String
  ): IO[Either[Failure, LogtoAuthCode.TokenResult]] =
    send(tokenRequest(endpoint, clientId, redirectUri, code, verifier), "").map { r =>
      if r.status == 200 then LogtoAuthCode.parseTokenResponse(r.body).left.map(m => Failure.Protocol("token", "", m))
      else
        val parsed = parser.parse(r.body).toOption
        val code = parsed.flatMap(_.hcursor.downField("error").as[String].toOption).getOrElse("")
        Left(
          if code.contains("rate") then Failure.RateLimited
          else Failure.Protocol("token", code, s"HTTP ${r.status}")
        )
    }

  // ── production transport ────────────────────────────────────────────────

  /**
   * JDK-backed dance transport on the shared Direct15s policy (same client
   * settings as [[LogtoTransport.jdkSend]]). Adds what the Experience
   * dialect needs over the plain send: the request `method` (GET/PUT), the
   * `Cookie` header from the caller's jar, and the raw `Set-Cookie` +
   * `Location` response headers. Redirects are NEVER auto-followed (the
   * 303/302 legs ARE the protocol). Transport failures surface as
   * status 0 with the exception message — mapped to Failure.Transport.
   */
  val jdkSend: Send = (req, cookieHeader) =>
    IO.blocking {
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
      val builder = HttpRequest
        .newBuilder()
        .uri(URI.create(req.url))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", req.contentType)
      req.method.toUpperCase match
        case "GET" => builder.GET()
        case "PUT" => builder.PUT(HttpRequest.BodyPublishers.ofString(req.body))
        case _ => builder.POST(HttpRequest.BodyPublishers.ofString(req.body))
      if cookieHeader.nonEmpty then builder.header("Cookie", cookieHeader)
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      val setCookies =
        response.headers().allValues("set-cookie").asScala.toList
      val location =
        Option(response.headers().firstValue("location").orElse(null))
      Resp(response.statusCode(), setCookies, location, response.body())
    }.handleErrorWith(e => IO.pure(Resp(0, Nil, None, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))))

end LogtoPasswordFlow
