package nebflow.neblink

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.comcast.ip4s.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import java.io.InputStream
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * hblife 批（2026-09-19）· presence **心跳线程生命周期**缺陷修复的行为钉 + 读数面。
 *
 * 上游唯一技术依据 = `.nebflow/reports/20260919_devflip-forensic.md`
 * §0（第 4 面 99.21%）/ §3.4（**过阈反证**：心跳超时到达间隔 p50 = 5.000 s = 12.0 次/分，
 * 而单连接自循环理论周期 ≈16.2 s = 3.70 次/分 ⇒ 现场有并发陈旧心跳线程打同一 peer）/
 * §8 行 ⑤（**线程级证据当时未取到** ⇒ 本 spec 即为补该读数）。
 *
 * 缺陷形态（改前，`NeblinkPresenceService`）：每连接一条 `presence-hb-<name>` 线程，
 * 但 `connections.put` 是**覆盖式**且不退役被顶替的 conn ⇒ 出现「宿 conn 已不在册」的
 * **游离心跳线程**；心跳拍按 deviceId 反查决定生死、僵尸分支条件化清理 + **无条件**
 * `removePeer + startReconnect` ⇒ 陈旧线程每次醒来都制造一次「掉线 + 重连」，
 * 即现场读到的 5.000 s 自激节拍（陈旧线程的 `lastPong` 永不被刷新 ⇒ **每一拍**都判逾期）。
 *
 * 本 spec 钉的四条判据（逐条对应任务书 §2/§3）：
 *  ① **无堆积**：任一颗刻 `count(presence-hb-*) ≤ count(connections)`，稳态相等（=1:1）；
 *     🔴 用**并发顶替**形态触发（两条真 conn 同时存在）——这是改前唯一能产生游离心跳
 *     线程的路径，也是本 spec 对「修复载重」的判别面。
 *  ② **5 s 自激消失**：对端**在线** ⇒ 窗口内 `Heartbeat timeout ... auto-reconnecting` = **0 行**；
 *     **聋对端**（握手成功但不回 pong）⇒ 节拍回到**单连接理论带**（拍间隔 ×3 + 握手），
 *     🔴 不得出现 5.000 s 整间隔 / 12 次每分。
 *  ③ **迟到事件不得重复处置**：每轮真断连**恰一次**重连序列（1 条掉线行 + 1 条 `Reconnected`），
 *     轮间线程/连接计数不增长。
 *  ④ **真掉线仍须摘除（红向）**：TCP 真断（EOF，**无** close 帧）⇒ 仍 `removePeer` + 重连。
 *
 * 🔴 隔离纪律（任务书 §3/§4/§5）：全程**本机回环** + **非 8080 端口**（`freePort()` 取空闲端口）
 * + **每实例自有 home**（独立 dataRoot ⇒ 独立 deviceId）+ **零生产凭据**（token 用测试常量、
 * 不建 NebLink 客户端、不配 provider ⇒ 零出网）。**真** presence WS 连接（真 101 握手 + 真
 * presence 路由 + 真 ping/pong 帧），无 WS mock。
 *
 * 🔴 线程读数只看**本 spec 的 forked test JVM**（进程内 `Thread.getAllStackTraces`）——
 * 结构上不可能 attach 宿主（禁 jcmd/jstack 打宿主），也不可能把同机他实例的线程计入
 * （它们不在本 JVM 内）；每轮另落一条**实例清册**读数（`pgrep -fl 'nebflow[.]Main'`，
 * 只读、零信号、零 kill）作为混淆因子排除。
 *
 * 读数落盘：`NB_HB_EVIDENCE_ROOT/<NB_HB_RUN_ID>/` 下的 `*.jsonl`（默认 `/tmp/hbflife-impl/evidence/adhoc`），
 * 每 1 s 一行样本 ⇒ 同时充当长跑心跳（🔴 禁「零输出窗口」）。
 */
class NeblinkPresenceHeartbeatLifecycleSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 900.seconds

  private val TestToken = "test-token-hblife"

  /** 观测窗（秒）。默认 180 = 任务书 §3 第 2 项要求的「≥3 分钟窗口」。 */
  private val WatchSec: Int = sys.env.get("NB_HB_WATCH_SEC").flatMap(_.toIntOption).getOrElse(180)

  /** 聋对端窗口（秒）。 */
  private val DeafSec: Int = sys.env.get("NB_HB_DEAF_SEC").flatMap(_.toIntOption).getOrElse(180)

  /** 真断连轮数（任务书 §3 第 1 项要求 ≥5 轮）。 */
  private val DropRounds: Int = sys.env.get("NB_HB_ROUNDS").flatMap(_.toIntOption).getOrElse(6)

  private val PeerNameOnline = "HB-ONLINE-PEER"
  private val PeerNameDeaf = "HB-DEAF-PEER"
  private val PeerNameRounds = "HB-ROUND-PEER"

  /** 本机环回重连的实测握手量级（ms）——喂给生产源码的同一份理论公式，不抄读数。 */
  private val LoopbackHandshakeMs = 50L

  /**
   * 拨号预算统一放宽到 tailnet 档（3000ms）。WHY：本 spec 的拨号全部打在**真 presence 路由**
   * 上，JVM 内首次建连要把 http4s/Ember WS 栈的类加载放进拨号窗口（同
   * `NeblinkPresenceServiceSpec` 的取舍）；本 spec 还刻意把**首个候选设为黑洞**（拨号窗口
   * = 该档预算）让「两条 conn 同时存在」的触发窗更稳。断言与档位无关。
   */
  private val TestBudget: DialBudget = DialBudget(classify = _ => DialAddressClass.Tailnet)

  private var tmpRoot: Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpRoot = Files.createTempDirectory("nb-hblife-spec")
    PathUtil.setDataRoot(os.Path(tmpRoot, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    // best-effort（后台心跳/重连腿可能仍在写）：清理异常绝不得顶掉真正的断言
    var attempts = 0
    var removed = false
    while !removed && attempts < 3 do
      attempts += 1
      try
        os.remove.all(os.Path(tmpRoot, os.pwd))
        removed = true
      catch case _: Exception => Thread.sleep(200)
    super.afterEach(context)

  // ===== 读数原语 =====

  private final case class LogRec(ts: Long, level: String, msg: String):
    def isTimeout: Boolean = msg.contains("Heartbeat timeout") && msg.contains("auto-reconnecting")
    def isDisconnected: Boolean = msg.contains("Presence disconnected") && msg.contains("auto-reconnecting")
    def isReconnected: Boolean = msg.contains("Reconnected to ")
    def isStaleBeat: Boolean = msg.contains("Stale heartbeat beat")

  private final case class Tape(timeout: Int, disc: Int, recon: Int, stale: Int, dialFail: Int)

  /** logback 采集器（同 `NeblinkRelayTunnelAuthSpec` 先例）；**带时间戳**（间隔读数需要）。 */
  private final class TapeAppender extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val recs = new ConcurrentLinkedQueue[LogRec]()

    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      recs.add(LogRec(event.getTimeStamp, event.getLevel.toString, event.getFormattedMessage))

  /**
   * 采集 `nebflow.neblink.presence`：并把该 logger 提到 DEBUG（证据里要看到「Stale heartbeat
   * beat」这类**拍级**读数），退出时恢复原级别并摘掉 appender。
   */
  private def capturePresence[A](body: ConcurrentLinkedQueue[LogRec] => IO[A]): IO[A] =
    IO {
      org.slf4j.LoggerFactory.getLogger("nebflow.neblink.presence") match
        case lb: ch.qos.logback.classic.Logger =>
          val appender = new TapeAppender
          appender.setContext(lb.getLoggerContext)
          appender.start()
          lb.addAppender(appender)
          val saved: Option[ch.qos.logback.classic.Level] = Option(lb.getLevel)
          lb.setLevel(ch.qos.logback.classic.Level.DEBUG)
          (lb, appender, saved)
        case other => fail(s"expected a logback logger for nebflow.neblink.presence, got $other")
    }.flatMap { (lb, appender, saved) =>
      body(appender.recs).guarantee(IO {
        saved match
          case Some(l) => lb.setLevel(l)
          case None => lb.setLevel(null)
        lb.detachAppender(appender)
      })
    }

  private def recList(recs: ConcurrentLinkedQueue[LogRec]): List[LogRec] =
    recs.toArray.toList.map(_.asInstanceOf[LogRec])

  private def tape(recs: ConcurrentLinkedQueue[LogRec]): Tape =
    val all = recList(recs)
    Tape(
      all.count(_.isTimeout),
      all.count(_.isDisconnected),
      all.count(_.isReconnected),
      all.count(_.isStaleBeat),
      all.count(_.msg.contains("Presence dial failed"))
    )

  private def timeoutStamps(recs: ConcurrentLinkedQueue[LogRec]): List[Long] =
    recList(recs).filter(_.isTimeout).map(_.ts).sorted

  /** 到达间隔（ms）：相邻时间戳之差。 */
  private def intervals(ts: List[Long]): List[Long] = ts.zip(ts.drop(1)).map((a, b) => b - a)

  private def pctl(xs: List[Long], q: Double): Long =
    if xs.isEmpty then -1L
    else
      val s = xs.sorted
      s(math.min(s.size - 1, math.max(0, math.round(s.size * q).toInt - 1)))

  /** 本 JVM 内 `presence-hb-*` 线程名（`peerName = None` ⇒ 全量）。 */
  private def hbThreads(peerName: Option[String]): List[String] =
    Thread
      .getAllStackTraces()
      .keySet()
      .asScala
      .map(_.getName)
      .filter(_.startsWith("presence-hb-"))
      .filter(n => peerName.forall(p => n == s"presence-hb-$p"))
      .toList
      .sorted

  /** 实例清册（**只读**探针：本机在跑的 nebflow 实例 pid+cmdline；零信号、零 kill）。 */
  private def instanceRoster(): String =
    try
      val pb = new ProcessBuilder("bash", "-lc", "pgrep -fl 'nebflow[.]Main' | head -20")
      pb.redirectErrorStream(true)
      val p = pb.start()
      val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
      if out.isEmpty then "(none)" else out.replace('\n', ' ').replace('|', '/')
    catch case _: Exception => "(probe failed)"

  // ===== 证据落盘 =====

  private lazy val evDir: Path =
    val root = sys.env.getOrElse("NB_HB_EVIDENCE_ROOT", "/tmp/hbflife-impl/evidence")
    val runId = sys.env.getOrElse("NB_HB_RUN_ID", "adhoc")
    val d = Paths.get(root, runId)
    Files.createDirectories(d)
    d

  private def evAppend(file: String, lines: List[String]): Unit =
    if lines.nonEmpty then
      try
        Files.write(
          evDir.resolve(file),
          lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND
        )
      catch case _: Exception => () // 证据写盘失败不得顶掉断言（读数同时进 stdout/结果文本）

  private def evReset(file: String): Unit =
    try Files.deleteIfExists(evDir.resolve(file))
    catch case _: Exception => ()

  // ===== harness（与 `NeblinkPresenceServiceSpec` 同形：真网关 + 自有 home + 自有端口） =====

  private def freePort(): Int =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  private def createInstance(name: String, port: Int, d: Dispatcher[IO]): IO[NeblinkService] =
    IO.blocking {
      val home = tmpRoot.resolve(s"home-$name")
      Files.createDirectories(home)
      PathUtil.setDataRoot(os.Path(home, os.pwd))
    } *> NeblinkService.create(port, d)

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

  /** 服务端：`Router("/api" -> rest <+> presenceWsRoutes)`（同 GatewayMain 挂载形），只绑 127.0.0.1。 */
  private def serve(ms: NeblinkService, ps: NeblinkPresenceService, port: Int): Resource[IO, Unit] =
    Resource.eval(
      IO(ms.updateTrustedIps(Set("127.0.0.1", "::1", InetAddress.getByName("127.0.0.1").toString)))
    ) *>
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

  private def peer(id: String, name: String, candidates: List[String]): PeerInfo =
    PeerInfo(deviceId = id, deviceName = name, platform = "macos", address = candidates.head, endpoints = candidates)

  private def awaitCond(timeoutMs: Long, what: String)(pred: => Boolean): IO[Unit] =
    IO.defer {
      val deadline = System.currentTimeMillis() + timeoutMs
      def loop: IO[Unit] =
        IO.defer {
          if pred then IO.unit
          else if System.currentTimeMillis() > deadline then
            IO.raiseError(new AssertionError(s"未在 ${timeoutMs}ms 内满足：$what"))
          else IO.sleep(20.millis).flatMap(_ => loop)
        }
      loop
    }

  private def awaitConnected(ps: NeblinkPresenceService, id: String, timeoutMs: Long = 15_000L): IO[Unit] =
    awaitCond(timeoutMs, s"$id 未接通")(ps.isConnected(id))

  /** 等静默：连接表与 `presence-hb-*` 线程都归零（用例开工前的场地检查）。 */
  private def awaitQuiet(ps: NeblinkPresenceService, timeoutMs: Long = 10_000L): IO[Unit] =
    awaitCond(timeoutMs, s"场地未静默（conns=${ps.connectionCount} hb=${hbThreads(None).size}）") {
      ps.connectionCount == 0 && hbThreads(None).isEmpty
    }

  // ===== 样本 =====

  private final case class Sample(
    tag: String,
    i: Int,
    tMs: Long,
    hbPeer: Int,
    hbAll: Int,
    conns: Int,
    gens: String,
    tape: Tape
  ):

    def json: String =
      s"""{"tag":"$tag","i":$i,"t_ms":$tMs,"hb_peer":$hbPeer,"hb_all":$hbAll,"conns":$conns,"gens":"$gens",""" +
        s""""timeout":${tape.timeout},"disc":${tape.disc},"recon":${tape.recon},"stale":${tape.stale},"dial_fail":${tape.dialFail}}"""

  private def sampleOnce(
    ps: NeblinkPresenceService,
    peerName: String,
    recs: ConcurrentLinkedQueue[LogRec],
    tag: String,
    i: Int
  ): IO[Sample] =
    IO {
      // 🔴 连接侧一次快照取齐（`conns` 与 `gens` 同源）——分两次读会在换连接的瞬间自相矛盾
      // （先读到 gen 后读到 0 条 ⇒ 采样本给出幽灵「线程 > 连接」帧）
      val gensMap = ps.connectionGens
      val gens = gensMap.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString(",")
      Sample(
        tag,
        i,
        System.currentTimeMillis(),
        hbThreads(Some(peerName)).size,
        hbThreads(None).size,
        gensMap.size,
        gens,
        tape(recs)
      )
    }.flatTap(s => IO(println(s"[hb-sample] ${s.json}")))

  /**
   * 采样窗：每 1 s 一采样，逐行落盘 + 逐行打 stdout（🔴 同时充当长跑心跳）。
   * 递归一律 `IO.defer` + `flatMap(_ => loop(...))` —— `IO.sleep(x) *> loop` 的实参在构造期
   * 求值会同步自递归（`StackOverflowError` 属 `VirtualMachineError`，`NonFatal` 兜不住 ⇒ 挂死 sbt）。
   */
  private def watch(
    ps: NeblinkPresenceService,
    peerName: String,
    recs: ConcurrentLinkedQueue[LogRec],
    tag: String,
    seconds: Int
  ): IO[List[Sample]] =
    IO.defer {
      val buf = scala.collection.mutable.ListBuffer.empty[Sample]
      def loop(i: Int): IO[List[Sample]] =
        IO.defer {
          if i > seconds then IO(evAppend(s"$tag.jsonl", buf.toList.map(_.json))).as(buf.toList)
          else
            sampleOnce(ps, peerName, recs, tag, i).flatMap { s =>
              buf += s
              IO.sleep(1.second).flatMap(_ => loop(i + 1))
            }
        }
      loop(1)
    }

  // ===== ① 无堆积 + 在线零超时（并发顶替触发） =====

  test("① 无堆积/在线零超时：并发顶替后 count(presence-hb-*) ≤ count(connections)（稳态相等）且 0 条超时行") {
    val portA = freePort()
    val portB = freePort()
    val deadPort = freePort()
    assert(portA != 8080 && portB != 8080 && portA != portB, s"端口纪律: A=$portA B=$portB")
    evReset("t1_online.jsonl")
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msB <- createInstance("hb-b1", portB, d)
          psB = mkPresence(msB, portB, d)
          msA <- createInstance("hb-a1", portA, d)
          psA = mkPresence(msA, portA, d)
          _ <- useSharedScratch
          idA <- msA.identity
          _ <- seedPeer(msB, idA)
          out <- serve(msB, psB, portB).use { _ =>
            capturePresence { recs =>
              val liveEp = s"http://127.0.0.1:$portB"
              val deadEp = s"http://127.0.0.2:$deadPort" // 环回别名黑洞：无监听、不回包 ⇒ 拨号窗口 = 预算
              val p = peer("hb-online-1", PeerNameOnline, List(deadEp, liveEp))
              for
                // 预热（**不参与断言**）：把 WS 栈类加载从被测窗口挪出去；随后清场
                _ <- psA.connect(peer("hb-warmup", "HB-WARMUP", List(liveEp))).attempt.void
                _ <- awaitConnected(psA, "hb-warmup")
                _ <- psA.disconnectAll()
                _ <- awaitQuiet(psA)
                _ <- IO(println(s"[hb-roster] t1-before ${instanceRoster()}"))
                // 🔴 并发顶替触发：第 1 次拨号在黑洞候选上耗掉整个预算（拨号窗口），第 2 次在第 1 次
                // `put` 之前进入 ⇒ 两条**真** conn 同时存在（改前唯一能产生游离心跳线程的形态；
                // 修复后第 2 次发布必须**先退役**第 1 条）。
                f1 <- psA.connect(p).start
                _ <- IO.sleep(400.millis)
                f2 <- psA.connect(p).start
                _ <- f1.join.attempt
                _ <- f2.join.attempt
                _ <- awaitConnected(psA, "hb-online-1")
                _ <- IO.sleep(1500.millis) // 让顶替的退役动作落定（shutdownNow + close）
                samples <- watch(psA, PeerNameOnline, recs, "t1_online", WatchSec)
                stamps <- IO(timeoutStamps(recs))
                finalTape <- IO(tape(recs))
                roster <- IO(instanceRoster())
                gens <- IO(psA.connectionGens)
              yield (samples, stamps, finalTape, roster, gens, idA.deviceId)
              end for
            }
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield out
      }
      .map { case (samples, stamps, finalTape, roster, gens, devIdA) =>
        val violations = samples.filter(s => s.hbPeer > s.conns)
        val over = samples.filter(s => s.hbAll > 1)
        val iv = intervals(stamps)
        val fiveSec = iv.count(d => d >= 4_800L && d <= 5_200L)
        val rate = if WatchSec > 0 then stamps.size * 60.0 / WatchSec else -1.0
        assert(
          violations.isEmpty,
          s"🔴 判据①违反：出现 count(presence-hb-*) > count(connections) 的样本 ${violations.size}/${samples.size} 条" +
            s"（前 3 条：${violations.take(3).map(_.json).mkString(" | ")}）"
        )
        assert(
          over.isEmpty,
          s"🔴 判据①违反：出现**并存**心跳线程（同一 peer 的 count(presence-hb-*) > 1）${over.size} 帧：" +
            s"${over.take(3).map(_.json).mkString(" | ")}"
        )
        assert(
          samples.forall(s => s.hbPeer == 1 && s.conns == 1),
          s"🔴 稳态必须恒为 1 线程 : 1 连接（样本集合 ${samples.map(s => s"${s.hbPeer}/${s.conns}").distinct.mkString(",")}；末样本 ${samples.last.json}）"
        )
        assertEquals(
          finalTape.timeout,
          0,
          s"🔴 判据②违反：对端**在线**时窗口内不得出现 `Heartbeat timeout ... auto-reconnecting`（现读 ${finalTape.timeout} 条，" +
            s"节拍 ${"%.2f".format(rate)} 次/分，p50=${pctl(iv, 0.5)}ms）"
        )
        assertEquals(fiveSec, 0, s"🔴 出现 5.000 s 整间隔 ${fiveSec} 次（自激节拍）：${iv.mkString(",")}")
        assertEquals(finalTape.disc, 0, s"🔴 无真断连却出现掉线行 ${finalTape.disc} 条（迟到事件重复处置）")
        assertEquals(finalTape.recon, 0, s"🔴 无真断连却出现重连行 ${finalTape.recon} 条（自激重连）")
        assertEquals(finalTape.stale, 0, s"🔴 出现陈旧拍 ${finalTape.stale} 条（不该有游离心跳线程醒来）")
        println(
          s"[hb-evidence] t1 devIdA=$devIdA samples=${samples.size} timeout=${finalTape.timeout} disc=${finalTape.disc} " +
            s"recon=${finalTape.recon} stale=${finalTape.stale} dial_fail=${finalTape.dialFail} " +
            s"max_hb=${samples.map(_.hbPeer).max} max_conns=${samples.map(_.conns).max} gens=$gens roster=$roster"
        )
      }
  }

  // ===== ② 聋对端（不回 pong）⇒ 节拍回到单连接理论带 =====

  test("② 聋对端：心跳超时间隔回到单连接理论带（拍间隔×3+握手），🔴 不得出现 5.000 s 整间隔") {
    val portA = freePort()
    assert(portA != 8080, s"端口纪律: A=$portA")
    evReset("t2_deaf.jsonl")
    val deaf = new DeafWsFixture
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msA <- createInstance("hb-a2", portA, d)
          psA = mkPresence(msA, portA, d)
          _ <- useSharedScratch
          _ <- IO(println(s"[hb-roster] t2-before ${instanceRoster()}"))
          out <- capturePresence { recs =>
            val p = peer("hb-deaf-1", PeerNameDeaf, List(deaf.endpoint))
            for
              // 预热（不参与断言）：首发拨号把 WS 栈类加载吃在窗口外；随后清场
              _ <- psA.connect(peer("hb-warmup2", "HB-WARMUP2", List(deaf.endpoint))).attempt.void
              _ <- awaitConnected(psA, "hb-warmup2")
              _ <- psA.disconnectAll()
              _ <- awaitQuiet(psA)
              _ <- psA.connect(p)
              _ <- awaitConnected(psA, "hb-deaf-1")
              samples <- watch(psA, PeerNameDeaf, recs, "t2_deaf", DeafSec)
              stamps <- IO(timeoutStamps(recs))
              finalTape <- IO(tape(recs))
              roster <- IO(instanceRoster())
            yield (samples, stamps, finalTape, roster)
          }
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
        yield out
      }
      .guarantee(IO(deaf.close()))
      .map { case (samples, stamps, finalTape, roster) =>
        val iv = intervals(stamps)
        // 理论周期**从生产源码的同一份公式**取（不抄数）：环回实测握手 ≈50ms
        val theory = NeblinkPresenceService.singleConnectionSelfCycleMs(LoopbackHandshakeMs)
        val beat = NeblinkPresenceService.HeartbeatIntervalSec * 1000L
        val fiveSec = iv.count(d => d >= 4_800L && d <= 5_200L)
        val offGrid = iv.filter(d => math.abs(d - beat * math.round(d.toDouble / beat.toDouble)) > 600L)
        val rate = if DeafSec > 0 then stamps.size * 60.0 / DeafSec else -1.0
        val over = samples.filter(s => s.hbAll > 1)
        val sustained = samples.zipWithIndex.filter { (s, idx) =>
          s.hbPeer > s.conns && idx + 1 < samples.size && samples(idx + 1).hbPeer > samples(idx + 1).conns
        }
        val eqSamples = samples.count(s => s.hbPeer == s.conns)
        assert(
          over.isEmpty,
          s"🔴 判据①违反：出现**并存**心跳线程（同一 peer 的 count(presence-hb-*) > 1）${over.size} 帧 —— 这就是积压签名：" +
            s"${over.take(3).map(_.json).mkString(" | ")}"
        )
        assert(
          sustained.isEmpty,
          s"🔴 判据①违反：`线程数 > 连接数` 持续 ≥2 帧（≥1s，超出换连接过渡帧）${sustained.size} 处：" +
            s"${sustained.take(3).map(_._1.json).mkString(" | ")}"
        )
        assert(
          eqSamples * 100 / math.max(1, samples.size) >= 80,
          s"🔴 稳态必须 `线程数 = 连接数`（聋对端窗允许换连接的过渡帧）：仅 $eqSamples/${samples.size} 帧相等" +
            s"（分布 ${samples.groupBy(s => s"${s.hbPeer}/${s.conns}").view.mapValues(_.size).toMap}）"
        )
        assert(finalTape.timeout >= 2, s"聋对端必须触发超时腿（现读 ${finalTape.timeout} 条，窗口 ${DeafSec}s）")
        assertEquals(fiveSec, 0, s"🔴 判据②违反：出现 5.000 s 整间隔 ${fiveSec} 次（自激节拍复现）；间隔=${iv.mkString(",")}")
        assert(
          iv.nonEmpty && pctl(iv, 0.5) >= theory - 2_000L && pctl(iv, 0.5) <= theory + 4_000L,
          s"🔴 间隔中位数必须落在单连接理论带 [${theory - 2_000}, ${theory + 4_000}]ms" +
            s"（理论 = 拍间隔×k+h，k=${math.round(theory.toDouble / beat.toDouble)}，h=${LoopbackHandshakeMs}ms；现读 p50=${pctl(iv, 0.5)} n=${iv.size} all=${iv.mkString(",")}）"
        )
        assert(
          offGrid.isEmpty,
          s"🔴 间隔必须落在心跳拍网格的整数倍上（±600ms）——否则不是「单连接自循环」而是别的泵：${offGrid.mkString(",")}（all=${iv.mkString(",")}）"
        )
        assertEquals(finalTape.disc, 0, s"超时腿退出的连接不应再走「掉线」腿（迟到事件重复处置）：${finalTape.disc} 条")
        println(
          s"[hb-evidence] t2 n=${stamps.size} rate_per_min=${"%.2f".format(rate)} p50=${pctl(iv, 0.5)} p90=${pctl(iv, 0.9)} " +
            s"min=${iv.minOption.getOrElse(-1L)} max=${iv.maxOption.getOrElse(-1L)} theory=${theory}ms fiveSecHits=$fiveSec " +
            s"disc=${finalTape.disc} recon=${finalTape.recon} stale=${finalTape.stale} served=${deaf.served} " +
            s"eq_samples=$eqSamples/${samples.size} dist=${samples.groupBy(s => s"${s.hbPeer}/${s.conns}").view.mapValues(_.size).toMap} roster=$roster"
        )
      }
  }

  // ===== ③/④ 真断连 ≥5 轮：每轮恰一次重连序列 + 逐轮采样 =====

  test("③/④ 真断连 ×N 轮：每轮恰一次重连序列（1 掉线行 + 1 重连行），轮间线程/连接无堆积") {
    val portA = freePort()
    val portB = freePort()
    assert(portA != 8080 && portB != 8080 && portA != portB, s"端口纪律: A=$portA B=$portB")
    evReset("t3_rounds.jsonl")
    Dispatcher
      .parallel[IO]
      .use { d =>
        for
          msB <- createInstance("hb-b3", portB, d)
          psB = mkPresence(msB, portB, d)
          msA <- createInstance("hb-a3", portA, d)
          psA = mkPresence(msA, portA, d)
          _ <- useSharedScratch
          idA <- msA.identity
          _ <- seedPeer(msB, idA)
          relay = new TcpRelay(portB)
          out <- serve(msB, psB, portB).use { _ =>
            capturePresence { recs =>
              val p = peer("hb-round-1", PeerNameRounds, List(relay.endpoint))
              val recsOut = scala.collection.mutable.ListBuffer.empty[String]
              def roundOnce(r: Int, genPrev: Long): IO[Unit] =
                IO.defer {
                  for
                    s0 <- sampleOnce(psA, PeerNameRounds, recs, s"t3_r$r-before", r)
                    t0 = s0.tape
                    _ <- IO(println(s"[hb-roster] round=$r ${instanceRoster()}"))
                    _ <- IO(relay.dropAll()) // 真断连：整对 TCP 直接关（无 close 帧 ⇒ EOF/异常路径）
                    // ④ 真掉线仍须摘除：先等「掉线类」日志出现（⇒ removePeer 已发生）
                    _ <- awaitCond(15_000L, s"第 $r 轮掉线日志未出现（tape=$t0）") {
                      val t = tape(recs)
                      (t.timeout + t.disc) > (t0.timeout + t0.disc)
                    }
                    // ③ 再等代际前进（新 conn 发布）且接通：一轮恰一次重连序列
                    _ <- awaitCond(20_000L, s"第 $r 轮未重连（gen 未前进，prev=$genPrev）") {
                      psA.isConnected("hb-round-1") && psA.connectionGens.getOrElse("hb-round-1", -1L) != genPrev
                    }
                    _ <- IO.sleep(300.millis)
                    s1 <- sampleOnce(psA, PeerNameRounds, recs, s"t3_r$r-after", r)
                    t1 = s1.tape
                    genAfter = psA.connectionGens.getOrElse("hb-round-1", -1L)
                    rec =
                      s"""{"round":$r,"gen_before":$genPrev,"gen_after":$genAfter,"hb_before":${s0.hbPeer},""" +
                        s""""hb_all_before":${s0.hbAll},"conns_before":${s0.conns},"hb_after":${s1.hbPeer},""" +
                        s""""hb_all_after":${s1.hbAll},"conns_after":${s1.conns},"d_disc":${t1.disc - t0.disc},""" +
                        s""""d_timeout":${t1.timeout - t0.timeout},"d_recon":${t1.recon - t0.recon},"d_stale":${t1.stale - t0.stale}}"""
                    _ <- IO(println(s"[hb-round] $rec"))
                    _ <- IO(recsOut += rec)
                    _ <- IO(evAppend("t3_rounds.jsonl", List(rec)))
                    _ <- if r < DropRounds then roundOnce(r + 1, genAfter) else IO.unit
                  yield ()
                }
              for
                _ <- psA.connect(peer("hb-warmup3", "HB-WARMUP3", List(relay.endpoint))).attempt.void
                _ <- psA.disconnectAll()
                _ <- awaitQuiet(psA)
                _ <- msA.upsertPeer(p)
                _ <- psA.connect(p)
                _ <- awaitConnected(psA, "hb-round-1")
                gen0 <- IO(psA.connectionGens.getOrElse("hb-round-1", -1L))
                _ <- roundOnce(1, gen0)
                finalTape <- IO(tape(recs))
                roster <- IO(instanceRoster())
              yield (recsOut.toList, finalTape, roster)
            }
          }
          _ <- IO(relay.close())
          _ <- psA.disconnectAll().handleErrorWith(_ => IO.unit)
          _ <- psB.disconnectAll().handleErrorWith(_ => IO.unit)
        yield out
      }
      .map { case (rounds, finalTape, roster) =>
        assertEquals(rounds.size, DropRounds, s"必须跑满 $DropRounds 轮")
        val staleGen = rounds.filter(r => numOf(r, "gen_before") < 0 || numOf(r, "gen_after") <= numOf(r, "gen_before"))
        assert(staleGen.isEmpty, s"每轮都必须有现役连接且代际前进：${staleGen.mkString(" | ")}")
        rounds.zipWithIndex.foreach { (rec, i) =>
          val dDisc = numOf(rec, "d_disc")
          val dTimeout = numOf(rec, "d_timeout")
          assertEquals(dDisc + dTimeout, 1, s"🔴 第 ${i + 1} 轮掉线类日志必须**恰 1 条**（got disc=$dDisc timeout=$dTimeout）：$rec")
          assertEquals(numOf(rec, "d_recon"), 1, s"🔴 第 ${i + 1} 轮重连必须**恰 1 次**（got ${numOf(rec, "d_recon")}）：$rec")
          assert(
            numOf(rec, "hb_after") <= numOf(rec, "conns_after"),
            s"🔴 判据①违反（第 ${i + 1} 轮）：线程数 > 连接数：$rec"
          )
          assertEquals(numOf(rec, "hb_after"), numOf(rec, "conns_after"), s"🔴 第 ${i + 1} 轮线程数 ≠ 连接数：$rec")
        }
        assert(
          rounds
            .map(r => numOf(r, "hb_after"))
            .distinct == List(1) && rounds.map(r => numOf(r, "conns_after")).distinct == List(1),
          s"🔴 轮间不得堆积（每轮 hb/conns 必须恒为 1）：${rounds.mkString(" | ")}"
        )
        assertEquals(finalTape.stale, 0, s"🔴 出现陈旧拍 ${finalTape.stale} 条（游离心跳线程醒来）")
        println(
          s"[hb-evidence] t3 rounds=${rounds.size} disc=${finalTape.disc} timeout=${finalTape.timeout} recon=${finalTape.recon} " +
            s"stale=${finalTape.stale} roster=$roster"
        )
        println(rounds.mkString("\n"))
      }
  }

  private def numOf(rec: String, key: String): Int =
    val pat = s""""$key":(-?[0-9]+)""".r
    pat.findFirstMatchIn(rec).map(_.group(1).toInt).getOrElse(fail(s"读数缺字段 $key：$rec"))

end NeblinkPresenceHeartbeatLifecycleSpec

/**
 * 透明 TCP 中继（**非 WS mock**）：字节级双向转发 ⇒ 被测链路仍是**真** presence WS
 * （真 101 握手 + 真 ping/pong 帧），只是可被测试**确定性地**掐断。
 *
 * `dropAll()`：关掉当前所有「客户端 socket ↔ 服务端 socket」对 ⇒ 客户端读到 EOF
 * （**无** WS close 帧）= 任务书 §3 的「真掉线」形态（判据④红向）。
 */
private final class TcpRelay(targetPort: Int):
  private val server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
  private val pairs = new java.util.concurrent.CopyOnWriteArrayList[(Socket, Socket)]()
  @volatile private var stopped = false

  def port: Int = server.getLocalPort
  def endpoint: String = s"http://127.0.0.1:$port"

  private val acceptor: Thread =
    val body: Runnable = () =>
      while !stopped do
        try
          val client = server.accept()
          val up = new Socket(InetAddress.getByName("127.0.0.1"), targetPort)
          pairs.add((client, up))
          pump(client, up)
          pump(up, client)
        catch case _: Throwable => stopped = true
    val t = new Thread(body, "hb-tcp-relay-accept")
    t.setDaemon(true)
    t.start()
    t

  private def pump(from: Socket, to: Socket): Unit =
    val body: Runnable = () =>
      val buf = new Array[Byte](8192)
      try
        var n = from.getInputStream.read(buf)
        while n >= 0 do
          to.getOutputStream.write(buf, 0, n)
          to.getOutputStream.flush()
          n = from.getInputStream.read(buf)
      catch case _: Throwable => ()
      finally closeQuietly(to)
    val t = new Thread(body, "hb-tcp-relay-pump")
    t.setDaemon(true)
    t.start()

  /** 掐断当前所有转发对（客户端随后读到 EOF ⇒ 真掉线，无 close 帧）。 */
  def dropAll(): Unit =
    pairs.forEach { (c, u) =>
      closeQuietly(c); closeQuietly(u)
    }
    pairs.clear()

  private def closeQuietly(s: Socket): Unit =
    try s.close()
    catch case _: Throwable => ()

  def close(): Unit =
    stopped = true
    dropAll()
    try server.close()
    catch case _: Throwable => ()

end TcpRelay

/**
 * 聋对端夹具（**只在本机回环**）：完成真 WS 握手（`101 + Sec-WebSocket-Accept`），此后
 * **吞掉**客户端的一切帧（不回 pong、不回 close）⇒ 客户端心跳必然逾期。
 *
 * 与 `NeblinkPresenceDialBudgetSpec` 的 `PresenceWsFixture` 同形但**不复用**：那个是
 * 该文件内的顶层 `private` 类型，复用会跨文件耦合他人面（本批写面纪律：只许新增文件）。
 * 断言语义不依赖它：本 spec 只用它造「对端在线上但不应答」这一条**真**链路形态。
 */
private final class DeafWsFixture:
  private val serverSocket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
  private val clients = new java.util.concurrent.CopyOnWriteArrayList[Socket]()
  private val servedCount = new AtomicInteger(0)
  @volatile private var stopped = false

  def port: Int = serverSocket.getLocalPort
  def endpoint: String = s"http://127.0.0.1:$port"
  def served: Int = servedCount.get()

  private val acceptor: Thread =
    val body: Runnable = () =>
      while !stopped do
        try
          val s = serverSocket.accept()
          clients.add(s)
          val t = new Thread(() => serve(s), "hb-deaf-handler")
          t.setDaemon(true)
          t.start()
        catch case _: Throwable => stopped = true
    val t = new Thread(body, "hb-deaf-accept")
    t.setDaemon(true)
    t.start()
    t

  private def serve(s: Socket): Unit =
    try
      val in = s.getInputStream
      val request = readHeaders(in)
      servedCount.incrementAndGet()
      if !stopped then
        s.getOutputStream.write(handshakeResponse(request).getBytes(StandardCharsets.ISO_8859_1))
        s.getOutputStream.flush()
        val sink = new Array[Byte](2048)
        while !stopped && in.read(sink) >= 0 do () // 吞掉对端的一切帧（含 ping / close）
    catch case _: Throwable => ()
    finally
      try s.close()
      catch case _: Throwable => ()

  private def readHeaders(in: InputStream): String =
    val sb = new StringBuilder
    var state = 0
    var b = in.read()
    while b >= 0 && state < 4 do
      val c = b.toChar
      sb.append(c)
      state = (state, c) match
        case (0, '\r') => 1
        case (1, '\n') => 2
        case (2, '\r') => 3
        case (3, '\n') => 4
        case _ => 0
      if state < 4 then b = in.read()
    sb.toString

  end readHeaders

  private def handshakeResponse(request: String): String =
    val key = request.linesIterator
      .map(_.trim)
      .find(_.toLowerCase.startsWith("sec-websocket-key:"))
      .map(_.substring(18).trim)
      .getOrElse("")
    val accept = Base64.getEncoder.encodeToString(
      MessageDigest
        .getInstance("SHA-1")
        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8))
    )
    s"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"

  def close(): Unit =
    stopped = true
    try serverSocket.close()
    catch case _: Throwable => ()
    clients.forEach(s =>
      try s.close()
      catch case _: Throwable => ()
    )
end DeafWsFixture
