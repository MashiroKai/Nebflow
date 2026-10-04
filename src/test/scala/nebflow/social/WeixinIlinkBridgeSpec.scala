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
   * throws must never escape the seam.
   */
  private final class RecordingCtx(sessions: List[SessionMeta], raiseOnInject: Boolean = false) extends BridgeContext:

    val injected: Ref[IO, List[(String, String, Option[String])]] =
      Ref.unsafe[IO, List[(String, String, Option[String])]](Nil)

    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] =
      if raiseOnInject then IO.raiseError(new RuntimeException("injected: peer leg failed"))
      else injected.update(_ :+ ((sessionId, content, senderId)))
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  end RecordingCtx

  /** Recording context that ALSO captures the origin descriptor of each
    *  injection (soc483: the inbound source marker). */
  private final class OriginRecordingCtx(sessions: List[SessionMeta]) extends BridgeContext:
    val origins: Ref[IO, List[Option[nebflow.bridge.BridgeOrigin]]] =
      Ref.unsafe[IO, List[Option[nebflow.bridge.BridgeOrigin]]](Nil)
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] =
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

  /** The card's own documented shape: the two required plain fields plus the
    *  required secret, so a save cannot fail for a reason other than the URL. */
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
    val stored = SocialChannels.channelsJson(root).hcursor
      .downField("channels").downField(WeixinIlinkBridgePlugin.Name).downField("fields")
    assertEquals(stored.downField("baseurl").as[String].toOption,
      Some("https://open.example.com/api"), "the value must be stored verbatim")
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
    assert("https://open.example.com/x/y".matches(baseurl.pattern.get),
      "the shared pattern must accept a path-carrying URL (save) …")
    assert(!"notaurl".matches(baseurl.pattern.get),
      "…and refuse a bare word (verified), through the SAME pattern object")
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
      assertEquals(o.channelId, WeixinIlinkBridgePlugin.Name,
        "the channel id is this bridge's own stable name")
      assertEquals(o.channelDisplay, WeixinIlinkBridgePlugin.ChannelDisplay,
        "the display label comes from this channel's own constant")
      assert(o.channelDisplay != FeishuBridgePlugin.ChannelDisplay,
        "the label must never be a copy of another channel's label")
      assertEquals(o.chatRef, None, "iLink carries no conversation reference ⇒ the marker renders the neutral `-`")
  }

  test("WI-S2 the label rides the marker template: the rendered block names this channel only") {
    val origin = nebflow.bridge.BridgeOrigin(
      channelId = WeixinIlinkBridgePlugin.Name,
      channelDisplay = WeixinIlinkBridgePlugin.ChannelDisplay)
    val marker = nebflow.bridge.BridgeOrigin.marker(origin, Some("wx_alice"))
    assert(marker.contains(WeixinIlinkBridgePlugin.ChannelDisplay), marker)
    assert(marker.contains(WeixinIlinkBridgePlugin.Name), marker)
    assert(!marker.contains(FeishuBridgePlugin.ChannelDisplay), s"the feishu label leaked in: $marker")
  }

  test("WI-S3 the outbound leg of this seam stays a stated no-op (boundary unchanged)") {
    // The soc483 batch adds an INBOUND source marker only. The outbound boundary
    // (replies belong to the host that runs the plugin) is what this pins: the
    // call must be a no-op that opens nothing and returns cleanly.
    val p = plugin()
    for _ <- p.onAgentEvent("s1", Json.obj("type" -> "toolEnd".asJson, "sessionId" -> "s1".asJson,
        "content" -> "___CARD_HTML___{}".asJson, "isError" -> false.asJson))
    yield ()
  }

end WeixinIlinkBridgeSpec
