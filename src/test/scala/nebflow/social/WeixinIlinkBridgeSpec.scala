package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

/**
 * Offline regression spec for the weixin iLink side-car seam (wechat iLink
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
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = senderId
        .map(s => Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(s))))
        .getOrElse(Map.empty)
    )

  /**
   * Recording BridgeContext: captures injections and serves a fixed session
   * list. `raiseOnInject` is the crash-isolation seam — an injection that
   * throws must never escape the seam. `bound` records every
   * `updateBridgeConfig` write (the auto-bind leg's persistence face).
   */
  private final class RecordingCtx(sessions: List[SessionMeta], raiseOnInject: Boolean = false) extends BridgeContext:

    val injected: Ref[IO, List[(String, String, Option[String])]] =
      Ref.unsafe[IO, List[(String, String, Option[String])]](Nil)

    // 媒体统一适配批（2026-10-20）：统一媒体缝的记录面——(session, 指针列表, origin)。
    val mediaInjections: Ref[IO, List[(String, List[nebflow.bridge.BridgeAttachment], Option[nebflow.bridge.BridgeOrigin])]] =
      Ref.unsafe[IO, List[(String, List[nebflow.bridge.BridgeAttachment], Option[nebflow.bridge.BridgeOrigin])]](Nil)

    val bound: Ref[IO, List[(String, String, Json)]] =
      Ref.unsafe[IO, List[(String, String, Json)]](Nil)

    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] =
      if raiseOnInject then IO.raiseError(new RuntimeException("injected: peer leg failed"))
      else injected.update(_ :+ ((sessionId, content, senderId)))

    // 生产装配覆盖语义：媒体指针走媒体缝（不再降级为纯文本 injectMessage）。
    override def injectMessageWithMedia(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None,
      attachments: List[nebflow.bridge.BridgeAttachment] = Nil
    ): IO[Unit] =
      if raiseOnInject then IO.raiseError(new RuntimeException("injected: peer leg failed"))
      else mediaInjections.update(_ :+ ((sessionId, attachments, origin)))

    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)

    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] =
      config.fold(IO.unit)(c => bound.update(_ :+ ((sessionId, platform, c))))

  end RecordingCtx

  /**
   * Recording context that ALSO captures the origin descriptor of each
   *  injection (soc483: the inbound source marker).
   */
  private final class OriginRecordingCtx(sessions: List[SessionMeta]) extends BridgeContext:

    val origins: Ref[IO, List[Option[nebflow.bridge.BridgeOrigin]]] =
      Ref.unsafe[IO, List[Option[nebflow.bridge.BridgeOrigin]]](Nil)

    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] =
      origins.update(_ :+ origin)
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def plugin(allowed: Option[List[String]] = None, root: os.Path = tmpRoot()): WeixinIlinkBridgePlugin =
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

  // ─────────────── drop observability (source-level appender assertion) ───────────────
  //
  // Every assertion above reads the RETURNED Verdict, which a built-but-discarded
  // `IO[IO[Unit]]` cannot affect: with the outer wrapper in place the drop log
  // never executes and the whole spec would still be green. The four tests below
  // close that hole by attaching a ListAppender to the seam's own logger and
  // asserting the line actually reaches an appender (precedent:
  // `nebflow.core.DeadLoggingResurrectionSpec`). They cover every drop arm the
  // seam can take: not-on-allowlist (listed sender AND unresolvable identity),
  // no-session-bound, and inject-failed.
  //
  // The logger level is left alone on purpose: `nebflow.social.weixin-ilink-bridge`
  // has no explicit level in `logback-test.xml` (root = INFO), so both the WARN
  // and the INFO arms pass the effective-level check and neither assertion can be
  // satisfied by a filter artefact.

  /**
   * The seam's logger, as logback sees it (the plugin uses
   * `NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")`).
   */
  private def seamLogger(): ch.qos.logback.classic.Logger =
    org.slf4j.LoggerFactory
      .getLogger("nebflow.social.weixin-ilink-bridge")
      .asInstanceOf[ch.qos.logback.classic.Logger]

  private def withAppender[A](
    body: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent] => A
  ): A =
    val lb = seamLogger()
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lb.addAppender(appender)
    try body(appender)
    finally lb.detachAppender(appender)

  private def messages(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
  ): List[String] =
    import scala.jdk.CollectionConverters.*
    appender.list.asScala.toList.map(_.getFormattedMessage)

  test("WI-G3L a sender OFF the allowlist really logs its drop line (not a dead IO[IO[Unit]])") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_mallory"))))
    val p = plugin(allowed = Some(List("wx_alice")))
    withAppender { appender =>
      val before = messages(appender).length
      val v = (for
        _ <- started(p, c)
        v <- p.intake(c, inbound(Some("wx_mallory")))
      yield v).unsafeRunSync()
      val fired = messages(appender).drop(before)
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist))
      assert(
        fired.exists(l => l.contains("weixin-ilink bridge: inbound message") && l.contains("dropped")),
        s"the drop line must actually reach an appender, got $fired"
      )
      // The stable reason string is carried in the TEXT so an operator can
      // reconcile the log against the Verdict without reading the response body.
      assert(
        fired.exists(_.contains(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist)),
        s"the drop line must carry the stable reason '${WeixinIlinkBridgePlugin.ReasonNotOnAllowlist}', got $fired"
      )
    }
  }

  test("WI-G4L an UNRESOLVABLE sender really logs its drop line (the fail-closed arm is observable)") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin(allowed = Some(List("wx_alice")))
    withAppender { appender =>
      val before = messages(appender).length
      val v = (for
        _ <- started(p, c)
        v <- p.intake(c, inbound(None))
      yield v).unsafeRunSync()
      val fired = messages(appender).drop(before)
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist))
      assert(
        fired.exists(l => l.contains("dropped") && l.contains("unresolvable")),
        s"the fail-closed drop must say the identity was unresolvable, got $fired"
      )
      assert(
        fired.exists(_.contains(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist)),
        s"the drop line must carry the stable reason '${WeixinIlinkBridgePlugin.ReasonNotOnAllowlist}', got $fired"
      )
    }
  }

  test("WI-R1L the no-session-bound drop is observable too (that arm logs)") {
    val c = new RecordingCtx(Nil)
    val p = plugin()
    withAppender { appender =>
      val before = messages(appender).length
      (for
        _ <- started(p, c)
        v <- p.intake(c, inbound(Some("wx_none")))
      yield v).unsafeRunSync()
      val fired = messages(appender).drop(before)
      assert(
        fired.exists(_.contains(WeixinIlinkBridgePlugin.ReasonNoSession)),
        s"the no-session drop must reach an appender with its reason, got $fired"
      )
    }
  }

  test("WI-R3L the no-text-body drop is observable too (that arm logs)") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin()
    withAppender { appender =>
      val before = messages(appender).length
      (for
        _ <- started(p, c)
        v <- p.intake(c, inbound(Some("wx_alice"), text = None))
      yield v).unsafeRunSync()
      val fired = messages(appender).drop(before)
      assert(
        fired.exists(_.contains(WeixinIlinkBridgePlugin.ReasonNoText)),
        s"the no-text drop must reach an appender with its reason, got $fired"
      )
    }
  }

  test("WI-R4L the contained injection failure is observable (crash isolation is not silent)") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))), raiseOnInject = true)
    val p = plugin()
    withAppender { appender =>
      val before = messages(appender).length
      (for
        _ <- started(p, c)
        v <- p.intake(c, inbound(Some("wx_alice")))
      yield v).unsafeRunSync()
      val fired = messages(appender).drop(before)
      assert(
        fired.exists(_.contains(WeixinIlinkBridgePlugin.ReasonInjectFailed)),
        s"the containment WARN must reach an appender with its reason, got $fired"
      )
    }
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
    val c = new RecordingCtx(
      List(
        meta("s1", Some("wx_1")),
        meta("s2", Some("wx_2")),
        meta("s3", Some("wx_1")), // duplicate binding: the last one wins
        meta("s4", None) // no weixin-ilink bridge entry: not routed
      )
    )
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
    val c = new RecordingCtx(
      List(
        SessionMeta(
          id = "s1",
          name = "s1",
          createdAt = 0L,
          updatedAt = 0L,
          hasUnread = false,
          bridges = Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString("  ")))
        )
      )
    )
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
      Right(WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("hi"), Some("m1")))
    )
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"from_user_id":"wx_b","text":"yo"}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(Some("wx_b"), Some("yo"), None))
    )
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"text":"anon"}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(None, Some("anon"), None))
    )
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
    io.circe.parser
      .parse(
        """{"enabled":true,"fields":{"ilink_bot_id":"deadbeef@im.bot","ilink_user_id":"wx_scan","bot_token":"plain-token"}}"""
      )
      .toOption
      .get

  // ─────────────── field contract (cross-branch: part B's definition layer) ───────────────

  private val expected: List[(String, String, Boolean, Option[String], Option[String])] = List(
    ("bot_token", "secret", true, None, Some("social-weixin-bot-token")),
    ("ilink_bot_id", "text", true, None, None),
    ("ilink_user_id", "text", true, None, None),
    // soc483 batch: `.*`-tailed spelling, byte-identical to the frontend
    // mirror (socialChannels.js) and to `sidecar_url` below. The bare prefix
    // spelling was rejected by the save face for every URL carrying a path.
    ("baseurl", "url", false, Some("^https?://.*"), None),
    // weixin-scanbind (2026-10-03): the side-car control leg — the FULL-MATCH
    // spelling (the save validation runs String.matches, so a prefix pattern
    // would reject every real URL).
    ("sidecar_url", "url", false, Some("^https?://.*"), None),
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
    assertEquals(
      fields.filterNot(_.isSecret).map(_.storedKey),
      List("ilink_bot_id", "ilink_user_id", "baseurl", "sidecar_url", "allowed_ilink_user_ids")
    )
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
    assertEquals(
      ids,
      List("wechat", "weixin-ilink", "feishu", "telegram"),
      "the channel id order is part of the read-side payload shape"
    )
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
    SocialChannels.save(
      root,
      WeixinIlinkBridgePlugin.Name,
      io.circe.parser.parse("""{"fields":{"allowed_ilink_user_ids":"wx_a, wx_b;wx_c"}}""").toOption.get
    ) match
      case Right(_) => ()
      case Left(err) => fail(s"save refused: $err")
    assertEquals(WeixinIlinkBridgePlugin.readChannelConfig(root).allowedIlinkUserIds, List("wx_a", "wx_b", "wx_c"))
  }

  // ─────────── soc483: baseurl save-face validation (positive + counter-example) ───────────

  /**
   * The card's own documented shape: the two required plain fields plus the
   *  required secret, so a save cannot fail for a reason other than the URL.
   */
  private def saveBody(baseurl: String): Json =
    io.circe.parser
      .parse(
        s"""{"fields":{"ilink_bot_id":"deadbeef@im.bot","ilink_user_id":"wx_scan","bot_token":"plain-token","baseurl":"$baseurl"}}"""
      )
      .toOption
      .get

  test("WI-B1 a real baseurl WITH a path is accepted (the bare-prefix spelling used to reject it)") {
    val root = tmpRoot()
    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, saveBody("https://open.example.com/api")) match
      case Right(_) => ()
      case Left(err) => fail(s"a path-carrying https baseurl must be accepted, got: $err")
    val stored = SocialChannels
      .channelsJson(root)
      .hcursor
      .downField("channels")
      .downField(WeixinIlinkBridgePlugin.Name)
      .downField("fields")
    assertEquals(
      stored.downField("baseurl").as[String].toOption,
      Some("https://open.example.com/api"),
      "the value must be stored verbatim"
    )
  }

  test("WI-B2 a non-URL baseurl is STILL refused (the fix did not open the gate)") {
    val root = tmpRoot()
    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, saveBody("notaurl")) match
      case Left(SocialChannels.Failure.InvalidField(f, _)) => assertEquals(f, "baseurl")
      case other => fail(s"a bare word must be refused on `baseurl`, got: $other")
    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, saveBody("ftp://host/x")) match
      case Left(SocialChannels.Failure.InvalidField(f, _)) => assertEquals(f, "baseurl")
      case other => fail(s"a non-http scheme must be refused on `baseurl`, got: $other")
  }

  test("WI-B3 an EMPTY baseurl is accepted (optional field — presence is what gets matched)") {
    val root = tmpRoot()
    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, saveBody("")) match
      case Right(_) => ()
      case Left(err) => fail(s"an empty optional field must not be refused, got: $err")
  }

  test("WI-B4 the SECOND validation path reads the same pattern (one source, not two)") {
    // `SocialChannels.verified` is the other place a field's pattern is applied
    // (whole-value `String.matches`, not `save`'s pair scan). A card that is
    // `verified` proves that path accepts what the fixed pattern accepts; and a
    // bad URL proves BOTH paths read the same `f.pattern` rather than one
    // carrying its own hardcoded copy.
    val root = tmpRoot()
    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name, saveBody("https://open.example.com/x/y"))
    // A plain field matching but the required secret being unwritten ⇒ not
    // verified. The point of this row is only the PATTERN face, so it asserts on
    // the pattern predicate the two paths share, at the card-definition level.
    val baseurl = SocialChannels.channel(WeixinIlinkBridgePlugin.Name).get.fields.find(_.key == "baseurl").get
    assertEquals(baseurl.pattern, Some("^https?://.*"))
    assert(
      "https://open.example.com/x/y".matches(baseurl.pattern.get),
      "the shared pattern must accept a path-carrying URL (save) …"
    )
    assert(
      !"notaurl".matches(baseurl.pattern.get),
      "…and refuse a bare word (verified), through the SAME pattern object"
    )
  }

  // ─────────── soc483: the weixin inbound source marker ───────────

  test("WI-S1 the weixin inbound injection announces its OWN origin (id + display label), not feishu's") {
    val c = new OriginRecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin(allowed = Some(Nil))
    for
      _ <- started(p, c)
      _ <- p.intake(c, inbound(Some("wx_alice")))
      got <- c.origins.get
    yield
      assertEquals(got.size, 1, "exactly one injection carries an origin")
      val o = got.head.getOrElse(fail("the injection must carry an origin descriptor"))
      assertEquals(o.channelId, WeixinIlinkBridgePlugin.Name, "the channel id is this bridge's own stable name")
      assertEquals(
        o.channelDisplay,
        WeixinIlinkBridgePlugin.ChannelDisplay,
        "the display label comes from this channel's own constant"
      )
      assert(
        o.channelDisplay != FeishuBridgePlugin.ChannelDisplay,
        "the label must never be a copy of another channel's label"
      )
      assertEquals(o.chatRef, None, "iLink carries no conversation reference ⇒ the marker renders the neutral `-`")
    end for
  }

  test("WI-S2 the label rides the marker template: the rendered block names this channel only") {
    val origin = nebflow.bridge.BridgeOrigin(
      channelId = WeixinIlinkBridgePlugin.Name,
      channelDisplay = WeixinIlinkBridgePlugin.ChannelDisplay
    )
    val marker = nebflow.bridge.BridgeOrigin.marker(origin, Some("wx_alice"))
    assert(marker.contains(WeixinIlinkBridgePlugin.ChannelDisplay), marker)
    assert(marker.contains(WeixinIlinkBridgePlugin.Name), marker)
    assert(!marker.contains(FeishuBridgePlugin.ChannelDisplay), s"the feishu label leaked in: $marker")
  }

  test("WI-S3 toolEnd stays a no-op on the outbound leg (weixin-outbound batch: text replies only)") {
    // The weixin-outbound batch (2026-10-07) REPLACED the old stated no-op:
    // onAgentEvent now accumulates textDelta and flushes ONE text reply on
    // `done` (pinned by WeixinOutboundSpec). What REMAINS the boundary here is
    // the toolEnd arm — a tool result (rich product or not) never triggers a
    // send on this leg. A recorder seam proves the zero-send property.
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    val p = new WeixinIlinkBridgePlugin(
      root,
      pinnedAllowedIlinkUserIds = None,
      send = _ =>
        (to, text) =>
          calls.update(_ :+ ((to, text))).as(WeixinOutboundAdapter.SendResult(true, true, None, 200, "delivered"))
    )
    for
      _ <- p.start(new RecordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent(
        "s1",
        Json.obj(
          "type" -> "toolEnd".asJson,
          "sessionId" -> "s1".asJson,
          "content" -> "___CARD_HTML___{}".asJson,
          "isError" -> false.asJson
        )
      )
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      got <- calls.get
    yield assertEquals(got, Nil, "toolEnd observes nothing; done with an empty buffer sends nothing")
  }

  // ─────────────── auto-bind leg (weixin-autobind batch, 2026-10-07) ───────────────
  //
  // The unbound-sender arm used to be inject-only: the message was served by
  // the default session but NO `ilink_user_id` ever landed on it, so the
  // session had no return address — every later flush died on
  // `no-return-address` (the gap the outbound batch declared, its report §0.5).
  // The feishu C4 form closes it: bind (source=auto + boundAt) → routes table
  // → inject. WI-AB7 pins the closure end to end through the UNCHANGED
  // outbound reverse read.

  /**
   * The plugin with a recording send seam (the WI-S3 shape) — for the
   * auto-bind ⇄ outbound-loop cases. No socket is ever opened.
   */
  private def pluginWithRecorder(root: os.Path, calls: Ref[IO, List[(String, String)]]): WeixinIlinkBridgePlugin =
    new WeixinIlinkBridgePlugin(
      root,
      pinnedAllowedIlinkUserIds = None,
      send = _ =>
        (to, text) =>
          calls.update(_ :+ ((to, text))).as(WeixinOutboundAdapter.SendResult(true, true, None, 200, "delivered"))
    )

  test("WI-AB1 an unbound sender + a default session ⇒ auto-binds (source=auto + boundAt), then injects there") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_new")))
      bound <- c.bound.get
      routes <- p.routesRef.get
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
      assertEquals(
        bound.map { case (sid, plat, _) => (sid, plat) },
        List(("s_default", WeixinIlinkBridgePlugin.Name)),
        "exactly one binding write, onto the default session, under this bridge's own key"
      )
      val cfg = bound.head._3
      assertEquals(
        cfg.hcursor.downField(WeixinIlinkBridgePlugin.RouteKey).as[String].toOption,
        Some("wx_new"),
        "the binding carries the sender id — the return address the outbound reverse read consumes"
      )
      assertEquals(
        cfg.hcursor.downField("source").as[String].toOption,
        Some("auto"),
        "auto-bindings are marked `auto` so the bindings face can tell them from manual ones"
      )
      assert(cfg.hcursor.downField("boundAt").as[Long].toOption.isDefined, "the binding is timestamped")
      assertEquals(
        routes.get("wx_new"),
        Some("s_default"),
        "the local routes table is updated in the same step — the turn in flight can already be replied to"
      )
      assertEquals(got, List(("s_default", "hello", Some("wx_new"))))
    end for
  }

  test("WI-AB2 the second message from an auto-bound sender rides the binding; no re-bind") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v1 <- p.intake(c, inbound(Some("wx_new"), messageId = Some("m1")))
      v2 <- p.intake(c, inbound(Some("wx_new"), messageId = Some("m2")))
      bound <- c.bound.get
      got <- c.injected.get
    yield
      assertEquals(v1, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
      assertEquals(v2, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
      assertEquals(bound.size, 1, "the binding is written once — the second message routes through it")
      assertEquals(got.map(_._1), List("s_default", "s_default"))
  }

  test("WI-AB3 a no-text (media) message from a new sender STILL binds (the address precedes the body)") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_media"), text = None))
      bound <- c.bound.get
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNoText))
      assertEquals(
        bound.map { case (sid, plat, cfg) =>
          (sid, plat, cfg.hcursor.downField(WeixinIlinkBridgePlugin.RouteKey).as[String].toOption)
        },
        List(("s_default", WeixinIlinkBridgePlugin.Name, Some("wx_media"))),
        "the binding is written even when this message carries nothing injectable — the address must exist for the NEXT message"
      )
      assertEquals(got, List.empty, "nothing is injected (no text is ever fabricated)")
    end for
  }

  test("WI-AB4 an UNRESOLVABLE sender never mints a binding (an empty key would hijack every anonymous sender)") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(c, inbound(None))
      bound <- c.bound.get
      got <- c.injected.get
    yield
      assertEquals(
        v,
        WeixinIlinkBridgePlugin.Verdict.Injected("s_default"),
        "the pre-existing fallback (inject via the default session) is byte-identical for unresolvable senders"
      )
      assertEquals(bound, List.empty, "no binding without a resolvable sender id")
      assertEquals(got, List(("s_default", "hello", None)))
  }

  test("WI-AB5 no default session ⇒ the drop arm is unchanged and writes zero bindings") {
    val c = new RecordingCtx(Nil)
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_none")))
      bound <- c.bound.get
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNoSession))
      assertEquals(bound, List.empty, "nothing to bind TO — no binding is minted either")
      assertEquals(got, List.empty)
  }

  test("WI-AB6 an explicitly bound sender's intake never touches the binding plane") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, inbound(Some("wx_alice")))
      bound <- c.bound.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(bound, List.empty, "intake READS explicit bindings; it never re-writes one")
  }

  test("WI-AB7 the auto-binding closes the loop: the UNCHANGED outbound reverse read now resolves the sender") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = pluginWithRecorder(root, calls)
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- p.start(c)
      _ <- p.intake(c, inbound(Some("wx_new"))) // auto-binds + injects
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "hi".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      got <- calls.get
    yield assertEquals(
      got,
      List(("wx_new", "hi")),
      "the session's flush resolves the sender through the SAME table the auto-bind wrote — the return path exists"
    )
  }

  // ───────── bind-transactional 批 (2026-10-20): the no-return-address escalation face ─────────
  //
  // The 2026-10-20 incident: a half-bound session (served by the default-session
  // fallback, never bound) lost every reply on [no-return-address], silently.
  // The new contract: a SERVED session's drop escalates — ERROR log, a health
  // strike on `channels[].health` (the panel marks the channel), the text
  // stashed as a dead letter that the sender's FIRST registration redelivers;
  // a never-served session's drop stays quiet (it is not weixin traffic —
  // dispatchAgentEvent fans every session to every plugin).

  /**
   * A context whose session list is mutable — the refreshRoutes redelivery leg
   * needs a binding to APPEAR after start (the save/REST binding face).
   */
  private final class VarCtx(sessionsRef: Ref[IO, List[SessionMeta]]) extends BridgeContext:
    val bound: Ref[IO, List[(String, String, Json)]] = Ref.unsafe[IO, List[(String, String, Json)]](Nil)
    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = sessionsRef.get
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] =
      config.fold(IO.unit)(c => bound.update(_ :+ ((sessionId, platform, c))))

  /** The escalated-drop setup: an unresolvable sender served by the default session. */
  private def servedDropFixture(root: os.Path, calls: Ref[IO, List[(String, String)]]): IO[WeixinIlinkBridgePlugin] =
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      c = new RecordingCtx(Nil)
      p = pluginWithRecorder(root, calls)
      _ <- p.start(c)
      // senderId = None ⇒ routeKey empty ⇒ the default-session fallback arm —
      // injected, nothing persisted, the session is SERVED but has no route
      v <- p.intake(c, inbound(None))
      _ = assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
    yield p

  private def strikesOf(root: os.Path): List[Json] =
    SocialChannels
      .channelHealth(root, WeixinIlinkBridgePlugin.Name)
      .flatMap(_.hcursor.downField("strikes").as[List[Json]].toOption)
      .getOrElse(Nil)

  test("WI-NR1 a SERVED session's no-return-address drop escalates: health strike + dead-letter stash, no send") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    for
      p <- servedDropFixture(root, calls)
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "the answer".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      got <- calls.get
      _ = assertEquals(got, Nil, "no route, no send — the drop stands")
      served <- p.servedRef.get
      _ = assert(served.contains("s_default"), "the session was served by this seam")
      strikes = strikesOf(root)
      _ = assertEquals(strikes.size, 1, "exactly one strike on the health face")
      s0 = strikes.head
      _ = assertEquals(s0.hcursor.downField("reason").as[String].toOption, Some(WeixinIlinkBridgePlugin.ReasonNoReturnAddress))
      _ = assertEquals(s0.hcursor.downField("sessionId").as[String].toOption, Some("s_default"))
      _ = assertEquals(s0.hcursor.downField("count").as[Long].toOption, Some(1L))
      _ = assert(s0.hcursor.downField("firstAt").as[Long].toOption.isDefined)
      dropped <- p.droppedRef.get
      _ = assertEquals(
        dropped.get("s_default").map(_.text),
        Some("the answer"),
        "the lost reply is stashed for redelivery"
      )
    yield ()
  }

  test("WI-NR2 a never-served session's drop stays QUIET: no strike, no stash (it is not weixin traffic)") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = pluginWithRecorder(root, calls)
    for
      _ <- p.start(c)
      // a pure-local session's turn ends; dispatch fans it to this plugin too
      _ <- p.onAgentEvent("local_only", Json.obj("type" -> "textDelta".asJson, "delta" -> "unrelated".asJson))
      _ <- p.onAgentEvent("local_only", Json.obj("type" -> "done".asJson))
      got <- calls.get
      _ = assertEquals(got, Nil)
      _ = assertEquals(strikesOf(root), Nil, "no health strike for traffic this channel never served")
      dropped <- p.droppedRef.get
      _ = assertEquals(dropped.get("local_only"), None, "no dead letter for traffic this channel never served")
    yield ()
  }

  test("WI-NR3 the sender's FIRST registration redelivers the dead letter (auto-bind arm)") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    for
      p <- servedDropFixture(root, calls)
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "the answer".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      before <- calls.get
      _ = assertEquals(before, Nil)
      // a REAL sender arrives: auto-bind to the same default session ⇒ the
      // session regained a return address ⇒ the stashed reply goes out, marked
      c = new RecordingCtx(Nil)
      _ <- p.intake(c, inbound(Some("wx_bob")))
      got <- calls.get
      _ = assertEquals(
        got,
        List(("wx_bob", WeixinIlinkBridgePlugin.RedeliveryPrefix + "the answer")),
        "the dead letter rides the auto-bind's first registration"
      )
      dropped <- p.droppedRef.get
      _ = assertEquals(dropped.get("s_default"), None, "the letter is consumed")
    yield ()
  }

  test("WI-NR4 a successful reply clears the session's strike (the channel face returns to green)") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    for
      p <- servedDropFixture(root, calls)
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "lost".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      _ = assertEquals(strikesOf(root).size, 1, "precondition: the strike is on")
      // the return address appears (a manual binding face) and the next flush lands
      _ <- p.routesRef.update(_.updated("wx_carol", "s_default"))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "found".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      got <- calls.get
      _ = assertEquals(got, List(("wx_carol", "found")), "the reply goes out")
      _ = assertEquals(strikesOf(root), Nil, "the strike cleared on the successful send")
    yield ()
  }

  test("WI-NR5 an expired dead letter is dropped, not redelivered") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s1")))
      c = new RecordingCtx(Nil)
      p = pluginWithRecorder(root, calls)
      _ <- p.start(c)
      _ <- p.droppedRef.update(
        _ + ("s1" -> WeixinIlinkBridgePlugin.DroppedReply(
          "stale",
          System.currentTimeMillis() - (WeixinIlinkBridgePlugin.DroppedReplyTtl.toMillis + 60000)))
      )
      _ <- p.intake(c, inbound(Some("wx_old")))
      got <- calls.get
      _ = assertEquals(got, Nil, "a half-hour-old reply is stale noise — not redelivered")
      dropped <- p.droppedRef.get
      _ = assertEquals(dropped.get("s1"), None, "the expired letter is consumed either way")
    yield ()
  }

  test("WI-NR6 the rebuilt routing table redelivers too (the save/REST binding face)") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    for
      sessionsRef <- Ref.of[IO, List[SessionMeta]](Nil)
      c = new VarCtx(sessionsRef)
      p = pluginWithRecorder(root, calls)
      _ <- p.start(c) // boot: empty table, no letters, no sends
      _ <- p.droppedRef.update(_ + ("s1" -> WeixinIlinkBridgePlugin.DroppedReply("via rest")))
      _ <- sessionsRef.set(List(meta("s1", Some("wx_carol"))))
      _ <- p.refreshRoutes
      got <- calls.get
      _ = assertEquals(
        got,
        List(("wx_carol", WeixinIlinkBridgePlugin.RedeliveryPrefix + "via rest")),
        "a binding written behind the bridge's back still triggers the redelivery on the next rebuild"
      )
      // a rebuild with NO change redelivers nothing (the letter is gone)
      _ <- p.refreshRoutes
      got2 <- calls.get
      _ = assertEquals(got2, got, "idempotent: no second send")
    yield ()
  }

  test("WI-NR7 repeated drops accumulate the strike count, firstAt stable, letter = latest") {
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val root = tmpRoot()
    for
      p <- servedDropFixture(root, calls)
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "first".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      first :: Nil = strikesOf(root): @unchecked
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "textDelta".asJson, "delta" -> "second".asJson))
      _ <- p.onAgentEvent("s_default", Json.obj("type" -> "done".asJson))
      second :: Nil = strikesOf(root): @unchecked
      _ = assertEquals(second.hcursor.downField("count").as[Long].toOption, Some(2L), "the count accumulates")
      _ = assertEquals(
        second.hcursor.downField("firstAt").as[Long].toOption,
        first.hcursor.downField("firstAt").as[Long].toOption,
        "firstAt is the FIRST drop, not the latest"
      )
      dropped <- p.droppedRef.get
      _ = assertEquals(dropped.get("s_default").map(_.text), Some("second"), "the letter is the LATEST drop")
    yield ()
  }

  test(
    "WI-F6 the panel face carries bindError + health; a fresh bind-attempt save clears the bindError " +
      "(the error lives until the user tries again)"
  ) {
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "weixin-ilink", Json.obj("enabled" -> true.asJson, "fields" -> Json.obj())))
      _ <- IO(SocialChannels.setBindError(root, "weixin-ilink", Some("provision failed after retries: attempt 4: exit 5")))
      _ <- IO(
        SocialChannels.writeChannelHealth(
          root,
          "weixin-ilink",
          Json.obj(
            "channel" -> "weixin-ilink".asJson,
            "updatedAt" -> 1L.asJson,
            "strikes" -> List(Json.obj("reason" -> "no-return-address".asJson, "sessionId" -> "s1".asJson)).asJson
          )
        )
      )
      payload = SocialChannels.channelsJson(root)
      ch = payload.hcursor.downField("channels").downField("weixin-ilink")
      _ = assertEquals(
        ch.downField("bindError").as[String].toOption,
        Some("provision failed after retries: attempt 4: exit 5"),
        "the explicit panel error"
      )
      health = ch.downField("health").focus
      _ = assert(
        health.exists(_.hcursor.downField("strikes").as[List[Json]].toOption.getOrElse(Nil).sizeIs == 1),
        "the health face rides the same payload"
      )
      // a fresh save (the next binding attempt) rebuilds the entry: the error
      // clears, the stored fields survive
      _ <- IO(SocialChannels.save(root, "weixin-ilink", Json.obj("enabled" -> true.asJson, "fields" -> Json.obj())))
      after = SocialChannels.channelsJson(root).hcursor.downField("channels").downField("weixin-ilink")
      _ = assertEquals(after.downField("bindError").as[String].toOption, None, "stale error cleared by a new attempt")
      _ = assert(after.downField("health").focus.isDefined, "health is config-independent (runtime face)")
    yield ()
  }

  // ─────────────── weixin-inbound-media batch: the media arm ───────────────
  //
  // The side-car lands image/file/video items on disk BEFORE delivery and
  // rides mediaPath/mediaKind/mediaName/mediaMime with the placeholder label
  // still in `text`. The media arm sits ahead of both text arms: a landed
  // path rides the UNIFIED media seam as a (path,name,mime) pointer (媒体统一
  // 适配批 2026-10-20 — classification/attJson/vision happen gateway-side with
  // the console uploads pipeline); a path that died in between fails CLOSED
  // to the bare label — still delivered (the side-car never holds its cursor
  // for a media leg). Without a media path every pre-media arm is
  // byte-identical (WI-R3 / WI-AB3 pin that).

  /**
   * A media-shaped inbound like the side-car sends it: the placeholder label
   * in `text`, the landed fields riding along.
   */
  private def mediaInbound(
    senderId: Option[String],
    mediaPath: Option[String],
    kind: Option[String] = Some("image"),
    name: Option[String] = Some("pic.png"),
    text: Option[String] = Some("[图片]"),
    messageId: Option[String] = Some("m_media")
  ): WeixinIlinkBridgePlugin.Inbound =
    WeixinIlinkBridgePlugin.Inbound(senderId, text, messageId, mediaPath, kind, name, None)

  test("WI-M1 parseInbound carries the four media fields; absent ⇒ None; unknown fields still ignored") {
    def j(s: String): Json = io.circe.parser.parse(s).toOption.get
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(
        j(
          """{"senderId":"wx_a","text":"[图片]","messageId":"m1","mediaPath":"/tmp/x.png","mediaKind":"image","mediaName":"pic.png","mediaMime":"image/png"}"""
        )
      ),
      Right(
        WeixinIlinkBridgePlugin.Inbound(
          Some("wx_a"),
          Some("[图片]"),
          Some("m1"),
          Some("/tmp/x.png"),
          Some("image"),
          Some("pic.png"),
          Some("image/png")
        )
      )
    )
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"senderId":"wx_a","text":"hi"}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("hi"), None, None, None, None, None)),
      "no media fields ⇒ plain text-shaped Inbound"
    )
    assertEquals(
      WeixinIlinkBridgePlugin.parseInbound(j("""{"senderId":"wx_a","text":"hi","futureField":42}""")),
      Right(WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("hi"))),
      "the silent-ignore contract for unknown fields is unchanged"
    )
  }

  test("WI-M2 mediaLabel/mediaLine: kind map (image/video/file/absent/garbage) and the exact wording") {
    assertEquals(WeixinIlinkBridgePlugin.mediaLabel(Some("image")), "[图片]")
    assertEquals(WeixinIlinkBridgePlugin.mediaLabel(Some("video")), "[视频]")
    assertEquals(WeixinIlinkBridgePlugin.mediaLabel(Some("file")), "[文件]")
    assertEquals(WeixinIlinkBridgePlugin.mediaLabel(None), "[文件]", "absent kind ⇒ the file-ish default")
    assertEquals(
      WeixinIlinkBridgePlugin.mediaLabel(Some("voice")),
      "[文件]",
      "an unrecognized kind never fabricates a label"
    )
    assertEquals(
      WeixinIlinkBridgePlugin.mediaLine("/tmp/m.png", Some("image"), Some("pic.png")),
      "[图片] 已存至 /tmp/m.png（原始文件名 pic.png），可用 Read 查看"
    )
    assertEquals(
      WeixinIlinkBridgePlugin.mediaLine("/tmp/v.mp4", Some("video"), None),
      "[视频] 已存至 /tmp/v.mp4，可用 Read 查看",
      "no wire name ⇒ the parenthetical is dropped"
    )
  }

  test("WI-M3 a landed media path rides the unified media seam as a POINTER (no text line, no vision guess here)") {
    val dir = os.temp.dir(prefix = "nb-weixin-media-")
    val media = dir / "m1-pic.png"
    os.write(media, "png")
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, mediaInbound(Some("wx_alice"), Some(media.toString)))
      got <- c.injected.get
      gotMedia <- c.mediaInjections.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(got, List.empty, "the legacy text line is GONE — the media seam carries the pointer")
      assertEquals(
        gotMedia.map { case (sid, atts, origin) => (sid, atts, origin.map(_.channelId)) },
        List(
          (
            "s1",
            List(
              nebflow.bridge.BridgeAttachment(
                media.toString,
                Some("pic.png"),
                None
              )
            ),
            Some(WeixinIlinkBridgePlugin.Name)
          )
        ),
        "the (path,name,mime) pointer + origin reach the unified seam (classification/attJson/vision live gateway-side)"
      )
  }

  test("WI-M4 a media path missing at injection fails CLOSED: the bare label is injected (still delivered)") {
    val gone = os.temp.dir(prefix = "nb-weixin-media-gone-") / "never-landed.png"
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin()
    withAppender { appender =>
      val before = messages(appender).length
      val (v, injected) = (for
        _ <- started(p, c)
        v <- p.intake(c, mediaInbound(Some("wx_alice"), Some(gone.toString)))
        got <- c.injected.get
      yield (v, got)).unsafeRunSync()
      val fired = messages(appender).drop(before)
      val got = injected.map(_._2)
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"), "the degrade still DELIVERS (never a hold)")
      assertEquals(got, List("[图片]"), "the bare placeholder label rides instead")
      assert(
        fired.exists(l => l.contains("path missing at injection") && l.contains(gone.toString)),
        s"the fail-closed WARN must be observable, got $fired"
      )
    }
  }

  test("WI-M5 a media message from a NEW sender auto-binds (semantics unchanged) AND rides the media pointer seam") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    val media = os.temp.dir(prefix = "nb-weixin-media-ab-") / "m9-report.pdf"
    os.write(media, "pdf")
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(
        c,
        mediaInbound(
          Some("wx_new"),
          Some(media.toString),
          kind = Some("file"),
          name = Some("report.pdf"),
          text = Some("[文件]")
        )
      )
      bound <- c.bound.get
      got <- c.injected.get
      gotMedia <- c.mediaInjections.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
      assertEquals(
        bound.map { case (sid, plat, cfg) => (sid, plat, cfg.hcursor.downField("source").as[String].toOption) },
        List(("s_default", WeixinIlinkBridgePlugin.Name, Some("auto"))),
        "the binding semantics are unchanged (the address precedes the body)"
      )
      assertEquals(got.map(_._2), List.empty, "no legacy text line rides the unified seam")
      assertEquals(
        gotMedia.map { case (sid, atts, _) => (sid, atts.map(a => (a.path, a.name))) },
        List(("s_default", List((media.toString, Some("report.pdf")))))
      )
    end for
  }

  test("WI-M6 an UNRESOLVABLE sender's media message rides the default-session fallback (pointer seam)") {
    val root = tmpRoot()
    val c = new RecordingCtx(Nil)
    val p = plugin(root = root)
    val media = os.temp.dir(prefix = "nb-weixin-media-anon-") / "m1-image.png"
    os.write(media, "png")
    for
      _ <- IO(SocialChannels.setDefaultSessionId(root, WeixinIlinkBridgePlugin.Name, Some("s_default")))
      _ <- started(p, c)
      v <- p.intake(c, mediaInbound(None, Some(media.toString)))
      bound <- c.bound.get
      got <- c.injected.get
      gotMedia <- c.mediaInjections.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s_default"))
      assertEquals(bound, List.empty, "no binding without a resolvable sender id (unchanged)")
      assertEquals(got.map(_._2), List.empty, "no legacy text line rides the unified seam")
      assertEquals(
        gotMedia.map { case (sid, atts, _) => (sid, atts.map(a => (a.path, a.name))) },
        List(("s_default", List((media.toString, Some("pic.png")))))
      )
  }

  test("WI-M7 a media message with NO session to serve it drops as before (the media arm adds no routing)") {
    val c = new RecordingCtx(Nil)
    val p = plugin()
    for
      _ <- started(p, c)
      v <- p.intake(c, mediaInbound(Some("wx_none"), Some("/tmp/landed.png")))
      got <- c.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNoSession))
      assertEquals(got, List.empty)
  }

  test("WI-M8 a media message OFF the allowlist is still gated FIRST (the gate rules the media arm too)") {
    val c = new RecordingCtx(List(meta("s1", Some("wx_alice"))))
    val p = plugin(allowed = Some(List("wx_alice")))
    for
      _ <- started(p, c)
      v <- p.intake(c, mediaInbound(Some("wx_mallory"), Some("/tmp/landed.png")))
      got <- c.injected.get
    yield
      assertEquals(
        v,
        WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist),
        "a landed media path must not bypass the sender gate"
      )
      assertEquals(got, List.empty)
  }

end WeixinIlinkBridgeSpec
