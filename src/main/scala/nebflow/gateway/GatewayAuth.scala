package nebflow.gateway

import cats.effect.IO
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Request}

/** Which rail authenticated the request (priority order = declaration order below). */
sealed trait Credential extends Product with Serializable:
  /** Rail name — the read-only `x-<brand>-credential` probe header value (plan §4.2#3). */
  def railName: String

object Credential:
  /** Session cookie hit; carries the touched session record. */
  final case class ViaSession(record: SessionRecord) extends Credential:
    def railName: String = "session"
  /** Bearer hit (`isFileToken` distinguishes the file token from a Logto PAT). */
  final case class ViaBearer(isFileToken: Boolean) extends Credential:
    def railName: String = "bearer"

/**
 * The ONE gateway authentication criterion (phase 3b session rail).
 *
 * Fixed priority: session cookie > Bearer (file token / PAT) — selected per
 * call site through a [[Rails]] switch set, so each face declares exactly
 * which rails it accepts. The two file-token-in-request-target rails (the
 * `?token=` query param and the legacy `nebflow_token` cookie) were RETIRED
 * in stage D of the token retirement: their browser-side emitters are gone
 * and no legal sender remains, so neither rail exists here anymore.
 * Break-glass access is the one-time console ticket (which mints a session)
 * or the file-token Bearer header.
 *
 * Fall-through semantics (the load-bearing decision): a HIGHER rail that is
 * PRESENTED but INVALID does not short-circuit the request into a rejection —
 * evaluation continues to the next rail. This keeps "an expired session
 * cookie plus a valid Bearer" authenticating, and it keeps the criterion
 * monotonic under rail additions. Every rail still performs a FULL value
 * validation — falling through only means "an invalid high rail is not
 * itself a rejection reason".
 *
 * Rail membership notes:
 *  - PAT (Logto JWT) exists ONLY under `ApiFull` — the /api Bearer branch,
 *    exactly where it lived pre-3b. No other face gains PAT.
 *  - `BearerFileOnly` exists for the AuthAdmin face (console-ticket mint /
 *    token rotate / sessions revoke): session and PAT are off. This is the
 *    R1-1 renewal-chain fix — a relay-mint session cookie must never be able
 *    to reach a face that mints `local-ticket`s, or a revoked host could
 *    renew its own sessions forever.
 */
object GatewayAuth:

  /**
   * Per-face rail switch set. The five canonical sets below are the ONLY
   * configurations in the codebase; a new face picks one of them (or argues
   * for a new entry here, never an ad-hoc literal at the call site).
   */
  final case class Rails(
    session: Boolean,
    bearerFile: Boolean,
    pat: Boolean
  )

  object Rails:
    /** `/api` domain ([[RestApiCtx]]). PAT stays here and ONLY here. */
    val ApiFull: Rails = Rails(session = true, bearerFile = true, pat = true)

    /** OpenAI-compatible chat face ([[ChatRoutes]]). Session added; 401 semantics unchanged. */
    val OpenAiCompat: Rails = Rails(session = true, bearerFile = true, pat = false)

    /** WS face: `/ws` handshake + the local file consumers (inject / nf-ticket / nf-authcheck). */
    val WsAuth: Rails = Rails(session = true, bearerFile = true, pat = false)

    /** Browser static faces: uploads + plugin assets. */
    val StaticAuth: Rails = Rails(session = true, bearerFile = false, pat = false)

    /** AuthAdmin face (R1-1): ONLY the file-token Bearer. Break-glass and CLI are local-machine semantics. */
    val BearerFileOnly: Rails = Rails(session = false, bearerFile = true, pat = false)
  end Rails

  /**
   * Read-only probe response header (plan §4.2#3): on a 204 from
   * `/api/nf-authcheck`, carries the rail name that hit (`session` / `bearer`).
   * The B' frontend purge hook consumes ONLY the `session` value; the other
   * value is diagnostic. Name derived from the brand single source — same
   * discipline as the session cookie name.
   */
  val CredentialHeader: String = s"x-${nebflow.shared.Branding.lowerName}-credential"

  /**
   * Run the rails in priority order; the FIRST rail that validates wins.
   * `None` = no rail accepted the request (the caller renders its own
   * rejection — 403 on the gateway faces, 401 on the OpenAI face, unchanged).
   *
   * `fileToken` is the LIVE file token (callers pass `tokenRef.get`) — after
   * a rotation the old Bearer dies here while live sessions keep working
   * (criterion 7).
   */
  def authenticate(
    req: Request[IO],
    fileToken: String,
    sessions: GatewaySessionStore,
    rails: Rails
  ): IO[Option[Credential]] =

    def sessionLeg: IO[Option[Credential]] =
      if !rails.session then IO.pure(None)
      else
        req.cookies.find(_.name == GatewaySessionStore.CookieName).map(_.content).filter(_.nonEmpty) match
          case None    => IO.pure(None)
          case Some(sid) => sessions.verifyAndTouch(sid).map(_.map(Credential.ViaSession.apply))

    def bearerLeg: IO[Option[Credential]] =
      if !rails.bearerFile && !rails.pat then IO.pure(None)
      else
        req.headers.get[Authorization].collectFirst {
          case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t
        } match
          case None => IO.pure(None)
          case Some(t) =>
            val fileOk = rails.bearerFile && Auth.validateToken(t, fileToken)
            val patOk = rails.pat && PatAuth.accepts(t)
            if fileOk || patOk then IO.pure(Some(Credential.ViaBearer(isFileToken = fileOk)))
            else IO.pure(None) // presented-but-invalid Bearer falls through (never short-circuits)

    sessionLeg.flatMap {
      case some @ Some(_) => IO.pure(some)
      case None           => bearerLeg
    }

  end authenticate

  /** Boolean convenience for callers whose contract is a plain verdict. */
  def accepts(
    req: Request[IO],
    fileToken: String,
    sessions: GatewaySessionStore,
    rails: Rails
  ): IO[Boolean] =
    authenticate(req, fileToken, sessions, rails).map(_.isDefined)

end GatewayAuth
