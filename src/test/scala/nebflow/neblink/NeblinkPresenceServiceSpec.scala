package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.comcast.ip4s.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import java.net.{InetAddress, ServerSocket}
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration.*

/**
 * F-1 / F-4 批（presdial-impl 2026-09-19）—— presence **拨号端口**缺陷的双向钉。
 *
 * 上游唯一技术依据 = `.nebflow/reports/20260919_000720_kaiflap-diag.md`
 * §1（D-1）/ §2.3 ③（修复方向 + 拨号链逐跳）/ §2.5 A 表（F-1 + F-4 的改动形状与拟加测试名）。
 *
 * 缺陷形态（现读代码，改前）：`dialCandidates` → `extractHost`（**只取 host、丢端口**）
 * → `openConnection` → `buildWsUri(host, id)` 用**本进程 `serverPort`** 拼 URL
 * ⇒ **任何非 8080 端口的实例永远拨不到对端**（8097 实例实际拨的是它自己的 8097），
 * 且失败行把**候选串**当成「拨过的端口」显示 ⇒ 系统性误导读数（§1 D-1 / F-4）。
 *
 * 验收（逐条对应任务书 §5）：
 *  ① `dial honours the candidate endpoint's port`：候选自带端口 ⇒ **拨的就是它**
 *     （旧形下目标 = 拨号方自己的 serverPort ⇒ 必红在断言上）；
 *  ② `falls back to the local port when the candidate carries none`：**控制项** ——
 *     缺端口时才回落 `serverPort`（两种形态下均绿）；
 *  ③ `dial failure line prints the resolved dial target`：失败/成功行标出**实拨目标**
 *     （F-4；旧形无 `dial target` 字样 ⇒ 红）；
 *  ④ e2e：**隔离实例对（loopback + 非 8080 端口 + 自有 home）互探可达** ——
 *     两个都跑真 presence 路由（真 101 握手），双向各拨一次；
 *  ⑤ 回归：`endpoints = Nil` 的**入站 peer**（回退面）照旧拨其 `address` 的端口。
 *
 * 🔴 隔离纪律（本批特定）：全程 **本机回环**（`127.0.0.1`）+ **非 8080 端口** +
 * **自有 home**（每实例独立 dataRoot ⇒ 独立 deviceId，正是 §2.2 W1/E-2 的机制）+
 * **不配任何凭据、不建 NebLink 客户端、不入任何名册**（`create` 只建本地服务，
 * `setClient` 从不调用 ⇒ 零出网）；收尾由 munit `afterEach` 清 temp home。
 *
 * 已知边角（读数，非缺陷）：入站 presence 的准入 = `isTrustedPeer(remoteIp)` 或
 * 「该 deviceId 在本机 peer 表里」（`RestApiRoutes:2432-2441` + `:3816-3822`，后者还要求
 * remoteIp 属私网/环回）。本 spec 用**名册命中**这条设计内路径（线上双方本来就在彼此名册里）；
 * 纯 IP 信任面留痕见首轮读数（`localhost/127.0.0.1` 形态不匹配 ⇒ 403），已登记为**未覆盖面**。
 */
class NeblinkPresenceServiceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val TestToken = "test-token-presdial"

  /**
   * 拨号预算统一放宽到 tailnet 档（3000ms）。WHY 不是默认档：本 spec 的拨号全部打在
   * **真 presence 路由**上，JVM 内首次建连要把 Ember/http4s WS 栈的类加载放进拨号窗口
   * （首轮实测 1500ms 档下 warmup 与随后一次实测拨号都 timeout，见
   * `logs/06_test_reminders_presence3.log`）；1500ms 会把这个与「端口是否正确」无关的
   * 冷启动噪声混进读数。放宽档不改变任何断言语义（旧形在放档下仍是「打错端口」）。
   * 同款取舍先例：`NeblinkPresenceDialBudgetSpec` 的 warmup 注释。
   */
  private val TestBudget: DialBudget = DialBudget(classify = _ => DialAddressClass.Tailnet)

  private var tmpRoot: Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpRoot = Files.createTempDirectory("nb-presdial-spec")
    PathUtil.setDataRoot(os.Path(tmpRoot, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    // 清理 best-effort：后台 fiber（sync loop / 心跳 / 宽限计时器）可能仍在写
    // ⇒ 清理异常绝不得顶掉真正的断言（同 DevicesDeltaBaselineSpec 的取舍）。
    var attempts = 0
    var removed = false
    while !removed && attempts < 3 do
      attempts += 1
      try
        os.remove.all(os.Path(tmpRoot, os.pwd))
        removed = true
      catch case _: Exception => Thread.sleep(200)
    super.afterEach(context)

  // ===== harness =====

  /** 刚释放的本机端口 ⇒ 既可用作监听端口（随后真 bind），也可用作「无人监听」档。 */
  private def freePort(): Int =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  /**
   * 一个**隔离实例**：自有 home（独立 dataRoot ⇒ 独立 deviceId）+ 自有 serverPort。
   * 🔴 不配 token、不建客户端 ⇒ 零出网、零名册（任务书 §3 的隔离纪律）。
   */
  private def createInstance(name: String, port: Int, d: Dispatcher[IO]): IO[NeblinkService] =
    IO.blocking {
      val home = tmpRoot.resolve(s"home-$name")
      Files.createDirectories(home)
      PathUtil.setDataRoot(os.Path(home, os.pwd))
    } *> NeblinkService.create(port, d)

  /** 另一实例创建完 ⇒ 把 dataRoot 拨回共享 scratch（路由夹具只读它）。 */
  private def useSharedScratch: IO[Unit] =
    IO.blocking {
      Files.createDirectories(tmpRoot.resolve("shared"))
      PathUtil.setDataRoot(os.Path(tmpRoot.resolve("shared"), os.pwd))
    }

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
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpRoot.resolve("shared"), os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  /**
   * 真网关形态的**服务端**：`Router("/api" -> rest <+> presenceWsRoutes)`（同 GatewayMain
   * 的挂载形），只绑 `127.0.0.1:<port>`（loopback 纪律）。
   */
  private def serve(ms: NeblinkService, ps: NeblinkPresenceService, port: Int): Resource[IO, Unit] =
    Resource.eval(IO(ms.updateTrustedIps(Set("127.0.0.1", "::1", InetAddress.getByName("127.0.0.1").toString)))) *>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(Port.fromInt(port).get)
        .withShutdownTimeout(2.seconds)
        .withHttpWebSocketApp { wsb =>
          val routes = new RestApiRoutes(
            token = TestToken,
            configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
              NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
            ),
            sharedResources = mkResources,
            sessionStore = null,
            wsRoutes = null,
            neblinkService = Some(ms),
            neblinkDiscovery = Some(new NeblinkDiscovery(ms, port, ps, None))
          )
          val app: HttpApp[IO] =
            Router("/api" -> (routes.routes <+> routes.presenceWsRoutes(wsb))).orNotFound
          app
        }
        .build
        .void

  /**
   * 「拨号方已在名册里」的生产同形：接方先认识拨号方的 deviceId（准入兜底路径）。
   * 占位地址刻意用 `http://127.0.0.1:1` —— 入站 handler 会用查询串里的**对端自称端口**
   * 覆盖它，于是「入站记下了对端通告的端口」这条断言才有判别力（同值会被写成恒真）。
   */
  private def seedPeer(receiver: NeblinkService, dialerId: DeviceIdentity): IO[Unit] =
    receiver.upsertPeer(
      PeerInfo(
        deviceId = dialerId.deviceId,
        deviceName = dialerId.deviceName,
        platform = dialerId.platform,
        address = "http://127.0.0.1:1"
      )
    )

  private def mkPresence(ms: NeblinkService, port: Int, d: Dispatcher[IO]): NeblinkPresenceService =
    val ps = new NeblinkPresenceService(ms, port, TestBudget)(d)
    ms.setPresenceService(ps)
    ps

  private def peer(id: String, candidates: List[String]): PeerInfo =
    PeerInfo(
      deviceId = id,
      deviceName = s"Dev-$id",
      platform = "macos",
      address = candidates.head,
      endpoints = candidates
    )

  private def warmup(ps: NeblinkPresenceService, endpoints: List[String]): IO[Unit] =
    ps.connect(peer("warmup", endpoints)).attempt.void

  /** logback 采集器（同 `NeblinkRelayTunnelAuthSpec` 的先例）。 */
  private final class ChannelAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new ConcurrentLinkedQueue[String]()

    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

  private def captureChannel[A](loggerName: String)(body: ConcurrentLinkedQueue[String] => IO[A]): IO[A] =
    IO {
      org.slf4j.LoggerFactory.getLogger(loggerName) match
        case lb: ch.qos.logback.classic.Logger =>
          val appender = new ChannelAppender
          appender.setContext(lb.getLoggerContext)
          appender.start()
          lb.addAppender(appender)
          (lb, appender)
        case other => fail(s"expected a logback logger for $loggerName, got $other")
    }.flatMap { (lb, appender) =>
      body(appender.lines).guarantee(IO(lb.detachAppender(appender)))
    }

  private def logLines(lines: ConcurrentLinkedQueue[String]): List[String] =
    lines.toArray.toList.map(_.toString)

  /** 等日志行出现（日志走 IO ⇒ 不赌「connect 返回时已落行」）。 */
  private def awaitLog(lines: ConcurrentLinkedQueue[String], pred: List[String] => Boolean): IO[List[String]] =
    IO.defer {
      val deadline = System.currentTimeMillis() + 5_000L
      def loop: IO[List[String]] =
        IO.defer {
          val seen = logLines(lines)
          if pred(seen) then IO.pure(seen)
          else if System.currentTimeMillis() > deadline then
            IO.raiseError(new AssertionError(s"日志未在 5000ms 内满足判据；现有行：\n${seen.mkString("\n")}"))
          else IO.sleep(25.millis).flatMap(_ => loop)
        }
      loop
    }

  // ===== ① 候选端口优先 =====

  test("dial honours the candidate endpoint's port") {
    val dialerPort = freePort() // 拨号方**自己的**网关端口：本用例不监听它 ⇒ 旧形必打空
    val peerPort = freePort()
    assert(
      dialerPort != peerPort && peerPort != 8080 && dialerPort != 8080,
      s"端口必须互异且非 8080: dialer=$dialerPort peer=$peerPort"
    )
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msB <- createInstance("b1", peerPort, d)
          psB = mkPresence(msB, peerPort, d)
          msA <- createInstance("a1", dialerPort, d)
          psA = mkPresence(msA, dialerPort, d)
          _ <- useSharedScratch
          idA <- msA.identity
          // 接方 B 先认识拨号方 A（准入兜底路径；线上双方都在彼此名册里）
          _ <- seedPeer(msB, idA)
          out <- serve(msB, psB, peerPort).use { _ =>
            val ep = s"http://127.0.0.1:$peerPort"
            val p = peer("peer-b1", List(ep))
            for
              _ <- msA.upsertPeer(p)
              _ <- warmup(psA, List(ep))
              _ <- psA.connect(p)
              connected <- IO(psA.isConnected("peer-b1"))
              st <- IO(psA.dialStatus("peer-b1"))
              chosen <- IO(psA.chosenEndpoint("peer-b1"))
              round <- IO(psA.lastDialRound("peer-b1"))
              peersAtB <- msB.peers
            yield (idA.deviceId, connected, st, chosen, round, peersAtB)
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield (dialerPort, peerPort, out)
      }
      .map { case (dialerPort, peerPort, (idA, connected, st, chosen, round, peersAtB)) =>
        val ep = s"http://127.0.0.1:$peerPort"
        assertEquals(
          st.map(_.error),
          Some(None),
          s"必须拨通**候选自带的端口** $peerPort（改前打的是拨号方自己的 $dialerPort ⇒ 必失败）: $st"
        )
        assertEquals(chosen, Some(ep), "择优结果 = 候选本身")
        assert(connected, "连接已建立（真 101 握手）")
        assertEquals(round.map(_.attempted), Some(List(ep)), "本轮拨的候选")
        // 🔴 对端入站看到的**通告端口**仍是拨号方自己的 serverPort（正确行为，零改动）——
        // 与「拨号目标端口 = 候选端口」是两件事，正是改前被混为一谈之处（§2.3 ①）。
        val atB = peersAtB
          .find(_.deviceId == idA)
          .getOrElse(fail(s"B 的 peer 表必须记录入站的 A（deviceId=$idA）: $peersAtB"))
        assertEquals(
          atB.address,
          s"http://127.0.0.1:$dialerPort",
          "入站 peer 的地址 = A **通告的它自己的**监听端口（零改动面）"
        )
      }
  }

  // ===== ② 缺端口回落（控制项） =====

  test("falls back to the local port when the candidate carries none") {
    val peerPort = freePort()
    assert(peerPort != 8080, s"必须非 8080: $peerPort")
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msB <- createInstance("b2", peerPort, d)
          psB = mkPresence(msB, peerPort, d)
          // 拨号方的 serverPort 与 B 的监听端口相同 ⇒ 缺端口候选回落它即通
          msA <- createInstance("a2", peerPort, d)
          psA = mkPresence(msA, peerPort, d)
          _ <- useSharedScratch
          idA <- msA.identity
          _ <- seedPeer(msB, idA)
          out <- serve(msB, psB, peerPort).use { _ =>
            val noPort = "http://127.0.0.1" // 🔴 候选**不带端口**
            val p = peer("peer-b2", List(noPort))
            for
              _ <- msA.upsertPeer(p)
              _ <- warmup(psA, List(noPort))
              _ <- psA.connect(p)
              connected <- IO(psA.isConnected("peer-b2"))
              st <- IO(psA.dialStatus("peer-b2"))
              round <- IO(psA.lastDialRound("peer-b2"))
            yield (connected, st, round)
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield out
      }
      .map { case (connected, st, round) =>
        assertEquals(st.map(_.error), Some(None), s"缺端口候选必须回落 serverPort（= 对端监听端口）: $st")
        assert(connected, "连接已建立（控制项：改前改后同绿）")
        assertEquals(round.map(_.attempted), Some(List("http://127.0.0.1")), "候选串本身不变")
      }
  }

  // ===== ③ F-4 日志：失败/成功行标出**实拨目标** =====

  test("dial failure line prints the resolved dial target") {
    val listenPort = freePort() // A：真监听
    val dialerPort = freePort() // B：**无监听**
    assert(
      listenPort != dialerPort && listenPort != 8080 && dialerPort != 8080,
      s"端口必须互异且非 8080: listen=$listenPort dialer=$dialerPort"
    )
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msA <- createInstance("a3", listenPort, d)
          psA = mkPresence(msA, listenPort, d)
          msB <- createInstance("b3", dialerPort, d)
          psB = mkPresence(msB, dialerPort, d)
          _ <- useSharedScratch
          idB <- msB.identity
          _ <- seedPeer(msA, idB)
          out <- serve(msA, psA, listenPort).use { _ =>
            val noPort = "http://127.0.0.1" // 目标回落 B 自己的 dialerPort（无监听）⇒ 必失败
            val withPort = s"http://127.0.0.1:$listenPort" // 目标 = 候选端口 ⇒ 必成功
            val dead = peer("peer-dead", List(noPort))
            val live = peer("peer-live", List(withPort))
            captureChannel("nebflow.neblink.presence") { lines =>
              for
                _ <- msB.upsertPeer(dead)
                _ <- warmup(psB, List(withPort))
                _ <- psB.connect(dead)
                _ <- msB.upsertPeer(live)
                _ <- psB.connect(live)
                seen <- awaitLog(
                  lines,
                  ls =>
                    ls.exists(l => l.contains("peer-dead") && l.contains("dial target")) &&
                      ls.exists(l => l.contains("peer-live") && l.contains("dial target"))
                )
                stDead <- IO(psB.dialStatus("peer-dead"))
                stLive <- IO(psB.dialStatus("peer-live"))
              yield (seen, stDead, stLive)
            }
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield (dialerPort, listenPort, out)
      }
      .map { case (dialerPort, listenPort, (lines, stDead, stLive)) =>
        val failLine = lines
          .find(l => l.contains("peer-dead") && l.contains("dial target"))
          .getOrElse(fail(s"缺失败行（含 dial target）:\n${lines.mkString("\n")}"))
        val okLine = lines
          .find(l => l.contains("peer-live") && l.contains("dial target"))
          .getOrElse(fail(s"缺成功行（含 dial target）:\n${lines.mkString("\n")}"))
        // 失败行：候选串无端口，实拨目标 = 拨号方自己的 serverPort（回落面）
        assert(failLine.contains("via http://127.0.0.1"), s"失败行必须仍带候选串（+ 实拨目标并列）: $failLine")
        assert(
          failLine.contains(s"dial target 127.0.0.1:$dialerPort"),
          s"失败行必须标出**实拨目标** 127.0.0.1:$dialerPort（改前只有候选串 ⇒ 歧义）: $failLine"
        )
        // 成功行：实拨目标 = 候选自带端口
        assert(
          okLine.contains(s"dial target 127.0.0.1:$listenPort"),
          s"成功行必须标出实拨目标 127.0.0.1:$listenPort: $okLine"
        )
        assert(stDead.flatMap(_.error).isDefined, s"peer-dead 必须失败: $stDead")
        assertEquals(stLive.map(_.error), Some(None), s"peer-live 必须成功: $stLive")
      }
  }

  // ===== ④ e2e：隔离实例对互探可达 =====

  test("e2e: an isolated instance pair (loopback, non-8080) reaches each other's presence route") {
    val portA = freePort()
    val portB = freePort()
    assert(
      portA != portB && portA != 8080 && portB != 8080,
      s"两侧端口必须互异且非 8080: A=$portA B=$portB"
    )
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msA <- createInstance("ea", portA, d)
          psA = mkPresence(msA, portA, d)
          msB <- createInstance("eb", portB, d)
          psB = mkPresence(msB, portB, d)
          _ <- useSharedScratch
          idA <- msA.identity
          idB <- msB.identity
          // 互探的前提：两侧名册里都有对方（线上同形）
          _ <- seedPeer(msA, idB)
          _ <- seedPeer(msB, idA)
          out <- serve(msA, psA, portA).use { _ =>
            serve(msB, psB, portB).use { _ =>
              val epA = s"http://127.0.0.1:$portA"
              val epB = s"http://127.0.0.1:$portB"
              val peerB = peer(idB.deviceId, List(epB)).copy(deviceName = "ISOLATED-B")
              val peerA = peer(idA.deviceId, List(epA)).copy(deviceName = "ISOLATED-A")
              for
                // 方向 1：A → B（候选 = B 自己的端口）
                _ <- msA.upsertPeer(peerB)
                _ <- warmup(psA, List(epB))
                _ <- psA.connect(peerB)
                aToB <- IO(psA.isConnected(idB.deviceId))
                stA <- IO(psA.dialStatus(idB.deviceId))
                roundA <- IO(psA.lastDialRound(idB.deviceId))
                seenAtB <- msB.peers
                // 方向 2：B → A（候选 = A 自己的端口）
                _ <- msB.upsertPeer(peerA)
                _ <- psB.connect(peerA)
                bToA <- IO(psB.isConnected(idA.deviceId))
                stB <- IO(psB.dialStatus(idA.deviceId))
                roundB <- IO(psB.lastDialRound(idA.deviceId))
                seenAtA <- msA.peers
              yield (aToB, bToA, stA, stB, roundA, roundB, seenAtB, seenAtA, epA, epB)
              end for
            }
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield out
      }
      .map { case (aToB, bToA, stA, stB, roundA, roundB, seenAtB, seenAtA, epA, epB) =>
        assert(aToB, s"隔离实例 A 必须拨通 B 的 presence 路由（候选 $epB）")
        assert(bToA, s"隔离实例 B 必须拨通 A 的 presence 路由（候选 $epA）")
        assertEquals(stA.map(_.error), Some(None), s"A→B 无错误: $stA")
        assertEquals(stB.map(_.error), Some(None), s"B→A 无错误: $stB")
        assertEquals(roundA.map(_.attempted), Some(List(epB)), "A 拨的候选 = B 的端口")
        assertEquals(roundB.map(_.attempted), Some(List(epA)), "B 拨的候选 = A 的端口")
        // 两侧入站都记下了对端**通告的自己的端口**（互探双向闭环；占位地址必被覆盖）
        assert(seenAtB.exists(p => p.address == epA), s"B 的入站表必须含 A 通告的 $epA: $seenAtB")
        assert(seenAtA.exists(p => p.address == epB), s"A 的入站表必须含 B 通告的 $epB: $seenAtA")
      }
  }

  // ===== ⑤ 回归：入站 peer（endpoints = Nil）回退面 =====

  test("regression: an inbound peer (endpoints = Nil) still dials the port in its address") {
    val listenPort = freePort()
    val dialerPort = freePort()
    assert(
      listenPort != dialerPort && listenPort != 8080 && dialerPort != 8080,
      s"端口必须互异且非 8080: listen=$listenPort dialer=$dialerPort"
    )
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msB <- createInstance("b5", listenPort, d)
          psB = mkPresence(msB, listenPort, d)
          // 拨号方自己的端口**无监听** ⇒ 接通只能是 address 里那个端口起了作用
          msA <- createInstance("a5", dialerPort, d)
          psA = mkPresence(msA, dialerPort, d)
          _ <- useSharedScratch
          idA <- msA.identity
          _ <- seedPeer(msB, idA)
          out <- serve(msB, psB, listenPort).use { _ =>
            val ep = s"http://127.0.0.1:$listenPort"
            // 入站 presence 路由建出来的 peer：endpoints = Nil，端口在 address 里（W2 形态）
            val inbound = PeerInfo(
              deviceId = "inbound-1",
              deviceName = "INBOUND",
              platform = "macos",
              address = ep,
              endpoints = Nil
            )
            for
              _ <- msA.upsertPeer(inbound)
              _ <- warmup(psA, List(ep))
              _ <- psA.connect(inbound)
              connected <- IO(psA.isConnected("inbound-1"))
              st <- IO(psA.dialStatus("inbound-1"))
              round <- IO(psA.lastDialRound("inbound-1"))
            yield (connected, st, round)
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield out
      }
      .map { case (connected, st, round) =>
        val ep = s"http://127.0.0.1:$listenPort"
        assertEquals(st.map(_.error), Some(None), s"endpoints=Nil 的入站 peer 必须照旧接通: $st")
        assert(connected, "入站 peer 回退面（address 自带端口）零回归")
        assertEquals(round.map(_.attempted), Some(List(ep)), "拨的就是 address 里的端口")
      }
  }

end NeblinkPresenceServiceSpec
