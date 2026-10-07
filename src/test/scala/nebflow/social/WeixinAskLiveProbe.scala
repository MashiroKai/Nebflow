package nebflow.social

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.InteractionHub
import nebflow.bridge.BridgeContext
import nebflow.shared.NebflowLogger

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/**
 * LIVE loopback probe for the weixin ask legs (askuser-remote P1,
 * 2026-10-07). NOT part of the offline suite — run explicitly:
 *
 * {{{
 * sbt "Test/runMain nebflow.social.WeixinAskLiveProbe"
 * }}}
 *
 * Chain under probe (batch brief: stub iLink 注入数字回复「2」→ intake 分流 →
 * Answered 汇点 → 问闭环):
 *
 *   REAL InteractionHub actor ← RegisterRoot(wsSend = record + fan-out to the
 *   bridge) — the hub renders the REAL askUser frame;
 *   REAL WeixinIlinkBridgePlugin → REAL WeixinOutboundAdapter.sendLive →
 *   REAL shim (node shim.mjs, loopback random port) → stub iLink (records the
 *   numbered card on the wire);
 *   REAL msgloop (node msgloop.mjs, FIXTURE mode — zero iLink traffic) tails
 *   an NDJSON fixture of iLink-shaped messages and POSTs them to the probe's
 *   ingress server;
 *   probe ingress server (loopback random port) = the ingest route's CONTRACT
 *   with the engine's OWN functions: WeixinIngressToken.accepts gate →
 *   parseInbound → plugin.deliver (the real route shell is http4s inside the
 *   gateway; this server reproduces the seam, not a second gate).
 *   Answers converge at the REAL hub: deliver → intake splitter →
 *   ctx.forwardInteractionAnswer → InteractionHubCommand.Answered → the
 *   waiting AskUserReply slot completes = 问闭环.
 *
 * 🔴 Egress discipline: the ONLY network this probe touches is loopback —
 * the shim's WEIXIN_SIDECAR_ILINK_BASE points at the in-process stub (the
 * real ilinkai endpoint is never reached) and the msgloop runs in FIXTURE
 * mode (zero iLink traffic). 🔴 Ports: everything binds 127.0.0.1:0 (random)
 * — 🔴 8080/8090/8091/8787 are never touched.
 */
