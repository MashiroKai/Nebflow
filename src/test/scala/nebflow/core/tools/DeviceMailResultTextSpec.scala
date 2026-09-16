package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.gateway.SessionStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.neblink.{NeblinkClient, NeblinkServerConfig, NeblinkService, PeerInfo}

/**
 * 设备腿**结果文本**：delivered 三态（B 批，2026-09-16 作者裁定「路径 B」）。
 *
 * 本 spec 驱动**真** chain（真 `NeblinkService` 名册 + 真 `NeblinkClient`（罐头传输）
 * ⇒ 真 `relayAgentMail` 解析 ⇒ 真 `MailTool` 设备腿），断言只落在**结果文本/请求体**
 * 上，**不引用**实现里的新类型名 —— 这正是「钉1 改前红」可复现的前提：同一份 spec
 * 在改前（旧返回类型 + 旧文案）跑**红**、改后跑**绿**（读数见交付报告）。
 *
 * 三态（服务端 `delivered` 键，读数 = `neblink-server` `routes.rs:1901-1906` /
 * `agentmail.rs:227-259`）：
 *   - `true`  ⇒ 载荷已推进对端**活体隧道**（≠ 对端已注入 ⇒ 禁「acknowledged」类措辞）；
 *   - `false` ⇒ 此刻无隧道 ⇒ 行已持久、随下一轮隧道注册补齐，**不是错误**；
 *   - 键**缺席**（旧服务端）⇒ 选定降级口径 = **保守支**（`absentKey` 用例逐字钉）：
 *     只在服务端**显式**断言 `true` 时才声称活体推送；缺席一律按「服务端已接受、
 *     尚未确认活体推送」处理（**禁冒认**一次服务端未断言的推送）。
 *
 * 面定性回归（同批，双向读数）：relay 请求体键集合逐字（`requestBodyKeys`）、
 * 失败面结构化（`category` + `raw error`）、ack 面零变更（成功腿仍登记 pending ⇒
 * `DeviceMailAck.await` 调用仍在）。
 */
class DeviceMailResultTextSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-device-mail-result-text"

  private val dispatcherResource: Dispatcher[IO] =
    Dispatcher.parallel[IO].allocated.unsafeRunSync()._1

  /** 真 NeblinkClient + **罐头**传输（`DeviceMailSpec:249` 同款形态：只桩 `sendRequest`，
    * 解析/鉴权/投递链全真）。 */
  private class CaptureClient(mailResponse: String):
    var calls = List.empty[(String, String, String)] // (method, url, body)

    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "net", secret = "s", deviceToken = None),
      serverPort = 9
    ):
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

  /** 与 `MailDeviceImagesSpec` 同款：真 NeblinkService + 名册（本 spec 另接线 relay client）。 */
  private def withResources(ctx0: ToolContext, ns: NeblinkService): ToolContext =
    val resources = new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
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
      neblinkService = Some(ns),
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )
    ctx0.copy(sharedResources = Some(resources))

  /** 真 chain 夹具：名册 dev-b/KAI-MBP + 真 NeblinkClient（罐头响应体）。
    * 登录腿由罐头传输满足（真 `login`），故 `relayAgentMail` 的 `withSession` 不触网。 */
  private def fixture(mailResponse: String): (CaptureClient, ToolContext) =
    val ns = NeblinkService.create(0, dispatcherResource).unsafeRunSync()
    ns.upsertPeer(PeerInfo("dev-b", "KAI-MBP", "darwin", "http://127.0.0.1:9")).unsafeRunSync()
    val client = new CaptureClient(mailResponse)
    ns.setRelayClient(Some(client.client))
    val loginOut = client.client.login("dev-a", "KAI-MBP", "darwin", Nil).unsafeRunSync()
    assert(loginOut.isRight, s"夹具登录必须成功（否则下面的读数不是设备腿结果文本）：$loginOut")
    (client, withResources(ToolContext(projectRoot = tempRoot.toString), ns))

  private def call(ctx: ToolContext): String =
    MailTool.call(JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson), ctx)
      .unsafeRunSync() match
      case Right(text) => text
      case Left(err)   => fail(s"expected the device leg to succeed, got error: ${err.message}")

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  // ============================================================
  // ① delivered=true ⇒ 成功分支（🔴 收窄口径：禁「peer acknowledged」类过强措辞）
  // ============================================================

  test("delivered=true ⇒ 结果文本走「活体通道推送」分支（且禁 ack/已注入类过强措辞）"):
    val (_, ctx) = fixture("""{"messageId":"m-77","delivered":true}""")
    val text = call(ctx)
    assertEquals(
      text,
      "Message sent to device 'KAI-MBP' — the frozen agent_mail payload (type=agent_mail, to_nebula=true) " +
        "is on the relay route /api/relay/dev-b/mail: pushed to the peer's live channel — the peer's Nebula " +
        "session will be injected at its next turn boundary.",
      "delivered=true ⇒ 结果文本**逐字**（全文等式；卡面短语逐字在内）"
    )
    assert(
      text.contains("pushed to the peer's live channel"),
      s"delivered=true 必须逐字给出活体通道读数，实得：$text"
    )
    assert(!text.contains("peer acknowledged"), s"推送被隧道接受 ≠ 对端已注入：禁过强措辞 —— $text")
    assert(!text.contains("offline"), s"delivered=true 不得落离线文案 —— $text")
    assert(!text.contains("Awaiting ack"), s"「Awaiting ack」已删（作者裁定；eventId 手柄不保留）—— $text")
    assert(text.contains("Message sent to device 'KAI-MBP'"), s"目标名必须仍在（既有框架逐字）—— $text")
    assert(text.contains("/api/relay/dev-b/mail"), s"既有投递路径读数必须仍在 —— $text")

  // ============================================================
  // ② delivered=false ⇒ 离线分支（**非错误**）
  // ============================================================

  test("delivered=false ⇒ 离线在队分支逐字合卡（服务端已接受，不是错误）"):
    val (_, ctx) = fixture("""{"messageId":"m-78","delivered":false}""")
    val text = call(ctx)
    assertEquals(
      text,
      "Message sent to device 'KAI-MBP' — the frozen agent_mail payload (type=agent_mail, to_nebula=true) " +
        "is on the relay route /api/relay/dev-b/mail: server accepted; peer device offline — queued until " +
        "it comes online (not an error). It will be readable on that device once it comes online.",
      "delivered=false ⇒ 结果文本**逐字**（全文等式；卡面离线短语逐字在内）"
    )
    assert(
      text.contains("server accepted; peer device offline — queued until it comes online (not an error)"),
      s"delivered=false 必须逐字给出离线在队读数（且不得判成失败），实得：$text"
    )
    assert(
      text.contains("readable on that device once it comes online"),
      s"允许的「上线后可读」提示应显式在场 —— $text"
    )
    assert(!text.contains("pushed to the peer's live channel"), s"离线支不得声称活体推送 —— $text")
    assert(!text.contains("Awaiting ack"), s"「Awaiting ack」已删 —— $text")

  // ============================================================
  // ③ 键缺席（旧服务端）⇒ 选定降级口径：**保守支**（不崩、不冒认）
  // ============================================================

  test("delivered 键缺席（旧服务端）⇒ 保守支：不崩、不冒认活体推送"):
    val (_, ctx) = fixture("""{"messageId":"m-79"}""")
    val text = call(ctx)
    assert(
      text.contains("queued until it comes online (not an error)"),
      s"键缺席 ⇒ 首选定降级口径 = 保守支（只在显式 true 时才声称活体推送），实得：$text"
    )
    assert(!text.contains("pushed to the peer's live channel"), s"键缺席不得冒认推送 —— $text")

  test("响应体为空对象（无 id 无 delivered）⇒ 结果文本照常产出（不崩）"):
    val (_, ctx) = fixture("{}")
    val text = call(ctx)
    assert(text.nonEmpty, s"空响应也必须给出可判读文本 —— $text")
    assert(!text.contains("Awaiting ack"), s"「Awaiting ack」已删 —— $text")

  test("非 JSON 响应（例如纯文本 ack）⇒ 不崩（既有宽容分支零语义变更）"):
    val (_, ctx) = fixture("message-80")
    val text = call(ctx)
    assert(text.nonEmpty, s"非 JSON 响应也必须给出可判读文本 —— $text")

  // ============================================================
  // ④ 失败面：结构化（类别 + 原因 + 原始错误面原文摘录），禁只写「失败」
  // ============================================================

  test("relay 失败（远端 error 面）⇒ 结构化失败文本（类别 + 原因 + 原始错误原文摘录）"):
    val (_, ctx) = fixture("""{"error":"relay: device not enrolled"}""")
    val err = MailTool.call(
      JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson),
      ctx
    ).unsafeRunSync() match
      case Left(e)  => e.message
      case Right(t) => fail(s"远端 error 面必须如实报错（禁静默成功），实得成功文本：$t")
    assertEquals(
      err,
      "Device mail to 'KAI-MBP' FAILED — category: relay-route-refused; reason: the NebLink relay did not " +
        "accept the agent_mail payload (HTTP/session/transport failure, or a remote error from the relay), " +
        "so nothing was sent and nothing is queued — fix the cause and retry; raw error: relay: device not enrolled",
      "失败面**逐字**（全文等式：类别 + 原因 + 原始错误面原文摘录）"
    )
    assert(err.contains("FAILED"), s"失败面必须显式（禁静默）—— $err")
    assert(err.contains("category:"), s"失败面必须带**类别**（禁只写「失败」）—— $err")
    assert(err.contains("reason:"), s"失败面必须带**原因** —— $err")
    assert(err.contains("relay: device not enrolled"), s"失败面必须带**原始错误面原文摘录** —— $err")

  test("relay 失败（会话/传输面）⇒ 同一结构化失败文本（原文摘录 = 该面错误原文）"):
    val ns = NeblinkService.create(0, dispatcherResource).unsafeRunSync()
    ns.upsertPeer(PeerInfo("dev-b", "KAI-MBP", "darwin", "http://127.0.0.1:9")).unsafeRunSync()
    ns.setRelayClient(Some(new CaptureClient("""{"messageId":"m-x"}""").client)) // 未登录 ⇒ 会话面失败
    val ctx = withResources(ToolContext(projectRoot = tempRoot.toString), ns)
    val err = MailTool.call(
      JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson),
      ctx
    ).unsafeRunSync() match
      case Left(e)  => e.message
      case Right(t) => fail(s"会话面失败必须如实报错（禁静默成功），实得成功文本：$t")
    assert(err.contains("category:") && err.contains("reason:"), s"两类失败都必须结构化 —— $err")
    assert(err.contains("raw error: Not logged in"), s"原文摘录必须来自失败面原文 —— $err")

  // ============================================================
  // ⑤ 面定性回归：请求体键集合逐字 + ack 面零变更（await 仍被调用）
  // ============================================================

  test("面定性回归①：relay 请求体键集合逐字 = 契约五键载荷（改前/改后逐字相等）"):
    val (client, ctx) = fixture("""{"messageId":"m-81","delivered":true}""")
    call(ctx)
    val (method, url, body) = client.calls.last
    assertEquals(method, "POST")
    assert(url.endsWith("/api/relay/dev-b/mail"), s"目标走路径（v2 ①）—— $url")
    val keys = io.circe.parser.parse(body).toOption.flatMap(_.asObject).map(_.keys.toList.sorted)
    assertEquals(
      keys,
      Some(List("from_device", "from_device_id", "text", "to_nebula", "type")),
      s"请求体键集合必须逐字是契约五键载荷本体（改前/改后相等）—— body=$body"
    )

  test("面定性回归②：ack 面零变更 —— 成功腿仍登记 pending（DeviceMailAck.await 调用仍在）"):
    val before = nebflow.neblink.DeviceMailAck.pendingCount.unsafeRunSync()
    val (_, ctx) = fixture("""{"messageId":"m-82","delivered":true}""")
    call(ctx)
    val after = nebflow.neblink.DeviceMailAck.pendingCount.unsafeRunSync()
    assertEquals(
      after,
      before + 1,
      s"成功腿必须仍登记一条待回执（await 调用本身零变更；其返回值不再进结果文本）before=$before after=$after"
    )

end DeviceMailResultTextSpec
