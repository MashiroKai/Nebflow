package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem as NebActorSystem, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord}
import nebflow.agent.SharedResources
import nebflow.gateway.SessionStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.PathUtil
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 设备邮件腿**事件号去重闸**（缺陷 B 最小修复 S1，2026-09-18 作者第二单）。
 *
 * 缺陷（上游 `mailreplay-recon`(n-bdb2edab) 只读取证，结果逐字采信）：服务端在隧道重连时
 * 按 at-least-once 重播「尚未脱账」的事件（本机生产日志实证：重连成功后 **4 ms** 跟入
 * `duplicate_eventId` 补投，好友面同期 131 次、设备邮件面 **0** 次）；好友腿有
 * `FriendService` 的 `guard.dedupe(eventId)` 把重放静默吸收，设备邮件腿（`agent_mail`，
 * `NeblinkRelayTunnel.scala:931-932` 独占路由）**从未接上**这道闸 ⇒ 每重播一次就再注入
 * 一次（多一个蓝气泡、多跑一轮模型与工具）。
 *
 * 本 spec = 该闸的机械读数（判据面三条齐备，都不依赖肉眼）：
 *   ① 注入面计数（记录型 actor 收到的 `ImmediateInput` 条数）；
 *   ② 回执面计数（记录型 ack 出口）+ 审计盘面（`relay-exec-audit.jsonl` 的 `action` 列）；
 *   ③ 日志原文（logback 捕获 `nebflow.neblink.devicemail`，与 `DeviceMailAckRaceSpec` 同款手法）。
 *
 * 读数的红/绿对照（钉「闸真的在拦」而不只是「测试绿」）：把 `DeviceMailInbox.injectWithDedup`
 * 整体回退为直接 `injectWithRetry(incoming, eventId)`（= 修前形态）后，①（绿钉）必须**转红**
 * 且读数 = 二次注入（注入计数 1 → 2）；恢复后复绿。两轮读数逐轮落盘（见交付报告）。
 */
