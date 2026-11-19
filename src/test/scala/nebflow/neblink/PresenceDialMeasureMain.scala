package nebflow.neblink

import nebflow.neblink.relay.PresenceDialSchedule

import nebflow.neblink.relay.EvictionPolicy

import nebflow.neblink.relay.DialBudget

import nebflow.neblink.core.NeblinkService

import nebflow.neblink.relay.NeblinkPresenceService

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import nebflow.core.tools.P2pReachability
import nebflow.shared.PathUtil

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * presence 拨号改造（P1–P4）的**实测夹具**（2026-11-04，非 spec）。
 *
 * 作者硬要求：「改前/改后同一方法各一遍，至少给：一轮拨号总耗时 / compute-blocker 线程被
 * 占用的时长 / 命中负缓存时的单轮耗时 / 同期文件打开 p99 是否受拨号影响 / 端点全不可达时
 * 整轮耗时上限」，且「每条断言红→绿」。
 *
 * 三条设计纪律：
 *  1. **同一个方法**：两臂都跑生产代码本体 `NeblinkPresenceService.connect(peer)`，只翻
 *     [[PresenceDialSchedule]]（`LegacySerial` = 改前形态：串行 + `IO.blocking`；
 *     `Production` = 改后：并发 + 专用阻塞池）。比的是调度，不是两套实现。
 *  2. **同一个端点集**：`kai` = 真机 KAI 两个候选（实测 SYN 黑洞：tailscale 100.91.165.120
 *     + LAN 192.168.1.145）；`blackhole-rfc5737` = RFC 5737 两个地址（确定性黑洞，任何机器
 *     可复现同一组数）。
 *  3. **线程归属取自生产读数面**（`dialThreadStats`）：`msOnCompute` 就是「compute-blocker
 *     被这个拨号占用的时长」，不是另外埋的探针。
 *
 * 另加两个机制读数（同一批）：
 *  - **compute 活性探针**：拨号期间在 compute 池上反复打点（`IO.monotonic` 两跳 = 一次调度
 *    往返），给出 p50/p99 —— 这就是「同期文件打开 p99 是否被拨号拉长」的**同因代理**：
 *    文件打开是 compute 池上的任务，拨号若占用 compute 线程，打开延迟就跟着涨（KAI 批那条
 *    11.3s 冷开离群正是这个机制）。
 *  - **风暴臂**：6 台设备同时拨号（真实舰队形态）——把「单条拨号占 1 条线程」放大成
 *    「整池被占」的可判读数。
 *
 * 重跑：`sbt "Test/runMain nebflow.neblink.PresenceDialMeasureMain"`（输出 `MEASURE …` 行）。
 */
