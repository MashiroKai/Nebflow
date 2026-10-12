package nebflow.gateway

import cats.effect.IO
import nebflow.shared.TunnelOriginMark
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
  /** Legacy `nebflow_token` cookie hit (file-token value, pre-3b face). */
  case object ViaLegacyCookie extends Credential:
    def railName: String = "legacy"
  /** `?token=` query param hit. */
  case object ViaParam extends Credential:
    def railName: String = "param"

/**
 * The ONE gateway authentication criterion (phase 3b session rail).
 *
 * Fixed priority (v3 :71): session cookie > Bearer (file token / PAT) >
 * legacy `nebflow_token` cookie > `?token=` param — selected per call site
 * through a [[Rails]] switch set, so each face declares exactly which rails
 * it accepts.
 *
 * Stage C origin gate: the two file-token-in-request-target rails (`?token=`
 * param / legacy `nebflow_token` cookie) additionally require a DIRECT
 * LOOPBACK connection — a tunnel-origin request falls through both (see
 * [[legacyRailsPermitted]]). The session/Bearer rails are origin-blind.
 *
 * Fall-through semantics (the load-bearing decision): a HIGHER rail that is
 * PRESENTED but INVALID does not short-circuit the request into a rejection —
 * evaluation continues to the next rail. This keeps every pre-3b single-rail
 * request byte-identical (phase A is strictly monotonic relaxation: add the
 * session rail on top, never remove a legacy rail), and it constructively
 * preserves the /ws stale-cookie tolerance (a stale session cookie plus a
 * valid `?token=` now authenticates, where before the stale cookie alone
 * rejected). Every rail still performs a FULL value validation — falling
 * through only means "an invalid high rail is not itself a rejection reason".
 *
 * Rail membership notes:
 *  - PAT (Logto JWT) exists ONLY under `ApiFull` — the /api Bearer branch,
 *    exactly where it lived pre-3b. No other face gains PAT.
 *  - `BearerFileOnly` exists for the AuthAdmin face (console-ticket mint /
 *    token rotate / sessions revoke): session, PAT, legacy cookie and param
 *    are ALL off. This is the R1-1 renewal-chain fix — a relay-mint session
 *    cookie must never be able to reach a face that mints `local-ticket`s,
 *    or a revoked host could renew its own sessions forever.
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
    pat: Boolean,
    legacyCookie: Boolean,
    param: Boolean
  )

  object Rails:
    /** `/api` domain ([[RestApiCtx]]). PAT stays here and ONLY here. legacyCookie stays off (R1-2). */
    val ApiFull: Rails = Rails(session = true, bearerFile = true, pat = true, legacyCookie = false, param = true)

    /** OpenAI-compatible chat face ([[ChatRoutes]]). Session added; 401 semantics unchanged. */
    val OpenAiCompat: Rails = Rails(session = true, bearerFile = true, pat = false, legacyCookie = false, param = false)

    /** WS face: `/ws` handshake + the three `extractToken` consumers (inject / nf-ticket / nf-authcheck). */
    val WsAuth: Rails = Rails(session = true, bearerFile = true, pat = false, legacyCookie = true, param = true)

    /** Browser static faces: uploads + plugin assets. */
    val StaticAuth: Rails = Rails(session = true, bearerFile = false, pat = false, legacyCookie = true, param = true)

    /** AuthAdmin face (R1-1): ONLY the file-token Bearer. Break-glass and CLI are local-machine semantics. */
    val BearerFileOnly: Rails = Rails(session = false, bearerFile = true, pat = false, legacyCookie = false, param = false)
  end Rails

  /**
   * Read-only probe response header (plan §4.2#3): on a 204 from
   * `/api/nf-authcheck`, carries the rail name that hit (`session` / `bearer`
   * / `legacy` / `param`). The B' frontend purge hook consumes ONLY the
   * `session` value; the other values are diagnostic. Name derived from the
   * brand single source — same discipline as the session cookie name.
   */
  val CredentialHeader: String = s"x-${nebflow.shared.Branding.lowerName}-credential"

  private val LegacyCookieName = "nebflow_token"

  /**
   * Stage C origin gate for the two file-token-in-request-target rails
   * (`?token=` param and the legacy `nebflow_token` cookie): accepted ONLY
   * from a DIRECT LOOPBACK connection — never across the portal tunnel, never
   * from a LAN/remote address. Tunnel origin reads through EITHER signal:
   *  - the process-self-signed tunnel mark (the WS tunnel rides a real
   *    loopback socket, so the mark is the only discriminator there — see
   *    [[nebflow.shared.TunnelOriginMark]]);
   *  - a missing remote address (the bridge's in-process HTTP calls have
   *    remote = None; a server-accepted request always carries one).
   * The mark wins over the address: "remote = Some(loopback) AND marked" is
   * treated as tunnel (GatewayAuthSpec pins this hybrid precedence). The
   * address check is pure loopback (127.0.0.1 / ::1 / the v4-mapped form) —
   * a LAN-direct client authenticates via the session/Bearer rails like any
   * other remote caller. Session and Bearer are NOT gated: tunnel traffic is
   * session-cookie driven by design (portal mints a one-time ticket, the
   * exchange sets the session), and Bearer stays a legitimate cross-tunnel
   * shape for CLI/script callers.
   */
  private def legacyRailsPermitted(req: Request[IO]): Boolean =
    !TunnelOriginMark.isMarked(req) && req.remote.exists(addr => addr.host.collapseMappedV4.isLoopback)

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

    def legacyLeg: IO[Option[Credential]] =
      if !rails.legacyCookie || !legacyRailsPermitted(req) then IO.pure(None)
      else
        req.cookies.find(_.name == LegacyCookieName).map(_.content).filter(_.nonEmpty) match
          case Some(v) if Auth.validateToken(v, fileToken) => IO.pure(Some(Credential.ViaLegacyCookie))
          case _ => IO.pure(None)

    def paramLeg: IO[Option[Credential]] =
      if !rails.param || !legacyRailsPermitted(req) then IO.pure(None)
      else
        req.params.get("token").filter(_.nonEmpty) match
          case Some(v) if Auth.validateToken(v, fileToken) => IO.pure(Some(Credential.ViaParam))
          case _ => IO.pure(None)

    sessionLeg.flatMap {
      case some @ Some(_) => IO.pure(some)
      case None =>
        bearerLeg.flatMap {
          case some @ Some(_) => IO.pure(some)
          case None =>
            legacyLeg.flatMap {
              case some @ Some(_) => IO.pure(some)
              case None => paramLeg
            }
        }
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