class DeviceMailDedupSpec extends FunSuite:

  private val LogName = "nebflow.neblink.devicemail"

  private val tempRoot: os.Path = os.pwd / "target" / "test-device-mail-dedup"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    DeviceMailInbox.resetForTest()
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  // ── 读数设施 ────────────────────────────────────────────────────────

  /** 捕获本腿日志原文（按级别分列）。 */
  private def withLegLog[A](f: => A): (A, List[String], List[String]) =
    val lbLogger = LoggerFactory.getLogger(LogName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      val a = f
      val events = appender.list.asScala.toList
      (
        a,
        events.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage),
        events.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)
      )
    finally lbLogger.detachAppender(appender)

  /** 审计盘面的 `action` 列（落盘读数，逐行现读）。 */
  private def auditActions(): List[String] =
    val f = (PathUtil.dataRoot / "logs" / "relay-exec-audit.jsonl").toNIO
    if !java.nio.file.Files.exists(f) then Nil
    else
      java.nio.file.Files
        .readAllLines(f)
        .asScala
        .toList
        .flatMap(l => parse(l).toOption.flatMap(_.hcursor.get[String]("action").toOption))

  /** 收件腿夹具（与 `DeviceMailSpec` 同款：真 sessionStore + Root 记录 + 记录型 wsSend/ack）。 */
  private final case class Inbox(
    msgs: Ref[IO, List[AgentCommand]],
    frames: Ref[IO, List[Json]],
    acks: Ref[IO, List[String]]
  ):
    def injections: Int = msgs.get.unsafeRunSync().size
    def alerts: Int = frames.get.unsafeRunSync().size
    def acked: List[String] = acks.get.unsafeRunSync()

  private val system = NebActorSystem("device-mail-dedup-spec")

  private def recordingRef(sink: Ref[IO, List[AgentCommand]]): nebflow.actor.ActorRef[AgentCommand] =
    def loop: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => sink.update(_ :+ msg).as(loop))
    system
      .spawn(loop, s"dmd-${java.util.UUID.randomUUID().toString.take(8)}")
      .unsafeRunSync()

  private def resourcesWith(
    registry: Map[String, AgentRecord],
    store: SessionStore
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
      neblinkService = None,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  /** 接线（有 live Nebula root + 记录型 ack 出口）。 */
  private def inboxFixture(): Inbox =
    val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    val meta = store.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    val msgs = Ref.unsafe[IO, List[AgentCommand]](Nil)
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    val acks = Ref.unsafe[IO, List[String]](Nil)
    val res = resourcesWith(
      Map(
        meta.id -> AgentRecord(
          sessionId = meta.id,
          ref = recordingRef(msgs),
          kind = AgentKind.Root,
          rootSessionId = meta.id
        )
      ),
      store
    )
    DeviceMailInbox.initialize(
      res,
      (j: Json) => frames.update(_ :+ j),
      Some((e: String) => acks.update(_ :+ e).as(NeblinkRelayTunnel.AckOutcome.Sent))
    )
    Inbox(msgs, frames, acks)

  end inboxFixture

  /** 接线（**无** live Nebula root ⇒ 注入必定失败：跑完 `InjectAttempts` 次重试）。 */
  private def inboxFixtureWithoutRoot(): Inbox =
    val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    val msgs = Ref.unsafe[IO, List[AgentCommand]](Nil)
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    val acks = Ref.unsafe[IO, List[String]](Nil)
    DeviceMailInbox.initialize(
      resourcesWith(Map.empty, store),
      (j: Json) => frames.update(_ :+ j),
      Some((e: String) => acks.update(_ :+ e).as(NeblinkRelayTunnel.AckOutcome.Sent))
    )
    Inbox(msgs, frames, acks)

  /** 契约载荷（逐字，与 `DeviceMailSpec` 同源）。 */
  private val contractPayload: Json =
    parse(
      """{"type":"agent_mail","from_device":"KAI-MBP","from_device_id":"dev-a","to_nebula":true,"text":"hello B"}"""
    )
      .fold(e => fail(s"contract literal must parse: ${e.getMessage}"), identity)

  /** v2.1 收件入场信封（帧根 `eventId`；服务端投递实证形态）。 */
  private def envelope(eventId: String, payload: Json = contractPayload): Json =
    Json.obj(
      "type" -> "friend_event".asJson,
      "eventId" -> eventId.asJson,
      "event" -> Json.obj("payload" -> payload, "type" -> "agent_mail".asJson)
    )

  private def settle(): Unit = IO.sleep(300.millis).unsafeRunSync()

  // ── 测试 ────────────────────────────────────────────────────────────

  test("S1① 绿钉：同一 eventId 二次送达（服务端重放）⇒ 零二次注入 + 重放支仍补 ack"):
    val (readings, warns, infos) = withLegLog {
      val in = inboxFixture()
      val id = "message-1000000000000001"
      DeviceMailInbox.handle(envelope(id)).unsafeRunSync()
      settle()
      val inj1 = in.injections
      val ack1 = in.acked
      DeviceMailInbox.handle(envelope(id)).unsafeRunSync() // 服务端重放（同一事件号）
      settle()
      (inj1, in.injections, ack1, in.acked, in.alerts, DeviceMailInbox.isDone(id).unsafeRunSync())
    }
    val (inj1, inj2, ack1, ack2, alerts, done) = readings
    println(s"[reading][S1①] injections=$inj1->$inj2 acks=${ack1.size}->${ack2.size} alerts=$alerts done=$done")
    assertEquals(inj1, 1, "首次送达必须注入（闸禁误伤首见）")
    assertEquals(inj2, 1, s"🔴 重放不得二次注入（零第二个蓝气泡、零重跑模型与工具），实得 $inj2")
    assertEquals(ack1, List("message-1000000000000001"), "首次注入后回执一次")
    assertEquals(
      ack2,
      List("message-1000000000000001", "message-1000000000000001"),
      "🔴 重放支仍须补发 ack（不 ack ⇒ 服务端永不脱账、重放无限循环）"
    )
    assertEquals(alerts, 0, "重放不是「注入失败」：零告警帧")
    assert(done, "账本应记为已处理")
    assert(
      infos.exists(l => l.contains("reason=duplicate_eventId") && l.contains("message-1000000000000001")),
      s"重复支须留可读 INFO（reason=duplicate_eventId），实得：$infos"
    )
    assertEquals(warns, Nil, "重放路径零 WARN（不是失败、也不是畸形帧）")
    assertEquals(
      auditActions(),
      List(
        "DeviceMail.inject.injected",
        "DeviceMail.inject.ack-sent",
        "DeviceMail.inject.duplicate-skipped",
        "DeviceMail.inject.ack-sent"
      ),
      "审计盘面：注入一次 + 两次回执（首见一次、重放补一次），重放支标明 duplicate-skipped"
    )

  test("S1② 闸不误伤：两个不同 eventId ⇒ 各自注入一次、各自回执一次"):
    val in = inboxFixture()
    DeviceMailInbox.handle(envelope("message-a-1")).unsafeRunSync()
    settle()
    DeviceMailInbox.handle(envelope("message-b-1")).unsafeRunSync()
    settle()
    println(s"[reading][S1②] injections=${in.injections} acks=${in.acked}")
    assertEquals(in.injections, 2, "不同事件号是两封信，禁合并/禁漏")
    assertEquals(in.acked, List("message-a-1", "message-b-1"))

  test("S1③ 无判重键：帧未带 eventId ⇒ 无事件号可判 ⇒ 照现状注入（不假装判过）"):
    val withId = envelope("message-m-1").asObject.getOrElse(fail("envelope must be an object"))
    val noId = Json.fromJsonObject(withId.remove("eventId"))
    val in = inboxFixture()
    DeviceMailInbox.handle(noId).unsafeRunSync()
    settle()
    DeviceMailInbox.handle(noId).unsafeRunSync()
    settle()
    println(s"[reading][S1③] injections=${in.injections} acks=${in.acked}")
    assertEquals(in.injections, 2, "无 eventId ⇒ 无去重键：两次都注入（本批不改无键支的现状）")
    assertEquals(in.acked, Nil, "无 eventId ⇒ 无从关联回执（既有 ack-not-sent 支）")

  test("S1④ 注入未落地 ⇒ 撤回认领（禁把一封信吞成「已处理」）：同一 eventId 之后仍会注入 + 补 ack"):
    val id = "message-noroot-1"
    val bad = inboxFixtureWithoutRoot()
    DeviceMailInbox.handle(envelope(id)).unsafeRunSync() // 三次尝试（2×2s 间隔）后失败
    settle()
    val failed = (bad.injections, bad.acked, bad.alerts, DeviceMailInbox.isDone(id).unsafeRunSync())
    println(s"[reading][S1④-fail] injections=${failed._1} acks=${failed._2.size} alerts=${failed._3} done=${failed._4}")
    assertEquals(failed._1, 0, "无 root ⇒ 零注入")
    assertEquals(failed._2, Nil, "失败支不回 ack（判词逐字不变）")
    assertEquals(failed._3, 1, "失败禁静默：一条告警帧")
    assertEquals(failed._4, false, "注入未落地 ⇒ 不得记入账本（否则重放会被静默吞掉）")

    val good = inboxFixture() // 根会话修复后
    DeviceMailInbox.handle(envelope(id)).unsafeRunSync() // 服务端重放同一条未脱账事件
    settle()
    println(s"[reading][S1④-retry] injections=${good.injections} acks=${good.acked}")
    assertEquals(good.injections, 1, "🔴 重放必须重试注入（禁被去重闸吞成「已处理」）")
    assertEquals(good.acked, List(id), "注入落地后回执一次")
    assert(DeviceMailInbox.isDone(id).unsafeRunSync(), "落地后账本记为已处理")

  test("S1⑤ 并发同帧（同一事件号正在注入）⇒ 跳过注入、不提前 ack（在飞的那次尝试拥有回执）"):
    val id = "message-inflight-1"
    val in = inboxFixture()
    assertEquals(DeviceMailInbox.claimEvent(id).unsafeRunSync(), DeviceMailInbox.Claim.Fresh, "首见 ⇒ 认领")
    val (readings, _, infos) = withLegLog {
      DeviceMailInbox.handle(envelope(id)).unsafeRunSync()
      settle()
      (in.injections, in.acked)
    }
    println(s"[reading][S1⑤] injections=${readings._1} acks=${readings._2.size}")
    assertEquals(readings._1, 0, "同事件号在飞 ⇒ 禁二次注入")
    assertEquals(readings._2, Nil, "在飞 ⇒ 不提前 ack（该事件尚未落地，禁报成已处理）")
    assert(
      infos.exists(_.contains("reason=concurrent_duplicate_eventId")),
      s"并发支须留可读 INFO，实得：$infos"
    )
    // 在飞尝试的结局由它自己收口（成功 ⇒ completeEvent；失败 ⇒ releaseEvent）
    DeviceMailInbox.releaseEvent(id).unsafeRunSync()
    assert(!DeviceMailInbox.isDone(id).unsafeRunSync(), "撤回后账本不留痕 ⇒ 后续重放仍可注入")

  test("S1⑥ 账本容量：上限 MaxClaimedEvents 条，超出裁最旧（不加无界内存）"):
    val cap = DeviceMailInbox.MaxClaimedEvents
    assertEquals(cap, 2048, "与 FriendMessagingGuard 同量级（2048）")
    val fresh = (1 to cap).forall { i =>
      val id = s"message-cap-$i"
      val claimed = DeviceMailInbox.claimEvent(id).unsafeRunSync() == DeviceMailInbox.Claim.Fresh
      DeviceMailInbox.completeEvent(id).unsafeRunSync()
      claimed
    }
    assert(fresh, s"前 $cap 个事件号均应为首见")
    assert(DeviceMailInbox.isDone("message-cap-1").unsafeRunSync(), "第 1 个仍在账本内")
    assert(
      DeviceMailInbox.claimEvent(s"message-cap-${cap + 1}").unsafeRunSync() == DeviceMailInbox.Claim.Fresh,
      "超出上限后新事件号仍可认领"
    )
    DeviceMailInbox.completeEvent(s"message-cap-${cap + 1}").unsafeRunSync()
    assertEquals(
      DeviceMailInbox.isDone("message-cap-1").unsafeRunSync(),
      false,
      "超上限 ⇒ 最旧的被裁（防无界增长）；裁剪后该号可再次认领"
    )
    assert(
      DeviceMailInbox.claimEvent(s"message-cap-1").unsafeRunSync() == DeviceMailInbox.Claim.Fresh,
      "被裁的号重新按首见处理"
    )
    assert(DeviceMailInbox.isDone(s"message-cap-${cap + 1}").unsafeRunSync(), "最新号仍在账本内")

end DeviceMailDedupSpec
