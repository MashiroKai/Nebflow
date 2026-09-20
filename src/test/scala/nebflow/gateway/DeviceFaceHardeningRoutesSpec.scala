package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.neblink.{NeblinkService, PeerInfo}
import org.http4s.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketContext
import org.typelevel.ci.CIString
import org.typelevel.vault.Key

/**
 * Device-face hardening batch (chain-devsec-bleed), gateway-side legs:
 *
 *  - **A1 receiver half** — the presence WS upgrade takes the claiming deviceId
 *    from the handshake header first (`Protocol.DeviceHeader`) and still accepts
 *    the legacy `?deviceId=` query param, so peers built before the change pair.
 *  - **A2** — the private-LAN predicate no longer calls IPv4/IPv6 LINK-LOCAL
 *    "private" (`169.254.0.0/16` / `fe80::/10`, both self-assignable on the
 *    wire), and device-ID membership now also requires the peer to be FRESH (the
 *    reused `NeblinkService.isPeerOnline` predicate) — a stale/offline row no
 *    longer grants the fallback.
 *  - **11-route takedown** — the retired arms are gone from the route table
 *    (they fall through to None), while their live siblings are still served.
 *
 * Route liveness is asserted against the surface AS PRODUCTION MOUNTS IT
 * (`routes <+> presenceWsRoutes(wsb)`, same shape as GatewayMain), with a Bearer
 * token: a declared arm answers — 200 or an app-level 4xx — while a path nobody
 * declares falls through to None, which is exactly the takedown's observable
 * contract. The first takedown test carries a POSITIVE CONTROL on a known-live
 * path, so an all-None surface cannot masquerade as a clean takedown.
 */
