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
   * 本地加速 M1 批 ① —— `local-ticket` 会话的**腿绑定**（防 localhost 任意读）。
   *
   * 近路产品链（v2 §3.3）：门户页探测 → local 类票 → `GET localhost/?ticket`
   * 303 + 会话 cookie 落 **localhost origin**（浏览器 cookie jar 按 host 共享、
   * 不分端口）。绑定即：这条会话令牌只在**真实环回 socket**（[[LoopbackLeg]]）
   * 上作数 —— cookie 一旦离开浏览器（被搬去隧道腿重放 / LAN / 公网直连），
   * 会话轨在本批新增的这条判据上拒绝，兜底只读面（隧道桥进程内请求
   * `remoteAddr=None`）同样拒绝。relay-mint 会话不受影响（门户/隧道的合法腿
   * 本就非环回，行为逐字不变）。
   *
   * 已知残余边界（申报）：**WS 隧道腿**的桥是真拨 `ws://127.0.0.1:<port>`
   * 回环 socket（TunnelOriginMark 文件头引注），对端地址呈现为环回——重放
   * 该腿需先持有受害者本人的门户会话（第二腿转发的是浏览器 cookie），不在
   * 本绑定的威胁模型内；HTTP 隧道腿（进程内，remote=None）与一切非环回
   * 直连腿都被本判据挡死。
   *
   * 顺带语义（无害申报）：verifyAndTouch 先于本判据发生，off-leg 重放会推进
   * 该会话的滑动窗——但重放方在该判据下永远拿不到任何面，推进只惠及本机
   * 合法用户，且 30d 绝对上限不豁免（原 12h，session TTL 批 2026-10-21）。
   */
  def localTicketOnLeg(record: SessionRecord, r: Request[?]): Boolean =
    record.origin != GatewaySessionStore.OriginLocalTicket || LoopbackLeg.isLoopback(r)

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
          case None => IO.pure(None)
          case Some(sid) =>
            sessions.verifyAndTouch(sid).map {
              // 本地加速批 ①（v2 §3.3 近路 + 会话令牌绑定）：local-ticket 会话
              // 只在环回腿上作数 —— 见 localTicketOnLeg。命中即原样放行；
              // off-leg 时本轨拒绝（None ⇒ fall-through，纪律不变）。
              case rec @ Some(record) if localTicketOnLeg(record, req) =>
                rec.map(Credential.ViaSession.apply)
              case Some(_) => None // local-ticket 脱腿：会话轨拒绝（可继续 fall-through）
              case None => None
            }

    def bearerLeg: IO[Option[Credential]] =
      if !rails.bearerFile && !rails.pat then IO.pure(None)
      else
        req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
          t
        } match
          case None => IO.pure(None)
          case Some(t) =>
            val fileOk = rails.bearerFile && Auth.validateToken(t, fileToken)
            val patOk = rails.pat && PatAuth.accepts(t)
            if fileOk || patOk then IO.pure(Some(Credential.ViaBearer(isFileToken = fileOk)))
            else IO.pure(None) // presented-but-invalid Bearer falls through (never short-circuits)

    sessionLeg.flatMap {
      case some @ Some(_) => IO.pure(some)
      case None => bearerLeg
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
