package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem as NebActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.tools.{FriendMessageTool, MailTool, ToolContext}
import nebflow.gateway.SessionStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 跨设备 Nebula 邮件（device-mail 批，2026-09-15；契约 v1 + v2）——契约单点、
 * 收件腿（注入 + 蓝气泡 + ack）与发送端点形态的机械读数。
 *
 * 覆盖（对应任务书验收）：
 *   - 契约载荷五键逐字（对**契约原文**解析后的 JSON 做等值比对，非自造期望值）；
 *   - 解析 fail-closed（老版本对端降级面：未知 type / 缺字段 / to_nebula 非真）；
 *   - 注入头行逐字 + source/eventType/sender 取值域（蓝气泡标签的数据源）；
 *   - 注入失败**禁静默**（告警帧 + 不误 ack）；
 *   - 收件回执（ack）关联与 eventId 前缀单点；
 *   - MailTool 目标面：schema（件数不变、required 只剩 message）+ 四类校验词表；
 *   - 发送端点形态（契约 v2 ①：目标走路径）与**定向**（v2 ②：无 fan-out）。
 */
class DeviceMailSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-device-mail"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    DeviceMailInbox.resetForTest()
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  /** 契约原文（**逐字**引用任务书冻结文本；本批禁改）。 */
  private val contractPayloadText =
    """{"type":"agent_mail","from_device":"KAI-MBP","from_device_id":"dev-a","to_nebula":true,"text":"hello B"}"""

  private def contractPayload: Json =
    parse(contractPayloadText).fold(e => fail(s"contract literal must parse: ${e.getMessage}"), identity)

  // ============================================================
  // ① 契约载荷（五键逐字）
  // ============================================================

  test("payload: 恰契约五键，逐键等值（对契约原文比对，非自造期望）"):
    val built = DeviceMail.payload("hello B", "KAI-MBP", "dev-a")
    assertEquals(built, contractPayload, "载荷必须与契约原文逐键等值")
    assertEquals(built.asObject.map(_.keys.toList).getOrElse(Nil).sorted, DeviceMail.PayloadKeys.sorted)
    assertEquals(built.asObject.map(_.size).getOrElse(0), 5, "禁自创字段：恰好五键")

  test("payload: to_nebula 恒 true（契约不变量）"):
    val built = DeviceMail.payload("x", "d", "i")
    assertEquals(built.hcursor.get[Boolean](DeviceMail.KeyToNebula).toOption, Some(true))

  test("parse: 契约原文 ⇒ Right（from_device 展示名 / from_device_id / 正文）"):
    assertEquals(
      DeviceMail.parse(contractPayload),
      Right(DeviceMail.Incoming("hello B", "KAI-MBP", "dev-a"))
    )

  test("parse fail-closed: 未知 type / 缺字段 / to_nebula 非真 / 空正文 一律 Left（禁崩溃、禁误渲染）"):
    def withKv(base: Json, drop: String, add: (String, Json)*): Json =
      val obj = base.asObject.get
      val dropped = obj.remove(drop)
      Json.fromJsonObject(add.foldLeft(dropped)((o, kv) => o.add(kv._1, kv._2)))

    assert(DeviceMail.parse(Json.obj("type" -> "something_else".asJson)).isLeft, "未知 type")
    assert(DeviceMail.parse(withKv(contractPayload, "text")).isLeft, "缺 text")
    assert(DeviceMail.parse(withKv(contractPayload, "from_device")).isLeft, "缺 from_device")
    assert(DeviceMail.parse(withKv(contractPayload, "from_device_id")).isLeft, "缺 from_device_id")
    assert(
      DeviceMail.parse(withKv(contractPayload, "to_nebula", DeviceMail.KeyToNebula -> false.asJson)).isLeft,
      "to_nebula=false 必须被拒（本批唯一目标形态）"
    )
    assert(
      DeviceMail.parse(withKv(contractPayload, "to_nebula", DeviceMail.KeyToNebula -> "true".asJson)).isLeft,
      "to_nebula 非布尔同样被拒"
    )
    assert(DeviceMail.parse(withKv(contractPayload, "text", "text" -> "".asJson)).isLeft, "空正文")
    assert(!DeviceMail.isAgentMail(Json.obj("type" -> "ack".asJson)), "ack 不是 agent_mail")

  test("注入头行逐字 + eventId 前缀单点（message-<id> 形态）"):
    assertEquals(DeviceMail.headerLine("KAI-MBP"), "[DEVICE-MAIL · from KAI-MBP]")
    assertEquals(
      DeviceMail.injectedText("KAI-MBP", "hello B"),
      "[DEVICE-MAIL · from KAI-MBP]\nhello B"
    )
    assertEquals(DeviceMail.stripMessagePrefix("message-abc123"), "abc123")
    assertEquals(DeviceMail.stripMessagePrefix("abc123"), "abc123")
    assertEquals(DeviceMail.withMessagePrefix("abc123"), "message-abc123")
    assertEquals(DeviceMail.withMessagePrefix("message-abc123"), "message-abc123")
    assertEquals(
      DeviceMail.ackEventId(parse("""{"type":"ack","eventId":"message-abc"}""").toOption.get),
      Some("message-abc")
    )
    assertEquals(DeviceMail.ackEventId(parse("""{"type":"ack"}""").toOption.get), None)

  // ============================================================
  // ② MailTool 目标面：schema + 四类校验词表
  // ============================================================

  private def requiredOf: List[String] =
    MailTool.inputSchema("required").flatMap(_.asArray).toList.flatten.flatMap(_.asString)

  private def props: Set[String] =
    MailTool.inputSchema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def bareCtx: ToolContext = ToolContext(projectRoot = tempRoot.toString)

  private def callErr(input: JsonObject): String =
    MailTool.call(input, bareCtx).unsafeRunSync() match
      case Left(err)  => err.message
      case Right(msg) => fail(s"expected an explicit error, got success: $msg")

  test("schema: `device` 入册；address 不再 required（两目标各自可空、禁双填）"):
    assert(props.contains("device"), s"schema 缺 device 参数：$props")
    assertEquals(requiredOf, List("message"), "required 只应剩 message（address/device 由运行期互斥闸判）")
    assert(!requiredOf.contains("address"), "address 必须离开 required（否则「双缺」与 schema 自相矛盾）")

  test("校验词表 ①互斥：address + device 同填 ⇒ MAIL_TARGET_EXCLUSIVE"):
    val msg = callErr(
      JsonObject(
        "address" -> "project:x".asJson,
        "device" -> "dev-b".asJson,
        "message" -> "hi".asJson
      )
    )
    assert(msg.contains(s"[${MailTool.ErrTargetExclusive}]"), msg)
    assert(msg.contains("mutually exclusive"), msg)

  test("校验词表 ②双缺：两个目标都没填 ⇒ MAIL_TARGET_MISSING"):
    val msg = callErr(JsonObject("message" -> "hi".asJson))
    assert(msg.contains(s"[${MailTool.ErrTargetMissing}]"), msg)
    assert(msg.contains("'address' (agent/team/project)"), msg)

  test("校验词表 ③非法形态：device: 前缀 / URL ⇒ MAIL_DEVICE_MALFORMED（零副作用，先于任何解析）"):
    val prefixed = callErr(JsonObject("device" -> "device:dev-b".asJson, "message" -> "hi".asJson))
    assert(prefixed.contains(s"[${MailTool.ErrDeviceMalformed}]"), prefixed)
    assert(prefixed.contains("device:"), prefixed)
    val url = callErr(JsonObject("device" -> "http://127.0.0.1:8080".asJson, "message" -> "hi".asJson))
    assert(url.contains(s"[${MailTool.ErrDeviceMalformed}]"), url)
    // 空白 device 经 trim 等价于「没填」⇒ 落双缺词表（与 address 缺席同判，不是 MALFORMED）
    val blank = callErr(JsonObject("device" -> "   ".asJson, "message" -> "hi".asJson))
    assert(blank.contains(s"[${MailTool.ErrTargetMissing}]"), blank)

  test("设备腿两条显式拒绝：delivery=queue 与 非 INFO 类型（禁静默丢语义）"):
    val q = callErr(JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson, "delivery" -> "queue".asJson))
    assert(q.contains("always immediate"), q)
    val t = callErr(JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson, "type" -> "INTERRUPT".asJson))
    assert(t.contains("only type \"INFO\" is supported"), t)

  test("message 仍必填（两种目标面一致）"):
    val a = callErr(JsonObject("address" -> "project:x".asJson))
    assertEquals(a, "Missing required parameter: message")
    val d = callErr(JsonObject("device" -> "KAI-MBP".asJson))
    assertEquals(d, "Missing required parameter: message")

  test("summarize 回显 device 目标（工具调用摘要可见设备面）"):
    assertEquals(MailTool.summarize(JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson)), "Mail(→device:KAI-MBP)")
    assertEquals(MailTool.summarize(JsonObject("address" -> "Nebula".asJson, "message" -> "hi".asJson)), "Mail(→Nebula)")

  // ============================================================
  // ③ 校验词表 ④未知设备（名册夹具：多设备注册表）
  // ============================================================

  private val dispatcherResource: Dispatcher[IO] =
    Dispatcher.parallel[IO].allocated.unsafeRunSync()._1

  private def neblineService: NeblinkService =
    NeblinkService.createForTest(serverPort = 8099, dispatcher = dispatcherResource, gracePeriod = 1.second)
      .unsafeRunSync()

  /** 多设备名册夹具（v2 ②：只投被寻址设备，不广播）。 */
  private val multiDeviceRoster: List[PeerInfo] = List(
    PeerInfo("dev-b", "KAI-MBP", "darwin", "http://127.0.0.1:8096"),
    PeerInfo("dev-c", "KAI-Air", "darwin", "http://127.0.0.1:8097"),
    PeerInfo("dev-d", "KAI-Studio", "darwin", "http://127.0.0.1:8098")
  )

  /** 真 NeblinkService + 名册夹具（`upsertPeer` 是公开面）。 */
  private def serviceWithPeers(peers: List[PeerInfo]): NeblinkService =
    val ns = neblineService
    peers.foreach(p => ns.upsertPeer(p).unsafeRunSync())
    ns

  private def deviceCtx(ns: NeblinkService): ToolContext =
    ToolContext(
      projectRoot = tempRoot.toString,
      sharedResources = Some(
        resourcesWith(
          registry = Map.empty,
          store = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
          ns = Some(ns)
        )
      )
    )

  private def deviceErr(ns: NeblinkService, device: String): String =
    MailTool.call(JsonObject("device" -> device.asJson, "message" -> "hi".asJson), deviceCtx(ns)).unsafeRunSync() match
      case Left(err)  => err.message
      case Right(msg) => fail(s"expected an explicit error for device='$device', got success: $msg")

  test("校验词表 ④未知设备：名册无该设备 ⇒ MAIL_DEVICE_NOT_FOUND + 候选清单（禁静默首命中）"):
    val msg = deviceErr(serviceWithPeers(multiDeviceRoster), "no-such-device")
    assert(msg.contains(s"[${MailTool.ErrDeviceNotFound}]"), msg)
    assert(msg.contains("not found among 3 peer(s)"), msg)
    assert(msg.contains("KAI-MBP") && msg.contains("KAI-Air"), s"候选清单必须列出可用设备：$msg")

  test("校验词表 ④歧义：多命中 ⇒ MAIL_DEVICE_NOT_FOUND + 逐条命中依据（禁静默首命中）"):
    val msg = deviceErr(serviceWithPeers(multiDeviceRoster), "KAI")
    assert(msg.contains(s"[${MailTool.ErrDeviceNotFound}]"), msg)
    assert(msg.contains("ambiguous"), msg)
    assert(msg.contains("matched by"), s"歧义必须逐条给区分依据：$msg")

  test("定向（v2 ②）：多设备名册下只有被寻址设备被解析为目标（其余零投递面）"):
    val ns = serviceWithPeers(multiDeviceRoster)
    // 精确 id / 精确名两种寻址形态都命中，且**不**广播：命中后只走单目标发送链
    // （此处 relay client 未接线 ⇒ 显式失败，正是「已定向到单目标、未做任何 fan-out」
    //  的可判读数：错误文案指向 relay client，而非「多设备」或候选清单）。
    val byId = deviceErr(ns, "dev-b")
    val byName = deviceErr(ns, "KAI-Air")
    assert(byId.contains("relay client is not initialized"), byId)
    assert(byName.contains("relay client is not initialized"), byName)
    assert(!byId.contains("ambiguous") && !byName.contains("ambiguous"), "唯一命中不得报歧义")
    assert(!byId.contains("not found among"), byId)
    // 歧义形态在**任何副作用之前**就被拒（零投递）
    val ambiguous = deviceErr(ns, "KAI")
    assert(ambiguous.contains("ambiguous") && !ambiguous.contains("relay client"), ambiguous)

  // ============================================================
  // ④ 发送端点形态（契约 v2 ①：目标走路径）
  // ============================================================

  private val cfg = NeblinkServerConfig(
    url = "http://127.0.0.1:9",
    networkId = "net",
    secret = "s",
    deviceToken = None
  )

  /** 真实 NeblinkClient + 罐头传输：驱动**真** login + relayAgentMail 链。
    *
    * `mailResponse` = 邮件端点（`/api/relay/.../mail`）的原始响应体，供形态/缺 id
    * 两态共用同一传输桩（禁为第二个用例另造一套链）。
    */
  private class CaptureClient(mailResponse: String = """{"messageId":"m-77"}"""):
    var calls = List.empty[(String, String, String)] // (method, url, body)

    val client = new NeblinkClient(cfg, serverPort = 9):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        IO {
          calls = calls :+ ((method, url, body))
          if url.endsWith("/api/device/login") || url.endsWith("/api/device/session") then
            Right("""{"token":"tok-1","networkId":"net","deviceId":"dev-a","peers":[]}""")
          else Right(mailResponse)
        }

  test("端点形态：POST {url}/api/relay/{target}/mail，body = 契约五键载荷本体"):
    val c = new CaptureClient
    c.client.mailUrl("dev-b") match
      case u =>
        assertEquals(u, "http://127.0.0.1:9/api/relay/dev-b/mail")
        assert(!u.contains("?"), "禁查询串（v2 ①）")
    val payload = DeviceMail.payload("hello B", "KAI-MBP", "dev-a")
    val loginOut = c.client.login("dev-a", "KAI-MBP", "darwin", Nil).unsafeRunSync()
    assert(loginOut.isRight, s"夹具登录必须成功（否则下面的 Left 不是端点形态的读数）：$loginOut")
    val r = (c.client.relayAgentMail("dev-b", payload)).unsafeRunSync()
    assertEquals(r, Right("m-77"), "响应 id 参与 ack 关联（宽容读取 messageId）")
    assertEquals(c.calls.size, 2, s"恰好两次请求（login + mail），无 fan-out：${c.calls.map(_._2)}")
    val (mailMethod, mailUrl, mailBody) = c.calls.last
    assertEquals(mailMethod, "POST")
    assertEquals(mailUrl, "http://127.0.0.1:9/api/relay/dev-b/mail")
    assertEquals(parse(mailBody).toOption, Some(payload), "body 必须逐字是契约五键载荷本体")
    assert(!mailBody.contains("dev-c") && !mailBody.contains("dev-d"), "body 内不得夹带其它目标（禁 body 内带 target）")
    assert(
      c.calls.map(_._2).count(_.contains("/api/relay/")) == 1,
      "定向：只发一次、只对被寻址设备（禁广播/禁 fan-out）"
    )

  test("响应缺 id ⇒ 空串（不伪造 id；ack 关联面显式可判）"):
    val c = new CaptureClient("{}")
    val loginOut = c.client.login("dev-a", "KAI-MBP", "darwin", Nil).unsafeRunSync()
    assert(loginOut.isRight, s"夹具登录必须成功：$loginOut")
    val r = c.client.relayAgentMail("dev-b", DeviceMail.payload("x", "KAI-MBP", "dev-a")).unsafeRunSync()
    assertEquals(r, Right(""), "无 id ⇒ 空串（调用方登记 @unkeyed 占位，不伪造 id）")

  // ============================================================
  // ⑤ 收件腿：注入（头行/source/INFO）+ ack（回执）+ 失败禁静默
  // ============================================================

  private val system = NebActorSystem("device-mail-spec")

  private def recordingRef(sink: Ref[IO, List[AgentCommand]]): nebflow.actor.ActorRef[AgentCommand] =
    // 记录型 actor：把收到的命令原样落进 sink，行为保持存活（Behaviors.same 不存在，
    // 故用自引用 def 重建等价行为实例）。
    def loop: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => sink.update(_ :+ msg).as(loop))
    system
      .spawn(loop, s"dm-${java.util.UUID.randomUUID().toString.take(8)}")
      .unsafeRunSync()

  private def resourcesWith(
      registry: Map[String, AgentRecord],
      store: SessionStore,
      ns: Option[NeblinkService] = None
  ): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = store,
      projectRoot = tempRoot,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      agentRegistry = Ref.unsafe[IO, Map[String, AgentRecord]](registry),
      neblinkService = ns,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  /** 收件腿夹具：真 sessionStore（meta.agentName == "Nebula"）+ 注册表 Root 记录 +
    * 记录型 wsSend/ackSender。 */
  private def inboxFixture(): (SharedResources, Ref[IO, List[AgentCommand]], Ref[IO, List[Json]], Ref[IO, List[String]]) =
    val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    val meta = store.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    val msgs = Ref.unsafe[IO, List[AgentCommand]](Nil)
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    val acks = Ref.unsafe[IO, List[String]](Nil)
    val res = resourcesWith(
      Map(meta.id -> AgentRecord(sessionId = meta.id, ref = recordingRef(msgs), kind = AgentKind.Root, rootSessionId = meta.id)),
      store
    )
    DeviceMailInbox.initialize(res, (j: Json) => frames.update(_ :+ j), Some((e: String) => acks.update(_ :+ e)))
    (res, msgs, frames, acks)

  /** v2.1 收件入场信封（**逐字**照服务端投递实证样例的键位/层级构造）：
    * `{"type":"friend_event","eventId":"message-<id>","event":{"payload":{<五键>},"type":"agent_mail"}}` */
  private def envelope(eventId: String, payload: Json = contractPayload): Json =
    Json.obj(
      "type" -> "friend_event".asJson,
      "eventId" -> eventId.asJson,
      "event" -> Json.obj(
        "payload" -> payload,
        "type" -> "agent_mail".asJson
      )
    )

  test("收件腿（v2.1 单一入场）：事件流信封 ⇒ 注入本机 Nebula —— 头行逐字 + source=deviceMail + INFO + sender=from_device"):
    val (_, msgs, frames, acks) = inboxFixture()
    DeviceMailInbox.handle(envelope("message-1000000000000001")).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()

    val got = msgs.get.unsafeRunSync()
    assertEquals(got.size, 1, s"应恰好注入一条 ImmediateInput，实得 $got")
    got.head match
      case AgentCommand.ImmediateInput(text, _, source, eventType, sender, _, _, fromUser) =>
        assertEquals(text, "[DEVICE-MAIL · from KAI-MBP]\nhello B")
        assertEquals(source, Some(DeviceMail.SourceDeviceMail))
        assertEquals(eventType, Some(DeviceMail.EventTypeInfo))
        assertEquals(sender, Some("KAI-MBP"))
        assertEquals(fromUser, false, "服务端注入不是真人输入（fromUser=false）")
      case other => fail(s"expected ImmediateInput, got $other")

    // 蓝气泡帧由 AgentActor 的唯一发射点产出（本 spec 只钉注入面取值域）；
    // 告警帧在成功路径必须**为 0**。
    assertEquals(frames.get.unsafeRunSync().size, 0, "成功路径不得发告警帧")
    assertEquals(
      acks.get.unsafeRunSync(),
      List("message-1000000000000001"),
      "收件回执按**帧级** eventId 原样 ack（既有 ack 出口；v2.1 服务端同 id 回投发送方）"
    )

  test("收件腿（v2.1 单一入场负控）：顶层裸 agent_mail 帧不再是入口 —— 零注入、零 ack、零告警"):
    val (_, msgs, frames, acks) = inboxFixture()
    DeviceMailInbox.handle(contractPayload).unsafeRunSync() // v2 时期的顶层直收形态
    IO.sleep(300.millis).unsafeRunSync()
    assertEquals(msgs.get.unsafeRunSync(), Nil, "双入口即违约：旧顶层形态不得注入")
    assertEquals(acks.get.unsafeRunSync(), Nil, "旧顶层形态不得回 ack")
    assertEquals(frames.get.unsafeRunSync(), Nil, "旧顶层形态不是「注入失败」：不发告警帧（只落可读 WARN）")

  test("收件腿（非本批事件）：普通 friend_event / 其它帧 ⇒ 零副作用（原样交回既有路径）"):
    val (_, msgs, frames, acks) = inboxFixture()
    DeviceMailInbox.handle(
      Json.obj("type" -> "friend_event".asJson, "eventId" -> "friend-evt-1".asJson,
        "event" -> Json.obj("type" -> "message".asJson, "payload" -> Json.obj("text" -> "hi".asJson)))
    ).unsafeRunSync()
    DeviceMailInbox.handle(Json.obj("type" -> "device_status_update".asJson)).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()
    assertEquals(msgs.get.unsafeRunSync(), Nil)
    assertEquals(frames.get.unsafeRunSync(), Nil)
    assertEquals(acks.get.unsafeRunSync(), Nil)

  test("收件腿：信封未带 eventId ⇒ 注入照常、不回 ack（显式登记，不静默）"):
    val withId = envelope("message-m-1").asObject.get
    val (_, msgs, _, acks) = inboxFixture()
    DeviceMailInbox.handle(Json.fromJsonObject(withId.remove("eventId"))).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()
    assertEquals(msgs.get.unsafeRunSync().size, 1, "注入照常发生")
    assertEquals(acks.get.unsafeRunSync(), Nil, "无 eventId ⇒ 不回 ack（审计行记 ack-not-sent）")

  test("收件腿：畸形载荷（缺字段/未知 event.type/缺 payload）⇒ 忽略 + 不注入 + 不告警（老版本对端降级不崩）"):
    val (_, msgs, frames, _) = inboxFixture()
    // 缺 from_device_id
    DeviceMailInbox.handle(envelope("message-x", Json.obj("type" -> "agent_mail".asJson, "from_device" -> "X".asJson))).unsafeRunSync()
    // 未知 event.type（老版本对端 / 新事件类型）
    DeviceMailInbox.handle(
      Json.obj("type" -> "friend_event".asJson, "eventId" -> "friend-evt-9".asJson,
        "event" -> Json.obj("type" -> "brand_new_type".asJson, "payload" -> Json.obj()))
    ).unsafeRunSync()
    // event.type 对但 payload 缺席
    DeviceMailInbox.handle(
      Json.obj("type" -> "friend_event".asJson, "event" -> Json.obj("type" -> "agent_mail".asJson))
    ).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()
    assertEquals(msgs.get.unsafeRunSync(), Nil, "畸形载荷不得注入任何内容")
    assertEquals(frames.get.unsafeRunSync(), Nil, "畸形载荷不是「注入失败」：不发告警帧（只落可读 WARN）")

  test("收件腿：无 live Nebula root ⇒ 重试后告警帧（注入失败禁静默）"):
    val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    val res = resourcesWith(Map.empty, store) // 注册表无 Root 记录
    DeviceMailInbox.initialize(res, (j: Json) => frames.update(_ :+ j), None)
    DeviceMailInbox.handle(envelope("message-fail-1")).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()
    val fs = frames.get.unsafeRunSync()
    assertEquals(fs.size, 1, s"应恰好一条告警帧，实得 $fs")
    assertEquals(fs.head.hcursor.get[String]("type").toOption, Some(DeviceMailInbox.InjectFailedFrameType))
    assertEquals(fs.head.hcursor.get[String]("fromDevice").toOption, Some("KAI-MBP"))
    assertEquals(fs.head.hcursor.get[Int]("attempts").toOption, Some(DeviceMailInbox.InjectAttempts))

  test("收件腿：未接线 ⇒ 显式失败（不静默成功）"):
    assertEquals(DeviceMailInbox.isWired, false)
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    // 未 initialize：handle 仍必须跑完（注入路径显式失败，不抛、不崩）并留可读读数
    DeviceMailInbox.handle(envelope("message-nowire-1")).unsafeRunSync()
    assertEquals(frames.get.unsafeRunSync(), Nil, "未接线时无前端广播口 ⇒ 零帧（WARN 日志是唯一读数）")

  // ============================================================
  // ⑥ ack 关联与超时腿（契约 v2 ④）
  // ============================================================

  test("ack：eventId 关联命中 ⇒ 出队（pending 归零）；未知 message- 族 ⇒ 不静默（WARN 路径可跑）"):
    DeviceMailAck.resetForTest().unsafeRunSync()
    val eventId = DeviceMailAck.await("dev-b", "m-77").unsafeRunSync()
    assertEquals(eventId, "message-m-77")
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 1)
    DeviceMailAck.handle(parse("""{"type":"ack","eventId":"message-m-77"}""").toOption.get).unsafeRunSync()
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "命中 ⇒ 出队")
    // 未命中且属 message- 族：走 WARN 分支（不抛异常即通过；日志面是可见读数）
    DeviceMailAck.handle(parse("""{"type":"ack","eventId":"message-nope"}""").toOption.get).unsafeRunSync()
    // 无 eventId 的 ack 帧：忽略（debug），不改变 pending 面
    DeviceMailAck.handle(Json.obj("type" -> "ack".asJson)).unsafeRunSync()
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0)

  test("ack：无响应 id ⇒ @unkeyed 占位（禁伪造 id，超时行据此可判）"):
    DeviceMailAck.resetForTest().unsafeRunSync()
    assertEquals(DeviceMailAck.await("dev-b", "").unsafeRunSync(), "message-@unkeyed")

  // ============================================================
  // ⑦ ack 边界（契约 v2.1 ②）：A 离线期 ack 不补投 ⇒ 超时按「未确认」，不自动重发
  // ============================================================

  test("ack 边界（v2.1 ②）：超时窗内无 ack ⇒ 到点出队为「未确认」，零重发、零补投"):
    DeviceMailAck.resetForTest().unsafeRunSync()
    val eventId = DeviceMailAck.await("dev-offline", "m-offline-1").unsafeRunSync()
    assertEquals(eventId, "message-m-offline-1")
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 1, "登记入 pending")
    // 窗内零 ack（对端离线：服务端不补投，本侧也绝不代发/重发——DeviceMailAck 结构上
    // 没有任何发送能力，只有 await/handle 两个入口）。
    IO.sleep(DeviceMailAck.AckTimeout + 1.second).unsafeRunSync()
    assertEquals(
      DeviceMailAck.pendingCount.unsafeRunSync(),
      0,
      s"到点（${DeviceMailAck.AckTimeout.toSeconds}s）出队 ⇒ 该发送按「未确认」终结（WARN + 审计行 ack-timeout 见测试日志）"
    )
    // 迟到 ack（超出等待窗）⇒ 未命中分支：WARN + 审计（禁静默），不改变任何交付结论
    DeviceMailAck.handle(parse(s"""{"type":"ack","eventId":"$eventId"}""").toOption.get).unsafeRunSync()
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "迟到 ack 不得复活已终结的发送")

  // ============================================================
  // ⑧ 端到端（服务端腿 = 契约转发桩）：发送腿 ⇄ 收件腿 ⇄ ack 闭环
  // ============================================================

  test("端到端（服务端腿以契约转发桩替代）：Mail(device:KAI-Air) → 真发送链（恰一次 POST /api/relay/dev-c/mail）→ 事件流信封 → 对端注入 + ack 关联闭环"):
    DeviceMailAck.resetForTest().unsafeRunSync()
    val (_, msgs, frames, acks) = inboxFixture() // 收件侧 = 真 inbox 装配（真 sessionStore + root 记录 + 记录型 ack 出口）
    val ns = serviceWithPeers(multiDeviceRoster)
    val capture = new CaptureClient
    assert(capture.client.login("dev-a", "KAI-MBP", "darwin", Nil).unsafeRunSync().isRight, "夹具登录必须成功")
    ns.setRelayClient(Some(capture.client))

    val out = MailTool.call(JsonObject("device" -> "KAI-Air".asJson, "message" -> "hello B".asJson), deviceCtx(ns)).unsafeRunSync()
    assert(out.isRight, s"device 邮件应成功下发：$out")

    // ① 发送面：**恰一次**、目标走路径、body = 契约五键本体、定向（其余设备零流量）
    val relayCalls = capture.calls.filter(_._2.contains("/api/relay/"))
    assertEquals(relayCalls.size, 1, s"禁 fan-out：恰一次 relay 邮件请求，实得 ${capture.calls.map(_._2)}")
    val (method, url, body) = relayCalls.head
    assertEquals(method, "POST")
    assertEquals(url, "http://127.0.0.1:9/api/relay/dev-c/mail", "目标（KAI-Air → dev-c）走路径")
    val sent = parse(body).toOption.getOrElse(fail("body 必须是 JSON"))
    assertEquals(sent.asObject.map(_.keys.toList.sorted).getOrElse(Nil), DeviceMail.PayloadKeys.sorted, "恰契约五键")
    assertEquals(sent.hcursor.get[String]("text").toOption, Some("hello B"))
    assertEquals(sent.hcursor.get[Boolean]("to_nebula").toOption, Some(true))
    assert(sent.hcursor.get[String]("from_device").toOption.exists(_.nonEmpty), "from_device = 本机自报展示名")
    assert(!body.contains("dev-b") && !body.contains("dev-d"), "body 内不得夹带其它设备（其余零投递）")

    // ② 服务端腿（外部依赖，本测试以**契约转发桩**替代）：把发出的载荷按 v2.1 事件流信封投给收件侧
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 1, "发送侧已登记 pending（eventId=message-<服务端 id>）")
    DeviceMailInbox.handle(envelope("message-m-77", sent)).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()
    assertEquals(msgs.get.unsafeRunSync().size, 1, "对端注入恰好一条 ImmediateInput")
    assertEquals(frames.get.unsafeRunSync(), Nil, "成功路径零告警帧")
    assertEquals(acks.get.unsafeRunSync(), List("message-m-77"), "对端按**帧级** eventId 回 ack")

    // ③ 回执回投（v2.1 ②）：同 eventId 的 ack 回到发送侧 ⇒ pending 归零（发送链 ack 闭环）
    DeviceMailAck.handle(parse("""{"type":"ack","eventId":"message-m-77"}""").toOption.get).unsafeRunSync()
    assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "ack 命中 ⇒ 出队（闭环）")

end DeviceMailSpec
