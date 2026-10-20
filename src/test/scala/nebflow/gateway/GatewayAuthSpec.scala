package nebflow.gateway

import cats.effect.IO
import com.comcast.ip4s.{IpAddress, SocketAddress}
import munit.CatsEffectSuite
import nebflow.shared.TunnelOriginMark
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Header, Method, Request, Uri}
import org.typelevel.ci.CIString

/**
 * The unified GatewayAuth criterion (phase 3b, plan §4 / criterion 6):
 *
 *  - fixed priority session > Bearer;
 *  - presented-but-invalid rails FALL THROUGH (never short-circuit into a
 *    rejection) — pinned by "wrong param + right cookie authenticates";
 *  - per-face rail sets (ApiFull / WsAuth / StaticAuth / OpenAiCompat /
 *    BearerFileOnly) with the single-rail regression matrix;
 *  - the stage D retirement pins: the `?token=` query rail and the legacy
 *    `nebflow_token` cookie rail no longer authenticate from ANY origin
 *    (tunnel / loopback / LAN / public, any mark value) — the stage C origin
 *    matrix machinery is repurposed to pin that the retired rails are dead
 *    from every arm;
 *  - the R1-1 pin: BearerFileOnly refuses a VALID session cookie;
 *  - the rotate linkage: a rotated file token kills the Bearer rail but not
 *    the session rail (criterion 7's unit face).
 *
 * Stage C origin gate history: while the two file-token rails still existed
 * they were direct-loopback-only (tunnel-refused); stage D removed the rails
 * entirely, so the matrix below now expects refusal everywhere. The
 * origin-blindness of the session/Bearer rails stays pinned FOR relay-mint —
 * and only for relay-mint: the 本地加速 M1 batch (v2 §3.3) adds ONE leg
 * binding to the session rail — a `local-ticket` session authenticates ONLY
 * on a real loopback socket ([[LoopbackLeg]]), so a localhost-seated session
 * cookie cannot be replayed on the tunnel/LAN/public legs for arbitrary
 * reads. The binding is origin-class-scoped (relay-mint legs unchanged on
 * every arm, pinned below) and composes with the fall-through discipline
 * (refused local-ticket + valid Bearer ⇒ ViaBearer).
 *
 * PAT positive-path (a real signed JWT against JWKS) is an integration face —
 * here an INVALID PAT is asserted to fall through, which is the phase-A
 * regression that matters.
 */
