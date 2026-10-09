package nebflow.social

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.InteractionHub
import nebflow.actor.*
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*
import scala.util.Try

/**
 * LIVE rig probe for the feishu askUser legs (askuser-remote P1, 2026-10-07).
 * NOT part of the offline suite — run explicitly:
 *
 * {{{
 * sbt "Test/runMain nebflow.social.FeishuAskLiveProbe <chat_id> [listenSecs]"
 * }}}
 *
 * What it proves, in ONE process:
 *
 *   ① REAL CARD OUT — a REAL InteractionHub renders a REAL askUser frame; the
 *      REAL FeishuBridgePlugin (production send seams) flattens it (D-1),
 *      renders the static 1.0 two-button card and sends it to the author's
 *      feishu chat via FeishuChannel.sendInteractive over the long connection.
 *   ② MACHINE LEG — a wire-same-shape `card.action.trigger` event is fed
 *      through the LIVE LISTENER's own dispatcher (`feedRawEvent` — the same
 *      dispatcher object the SDK websocket transport hands frames to), proving
 *      callback → allowlist gate → hub snapshot (A5 authority) →
 *      FeishuAskCard.parse → bridge answer leg → InteractionHubCommand.Answered
 *      → the REAL hub completes the waiting AskUserReply slot → the A4
 *      updated-card body comes back in the callback response.
 *   ③ 候作者一键 — the REAL card is live in the author's chat; a REAL click
 *      arrives through the same registered handler (the probe stays connected
 *      for the listen window and logs any real callback).
 *
 * 🔴 Rig isolation: this probe opens ZERO listening ports (one outbound
 * websocket to Feishu only) — 🔴 8080/8090/8091/8787 are never touched. The
 * running production instance is not started, not stopped, not read beyond
 * the credential face (read-only, values never printed).
 */