object WeixinAskLiveProbe:

  private val log = NebflowLogger.forName("nebflow.social.weixin-ask-liveprobe")

  def main(args: Array[String]): Unit =
    banner("weixin ask-legs LIVE loopback probe — hub → bridge → shim → (fixture) → ingress → hub")

    // ── ① stub iLink: records every sendmessage, answers ret=0 ──
    val recorded = new java.util.concurrent.CopyOnWriteArrayList[String]
    val stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    stub.createContext(
      "/ilink/bot/sendmessage",
      (exchange: HttpExchange) =>
        try
          val body = new String(exchange.getRequestBody.readAllBytes, StandardCharsets.UTF_8)
          recorded.add(s"POST ${exchange.getRequestURI}\n  body=$body")
          val resp = "{\"ret\":0,\"message_id\":987654321098765432}".getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, resp.length.toLong)
          exchange.getResponseBody.write(resp)
        finally exchange.close(),
    )
    stub.start()
    val stubBase = s"http://127.0.0.1:${stub.getAddress.getPort}"
    readout("stub iLink up", stubBase)

    // ── ② probe engine root: tokens minted, weixin-ilink card saved ──
    val root = os.temp.dir(prefix = "nb-weixin-ask-live-")
    WeixinOutboundAdapter.ensureToken(root).unsafeRunSync()
    WeixinIngressToken.ensure(root).unsafeRunSync()
    readout("engine tokens", s"out+ingress minted under ${root}/secrets (owner-only)")

    // ── ②b fake host state: ONE mirrored account — the shim's send face
    //    refuses without a confirmed login in the state mirror (the same
    //    seeding WeixinOutboundLiveProbe does). Stub-only values; nothing real.
    val hostState = root / "openclaw-state"
    val acctDir = hostState / "openclaw-weixin" / "accounts"
    os.makeDir.all(acctDir)
    os.write.over(acctDir / "liveprobe-im-bot.json",
      """{"token":"host-account-token-0123456789abcdef","userId":"wx_ask_liveprobe","savedAt":"2026-10-07T00:00:00.000Z"}""")
    os.write.over(hostState / "openclaw-weixin" / "accounts.json", """["liveprobe-im-bot"]""")
    readout("fake host account", s"$hostState (1 account: liveprobe-im-bot)")

    // ── ③ the REAL hub, wired the way GatewayMain wires it ──
    val system = ActorSystem("weixin-ask-liveprobe")
    val hub = system.spawn(InteractionHub(), "interaction-hub").unsafeRunSync()
    val bridgeRef = new java.util.concurrent.atomic.AtomicReference[WeixinIlinkBridgePlugin](null)

    /** The hub render target = the wsSend recording face GatewayMain registers:
      * here it records the frame AND fans it out to the bridge's onAgentEvent
      * (the wsHub-listener shape, in-process). */
    def wsSend(frame: Json): IO[Unit] =
      IO(println(s"  ▸ hub rendered: type=${frame.hcursor.downField("type").as[String].toOption.getOrElse("-")} " +
        s"requestId=${frame.hcursor.downField("requestId").as[String].toOption.getOrElse("-")}")) *>
        IO(Option(bridgeRef.get).foreach(_.onAgentEvent("s1", frame).unsafeRunSync()))

    (hub ! InteractionHubCommand.RegisterRoot("s1", wsSend)).unsafeRunSync()

    // ── ④ the REAL bridge plugin with the REAL production outbound ──
    val askCtx = new BridgeContext:
      override def injectMessage(sessionId: String, content: String, senderId: Option[String],
          origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] =
        IO(println(s"  ▸ injected as chat: session=$sessionId text=${content.take(60)}"))
      override def listPendingAsks: IO[List[Json]] =
        hub.?[List[Json]](reply => InteractionHubCommand.ListAllPendingAsks(reply))
      override def forwardInteractionAnswer(requestId: String, rootSessionId: String, payload: Json): IO[Unit] =
        IO(println(s"  ▸ bridge answer leg → hub: requestId=$requestId root=$rootSessionId payload=${payload.noSpaces}")) *>
          (hub ! InteractionHubCommand.Answered(
            InteractionAnswered(requestId, rootSessionId, payload))).void
      override def closeInteractionRequest(requestId: String): IO[Unit] =
        IO(println(s"  ▸ bridge close leg → hub: requestId=$requestId (B-3 top-old)")) *>
          (hub ! InteractionHubCommand.CloseRequest(requestId)).void
      def interruptAgent(sessionId: String): IO[Unit] = IO.unit
      def sessionMeta(sessionId: String): IO[Option[nebflow.shared.SessionMeta]] = IO.none
      def listSessions: IO[List[nebflow.shared.SessionMeta]] =
        IO.pure(List(nebflow.shared.SessionMeta(id = "s1", name = "s1", createdAt = 0L, updatedAt = 0L,
          hasUnread = false,
          bridges = Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> "wx_asker".asJson)))))
      def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

    val plugin = new WeixinIlinkBridgePlugin(root, pinnedAllowedIlinkUserIds = None,
      send = WeixinOutboundAdapter.sendLive) // the REAL production send
    bridgeRef.set(plugin)
    plugin.start(askCtx).unsafeRunSync()
    readout("bridge started", "REAL WeixinIlinkBridgePlugin, REAL sendLive, REAL hub faces")

    // ── ⑤ the REAL shim (loopback random port) ──
    val shimPort = freePort()
    val worktree = os.pwd
    val shim = Process(
      Seq("node", (worktree / "tools" / "weixin-sidecar" / "shim.mjs").toString, "--port", shimPort.toString),
      cwd = Some(worktree.toIO),
      "WEIXIN_SEND_TOKEN_FILE" -> WeixinOutboundAdapter.tokenPath(root).toString,
      "WEIXIN_SIDECAR_ILINK_BASE" -> stubBase,
      "OPENCLAW_STATE_DIR" -> (root / "openclaw-state").toString,
      "WEIXIN_SIDECAR_SEND_BACKOFF_MS" -> "150",
    ).run()
    val shimBase = s"http://127.0.0.1:$shimPort"
    awaitHealthy(shimBase, deadline = 10.seconds.fromNow)
    readout("real shim up", shimBase)

    SocialChannels.save(root, WeixinIlinkBridgePlugin.Name,
      io.circe.parser.parse(s"""{"fields":{"sidecar_url":"$shimBase","ilink_bot_id":"liveprobe@im.bot","ilink_user_id":"wx_asker"}}""")
        .toOption.get) match
      case Right(_) => readout("sidecar_url saved", shimBase)
      case Left(err) => throw new IllegalStateException(err.toString)

    // ── ⑥ the probe ingress server: the ingest route's CONTRACT with the
    //      engine's own gate/parse/deliver ──
    val ingressPort = freePort()
    val ingress = HttpServer.create(new InetSocketAddress("127.0.0.1", ingressPort), 0)
    ingress.createContext(
      "/api/social/channels/weixin-ilink/ingress",
      (exchange: HttpExchange) =>
        try
          val auth = Option(exchange.getRequestHeaders.getFirst("Authorization"))
            .map(_.stripPrefix("Bearer ").trim).filter(_.nonEmpty)
          val body = new String(exchange.getRequestBody.readAllBytes, StandardCharsets.UTF_8)
          val resp: (Int, String) =
            if !WeixinIngressToken.accepts(root, auth).unsafeRunSync() then
              (403, """{"error":"Unauthorized"}""")
            else
              io.circe.parser.parse(body).toOption match
                case None => (400, """{"error":"invalid_field","reason":"not json"}""")
                case Some(j) =>
                  WeixinIlinkBridgePlugin.parseInbound(j) match
                    case Left(reason) => (400, Json.obj("error" -> "invalid_field".asJson, "reason" -> reason.asJson).noSpaces)
                    case Right(in) =>
                      val verdict = plugin.deliver(in).unsafeRunSync()
                      (200, verdict.json.noSpaces)
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          val bytes = resp._2.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(resp._1, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        finally exchange.close(),
    )
    ingress.start()
    val ingressUrl = s"http://127.0.0.1:$ingressPort/api/social/channels/weixin-ilink/ingress"
    readout("probe ingress up", ingressUrl)

    // ── ⑦ the REAL msgloop in FIXTURE mode ──
    val fixture = Files.createTempFile("nb-weixin-ask-fixture", ".ndjson")
    val msgloop = Process(
      Seq("node", (worktree / "tools" / "weixin-sidecar" / "msgloop.mjs").toString),
      cwd = Some(worktree.toIO),
      "WEIXIN_MSGLOOP_INGRESS_URL" -> ingressUrl,
      "WEIXIN_SEND_TOKEN_FILE" -> (root / "secrets" / WeixinIngressToken.FileName).toString,
      "WEIXIN_MSGLOOP_FIXTURE" -> fixture.toString,
      "WEIXIN_MSGLOOP_STATE_DIR" -> (root / "msgloop-state").toString,
      "OPENCLAW_STATE_DIR" -> (root / "openclaw-state").toString,
      "WEIXIN_MSGLOOP_ACCOUNT_ID" -> "liveprobe-im-bot",
    ).run()
    readout("real msgloop up", s"FIXTURE mode, tails $fixture (zero iLink traffic)")

    def appendFixture(text: String, id: String): Unit =
      val line = Json.obj(
        "from_user_id" -> "wx_asker".asJson,
        "message_id" -> id.asJson,
        "item_list" -> Json.arr(Json.obj("type" -> 1.asJson, "text_item" -> Json.obj("text" -> text.asJson)))
      ).noSpaces
      Files.write(fixture, (line + "\n").getBytes(StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)

    // ── ⑧ THE ASK: a REAL hub Request → REAL frame → bridge → shim → stub ──
    def askOnce(rid: String, questions: List[(String, List[String], Boolean)]): Deferred[IO, List[String]] =
      val slot = Deferred[IO, List[String]].unsafeRunSync()
      val items = questions.map { (q, labels, multiple) =>
        val base = scala.collection.mutable.ListBuffer[(String, Json)](
          "question" -> q.asJson,
          "options" -> Json.fromValues(labels.map(l => Json.obj("label" -> l.asJson))),
          "allowOther" -> true.asJson)
        if multiple then base += "multiple" -> true.asJson
        Json.obj(base.toList*)
      }
      val payload = Json.obj(
        "type" -> "askUser".asJson, "sessionId" -> "s1".asJson, "agentName" -> "liveprobe".asJson,
        "items" -> Json.fromValues(items))
      (hub ! InteractionHubCommand.Request(InteractionRequest(
        requestId = rid, kind = InteractionKind.AskUser, payload = payload,
        reply = InteractionReply.AskUserReply(Some(replyRecorder(slot))),
        rootSessionId = "s1", sourceAgent = "liveprobe", sourceSession = "s1"))).unsafeRunSync()
      slot

    /** The AskUserReply target: a one-shot actor that completes the slot — the
      * same shape SendConfirm's oneShotReply serves. */
    def replyRecorder(slot: Deferred[IO, List[String]]): ActorRef[List[String]] =
      new ActorRef[List[String]]:
        def path: ActorPath = ActorPath("liveprobe", List("reply-slot"))
        def !(answers: List[String]): IO[Unit] = slot.complete(answers).void
        def ?[R](makeMsg: ActorRef[R] => List[String], timeout: Option[FiniteDuration]): IO[R] =
          IO.raiseError(new UnsupportedOperationException("probe reply slot is tell-only"))

    try
      // ── 例 1: 数字回复「2」 → intake 分流 → Answered 汇点 → 问闭环 ──
      val slot1 = askOnce("probe-ask-1", List(("用哪个选项?", List("甲", "乙", "丙"), false)))
      Thread.sleep(2500) // let the card hit the wire + the shim settle
      readout("WIRE (ask card)", s"${recorded.size} sendmessage call(s) at stub iLink")
      recorded.asScala.toList.foreach(l => println(s"    $l"))
      recorded.clear()

      appendFixture("2", "m-1")
      val a1 = slot1.get.timeout(30.seconds).unsafeRunSync()
      readout("例1 数字「2」", s"问闭环：hub slot completed with answers=$a1")

      // ── 例 2: 「问.编」 显式寻址兜底 ──
      val slot2 = askOnce("probe-ask-2", List(("一?", List("甲", "乙"), false), ("二?", List("丙", "丁"), false)))
      Thread.sleep(1500)
      appendFixture("2.2", "m-2")
      val a2 = slot2.get.timeout(30.seconds).unsafeRunSync()
      readout("例2 「2.2」", s"问.编 兜底：answers=$a2 (问2选2=丁)")

      // ── 例 3: 「他」 显式前缀自由文本 ──
      val slot3 = askOnce("probe-ask-3", List(("一?", List("甲", "乙"), false), ("二?", List("丙", "丁"), false)))
      Thread.sleep(1500)
      appendFixture("他 手写的内容", "m-3")
      val a3 = slot3.get.timeout(30.seconds).unsafeRunSync()
      readout("例3 「他 手写的内容」", s"Other 腿：answers=$a3 (问1 槽=手写的内容，问2 槽空)")

      banner("LIVE LOOPBACK VERDICT: 三例全链闭环 — hub → bridge ask card → shim → (fixture) → ingress 分流 → hub Answered → slot")
    finally
      msgloop.destroy()
      shim.destroy()
      ingress.stop(0)
      stub.stop(0)
      Thread.sleep(300)
      system.stopAll.unsafeRunSync()
      println(s"    [cleanup] msgloop/shim/ingress/stub stopped; temp root left: $root")

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  private def awaitHealthy(base: String, deadline: Deadline): Unit =
    val client = java.net.http.HttpClient.newHttpClient()
    while deadline.hasTimeLeft do
      try
        val resp = client.send(
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(s"$base/healthz")).GET().build(),
          java.net.http.HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() == 200 then return
      catch case _: Exception => ()
      Thread.sleep(200)
    throw new IllegalStateException(s"shim never became healthy at $base")

  private def banner(s: String): Unit = println(s"\n══════ $s ══════")
  private def readout(k: String, v: String): Unit = println(s"  ▸ $k: $v")

end WeixinAskLiveProbe