class DeviceFaceHardeningRoutesSpec extends FunSuite:

  private val TestToken = "test-token-devface"
  private val tempRoot: os.Path = os.pwd / "target" / "test-devface-routes"
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "devface-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private val routes = new RestApiRoutes(
    token = TestToken,
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    sharedResources = mkResources,
    sessionStore = null,
    wsRoutes = null
  )

  private def get(path: String): Request[IO] =
    Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path))

  private def post(path: String): Request[IO] =
    Request[IO](method = Method.POST, uri = Uri.unsafeFromString(path))

  private def served(req: Request[IO]): Boolean =
    mounted(authed(req)).value.unsafeRunSync().isDefined

  /** The route surface as PRODUCTION mounts it — `Router("/api" -> (routes.routes
    * <+> presenceWsRoutes(wsb)))`, the same shape GatewayMain:1124 and the
    * presence specs use.
    *
    * WHY the union and not just `routes`: the arms this batch retires sit in
    * `presenceWsRoutes` (they follow the WS presence arm inside that block),
    * while `/health`, `/groups` and the neblink arms sit in `routes`. Probing one
    * table would report every path as absent and read as a clean takedown —
    * which is exactly the false-green the positive control below exists to
    * catch (it did, on the first two runs of this spec).
    *
    * `wsb` is only captured; no WS arm body runs. Constructed directly from the
    * public companion so this stays a unit test with no bound port. */
  private val wsb: WebSocketBuilder2[IO] =
    WebSocketBuilder2[IO](Key.newKey[IO, WebSocketContext[IO]].unsafeRunSync())(using
      cats.Applicative[IO]
    )

  private def mounted: HttpRoutes[IO] = routes.routes <+> routes.presenceWsRoutes(wsb)

  /** Same Bearer shape the established gateway specs send (GroupApiRoutesSpec).
    * A token is deliberate: the invariant being pinned is "a path DECLARED in
    * the table answers (200 or an app-level 4xx) while a path nobody declares
    * falls through to None" — so the request must be one the handler would
    * actually serve. */
  private def authed(req: Request[IO]): Request[IO] =
    req.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $TestToken"))

  // ── A1 receiver half ───────────────────────────────────────────────────────

  test("A1② receiver: header wins over the legacy query param; query alone still works") {
    val fromHeader = get("/neblink/presence?deviceId=query-id")
      .putHeaders(Header.Raw(CIString("X-Neblink-Device"), "header-id"))
    val blankHeader = get("/neblink/presence?deviceId=query-id")
      .putHeaders(Header.Raw(CIString("X-Neblink-Device"), "   "))
    val h = routes.presencePeerDeviceId(fromHeader)
    val q = routes.presencePeerDeviceId(get("/neblink/presence?deviceId=query-id"))
    val n = routes.presencePeerDeviceId(get("/neblink/presence"))
    val b = routes.presencePeerDeviceId(blankHeader)
    println(s"[A1-R3] header=$h · query-only=$q · neither='$n' · blank-header=$b")
    assertEquals(h, "header-id", "the handshake header is the primary carrier")
    assertEquals(q, "query-id", "legacy dialers (query only) must keep pairing")
    assertEquals(n, "")
    assertEquals(b, "query-id", "a blank header must fall back, not shadow")
  }

  // ── A2 ─────────────────────────────────────────────────────────────────────

  test("A2: link-local addresses are no longer 'private LAN'; the legit private ranges stay") {
    val mustBePrivate = List(
      "127.0.0.1",
      "::1",
      "10.0.0.7",
      "10.255.255.254",
      "192.168.1.9",
      "172.16.0.1",
      "172.31.255.255",
      "::ffff:10.1.2.3"
    )
    val mustNot = List(
      "169.254.1.1",
      "169.254.0.1",
      "fe80::1",
      "fe80::aabb:ccff:fedd:eeff",
      "172.15.0.1",
      "172.32.0.1",
      "8.8.8.8",
      "203.0.113.4",
      ""
    )
    val wrongYes = mustBePrivate.filterNot(routes.isPrivateLanIp)
    val wrongNo = mustNot.filter(routes.isPrivateLanIp)
    println(s"[A2-R1] private(yes) = $mustBePrivate")
    println(s"[A2-R2] private(no)  = $mustNot  ← 169.254./fe80: were TRUE pre-change")
    assert(wrongYes.isEmpty, s"these legit private addresses were hurt: $wrongYes")
    assert(wrongNo.isEmpty, s"these must NOT count as private LAN: $wrongNo")
  }

  test("A2: the device-ID fallback also requires peer FRESHNESS (reused isPeerOnline)") {
    val now = System.currentTimeMillis()
    val fresh = PeerInfo("dev-fresh", "Fresh", "macos", "http://10.0.0.5:8080", lastSeen = now - 1_000)
    val stale = PeerInfo("dev-stale", "Stale", "macos", "http://10.0.0.6:8080", lastSeen = now - 600_000)
    val offlineMarker = PeerInfo("dev-off", "Off", "macos", "http://10.0.0.7:8080", lastSeen = 0L)
    val peers = List(fresh, stale, offlineMarker)
    val window = NeblinkService.onlineFreshnessMs(45)
    val results = List(
      ("fresh", routes.isFreshKnownPeer(peers, "dev-fresh", now, 45)),
      ("stale", routes.isFreshKnownPeer(peers, "dev-stale", now, 45)),
      ("offline(lastSeen=0)", routes.isFreshKnownPeer(peers, "dev-off", now, 45)),
      ("unknown", routes.isFreshKnownPeer(peers, "dev-nobody", now, 45))
    )
    println(s"[A2-R3] freshness window = ${window}ms · results = $results")
    assertEquals(
      results,
      List(("fresh", true), ("stale", false), ("offline(lastSeen=0)", false), ("unknown", false))
    )
  }

  // ── 11-route takedown ──────────────────────────────────────────────────────

  private val retired: List[(String, Request[IO])] = List(
    ("POST /api/flow/event", post("/flow/event")),
    ("GET /api/running-flows", get("/running-flows")),
    ("GET /api/flow/dag/:name", get("/flow/dag/code-review")),
    ("GET /api/teams", get("/teams")),
    ("GET /api/teams/status/:sid", get("/teams/status/test-session")),
    // NOTE: GET /api/agents/list is the ONE retired path that is deliberately
    // absent from this list — see the shadow test below. Its dedicated arm IS
    // gone, but `/agents/:name` (a pre-existing live arm) still matches the
    // path, so "falls through to None" would be a false claim for it.
    ("GET /api/skills", get("/skills")),
    ("POST /api/plugins/:name/dispatch/clear", post("/plugins/visual-report/dispatch/clear")),
    ("GET /api/flows/list", get("/flows/list")),
    ("GET /api/teams/:teamName", get("/teams/research")),
    ("GET /api/entity-agents", get("/entity-agents"))
  )

  private val stillServed: List[(String, Request[IO])] = List(
    ("GET /api/teams/mounted", get("/teams/mounted")),
    ("GET /api/teams/mailbox/:sid/:flow", get("/teams/mailbox/test-session/research")),
    ("GET /api/teams/def/:flow", get("/teams/def/research")),
    ("GET /api/team/rules/:name", get("/team/rules/research")),
    ("GET /api/agents/:name", get("/agents/Nebula")),
    ("GET /api/agents/:name/model", get("/agents/Nebula/model")),
    ("GET /api/presets", get("/presets")),
    ("GET /api/plugins", get("/plugins")),
    ("POST /api/plugins/:name/dispatch/grant", post("/plugins/visual-report/dispatch/grant"))
  )

  test("11-route takedown: every retired arm falls through the route table") {
    // POSITIVE CONTROL FIRST: the table must be answering at all before "None"
    // for a retired path means anything. Without it, a table that answered
    // nothing would read as a successful takedown (vacuous green).
    val control = served(get("/presets"))
    println(s"[RTE-R0] POSITIVE CONTROL GET /presets ⇒ served=$control")
    assert(control, "the route table answered nothing — the takedown readings would be vacuous")

    val alive = retired.collect { case (label, req) if served(req) => label }
    retired.foreach { case (label, req) => println(s"[RTE-R1] retired $label ⇒ served=${served(req)}") }
    assert(alive.isEmpty, s"these retired routes are still served: $alive")
  }

  test("11-route takedown: GET /agents/list is SHADOWED by the live /agents/:name arm") {
    // `/agents/list` used to be matched by its own dedicated arm, which sat
    // BEFORE `GET /agents/:name` in the route table. With that arm retired the
    // path now falls into the parameterized sibling (agentName = "list"), so
    // the path is still ANSWERED — by a different handler, with different
    // semantics (agent lookup, not the agent catalogue).
    //
    // This is pinned rather than asserted-away, because the pin is what makes
    // the takedown honest: the observable "an unauthenticated request is
    // refused" is IDENTICAL for both handlers (withAuth refuses before either
    // body runs), so this spec alone cannot prove WHICH arm answered. The
    // takedown's proof for this path is the source-level arm census in the
    // batch evidence, and the limitation is recorded in the batch report.
    val shadowed = served(get("/agents/list"))
    println(s"[RTE-R3] GET /agents/list ⇒ served=$shadowed (by /agents/:name, agentName=\"list\")")
    assert(shadowed, "the parameterized sibling answers this path — pinned so the shadow cannot change silently")
    assert(served(get("/agents/Nebula")), "the parameterized arm itself must stay live")
    assert(served(get("/agents")), "the bare GET /agents arm is a different, still-live arm")
  }

  test("11-route takedown: their live siblings are still served (zero collateral)") {
    val dead = stillServed.collect { case (label, req) if !served(req) => label }
    stillServed.foreach { case (label, req) => println(s"[RTE-R2] live $label ⇒ served=${served(req)}") }
    assert(dead.isEmpty, s"these live routes were collaterally dropped: $dead")
  }