object PresenceDialMeasureMain:

  /** 真机 KAI 的两个候选（实测 SYN 黑洞；tailnet 3000ms 档 + LAN 1500ms 档）。 */
  private val KaiEndpoints = List("http://100.91.165.120:6138", "http://192.168.1.145:6138")

  /** RFC 5737 TEST-NET 两地址（链路上无人应答 ⇒ 确定性黑洞，可复现）。 */
  private val Rfc5737Endpoints = List("http://203.0.113.7:6138", "http://198.51.100.9:6138")

  private final case class Arm(label: String, schedule: PresenceDialSchedule, sharedLedger: Boolean)

  private val Arms = List(
    // 改前形态 = 串行 + `IO.blocking` + **没有**共用可达性真源（今天 presence 腿的真实样子：
    // 负缓存只有文件腿在用）。改后 = 并发 + 专用池 + 共用真源 —— 三个开关一起翻，正是本批四件事。
    Arm("legacy", PresenceDialSchedule.LegacySerial, sharedLedger = false),
    Arm("production", PresenceDialSchedule.Production, sharedLedger = true)
  )

  private def json(parts: (String, String)*): String =
    parts.map((k, v) => s""""$k":$v""").mkString("{", ",", "}")

  private def str(s: String): String = "\"" + s.replace("\"", "'") + "\""

  /** 一套最小栈：真 `NeblinkService`（测试形态）+ 被测量的 `NeblinkPresenceService`。 */
  private def withStack[A](
    schedule: PresenceDialSchedule,
    reachLedger: Option[P2pReachability]
  )(use: NeblinkPresenceService => IO[A]): A =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(6138, dispatcher, 300.millis)
        ps = new NeblinkPresenceService(
          ms,
          6138,
          DialBudget.Default,
          EvictionPolicy.Default,
          schedule,
          reachLedger
        )(dispatcher)
        out <- use(ps).guarantee(ps.disconnectAll().handleErrorWith(_ => IO.unit))
      yield out
    }.unsafeRunSync()

  private def peer(id: String, candidates: List[String]) =
    nebflow.shared.PeerInfo(
      deviceId = id,
      deviceName = s"Measure-$id",
      platform = "macos",
      address = candidates.head,
      endpoints = candidates
    )

  /**
   * compute 活性探针：反复测「在 compute 池上跑一次 fiber 的调度往返」（µs）。
   * 拨号若占用 compute 线程，这个数就涨——它是「文件打开 p99 受影响」的同因代理。
   */
  private def sampleComputeLatency(
    running: java.util.concurrent.atomic.AtomicBoolean,
    sink: java.util.concurrent.ConcurrentLinkedQueue[Long]
  ): IO[Unit] =
    def loop: IO[Unit] =
      IO.defer {
        if !running.get() then IO.unit
        else
          for
            t0 <- IO.monotonic
            t1 <- IO.monotonic // 再一跳 = 又一次 compute 调度
            _ <- IO(sink.add((t1 - t0).toMicros))
            _ <- IO.sleep(5.millis)
            _ <- loop
          yield ()
      }
    loop

  private def pct(xs: List[Long], p: Double): Long =    if xs.isEmpty then -1L
    else
      val sorted = xs.sorted
      sorted(math.min(sorted.size - 1, math.max(0, (sorted.size * p).toInt)))

  /** 单臂单端点集的一轮读数（冷轮 ×2 各自新账本 + 负缓存轮）。 */
  private def measureArm(setLabel: String, endpoints: List[String], arm: Arm): Unit =
    def ledgerFor(): Option[P2pReachability] = if arm.sharedLedger then Some(new P2pReachability) else None

    // ① 冷轮（真拨）：roundMs / 线程归属 / compute 活性探针。**每轮一个新账本** ⇒ 真冷。
    val coldRuns = (1 to 2).map { runIdx =>
      withStack(arm.schedule, ledgerFor()) { ps =>
        val running = new java.util.concurrent.atomic.AtomicBoolean(true)
        val sink = new java.util.concurrent.ConcurrentLinkedQueue[Long]()
        for
          _ <- IO(ps.resetDialThreadStats())
          probe <- sampleComputeLatency(running, sink).start
          t0 <- IO.monotonic
          _ <- ps.connect(peer(s"cold$runIdx", endpoints))
          roundMs <- IO.monotonic.map(d => (d - t0).toMillis)
          _ <- IO(running.set(false))
          _ <- probe.join.timeout(2.seconds).handleErrorWith(_ => IO.unit)
          stats <- IO(ps.dialThreadStats)
          round <- IO(ps.lastDialRound(s"cold$runIdx"))
          latencies <- IO(sink.iterator().asScala.toList)
        yield (roundMs, stats, round, latencies)
      }
    }

    val rounds = coldRuns.map(_._1).toList
    val medRoundMs = rounds.sorted.apply(rounds.size / 2)
    val stats = coldRuns.last._2
    val latency = coldRuns.flatMap(_._4).toList
    println(
      "MEASURE dial " + json(
        "set" -> str(setLabel),
        "arm" -> str(arm.label),
        "endpoints" -> endpoints.size.toString,
        "concurrent" -> arm.schedule.concurrent.toString,
        "dedicatedPool" -> arm.schedule.dedicatedPool.toString,
        "sharedLedger" -> arm.sharedLedger.toString,
        "roundMsRuns" -> ("[" + rounds.mkString(",") + "]"),
        "roundMsMedian" -> medRoundMs.toString,
        "dialBodies" -> stats._1.toString,
        "dialBodiesOnCompute" -> stats._2.toString,
        "msOnCompute" -> stats._3.toString,
        "msOnPool" -> stats._4.toString,
        "attempted" -> str(coldRuns.last._3.map(_.attempted.size).getOrElse(-1).toString),
        "computeLatencyUsP50" -> pct(latency, 0.50).toString,
        "computeLatencyUsP99" -> pct(latency, 0.99).toString,
        "computeLatencySamples" -> latency.size.toString
      )
    )

    // ② 负缓存轮：改后臂先在共用真源里种一条负证据（等价于「文件腿刚付过探测税」），
    //    期望 0ms、零拨号体；改前臂**没有**共用真源 ⇒ 照付满预算（这是 P3 的红读数）。
    val ledger = new P2pReachability
    withStack(arm.schedule, if arm.sharedLedger then Some(ledger) else None) { ps =>
      val p = peer("cached", endpoints)
      for
        _ <-
          if arm.sharedLedger then
            IO(ledger.noteFailure(p.deviceId, nebflow.core.tools.P2pPathDecision.addressKey(p), System.currentTimeMillis()))
          else IO.unit
        _ <- IO(ps.resetDialThreadStats())
        t0 <- IO.monotonic
        _ <- ps.connect(p)
        ms <- IO.monotonic.map(d => (d - t0).toMillis)
        stats <- IO(ps.dialThreadStats)
        round <- IO(ps.lastDialRound("cached"))
      yield (ms, stats, round)
    } match
      case (ms, stats, round) =>
        println(
          "MEASURE dial " + json(
            "set" -> str(setLabel),
            "arm" -> str(arm.label),
            "mode" -> str("negative-cache-hit"),
            "sharedLedger" -> arm.sharedLedger.toString,
            "roundMs" -> ms.toString,
            "dialBodies" -> stats._1.toString,
            "msOnCompute" -> stats._3.toString,
            "attempted" -> round.map(_.attempted.size).getOrElse(-1).toString
          )
        )
  end measureArm

  /** 风暴臂：N 台设备同时拨号（真实舰队形态），量 compute 活性 p99 与 compute 线程占用。 */
  private def measureStorm(setLabel: String, endpoints: List[String], arm: Arm, peers: Int): Unit =
    withStack(arm.schedule, None) { ps =>
      val running = new java.util.concurrent.atomic.AtomicBoolean(true)
      val sink = new java.util.concurrent.ConcurrentLinkedQueue[Long]()
      for
        _ <- IO(ps.resetDialThreadStats())
        probe <- sampleComputeLatency(running, sink).start
        t0 <- IO.monotonic
        fibers <- (1 to peers).toList.traverse(i => ps.connect(peer(s"storm$i", endpoints)).start)
        _ <- IO.sleep(1.second) // 让所有拨号体都落到线程上（风暴中段取数）
        midStats <- IO(ps.dialThreadStats)
        _ <- fibers.traverse_(_.join.handleErrorWith(_ => IO.unit))
        roundMs <- IO.monotonic.map(d => (d - t0).toMillis)
        _ <- IO(running.set(false))
        _ <- probe.join.timeout(2.seconds).handleErrorWith(_ => IO.unit)
        stats <- IO(ps.dialThreadStats)
        latencies <- IO(sink.iterator().asScala.toList)
      yield (roundMs, stats, midStats, latencies)
    } match
      case (roundMs, stats, midStats, latencies) =>
        println(
          "MEASURE dial " + json(
            "set" -> str(setLabel),
            "arm" -> str(arm.label),
            "mode" -> str("storm"),
            "peers" -> peers.toString,
            "endpointsPerPeer" -> endpoints.size.toString,
            "roundMs" -> roundMs.toString,
            "dialBodies" -> stats._1.toString,
            "dialBodiesOnCompute" -> stats._2.toString,
            "msOnCompute" -> stats._3.toString,
            "msOnPool" -> stats._4.toString,
            "msOnComputeMidStorm" -> midStats._3.toString,
            "computeLatencyUsP50" -> pct(latencies, 0.50).toString,
            "computeLatencyUsP99" -> pct(latencies, 0.99).toString,
            "computeLatencySamples" -> latencies.size.toString
          )
        )

  /**
   * **文件打开影响臂**（硬验收第 4 项）：自建一个 `poolSize` 条工作线程的 IORuntime，让拨号能
   * 把 compute 池占满，然后量「同一时刻提交的**文件打开腿同形任务**」的排队延迟。
   *
   * 为什么不量真机冷开：真机冷开要引擎重启到本批代码（本批硬约束**禁止**重启/杀 pid 78176），
   * 而且当下唯一对端（KAI）两条腿都是 SYN 黑洞 ⇒ 没有可测的真机取件。这里的任务形态照搬
   * 文件打开腿在 compute 池上做的事（读本地文件 + 算 sha），量的是**同一台池子的排队**，
   * 即 KAI 那条 11.3s 冷开离群的机制本身。
   */
  private def measureFileOpenImpact(setLabel: String, endpoints: List[String], arm: Arm, maxDials: Int): Unit =
    // CE 3.6.1 实测：单参重载落在 9 参缺省链上 ⇒ 返回 (pool, poller, shutdown) **三元组**，
    // 不是文档画的二元组 ⇒ 用 productElement 取件并显式 ascribe（编译器无法给精确型）。
    val created = cats.effect.unsafe.IORuntime.createWorkStealingComputeThreadPool(maxDials)
    val computePool: scala.concurrent.ExecutionContext =
      created.productElement(0).asInstanceOf[scala.concurrent.ExecutionContext]
    val shutdownCompute: () => Unit = created.productArity match
      case 3 => () => created.productElement(2).asInstanceOf[() => Unit].apply()
      case _ => created.productElement(1).asInstanceOf[() => Unit]
    given rt: cats.effect.unsafe.IORuntime =
      cats.effect.unsafe.IORuntime
        .builder()
        .setCompute(computePool, shutdownCompute)
        .build()
    // 文件打开腿的同形任务：读一个 2 MiB 临时文件 + sha256（~几 ms CPU/IO）。
    // 记的是**提交→完成**的排队延迟：t0 在父 fiber（提交侧）取，子 fiber 完成后记账 ——
    // 这样池被占满时「子 fiber 迟迟不被调度」的等待也算进延迟里（否则会把病灶量没）。
    val tmpFile = Files.createTempFile("presence-dial-fileopen", ".bin")
    Files.write(tmpFile, new Array[Byte](2 * 1024 * 1024))
    val opens = new java.util.concurrent.ConcurrentLinkedQueue[Long]()

    val result = Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(6138, dispatcher, 300.millis)
        ps = new NeblinkPresenceService(
          ms,
          6138,
          DialBudget.Default,
          EvictionPolicy.Default,
          arm.schedule,
          None
        )(dispatcher)
        dials <- (1 to maxDials).toList.traverse(i => ps.connect(peer(s"open$i", endpoints)).start)
        _ <- IO.sleep(150.millis) // 等拨号体全部落到线程上（风暴中段）
        // 40 个取件任务**同时**提交（= 一次冷开的真实形态：一批取件同时落到 compute 池）；
        // t0 在提交侧取、由子 fiber 记账，join 带超时兜底 ⇒ 臂时长有界。
        openFibers <- (1 to 40).toList.traverse { _ =>
                        IO(System.nanoTime()).flatMap { t0 =>
                          IO.blocking {
                            val bytes = Files.readAllBytes(tmpFile)
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).length
                          }.flatMap(_ => IO(opens.add((System.nanoTime() - t0) / 1000L))).start
                        }
                      }
        _ <- openFibers.traverse_(_.join.void.timeout(30.seconds).handleErrorWith(_ => IO.unit))
        _ <- dials.traverse_(_.join.void.timeout(20.seconds).handleErrorWith(_ => IO.unit))
        _ <- ps.disconnectAll().handleErrorWith(_ => IO.unit)
      yield ()
    }
    result.unsafeRunSync()
    val lat = opens.iterator().asScala.toList
    println(
      "MEASURE dial " + json(
        "set" -> str(setLabel),
        "arm" -> str(arm.label),
        "mode" -> str("file-open-impact"),
        "computePoolSize" -> maxDials.toString,
        "simultaneousDials" -> maxDials.toString,
        "openLatencyMsP50" -> (pct(lat, 0.50) / 1000L).toString,
        "openLatencyMsP99" -> (pct(lat, 0.99) / 1000L).toString,
        "openLatencyMsMax" -> (lat.maxOption.getOrElse(-1L) / 1000L).toString,
        "samples" -> lat.size.toString
      )
    )
    try Files.deleteIfExists(tmpFile)
    catch case _: Throwable => ()
    rt.shutdown()
  end measureFileOpenImpact

  def main(args: Array[String]): Unit =
    // 隔离数据根：测量夹具绝不动作者的真实 dataRoot（血泪：`~/.stand` 里躺着生产会话）。
    val tmp = Files.createTempDirectory("presence-dial-measure")
    val savedRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(os.Path(tmp.toString, os.pwd))
    try
      println(
        "MEASURE env " + json(
          "pid" -> ProcessHandle.current().pid().toString,
          "cores" -> Runtime.getRuntime.availableProcessors().toString,
          "note" -> str("real sockets; same method connect(); only the schedule flips")
        )
      )
      val sets = List(("kai", KaiEndpoints), ("blackhole-rfc5737", Rfc5737Endpoints))
      for (label, eps) <- sets; arm <- Arms do measureArm(label, eps, arm)
      // 风暴臂只在确定性黑洞集上跑（真机端点对 6 路并发不友好）
      for arm <- Arms do measureStorm("blackhole-rfc5737", Rfc5737Endpoints, arm, peers = 6)
      // 文件打开影响臂：12 条拨号占满一个 12 线程 compute 池，量「文件打开同形任务」排队延迟
      for arm <- Arms do measureFileOpenImpact("blackhole-rfc5737", Rfc5737Endpoints, arm, maxDials = 12)
    finally
      PathUtil.setDataRoot(savedRoot)
      try os.remove.all(os.Path(tmp.toString, os.pwd))
      catch case _: Throwable => ()
      // 夹具自退（`ProbeTaxMeasureMain` 同款纪律）：global IORuntime 工作线程非 daemon。
      cats.effect.unsafe.IORuntime.global.shutdown()

end PresenceDialMeasureMain