object FeishuAskLiveProbe:

  private val log = NebflowLogger.forName("nebflow.social.feishu-ask-liveprobe")

  def main(args: Array[String]): Unit =
    banner("feishu ask legs LIVE rig probe — real card out + machine leg + 候作者一键")
    val chatId = args.headOption.getOrElse("")
    val listenSecs = args.lift(1).flatMap(_.toIntOption).getOrElse(90)
    if chatId.isEmpty then
      println("usage: FeishuAskLiveProbe <chat_id> [listenSecs]")
      System.exit(2)

    // ── ① credentials from the ACTIVE data root (read-only; values never printed) ──
    // The probe JVM is not `--home`-launched, so the default root resolution
    // can land on the STABLE root; the LIVE instance's credential face lives
    // under `$HOME/.nebflow` — pin it explicitly (NEBFLOW_PROBE_DATA_ROOT
    // overrides, for a rig that wants a different isolation root).
    val credsRoot = sys.env.get("NEBFLOW_PROBE_DATA_ROOT").map(os.Path(_, os.pwd))
      .getOrElse(os.Path(sys.env.getOrElse("HOME", os.home.toString), os.pwd) / ".nebflow")
    println(s"  ▸ probe data root: $credsRoot")
    val cred = FeishuCredentials.resolve(credsRoot) match
      case Right(c) => c
      case Left(err) =>
        println(s"  ▸ FATAL credential not resolved: $err")
        System.exit(2); ???
    println(s"  ▸ credential source=${cred.source} appId=${FeishuCredentials.redacted(cred.appId)} " +
      s"fp=${FeishuCredentials.fingerprint(cred.appId)}")
    val cb = FeishuCredentials.resolveCallbackCredentials(credsRoot)
    println(s"  ▸ callback credentials: $cb")

    // ── ② the REAL hub, wired the way GatewayMain wires it ──
    val system = ActorSystem("feishu-ask-liveprobe")
    val hub = system.spawn(InteractionHub(), "interaction-hub").unsafeRunSync()
    val bridgeRef = new java.util.concurrent.atomic.AtomicReference[FeishuBridgePlugin](null)

    def wsSend(frame: Json): IO[Unit] =
      IO(println(s"  ▸ hub rendered: type=${frame.hcursor.downField("type").as[String].toOption.getOrElse("-")} " +
        s"requestId=${frame.hcursor.downField("requestId").as[String].toOption.getOrElse("-")}")) *>
        IO {
          try optionallyFanOut(frame)
          catch case e: Throwable =>
            println(s"  ▸ [fan-out] RAISED ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
        }

    def optionallyFanOut(frame: Json): Unit =
      Option(bridgeRef.get).foreach(_.onAgentEvent("probe-root", frame).unsafeRunSync())

    (hub ! InteractionHubCommand.RegisterRoot("probe-root", wsSend)).unsafeRunSync()

    // ── ③ the REAL bridge (production seams; plugin root = a probe temp dir) ──
    val probeRoot = os.temp.dir(prefix = "nb-feishu-ask-live-")
    val askCtx = new nebflow.bridge.BridgeContext:
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
        (hub ! InteractionHubCommand.CloseRequest(requestId)).void
      def interruptAgent(sessionId: String): IO[Unit] = IO.unit
      def sessionMeta(sessionId: String): IO[Option[nebflow.shared.SessionMeta]] = IO.none
      def listSessions: IO[List[nebflow.shared.SessionMeta]] =
        IO.pure(List(nebflow.shared.SessionMeta(id = "probe-root", name = "probe-root", createdAt = 0L,
          updatedAt = 0L, hasUnread = false,
          bridges = Map(FeishuBridgePlugin.Name -> Json.obj("chat_id" -> chatId.asJson)))))
      def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

    val plugin = new FeishuBridgePlugin(
      probeRoot,
      send = FeishuBridgePlugin.defaultSend,       // REAL text seam (fallback leg)
      // REAL card seam (A-3) + a receipt/timeout jacket: the rig MUST see the
      // send's outcome on stdout — the SDK REST call has no honest default
      // timeout, so a hung open.feishu.cn call would otherwise be invisible.
      sendCard = (appId, appSecret, region, receiveId, cardJson) =>
        FeishuBridgePlugin.defaultSendCard(appId, appSecret, region, receiveId, cardJson)
          .timeout(25.seconds)
          .attempt
          .flatMap {
            case Right(res) =>
              IO(println(s"  ▸ [send] card ok=${res.ok} code=${res.code} msg=${res.msg} " +
                s"messageId=${res.messageId.getOrElse("-")} chat=${receiveId.take(10)}…")) *> IO.pure(res)
            case Left(e) =>
              IO(println(s"  ▸ [send] card RAISED/TIMED-OUT ${e.getClass.getSimpleName}: " +
                s"${Option(e.getMessage).getOrElse("").take(160)}")) *> IO.raiseError(e)
          },
      pinnedAllowedOpenIds = None,                  // unrestricted (the rig's member gate is the author's own tenant)
      pinnedCreds = Some(cred),
    )
    bridgeRef.set(plugin)
    // start() would try to open a SECOND long connection (its own listener) —
    // the rig keeps exactly ONE. The state the legs need (creds, routes) is
    // armed directly through the package-visible seams, mirroring what start
    // arms: creds = pinned, routes = probe-root → the author's chat.
    plugin.routesRef.set(Map(chatId -> "probe-root")).unsafeRunSync()
    // Arm the dispatch context WITHOUT start(): onAgentEvent routes every
    // frame through ctxRef (None ⇒ silently dropped) — the rig sets it
    // directly through the package-visible seam instead of start(), which
    // would open a second long connection.
    plugin.ctxRef.set(Some(askCtx)).unsafeRunSync()
    val listener = new FeishuChannel.Listener(
      cred.appId, cred.appSecret, region = "feishu",
      verificationToken = cb.verificationToken.getOrElse(""),
      encryptKey = cb.encryptKey.getOrElse(""),
      handler = in => println(s"  ▸ [listen] inbound ${in.messageType} from ${in.chatId}"),
      cardHandler = a =>
        Try(plugin.handleCardAction(askCtx, a).unsafeRunSync()) match
          case scala.util.Success(resp) =>
            println(s"  ▸ [card] click handled: toast=${resp.toast.getOrElse("-")} " +
              s"card=${resp.card.map(c => s"${c.length}B").getOrElse("-")}")
            resp
          case scala.util.Failure(e) =>
            println(s"  ▸ [card] handler raised ${e.getClass.getSimpleName}: ${e.getMessage}")
            FeishuChannel.CardActionResponse.none,
    )
    val connect = listener.start()
    readout("long connection", s"ok=${connect.ok} detail=${connect.detail}")
    if !connect.ok then
      listener.stop(); system.stopAll.unsafeRunSync(); System.exit(4)

    // ── ④ THE ASK: a REAL hub Request → REAL frame → bridge → REAL card out ──
    val slot = Deferred[IO, List[String]].unsafeRunSync()
    val payload = Json.obj(
      "type" -> "askUser".asJson, "sessionId" -> "probe-root".asJson, "agentName" -> "liveprobe".asJson,
      "items" -> Json.fromValues(List(
        Json.obj("question" -> "远程渠道 P1 终验：这张卡显示正常吗？".asJson,
          "options" -> Json.fromValues(List(
            Json.obj("label" -> "正常".asJson), Json.obj("label" -> "有问题".asJson))),
          "allowOther" -> true.asJson))))
    (hub ! InteractionHubCommand.Request(InteractionRequest(
      requestId = "probe-ask-1", kind = InteractionKind.AskUser, payload = payload,
      reply = InteractionReply.AskUserReply(Some(replySlot(slot))),
      rootSessionId = "probe-root", sourceAgent = "liveprobe", sourceSession = "probe-root"))).unsafeRunSync()
    Thread.sleep(4000) // let the card render + the send seam answer

    // ── ⑤ MACHINE LEG: wire-same-shape card.action.trigger through the LIVE dispatcher ──
    val wire = Json.obj(
      "schema" -> "2.0".asJson,
      "header" -> Json.obj(
        "event_id" -> "probe-evt-1".asJson,
        "event_type" -> "card.action.trigger".asJson,
        "create_time" -> System.currentTimeMillis().toString.asJson,
        "token" -> "".asJson,
      ),
      "event" -> Json.obj(
        "operator" -> Json.obj("open_id" -> "ou_liveprobe-machine".asJson, "tenant_key" -> "".asJson),
        "token" -> "".asJson,
        "action" -> Json.obj(
          "tag" -> "button".asJson,
          "value" -> Json.obj("nf" -> "ask".asJson, "rid" -> "probe-ask-1".asJson,
            "qi" -> 0.asJson, "oi" -> 0.asJson),
        ),
        "context" -> Json.obj("open_chat_id" -> chatId.asJson, "open_message_id" -> "om_probe".asJson),
      ),
    )
    println(s"  ▸ [machine leg] feeding card.action.trigger (${wire.noSpaces.length}B) through the LIVE dispatcher")
    val rawResponse = listener.feedRawEvent(wire.noSpaces.getBytes("UTF-8"))
    readout("MACHINE LEG dispatcher response", rawResponse.take(600))
    val answered = Try(slot.get.timeout(15.seconds).unsafeRunSync()).toOption
    readout("问闭环 (machine leg)", s"hub slot completed with answers=$answered")

    // ── ⑥ 候作者一键: stay connected; a REAL click rides the SAME handler ──
    banner(s"REAL card is live in $chatId — listening ${listenSecs}s for the author's click (same chain)")
    val deadline = System.currentTimeMillis() + listenSecs * 1000L
    // 10s heartbeat: the listen window is idle by design; a visible pulse keeps
    // process supervisors (stall guards) from reaping a healthy waiter.
    var beat = 0
    while System.currentTimeMillis() < deadline do
      Thread.sleep(math.min(10000, math.max(1, deadline - System.currentTimeMillis())))
      beat += 10
      if System.currentTimeMillis() < deadline then
        println(s"  ▸ [listen] +${math.min(beat, listenSecs)}s — card live, waiting for the author's click")
    listener.stop()
    system.stopAll.unsafeRunSync()
    banner("probe done — card sent, machine leg proven, awaiting the author's one-click verdict in the report")
    System.exit(0)

  private def replySlot(slot: Deferred[IO, List[String]]): ActorRef[List[String]] =
    new ActorRef[List[String]]:
      def path: ActorPath = ActorPath("liveprobe", List("reply-slot"))
      def !(answers: List[String]): IO[Unit] = slot.complete(answers).void
      def ?[R](makeMsg: ActorRef[R] => List[String], timeout: Option[FiniteDuration]): IO[R] =
        IO.raiseError(new UnsupportedOperationException("probe reply slot is tell-only"))

  private def banner(s: String): Unit = println(s"\n══════ $s ══════")
  private def readout(k: String, v: String): Unit = println(s"  ▸ $k: $v")

end FeishuAskLiveProbe
