package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

/** Offline regression spec for the weixin iLink side-car seam (wechat iLink
  * batch, 2026-09-30).
  *
  * Deliberately NO network, NO credential and NO host process: the seam holds no
  * socket at all (the official ClawBot plugin owns the protocol), the allowlist
  * is pinned through the constructor seam, and the injection context is a
  * recording fake. What is pinned is every gate / routing / containment decision
  * the seam makes, plus the server-side field contract of the `weixin-ilink`
  * card (and the fact that the `wechat` card is untouched).
  *
  * Mutation safety: each gate arm below is red-by-construction if the
  * corresponding decision is removed — [[WeixinIlinkBridgePlugin.intake]]'s
  * fail-closed branch, its position FIRST (so it also rules the default-session
  * arm), the containment of a raising `injectMessage`, and the field contract.
  */
class WeixinIlinkBridgeSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-ilink-")

  private def inbound(
      senderId: Option[String],
      text: Option[String] = Some("hello"),
      messageId: Option[String] = Some("msg_1")
  ): WeixinIlinkBridgePlugin.Inbound =
    WeixinIlinkBridgePlugin.Inbound(senderId, text, messageId)

  private def meta(id: String, senderId: Option[String]): SessionMeta =
    SessionMeta(
      id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = senderId
        .map(s => Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(s))))
        .getOrElse(Map.empty)
    )

  /** Recording BridgeContext: captures injections and serves a fixed session
    * list. `raiseOnInject` is the crash-isolation seam — an injection that
    * throws must never escape the seam. */
  private final class RecordingCtx(sessions: List[SessionMeta], raiseOnInject: Boolean = false)
      extends BridgeContext:
    val injected: Ref[IO, List[(String, String, Option[String])]] =
      Ref.unsafe[IO, List[(String, String, Option[String])]](Nil)
    def injectMessage(sessionId: String, content: String, senderId: Option[String]): IO[Unit] =
      if raiseOnInject then IO.raiseError(new RuntimeException("injected: peer leg failed"))
      else injected.update(_ :+ ((sessionId, content, senderId)))
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def plugin(allowed: Option[List[String]] = None,
      root: os.Path = tmpRoot()): WeixinIlinkBridgePlugin =
    new WeixinIlinkBridgePlugin(root, pinnedAllowedIlinkUserIds = allowed)

  /** bring the seam up over a recording context (armed gate + routing table). */
  private def started(p: WeixinIlinkBridgePlugin, ctx: BridgeContext): IO[Unit] =
    p.start(ctx)

  // ───────────────────────── gate truth table (4 arms) ─────────────────────────

  test("WI-G1 empty allowlist ⇒ unrestricted: an unknown sender with a binding passes") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin(allowed = Some(Nil))
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_alice")))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(got, List(("s1", "hello", Some("wx_alice"))))
  }

  test("WI-G2 non-empty allowlist + listed sender ⇒ injected into the bound session") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin(allowed = Some(List("wx_alice", "wx_bob")))
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_alice")))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(got, List(("s1", "hello", Some("wx_alice"))))
  }

  test("WI-G3 non-empty allowlist + sender OFF the list ⇒ dropped, nothing injected") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_mallory"))))
    val p = plugin(allowed = Some(List("wx_alice")))
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_mallory")))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist))
      assertEquals(got, List.empty)
  }

  test("WI-G4 non-empty allowlist + UNRESOLVABLE sender identity ⇒ dropped (fail-closed)") {
    // The binding exists and the text exists: only the sender identity is
    // missing. If the identity arm were not fail-closed (or not ordered first),
    // this message would be injected.
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin(allowed = Some(List("wx_alice")))
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(None))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist))
      assertEquals(got, List.empty)
  }

  test("WI-G4b the fail-closed arm is ordered FIRST: it also rules the default-session arm") {
    // A default session IS configured, so the only thing that can stop this
    // injection is the gate arm being evaluated before the default-session arm.
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(allowed = Some(List("wx_alice")), root = root)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(c, inbound(None))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist))
      assertEquals(got, List.empty)
  }

  // ───────────────────────── routing / containment ─────────────────────────

  test("WI-R1 no binding + no default session ⇒ dropped, nothing injected") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_other"))))
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_alice")))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNoSession))
      assertEquals(got, List.empty)
  }

  test("WI-R2 no binding + a configured default session ⇒ injected there (cold read of the config)") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_new")))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
      assertEquals(got, List(("s_default", "hello", Some("wx_new"))))
  }

  test("WI-R3 a message with no text body is never fabricated into a text injection") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_alice"), text = None))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNoText))
      assertEquals(got, List.empty)
  }

  test("WI-R4 crash isolation: a raising injectMessage is contained into a drop, never escapes") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))), raiseOnInject = true)
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_alice")))
    yield assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonInjectFailed))
  }

  test("WI-R5 routing rebuild reads only weixin-ilink bindings; duplicate sender id = last wins") {
    val c = new RecordingCtx(List(
      meta("s1", Some("wx_1")),
      meta("s2", Some("wx_2")),
      meta("s3", Some("wx_1")), // duplicate binding: the last one wins
      meta("s4", None)          // no weixin-ilink bridge entry: not routed
    ))
    val p = plugin()
    for
      _ <- started(p, c)
      routes <- p.routesRef.get
    yield
      assertEquals(routes.get("wx_1"), Some("s3"))
      assertEquals(routes.get("wx_2"), Some("s2"))
      assertEquals(routes.size, 2)
  }

  test("WI-R6 a blank ilink_user_id in a binding is not routable") {
    val c = new RecordingCtx(List(SessionMeta(
      id = "s1", name = "s1", createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString("  ")))
    )))
    val p = plugin()
    for
      _ <- started(p, c)
      routes <- p.routesRef.get
    yield assertEquals(routes, Map.empty)
  }

  test("WI-R7 deliver before start ⇒ dropped(adapter-not-started), no exception") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin()
    for v <- p.deliver(inbound(Some("wx_alice")))
    yield assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotStarted))
  }

  // ───────────────────────── ingest body parsing ─────────────────────────

  test("WI-P1 parseInbound accepts senderId and the peer spelling from_user_id; a missing key stays None") {
    def j(s: String): Json = io.circe.parser.parse(s).toOption.get
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"senderId":"wx_a","text":"hi","messageId":"m1"}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("hi"), Some("m1"))))
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"from_user_id":"wx_b","text":"yo"}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(Some("wx_b"), Some("yo"), None)))
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"text":"anon"}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(None, Some("anon"), None)))
    assert(WeixinIlinkBridgePlugin.parseInbound(j("""["not","an","object"]""")).isLeft)
  }

  // ───────────────────────── lifecycle / activation loop ─────────────────────────

  test("WI-A1 sync: disabled or unverified ⇒ not registered; enabled ∧ verified ⇒ registered and started") {
    val root = tmpRoot()
    for
      m <- nebflow.bridge.BridgeManager.create(new RecordingCtx(Nil))
      _ <- WeixinIlinkBridgePlugin.sync(m, root)
      none0 <- m.registeredNames
      _ <- IO(SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, verifiedBody()))
      _ <- WeixinIlinkBridgePlugin.sync(m, root)
      names <- m.registeredNames
    yield
      assertEquals(none0, Set.empty[String], "an unconfigured install registers nothing")
      assertEquals(names, Set(WeixinIlinkBridgePlugin.Name))
  }

  test("WI-A2 sync with a disabled card unregisters a previously registered seam") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val fake = new nebflow.bridge.BridgePlugin:
      def name: String = WeixinIlinkBridgePlugin.Name
      def start(ctx: BridgeContext): IO[Unit] = IO.unit
      def stop: IO[Unit] = IO.unit
      def onAgentEvent(sessionId: String, event: Json): IO[Unit] = IO.unit
    for
      m <- nebflow.bridge.BridgeManager.create(c)
      _ <- m.register(fake)
      before <- m.registeredNames
      _ <- WeixinIlinkBridgePlugin.sync(m, root)
      after <- m.registeredNames
    yield
      assertEquals(before, Set(WeixinIlinkBridgePlugin.Name))
      assertEquals(after, Set.empty[String], "no card ⇒ the seam is not registered")
  }

  /** A card that verifies: both required plain fields + the required secret. */
  private def verifiedBody(): Json =
    io.circe.parser.parse(
      """{"enabled":true,"fields":{"ilink_bot_id":"deadbeef@im.bot","ilink_user_id":"wx_scan","bot_token":"plain-token"}}"""
    ).toOption.get

  // ─────────────── field contract (cross-branch: part B's definition layer) ───────────────

  private val expected: List[(String, String, Boolean, Option[String], Option[String])] = List(
    ("bot_token", "secret", true, None, Some("social-weixin-bot-token")),
    ("ilink_bot_id", "text", true, None, None),
    ("ilink_user_id", "text", true, None, None),
    ("baseurl", "url", false, Some("^https?://"), None),
    ("allowed_ilink_user_ids", "text", false, None, None)
  )

  test("WI-F1 the weixin-ilink card matches the contract field by field, in order") {
    val spec = SocialChannels.channel("weixin-ilink")
    assert(spec.isDefined, "the weixin-ilink card must exist")
    val fields = spec.get.fields
    assertEquals(fields.map(_.key), expected.map(_._1), "the key set and its order are the contract")
    expected.zip(fields).foreach { case ((k, kind, req, pat, sec), f) =>
      assertEquals(f.key, k)
      assertEquals(f.kind, kind, s"$k kind")
      assertEquals(f.required, req, s"$k required")
      assertEquals(f.pattern, pat, s"$k pattern")
      assertEquals(f.secretName, sec, s"$k secretName")
    }
    // Stored key shape: the one secret keeps a `_ref` path, nothing else does.
    assertEquals(fields.filter(_.isSecret).map(_.storedKey), List("bot_token_ref"))
    assertEquals(fields.filterNot(_.isSecret).map(_.storedKey), List(
      "ilink_bot_id", "ilink_user_id", "baseurl", "allowed_ilink_user_ids"))
  }

  test("WI-F2 the wechat card's field set is UNCHANGED (regression lock)") {
    val wechat = SocialChannels.channel("wechat")
    assert(wechat.isDefined, "the existing wechat card must stay")
    assertEquals(
      wechat.get.fields.map(f => (f.key, f.kind, f.required, f.pattern, f.secretName)),
      List(
        ("app_id", "text", true, Some("^wx[0-9a-f]{16}$"), None),
        ("app_secret", "secret", true, None, Some("social-wechat-app-secret")),
        ("token", "secret", true, None, Some("social-wechat-token")),
        ("aes_key", "secret", true, None, Some("social-wechat-aes-key"))
      )
    )
  }

  test("WI-F3 channel id set: wechat unchanged and weixin-ilink present exactly once") {
    val ids = SocialChannels.channelIds
    assertEquals(ids.count(_ == "weixin-ilink"), 1, "the new card is registered once")
    assert(ids.contains("wechat"), "the wechat card stays registered")
    assertEquals(ids.distinct.size, ids.size, "no duplicate channel id")
    assertEquals(ids, List("wechat", "weixin-ilink", "feishu", "telegram"),
      "the channel id order is part of the read-side payload shape")
  }

  test("WI-F4 read side: the new card renders in channelsJson with its stored fields") {
    val root = tmpRoot()
    val json = SocialChannels.channelsJson(root)
    val entry = json.hcursor.downField("channels").downField("weixin-ilink")
    assertEquals(entry.downField("enabled").as[Boolean].toOption, Some(false))
    assertEquals(entry.downField("adapterRegistered").as[Boolean].toOption, Some(false))
  }

  test("WI-F5 the gate reads the stored card, and an unset field means unrestricted") {
    val root = tmpRoot()
    assertEquals(WeixinIlinkBridgePlugin.readChannelConfig(root).allowedIlinkUserIds, List.empty[String])
    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name,
      io.circe.parser.parse("""{"fields":{"allowed_ilink_user_ids":"wx_a, wx_b;wx_c"}}""").toOption.get) match
      case Right(_) => ()
      case Left(err) => fail(s"save refused: $err")
    assertEquals(
      WeixinIlinkBridgePlugin.readChannelConfig(root).allowedIlinkUserIds,
      List("wx_a", "wx_b", "wx_c"))
  }

end WeixinIlinkBridgeSpec