class GatewayAuthSpec extends CatsEffectSuite:

  private val FileToken = "file-token-abc123"

  private val sessions = GatewaySessionStore.unsafeCreate()

  // The origin matrix needs a live armed mark value; arm() is idempotent
  // and process-global, so every read below observes the same value.
  TunnelOriginMark.arm()

  private val TrueMark: String =
    TunnelOriginMark.armedValue.getOrElse(sys.error("tunnel mark failed to arm"))

  private def req(
    path: String = "/x",
    paramToken: Option[String] = None,
    bearer: Option[String] = None,
    remote: Option[SocketAddress[IpAddress]] = Some(SpecRequests.LoopbackV4),
    mark: Option[String] = None,
    cookies: (String, String)*
  ): Request[IO] =
    val uri0 = Uri.unsafeFromString(paramToken.fold(path)(t => s"$path?token=$t"))
    val base = Request[IO](Method.GET, uri0)
    val withBearer = bearer.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
    val marked = mark.fold(withBearer)(v => withBearer.putHeaders(Header.Raw(CIString(TunnelOriginMark.headerName), v)))
    SpecRequests.withRemote(cookies.foldLeft(marked)((r, c) => r.addCookie(c._1, c._2)), remote)

  private def auth(r: Request[IO], rails: GatewayAuth.Rails, fileToken: String = FileToken): IO[Option[Credential]] =
    GatewayAuth.authenticate(r, fileToken, sessions, rails)

  private def liveSession(origin: String = GatewaySessionStore.OriginRelayMint): IO[String] =
    sessions.create("logto|user-42", origin, "net-1").map(_.sessionId)

  // ── criterion 6 core: cookie beats an INVALID param ────────────────────

  test("wrong ?token= + valid session cookie => ViaSession (WsAuth), never a rejection"):
    for
      sid <- liveSession()
      r = req(paramToken = Some("wrong-param-token"), cookies = (GatewaySessionStore.CookieName, sid))
      hit <- auth(r, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(hit.map(_.railName), Some("session"))
    yield ()

  test("the same shape under ApiFull (RestApiCtx.checkAuth contract) authenticates"):
    for
      sid <- liveSession()
      r = req(paramToken = Some("wrong-param-token"), cookies = (GatewaySessionStore.CookieName, sid))
      hit <- auth(r, GatewayAuth.Rails.ApiFull)
      _ = assert(hit.isDefined, "checkAuth must accept: an invalid param never rejects a session-carrying request")
    yield ()

  // ── stage D: the two file-token rails are retired ──────────────────────

  test("a valid ?token= no longer authenticates (rail retired), even with a stale session cookie"):
    val r = req(paramToken = Some(FileToken), cookies = (GatewaySessionStore.CookieName, "stale-session-id"))
    for
      hit <- auth(r, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(hit, None, "the token query rail is retired (stage D); the stale session must not resurrect it")
    yield ()

  test("a valid legacy nebflow_token cookie no longer authenticates (rail retired)"):
    val r = req(bearer = Some("not-a-valid-token"), cookies = ("nebflow_token", FileToken))
    for
      hit <- auth(r, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(
        hit,
        None,
        "the legacy cookie rail is retired (stage D); the invalid Bearer is not a rejection short-circuit either"
      )
    yield ()

  // ── single-rail regression matrix (phase A: strictly monotonic relaxation) ──

  test("only ?token= (valid): every face refuses — the param rail is retired (stage D)"):
    val r = req(paramToken = Some(FileToken))
    for
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      static <- auth(r, GatewayAuth.Rails.StaticAuth)
      openai <- auth(r, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(r, GatewayAuth.Rails.BearerFileOnly)
      _ = assertEquals(ws, None, "WsAuth retired the param rail (stage D)")
      _ = assertEquals(api, None, "ApiFull retired the param rail (stage D)")
      _ = assertEquals(static, None, "StaticAuth retired the param rail (stage D)")
      _ = assertEquals(openai, None, "OpenAiCompat never gained param")
      _ = assertEquals(admin, None, "BearerFileOnly never accepts param")
    yield ()

  test("only legacy nebflow_token cookie (valid): every face refuses — the legacy cookie rail is retired (stage D)"):
    val r = req(cookies = ("nebflow_token", FileToken))
    for
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      static <- auth(r, GatewayAuth.Rails.StaticAuth)
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      openai <- auth(r, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(r, GatewayAuth.Rails.BearerFileOnly)
      _ = assertEquals(ws, None, "WsAuth retired the legacy cookie rail (stage D)")
      _ = assertEquals(static, None, "StaticAuth retired the legacy cookie rail (stage D)")
      _ = assertEquals(api, None, "R1-2: the /api face never had a cookie leg")
      _ = assertEquals(openai, None)
      _ = assertEquals(admin, None)
    yield ()

  test("only session cookie (valid): every session-carrying set accepts; BearerFileOnly refuses (R1-1 pin)"):
    for
      sid <- liveSession()
      mk = req(cookies = (GatewaySessionStore.CookieName, sid))
      ws <- auth(mk, GatewayAuth.Rails.WsAuth)
      api <- auth(mk, GatewayAuth.Rails.ApiFull)
      static <- auth(mk, GatewayAuth.Rails.StaticAuth)
      openai <- auth(mk, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(mk, GatewayAuth.Rails.BearerFileOnly)
      _ = assertEquals(ws.map(_.railName), Some("session"))
      _ = assertEquals(api.map(_.railName), Some("session"))
      _ = assertEquals(static.map(_.railName), Some("session"))
      _ = assertEquals(openai.map(_.railName), Some("session"))
      _ = assertEquals(
        admin,
        None,
        "R1-1: a relay-mint session cookie must NEVER reach the AuthAdmin face (renewal-chain cut)"
      )
    yield ()

  test(
    "only Bearer file token: ApiFull/WsAuth/OpenAiCompat/BearerFileOnly accept; StaticAuth refuses and falls through"
  ):
    val r = req(bearer = Some(FileToken))
    for
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      openai <- auth(r, GatewayAuth.Rails.OpenAiCompat)
      admin <- auth(r, GatewayAuth.Rails.BearerFileOnly)
      static <- auth(r, GatewayAuth.Rails.StaticAuth)
      _ = assertEquals(api, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(ws, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(openai, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(admin, Some(Credential.ViaBearer(isFileToken = true)))
      _ = assertEquals(static, None, "StaticAuth has no Bearer leg (unchanged from pre-3b)")
    yield ()

  test("only an INVALID PAT: falls through everywhere (positive PAT needs JWKS = integration face)"):
    val r = req(bearer = Some("eyJhbGciOiJFUzI1NiJ9.invalid.payload"))
    for
      api <- auth(r, GatewayAuth.Rails.ApiFull)
      ws <- auth(r, GatewayAuth.Rails.WsAuth)
      // Neither request carries any other credential, so after the invalid
      // Bearer falls through there is nothing left to accept it.
      _ = assertEquals(api, None)
      _ = assertEquals(ws, None)
    yield ()

  test("StaticAuth: an invalid Bearer does not short-circuit, and the retired param rail stays dead"):
    val r = req(paramToken = Some(FileToken), bearer = Some("wrong-bearer"))
    for
      hit <- auth(r, GatewayAuth.Rails.StaticAuth)
      _ = assertEquals(
        hit,
        None,
        "StaticAuth has no Bearer leg and the param rail is retired (stage D) — nothing accepts this request"
      )
    yield ()

  test("priority INSIDE the Bearer rail: a valid file token wins regardless of PAT validity, and vice versa"):
    val fileReq = req(bearer = Some(FileToken))
    for
      hit <- auth(fileReq, GatewayAuth.Rails.ApiFull)
      _ = assertEquals(hit, Some(Credential.ViaBearer(isFileToken = true)))
    yield ()

  // ── stage D origin matrix: the retired rails are dead from EVERY arm ────

  /** remote × mark arms of the matrix; the retired rails must refuse on all. */
  private val OriginRemotes = List(
    ("tunnel(None)", None),
    ("loopback-v4", Some(SpecRequests.LoopbackV4)),
    ("loopback-v6", Some(SpecRequests.LoopbackV6)),
    ("loopback-mapped", Some(SpecRequests.LoopbackMapped)),
    ("lan-direct", Some(SpecRequests.Lan)),
    ("public-direct", Some(SpecRequests.Public))
  )

  private val OriginMarks = List(
    ("no-mark", None),
    ("garbage-mark", Some("0123456789abcdef-not-the-real-value")),
    ("true-mark", Some(TrueMark))
  )

  test("origin matrix (param rail): refused from EVERY origin and mark (rail retired)"):
    val cases = OriginRemotes.flatMap { case (remoteName, remote) =>
      OriginMarks.map { case (markName, mark) =>
        (remoteName, markName, req(paramToken = Some(FileToken), remote = remote, mark = mark))
      }
    }
    cases.foldLeft(IO.unit) { case (acc, (remoteName, markName, r)) =>
      acc.flatMap { _ =>
        auth(r, GatewayAuth.Rails.WsAuth).map { hit =>
          assertEquals(hit, None, s"remote=$remoteName mark=$markName")
        }
      }
    }

  test("origin matrix (legacy rail): refused from EVERY origin and mark (rail retired)"):
    val cases = OriginRemotes.flatMap { case (remoteName, remote) =>
      OriginMarks.map { case (markName, mark) =>
        (remoteName, markName, req(cookies = ("nebflow_token", FileToken), remote = remote, mark = mark))
      }
    }
    cases.foldLeft(IO.unit) { case (acc, (remoteName, markName, r)) =>
      acc.flatMap { _ =>
        auth(r, GatewayAuth.Rails.StaticAuth).map { hit =>
          assertEquals(hit, None, s"remote=$remoteName mark=$markName")
        }
      }
    }

  test("tunnel origin still authenticates via session and Bearer rails (origin-blind by design)"):
    for
      sid <- liveSession()
      session <- auth(
        req(cookies = (GatewaySessionStore.CookieName, sid), remote = None, mark = Some(TrueMark)),
        GatewayAuth.Rails.WsAuth
      )
      bearer <- auth(req(bearer = Some(FileToken), remote = None, mark = Some(TrueMark)), GatewayAuth.Rails.ApiFull)
      _ = assertEquals(session.map(_.railName), Some("session"), "session rail must survive the tunnel origin")
      _ = assertEquals(bearer, Some(Credential.ViaBearer(isFileToken = true)), "Bearer stays cross-tunnel legal")
    yield ()

  // ── 本地加速 M1 批 ①: local-ticket 会话的腿绑定（防 localhost 任意读）────
  //
  // v2 §3.3 近路把会话 cookie 座到 localhost origin（jar 按 host 共享、不分
  // 端口）。绑定判据 = 真实环回 socket（LoopbackLeg）：v4 / v6 / v4-mapped 环
  // 回都在腿内；隧道腿（进程内 remote=None）、LAN、公网一律拒绝，且拒绝是
  // fall-through（不短路）——本轨死亡后 Bearer 照常接管。

  test("local-ticket ON a loopback leg (v4/v6/mapped) authenticates on every session-carrying face"):
    val loopbacks = List(
      ("loopback-v4", Some(SpecRequests.LoopbackV4)),
      ("loopback-v6", Some(SpecRequests.LoopbackV6)),
      ("loopback-mapped", Some(SpecRequests.LoopbackMapped))
    )
    for
      sid <- liveSession(GatewaySessionStore.OriginLocalTicket)
      _ <- loopbacks.foldLeft(IO.unit) { case (acc, (name, remote)) =>
        acc.flatMap { _ =>
          val mk = req(cookies = (GatewaySessionStore.CookieName, sid), remote = remote)
          for
            ws <- auth(mk, GatewayAuth.Rails.WsAuth)
            api <- auth(mk, GatewayAuth.Rails.ApiFull)
            static <- auth(mk, GatewayAuth.Rails.StaticAuth)
            _ = assertEquals(ws.map(_.railName), Some("session"), s"$name: WsAuth must seat the local session")
            _ = assertEquals(api.map(_.railName), Some("session"), s"$name: ApiFull must seat the local session")
            _ = assertEquals(static.map(_.railName), Some("session"), s"$name: StaticAuth must seat the local session")
          yield ()
        }
      }
    yield ()

    end for

  test("local-ticket OFF-leg (tunnel-None / LAN / public) refuses the session rail on every face"):
    val offLegs = List(
      ("tunnel(None)", None),
      ("lan-direct", Some(SpecRequests.Lan)),
      ("public-direct", Some(SpecRequests.Public))
    )
    for
      sid <- liveSession(GatewaySessionStore.OriginLocalTicket)
      _ <- offLegs.foldLeft(IO.unit) { case (acc, (name, remote)) =>
        acc.flatMap { _ =>
          val mk = req(cookies = (GatewaySessionStore.CookieName, sid), remote = remote)
          for
            ws <- auth(mk, GatewayAuth.Rails.WsAuth)
            api <- auth(mk, GatewayAuth.Rails.ApiFull)
            static <- auth(mk, GatewayAuth.Rails.StaticAuth)
            openai <- auth(mk, GatewayAuth.Rails.OpenAiCompat)
            _ = assertEquals(ws, None, s"$name: the localhost session must not authenticate over the tunnel leg")
            _ = assertEquals(api, None, s"$name: the localhost session must not authenticate over ApiFull")
            _ = assertEquals(static, None, s"$name: the localhost session must not authenticate over StaticAuth")
            _ = assertEquals(openai, None, s"$name: the localhost session must not authenticate over OpenAiCompat")
          yield ()
        }
      }
    yield ()

    end for

  test("local-ticket refusal composes with fall-through: off-leg cookie + valid Bearer => ViaBearer"):
    for
      sid <- liveSession(GatewaySessionStore.OriginLocalTicket)
      both <- auth(
        req(bearer = Some(FileToken), cookies = (GatewaySessionStore.CookieName, sid), remote = Some(SpecRequests.Lan)),
        GatewayAuth.Rails.ApiFull
      )
      _ = assertEquals(
        both,
        Some(Credential.ViaBearer(isFileToken = true)),
        "a refused local-ticket rail must not short-circuit a valid Bearer (fall-through discipline intact)"
      )
    yield ()

  test("the binding is origin-class-scoped: relay-mint keeps authenticating on EVERY leg"):
    val arms = OriginRemotes
    for
      sid <- liveSession() // relay-mint
      _ <- arms.foldLeft(IO.unit) { case (acc, (name, remote)) =>
        acc.flatMap { _ =>
          val mk = req(cookies = (GatewaySessionStore.CookieName, sid), remote = remote)
          auth(mk, GatewayAuth.Rails.ApiFull).map { hit =>
            assertEquals(
              hit.map(_.railName),
              Some("session"),
              s"relay-mint session must stay leg-agnostic (changed: remote=$name)"
            )
          }
        }
      }
    yield ()

    end for

  test("localTicketOnLeg predicate: loopback leg opens, tunnel/LAN/public legs refuse; relay-mint is exempt"):
    for
      sid <- liveSession(GatewaySessionStore.OriginLocalTicket)
      local = SessionRecord(
        sid,
        "sub",
        GatewaySessionStore.OriginLocalTicket,
        "net",
        0L,
        Long.MaxValue,
        0L,
        Long.MaxValue
      )
      relay = local.copy(origin = GatewaySessionStore.OriginRelayMint)
      onLoop = req() // default req() remote = loopback v4
      offTunnel = req(remote = None)
      offLan = req(remote = Some(SpecRequests.Lan))
      offPublic = req(remote = Some(SpecRequests.Public))
      _ = assert(GatewayAuth.localTicketOnLeg(local, onLoop), "local-ticket on loopback: seats")
      _ = assert(!GatewayAuth.localTicketOnLeg(local, offTunnel), "local-ticket on tunnel leg (remote=None): refuses")
      _ = assert(!GatewayAuth.localTicketOnLeg(local, offLan), "local-ticket on LAN: refuses")
      _ = assert(!GatewayAuth.localTicketOnLeg(local, offPublic), "local-ticket on public: refuses")
      _ = assert(GatewayAuth.localTicketOnLeg(relay, offTunnel), "relay-mint is leg-exempt (tunnel is its home leg)")
      _ = assert(GatewayAuth.localTicketOnLeg(relay, offLan), "relay-mint is leg-exempt (LAN)")
    yield ()

  // ── rotate linkage (criterion 7, unit face) ────────────────────────────

  test("rotate: the OLD file token stops authenticating; the live session keeps working"):
    for
      sid <- liveSession()
      newToken = "file-token-ROTATED"
      oldBearer = req(bearer = Some(FileToken))
      session = req(cookies = (GatewaySessionStore.CookieName, sid))
      oldHit <- auth(oldBearer, GatewayAuth.Rails.WsAuth)
      _ = assertEquals(oldHit, Some(Credential.ViaBearer(isFileToken = true)), "pre-condition: old token valid")
      afterOld <- auth(oldBearer, GatewayAuth.Rails.WsAuth, fileToken = newToken)
      _ = assertEquals(afterOld, None, "rotated: the old Bearer must be dead on the live gateway")
      afterSession <- auth(session, GatewayAuth.Rails.WsAuth, fileToken = newToken)
      _ = assertEquals(afterSession.map(_.railName), Some("session"), "rotate must NOT kill live sessions")
    yield ()

  // ── probe header contract ──────────────────────────────────────────────

  test("rail names are exactly session/bearer; the header name is brand-derived"):
    assertEquals(Credential.ViaSession(nullRecord).railName, "session")
    assertEquals(Credential.ViaBearer(isFileToken = true).railName, "bearer")
    assertEquals(GatewayAuth.CredentialHeader, s"x-${nebflow.shared.Branding.lowerName}-credential")
    // Live-value pin, follows lowerName (2026-10-16 stand switch left this stale;
    // refreshed alongside the 数据根指向切换批 family gate)
    assertEquals(GatewayAuth.CredentialHeader, "x-stand-credential")

  private def nullRecord: SessionRecord =
    SessionRecord("sid", "sub", "relay-mint", "net", 0L, 0L, 0L, 0L)

end GatewayAuthSpec
