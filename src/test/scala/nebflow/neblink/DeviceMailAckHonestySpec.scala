package nebflow.neblink

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem as NebActorSystem, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord}
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.gateway.SessionStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import org.slf4j.LoggerFactory

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 回执诚实性（F3 + F4 + F5，2026-09-18 作者裁示「**回执诚实性修、单列小批**」）。
 *
 * 缺陷（上游 `mailreplay-recon`(n-bdb2edab) 只读取证，§1-④ / §2-⑦ / §4 F3-F5 逐字采信）：
 * `NeblinkRelayTunnel.sendAck` 在 `wsRef == None` 时**返回成功**（只落一行 DEBUG），
 * `GatewayMain` 的两处装配缝（好友腿 `:900-905` / 设备邮件腿 `:1010-1015`）又把
 * `relayTunnelOpt` 为空吞成 `IO.unit` ⇒ `DeviceMailInbox.sendReceipt` 照打
 * `ack sent to the server` + 审计 `ack-sent`——**线上零帧却报已回执**（假陈述）；
 * 服务端因此不脱账 ⇒ 重放不退（放大因）。
 *
 * 本 spec = 修正后的**机械读数**，逐条对应任务书验收 1-4：
 *   - **H1**（F3）返回形态**可判别**：无 live socket ⇒ `AckOutcome.NoLiveSocket`，
 *     **不再是「成功」**（修前返回 `IO[Unit]`，类型上就分不出来）；
 *   - **H2**（F5 绿线 · 隧道对象不在册）：`relayTunnelOpt == None` 时注入照常成功，
 *     但日志 = `ack-not-sent reason=no_live_socket`、审计 = `ack-not-sent`，
 *     **零** `ack-sent`（禁假陈述）；
 *   - **H3**（F5 绿线 · 真 socket 连过又被服务端断开 ⇒ socket 不在册）：同 H2 读数，
 *     且**线级**读数 = 真 RFC 6455 夹具收到的 ack 帧数 **0**；
 *   - **H4**（真发出路径零回归）：socket 在册 ⇒ `ack sent to the server` + 审计
 *     `ack-sent` 照旧，**且线上真有帧**（夹具直读帧原文，逐字节 = 冻结帧形）；
 *   - **H5**（F5 第三态）：`SendFailed` 同样**不得**被读成已回执（审计 `ack-send-failed`）；
 *   - **H6**（F4 单一实现点）：两处装配缝**只**调 `NeblinkRelayTunnel.sendAckLive`
 *     （源码级读数：`GatewayMain` 内 `sendAckLive` 恰 2 处、直接 `.sendAck(` 0 处）。
 *
 * 线级读数为什么用真夹具：`RelayAuthFixtureServer` 走真 RFC 6455 升级 + 真帧编解码
 * （JDK 客户端掩码 / 夹具解掩码），比任何 mock 回调硬——「线上真有帧」这句话在这里
 * 是被**读到**的，不是被断言的。
 *
 * 红/绿对照（任务书验收 1 与 8）：把修法回退成「吞成成功」后，H1/H2/H3 必须转红
 * （读数逐轮落盘，见交付报告 §红钉/绿钉）。
 */
class DeviceMailAckHonestySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 3.minutes

  private val LogName = "nebflow.neblink.devicemail"
  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-device-mail-ack-honesty")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    DeviceMailInbox.resetForTest()

  override def afterEach(context: AfterEach): Unit =
    DeviceMailInbox.resetForTest()
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 读数设施 ────────────────────────────────────────────────────────

  /**
   * 捕获本腿（`nebflow.neblink.devicemail`）日志原文，按级别分列（同
   * `DeviceMailDedupSpec` 手法；Resource 形态 ⇒ 断言失败/超时也必 detach）。
   */
  private def withLegLog[A](io: IO[A]): IO[(A, List[String], List[String])] =
    val acquire = IO {
      val lb = LoggerFactory.getLogger(LogName).asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      lb.addAppender(appender)
      (lb, appender)
    }
    Resource
      .make(acquire) { case (lb, appender) => IO(lb.detachAppender(appender)).void }
      .use { case (_, appender) =>
        io.map { a =>
          val events = appender.list.asScala.toList
          (
            a,
            events.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage),
            events.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)
          )
        }
      }
  end withLegLog

  private final case class AuditLine(action: String, command: String, deviceId: String)

  /**
   * 审计盘面（`<dataRoot>/logs/relay-exec-audit.jsonl`，逐行现读）。
   *
   * ⚠ **R5 脱敏（须知，勿读成缺字段）**：`RelayExecAudit.redact` 把摘要里
   * **≥32 字符**的 token 字符集连续段替换成 `[redacted len=.. pre=.. sha256:..]`
   * ——所以本 spec 的断言一律落在 `action` 与**短字面**（`reason=no_live_socket` /
   * `eventId=<≤24 字符 id>`）上，绝不拿 `command` 列做「事件号是否出现」的判重读数
   * （那正是上游 §1-④ R5 的坑）。
   */
  private def auditLines(): IO[List[AuditLine]] =
    IO.blocking {
      val f = (PathUtil.dataRoot / "logs" / "relay-exec-audit.jsonl").toNIO
      if !Files.exists(f) then Nil
      else
        Files
          .readAllLines(f)
          .asScala
          .toList
          .flatMap(l =>
            parse(l).toOption.map { j =>
              AuditLine(
                j.hcursor.get[String]("action").getOrElse(""),
                j.hcursor.get[String]("command").getOrElse(""),
                j.hcursor.get[String]("deviceId").getOrElse("")
              )
            }
          )
      end if
    }

  // ── 收件腿夹具（真 sessionStore + Root 记录 + 记录型 actor/wsSend） ──

  private final case class Inbox(
    msgs: Ref[IO, List[AgentCommand]],
    frames: Ref[IO, List[Json]]
  ):
    def injections: Int = msgs.get.unsafeRunSync().size
    def alerts: Int = frames.get.unsafeRunSync().size

  private val system = NebActorSystem("device-mail-ack-honesty-spec")

  private def recordingRef(sink: Ref[IO, List[AgentCommand]]): nebflow.actor.ActorRef[AgentCommand] =
    def loop: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => sink.update(_ :+ msg).as(loop))
    system
      .spawn(loop, s"dmah-${java.util.UUID.randomUUID().toString.take(8)}")
      .unsafeRunSync()

  private def resourcesWith(
    registry: Map[String, AgentRecord],
    store: SessionStore,
    ns: Option[NeblinkService]
  ): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = store,
      projectRoot = PathUtil.dataRoot,
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

  /**
   * 接线（真 sessionStore 里一条 `agentName == "Nebula"` 的 root 会话 ⇒ 注入必成功）。
   *
   * 🔴 ack 出口 = **生产同款**（`GatewayMain:1010-1015` 的形态）：`sendAckLive` 的
   * live 读隧道 —— 本 spec 不注入 mock 回执出口，读到的就是生产判据。
   */
  private def wireInbox(ms: NeblinkService): Inbox =
    val root = PathUtil.dataRoot
    val store = SessionStore(root / "sessions", root / "tasks")
    val meta = store.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
    val msgs = Ref.unsafe[IO, List[AgentCommand]](Nil)
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    val res = resourcesWith(
      Map(
        meta.id -> AgentRecord(
          sessionId = meta.id,
          ref = recordingRef(msgs),
          kind = AgentKind.Root,
          rootSessionId = meta.id
        )
      ),
      store,
      Some(ms)
    )
    DeviceMailInbox.initialize(
      res,
      (j: Json) => frames.update(_ :+ j),
      Some(e => NeblinkRelayTunnel.sendAckLive(ms.relayTunnelOpt, e))
    )
    Inbox(msgs, frames)

  end wireInbox

  /** v2.1 收件入场信封（逐字，与 `DeviceMailSpec` / `DeviceMailDedupSpec` 同源）。 */
  private val contractPayload: Json =
    parse(
      """{"type":"agent_mail","from_device":"KAI-MBP","from_device_id":"dev-a","to_nebula":true,"text":"hello B"}"""
    )
      .fold(e => fail(s"contract literal must parse: ${e.getMessage}"), identity)

  private def envelope(eventId: String): Json =
    Json.obj(
      "type" -> "friend_event".asJson,
      "eventId" -> eventId.asJson,
      "event" -> Json.obj("payload" -> contractPayload, "type" -> "agent_mail".asJson)
    )

  // ── 真 WS 夹具 + 最小栈（与 GatewayMain 等价） ─────────────────────

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Boolean] =
    IO.monotonic.flatMap { start =>
      def loop: IO[Boolean] =
        cond.flatMap { ok =>
          if ok then IO.pure(true)
          else
            IO.monotonic.flatMap { now =>
              if now - start > timeout then IO.pure(false) else IO.sleep(50.millis) *> loop
            }
        }
      loop
    }

  /**
   * fixture + 真 `NeblinkClient` + 真 `NeblinkRelayTunnel`（同 GatewayMain 装配：
   * relay client 注册 + 隧道注册 + server 址写进 config ref）。
   */
  private def withStack[A](fix: RelayAuthFixtureServer)(
    body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel) => IO[A]
  ): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = new NeblinkClient(
          NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
          0,
          identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
        )
        _ = ms.setRelayClient(Some(client))
        _ <- client.login(Device, "qa-host", "macos", Nil)
        tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        _ <- ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
          )
        )
        fiber <- tunnel.connect().start
        out <- body(ms, client, tunnel).guarantee(fiber.cancel *> tunnel.stop())
      yield out
    }

  // ── H1（F3）：返回形态可判别 ───────────────────────────────────────

  test("H1 · F3：无 live socket ⇒ sendAck 返回 NoLiveSocket（判别值，不再是「成功」）") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(None: Option[String]))(dispatcher)
        noSocket <- tunnel.sendAck("message-1000000000000001") // 从未连接 ⇒ wsRef == None
        noTunnel <- NeblinkRelayTunnel.sendAckLive(None, "message-1000000000000001") // 装配缝空支
        _ <- IO(
          println(
            s"[reading][H1] tunnel.sendAck(no socket)=$noSocket sendAckLive(None)=$noTunnel " +
              s"(修前两者都是 IO[Unit] = 「成功」，类型上不可判别)"
          )
        )
      yield
        assertEquals(noSocket, NeblinkRelayTunnel.AckOutcome.NoLiveSocket, "无 socket 必须如实报 NoLiveSocket")
        assertEquals(noTunnel, NeblinkRelayTunnel.AckOutcome.NoLiveSocket, "隧道不在册必须如实报 NoLiveSocket")
        assert(tunnel.isAlive == false, "前提：该隧道从未连上（零 live socket）")
    }
  }

  // ── H2（F5 绿线）：隧道对象不在册 ─────────────────────────────────

  test("H2 · F5 绿线：relayTunnelOpt=None ⇒ 注入成功但记 ack-not-sent reason=no_live_socket，零 ack-sent") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher) // 从不 setRelayTunnel ⇒ relayTunnelOpt == None
        _ <- IO(assert(ms.relayTunnelOpt.isEmpty, "前提：隧道对象不在册"))
        inbox = wireInbox(ms)
        capture <- withLegLog(
          DeviceMailInbox.handle(envelope("message-honest-none"))
            *> IO.sleep(300.millis)
            *> auditLines()
        )
        (audit, warns, infos) = capture
        _ <- IO(
          println(
            s"[reading][H2] injections=${inbox.injections} alerts=${inbox.alerts} " +
              s"warns=${warns.mkString(" | ")} infos=${infos.mkString(" | ")} " +
              s"audit=${audit.map(_.action).mkString(",")}"
          )
        )
      yield
        assertEquals(inbox.injections, 1, "注入照常成功（本支不改注入面）")
        assertEquals(inbox.alerts, 0, "注入成功 ⇒ 零告警帧")
        assert(
          warns.exists(l => l.contains("ack-not-sent reason=no_live_socket")),
          s"🔴 必须如实记 ack-not-sent reason=no_live_socket，实得：$warns"
        )
        assert(
          !infos.exists(_.contains("ack sent to the server")),
          s"🔴 不得再出现 ack sent to the server（假陈述），实得：$infos"
        )
        assert(
          !infos.exists(_.contains("ack-sent")) && !warns.exists(_.contains("ack-sent")),
          "🔴 零 ack-sent 陈述"
        )
        assertEquals(
          audit.map(_.action),
          List("DeviceMail.inject.injected", "DeviceMail.inject.ack-not-sent"),
          "审计行与事实一致：注入一次 + 回执**未发出**（禁 ack-sent）"
        )
        assert(
          audit
            .exists(a => a.action == "DeviceMail.inject.ack-not-sent" && a.command.contains("reason=no_live_socket")),
          s"审计行须写明 reason=no_live_socket，实得：${audit.map(_.command)}"
        )
    }
  }

  // ── H3（F5 绿线 · 线级）：真 socket 连过又被服务端断开 ────────────

  test("H3 · F5 绿线（真 socket 生命周期）：socket 不在册 ⇒ ack-not-sent + 线上零 ack 帧") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.pongReplies = true
      fix.recordClientFrames = true
      withStack(fix) { (ms, _, tunnel) =>
        for
          live <- waitUntil(20.seconds)(IO(tunnel.isAlive))
          _ <- IO(assert(live, s"基线连接失败：${fix.relayAttempts}"))
          _ <- IO(fix.close()) // 服务端断开：socket 撕掉且不再接受连接
          dropped <- waitUntil(20.seconds)(IO(!tunnel.isAlive))
          _ <- IO(assert(dropped, "服务端断开后隧道必须落到无 live socket"))
          inbox = wireInbox(ms)
          capture <- withLegLog(
            DeviceMailInbox.handle(envelope("message-honest-drop"))
              *> IO.sleep(400.millis)
              *> auditLines()
          )
          (audit, warns, infos) = capture
          ackFrames = fix.ackFrameCount("message-honest-drop")
          allFrames = fix.clientFrames
          _ <- IO(
            println(
              s"[reading][H3] tunnelAliveAtAck=${tunnel.isAlive} injections=${inbox.injections} " +
                s"wireAckFrames=$ackFrames wireFrames=${allFrames.size} " +
                s"warns=${warns.mkString(" | ")} audit=${audit.map(_.action).mkString(",")}"
            )
          )
        yield
          assertEquals(inbox.injections, 1, "注入照常成功（注入面零回归）")
          assertEquals(ackFrames, 0, "🔴 线上必须**零** ack 帧（socket 不在册 ⇒ 帧压根没上线）")
          assert(
            warns.exists(l => l.contains("ack-not-sent reason=no_live_socket")),
            s"🔴 必须如实记 ack-not-sent reason=no_live_socket，实得：$warns"
          )
          assert(
            !infos.exists(_.contains("ack sent to the server")),
            s"🔴 不得再出现 ack sent to the server（修前形态正是「线上零帧却报已回执」），实得：$infos"
          )
          assertEquals(
            audit.map(_.action),
            List("DeviceMail.inject.injected", "DeviceMail.inject.ack-not-sent"),
            "审计行与线级事实一致"
          )
      }
    }
  }

  // ── H4（真发出路径零回归 + 线上真有帧） ──────────────────────────

  test("H4 · 真发出路径零回归：socket 在册 ⇒ ack-sent 照旧，且线上真有冻结帧形的 ack 帧") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.pongReplies = true
      fix.recordClientFrames = true
      withStack(fix) { (ms, _, tunnel) =>
        val id = "message-honest-live"
        for
          live <- waitUntil(20.seconds)(IO(tunnel.isAlive))
          _ <- IO(assert(live, s"基线连接失败：${fix.relayAttempts}"))
          // 同一实现点的直读：socket 在册 ⇒ Sent（可判别值的阳性支）
          outcome <- NeblinkRelayTunnel.sendAckLive(ms.relayTunnelOpt, "message-honest-direct")
          inbox = wireInbox(ms)
          capture <- withLegLog(
            DeviceMailInbox.handle(envelope(id))
              *> IO.sleep(500.millis)
              *> auditLines()
          )
          (audit, warns, infos) = capture
          ackFrames = fix.clientFrames.filter(f => f.contains("\"ack\"") && f.contains(id))
          _ <- IO(
            println(
              s"[reading][H4] outcome=$outcome injections=${inbox.injections} alerts=${inbox.alerts} " +
                s"wireAckFrames=${ackFrames.mkString(" | ")} warns=${warns.mkString(" | ")} " +
                s"audit=${audit.map(_.action).mkString(",")}"
            )
          )
        yield
          assertEquals(outcome, NeblinkRelayTunnel.AckOutcome.Sent, "socket 在册 ⇒ 结局必须可判别为 Sent")
          assertEquals(inbox.injections, 1, "注入照常")
          assertEquals(inbox.alerts, 0, "成功路径零告警帧")
          assertEquals(ackFrames.size, 1, s"🔴 线上必须有**恰好一帧** ack（实得 $ackFrames）")
          assertEquals(
            ackFrames.head,
            s"""{"type":"ack","eventId":"$id"}""",
            "线上帧形逐字节 = 冻结契约帧形（唯一编码点 `NeblinkRelayTunnel.ackFrame`）"
          )
          assert(
            infos.exists(l => l.contains("ack sent to the server") && l.contains(id)),
            s"真发出时 INFO 照旧（零回归），实得：$infos"
          )
          assert(warns.isEmpty, s"真发出路径零 WARN，实得：$warns")
          assertEquals(
            audit.map(_.action),
            List("DeviceMail.inject.injected", "DeviceMail.inject.ack-sent"),
            "审计行照旧 = 注入一次 + 回执**已发出**"
          )
        end for
      }
    }
  }

  // ── H5（F5 第三态）：SendFailed 不得被读成已回执 ──────────────────

  test("H5 · F5：SendFailed ⇒ ack-send-failed（不是 ack-sent），零假陈述") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        root = PathUtil.dataRoot
        store = SessionStore(root / "sessions", root / "tasks")
        meta = store.createSession("Nebula", agentName = Some("Nebula")).unsafeRunSync()
        msgs = Ref.unsafe[IO, List[AgentCommand]](Nil)
        frames = Ref.unsafe[IO, List[Json]](Nil)
        res = resourcesWith(
          Map(
            meta.id -> AgentRecord(
              sessionId = meta.id,
              ref = recordingRef(msgs),
              kind = AgentKind.Root,
              rootSessionId = meta.id
            )
          ),
          store,
          Some(ms)
        )
        _ = DeviceMailInbox.initialize(
          res,
          (j: Json) => frames.update(_ :+ j),
          Some(_ => IO.pure(NeblinkRelayTunnel.AckOutcome.SendFailed("boom")))
        )
        capture <- withLegLog(
          DeviceMailInbox.handle(envelope("message-honest-fail"))
            *> IO.sleep(300.millis)
            *> auditLines()
        )
        (audit, warns, infos) = capture
        _ <- IO(
          println(
            s"[reading][H5] injections=${msgs.get.unsafeRunSync().size} alerts=${frames.get.unsafeRunSync().size} " +
              s"warns=${warns.mkString(" | ")} audit=${audit.map(_.action).mkString(",")}"
          )
        )
      yield
        assertEquals(msgs.get.unsafeRunSync().size, 1, "注入照常")
        assert(warns.exists(_.contains("ack send FAILED")), s"写失败须如实记 WARN，实得：$warns")
        assert(!infos.exists(_.contains("ack sent to the server")), s"🔴 不得出现 ack-sent 陈述，实得：$infos")
        assertEquals(
          audit.map(_.action),
          List("DeviceMail.inject.injected", "DeviceMail.inject.ack-send-failed"),
          "写失败支的审计值 = ack-send-failed（既非 ack-sent、也非 ack-not-sent）"
        )
    }
  }

  // ── H6（F4 单一实现点）：源码级读数 ───────────────────────────────

  test("H6 · F4 单一实现点：两处装配缝只调 sendAckLive（无第二份同族判断）") {
    IO.blocking {
      def count(hay: String, needle: String): Int =
        var n = 0
        var i = hay.indexOf(needle)
        while i >= 0 do
          n += 1
          i = hay.indexOf(needle, i + needle.length)
        n
      val main = os.pwd / "src" / "main" / "scala" / "nebflow"
      val gateway = os.read(main / "gateway" / "GatewayMain.scala")
      val tunnel = os.read(main / "neblink" / "NeblinkRelayTunnel.scala")
      val gatewaySites = count(gateway, "NeblinkRelayTunnel.sendAckLive(")
      val directSendAck = count(gateway, ".sendAck(")
      val helperDefs = count(tunnel, "def sendAckLive(")
      val swallow = count(gateway, "case None    => IO.unit")
      println(
        s"[reading][H6] gateway.sendAckLive=$gatewaySites gateway.directSendAck=$directSendAck " +
          s"tunnel.sendAckLiveDefs=$helperDefs gateway.swallowCase=$swallow"
      )
      assertEquals(gatewaySites, 2, "两个装配缝（好友腿 + 设备邮件腿）都必须走同一实现点")
      assertEquals(directSendAck, 0, "装配缝不得再各自调用 `.sendAck(...)`（那正是同族判断写两遍的形态）")
      assertEquals(helperDefs, 1, "`sendAckLive` 定义只此一处")
    }
  }

end DeviceMailAckHonestySpec
