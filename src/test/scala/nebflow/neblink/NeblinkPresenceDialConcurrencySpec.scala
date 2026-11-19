package nebflow.neblink

import nebflow.neblink.relay.PresenceDialThreadViolation

import nebflow.neblink.relay.PresenceDialPool

import nebflow.neblink.relay.PresenceDialSchedule

import nebflow.neblink.relay.DialAddressClass

import nebflow.neblink.relay.EvictionPolicy

import nebflow.neblink.relay.PresenceDialRound

import nebflow.neblink.relay.NeblinkPresenceService

import nebflow.neblink.core.NeblinkService

import nebflow.neblink.relay.DialBudget

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.tools.{P2pPathDecision, P2pReachability}
import nebflow.shared.{PathUtil, PeerInfo}

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * presence-dial-overhaul 实施批（2026-11-04，作者 13:05 批「可以派」）的验收 spec。
 *
 * 三条独立调查交叉指向同一根因：presence 直连腿一直连不上（KAI 两端点均 SYN 黑洞，
 * 日 101–175 条 `Presence dial failed … timeout`），且一轮拨号 = 4500ms、**占用
 * `io-compute-blocker` 线程**（KAI 场景那次 11.3s 冷开离群即它造成）。
 *
 * 四件要做（P1–P4），每件一组可判的红→绿：
 *
 *  - **P3 单一真源**：生产装配必须显式接 `reachLedger = Some(P2pReachability.current)`
 *    （缺省 `None` 只为排程类 spec 隔离）——源码护栏 + 行为断言两条腿。
 *  - **P1 并发化**：一轮多候选**并发**拨（上限 4），首胜放行——同方法（`connect`）、同端点、
 *    同机，只翻 `PresenceDialSchedule`：串行 ≈ Σ 预算，并发 ≈ max；并发度有界（截断留痕）。
 *  - **P2 搬离 compute-blocker**：拨号体必须落在 `presence-dial-*` 专用池；改前口径落在
 *    `io-compute-*`（红证据）；错线程当场炸红（[[PresenceDialThreadViolation]]）。
 *  - **P4 退避与可观测**：结构化行 `[presence-dial]` 只在**决策迁移边**发 INFO（行数纪律）。
 *
 * 🔴 本 spec **不引入真实跨机拨号**：只打本机环回（127.0.0.1 夹具 / 127.0.0.2·3 黑洞）。
 */
class NeblinkPresenceDialConcurrencySpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-presence-dial-overhaul")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** Windows 环回无黑洞语义（127/8 全段 ECONNREFUSED）⇒ 黑洞轴用例跳过（F-D spec 同款门）。 */
  private val windowsBlackholeUnsupported: Boolean =
    System.getProperty("os.name", "").toLowerCase.contains("win")

  // ===== harness =====

  /** 仓内源码（文本对账护栏；`CliBrandDefaultsSpec` 先例：spec 从仓根相对路径读）。 */
  private def sourceOf(relPath: String): String =
    val p = java.nio.file.Path.of(relPath).toFile
    assert(p.exists(), s"护栏目标源码缺失: ${p.getAbsolutePath}")
    scala.io.Source.fromFile(p).mkString

  private def withStack[A](
    serverPort: Int,
    budget: DialBudget = DialBudget.Default,
    schedule: PresenceDialSchedule = PresenceDialSchedule.Production,
    reachLedger: Option[P2pReachability] = None,
    dialHook: (String, Int, Long) => Unit = (_, _, _) => ()
  )(use: (NeblinkService, NeblinkPresenceService) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(serverPort, dispatcher, 300.millis)
        ps = new NeblinkPresenceService(ms, serverPort, budget, EvictionPolicy.Default, schedule, reachLedger, dialHook)(
          dispatcher
        )
        _ = ms.setPresenceService(ps)
        out <- use(ms, ps).guarantee(ps.disconnectAll().handleErrorWith(_ => IO.unit))
      yield out
    }

  private def peer(id: String, candidates: List[String]): PeerInfo =
    PeerInfo(
      deviceId = id,
      deviceName = s"Dev-$id",
      platform = "macos",
      address = candidates.head,
      endpoints = candidates
    )

  private def closedPort(): Int =
    val s = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  /** 源级日志夹具（`NeblinkPresenceDialBudgetSpec` 同款）：收 `nebflow.neblink.presence` 事件。 */
  private def withPresenceLogs[A](use: IO[A]): IO[(A, List[String])] =
    IO {
      val logger = org.slf4j.LoggerFactory
        .getLogger("nebflow.neblink.presence")
        .asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      appender.start()
      logger.addAppender(appender)
      (logger, appender)
    }.flatMap { pair =>
      val logger = pair._1
      val appender = pair._2
      use
        .map(a => (a, appender.list.asScala.toList.map(_.getFormattedMessage)))
        .guarantee(IO(logger.detachAppender(appender)))
    }

  private def roundOf(ps: NeblinkPresenceService, id: String): PresenceDialRound =
    ps.lastDialRound(id).getOrElse(fail(s"未记录到 $id 的拨号轮"))

  private def awaitRoundWhere(
    ps: NeblinkPresenceService,
    id: String,
    pred: PresenceDialRound => Boolean,
    timeoutMs: Long = 8_000L
  ): IO[PresenceDialRound] =
    IO.defer {
      val deadline = System.currentTimeMillis() + timeoutMs
      def loop: IO[PresenceDialRound] =
        IO.defer {
          ps.lastDialRound(id) match
            case Some(r) if pred(r) => IO.pure(r)
            case other =>
              if System.currentTimeMillis() > deadline then
                IO.raiseError(
                  new AssertionError(
                    s"$id 的轮记录未在 ${timeoutMs}ms 内满足判据；最后读到 ${other.map(_.toString).getOrElse("None")}"
                  )
                )
              else IO.sleep(20.millis).flatMap(_ => loop)
        }
      loop
    }

  // ===== P3 源码护栏：生产装配必须接共用可达性真源 =====

  test("P3 护栏：GatewayMain 的生产装配必须显式 reachLedger = Some(P2pReachability.current)") {
    val src = sourceOf("src/main/scala/nebflow/gateway/GatewayMain.scala")
    val needle = "reachLedger = Some(nebflow.core.tools.P2pReachability.current)"
    assert(src.contains(needle), "GatewayMain 未接共用可达性真源 ⇒ 探测税/拨号税会重新各付一遍（P3 破防）")
    val at = src.indexOf(needle)
    val ctorAt = src.lastIndexOf("new nebflow.neblink.relay.NeblinkPresenceService", at)
    assert(
      ctorAt >= 0 && (at - ctorAt) < 600,
      "reachLedger 装配点必须落在 NeblinkPresenceService 的构造调用里"
    )
    assert(!src.contains("LegacySerial"), "生产装配面不得出现 LegacySerial（改前口径只给实测夹具）")
  }

  test("P2 护栏：拨号体只有 dialBlocking 一个线程归属收口点，且不许直连 IO.blocking") {
    val src = sourceOf("src/main/scala/nebflow/neblink/relay/NeblinkPresenceService.scala")
    val poolSrc = sourceOf("src/main/scala/nebflow/neblink/relay/PresenceDialPool.scala")
    assert(src.contains("private def dialBlocking"), "拨号线程归属的单一收口点缺失")
    assert(src.contains("PresenceDialPool.run(where)(guarded())"), "生产拨号体必须提交 PresenceDialPool")
    assert(
      !src.contains("IO.blocking(openConnection"),
      "拨号体不允许直接 IO.blocking —— 那会把阻塞拨号放回 compute 线程（P2 破防）"
    )
    assert(poolSrc.contains("def requireDialThread"), "线程占用断言（requireDialThread）缺失 —— 防改回的护栏没了")
    assert(
      poolSrc.contains("throw new PresenceDialThreadViolation"),
      "线程占用断言必须**炸红**（不是只打一行警告），否则改回 IO.blocking 会静默退化"
    )
  }

  // ===== P1 并发化 =====

  test("P1 读数：并发上界 = max(单候选预算)，串行上界 = Σ（纯映射对照）") {
    val eps = List("http://100.64.0.5:1", "http://192.168.1.7:1")
    assertEquals(
      PresenceDialSchedule.Production.roundUpperBoundMs(eps, DialBudget.Default),
      3_000L,
      "并发口径 = max(tailnet 3000, lan 1500) = 3000 —— KAI 形态改后读数（改前 4500ms）"
    )
    assertEquals(
      PresenceDialSchedule.LegacySerial.roundUpperBoundMs(eps, DialBudget.Default),
      4_500L,
      "串行口径 = Σ = 4500 —— 改前读数（KAI 批实测的原值）"
    )
    assertEquals(PresenceDialSchedule.Production.maxConcurrentPerRound, 4, "并发度上限 = 4（不是无界）")
    assertEquals(
      NeblinkPresenceService.concurrentDialRoundUpperBoundMs(eps, DialBudget.Default),
      3_000L,
      "梯子时长读数必须改用并发公式（否则报给作者的上界比实际慢一倍）"
    )
  }

  test("P1 实拨：并发轮 ≈ max（1×预算），串行对照臂 ≈ Σ（2×预算）——同方法只翻调度开关".tag(munit.Tag("host-smoke"))) {
    assume(sys.env.get("STAND_HOST_SMOKE").contains("1"), "set STAND_HOST_SMOKE=1 for network timing smoke")
    assume(!windowsBlackholeUnsupported, "Windows 环回无黑洞语义 ⇒ 黑洞对照臂跳过")
    val port = closedPort()
    val eps = List(s"http://127.0.0.2:$port", s"http://127.0.0.3:$port")

    def measure(sched: PresenceDialSchedule): IO[Long] =
      withStack(port, schedule = sched) { (_, ps) =>
        for
          t0 <- IO.monotonic
          _ <- ps.connect(peer("race", eps))
          ms <- IO.monotonic.map(d => (d - t0).toMillis)
          _ <- awaitRoundWhere(ps, "race", _.attempted.size == 2)
        yield ms
      }

    (for
      concurrentMs <- measure(PresenceDialSchedule.Production)
      legacyMs <- measure(PresenceDialSchedule.LegacySerial)
    yield (concurrentMs, legacyMs)).map { case (concurrentMs, legacyMs) =>
      assert(
        concurrentMs >= 1_300L && concurrentMs <= 3_200L,
        s"并发一轮实测 ${concurrentMs}ms 应 ≈ max(单候选预算 1500ms)（改前 Σ=3000ms）"
      )
      assert(
        legacyMs >= 2_700L && legacyMs <= 4_800L,
        s"改前口径（对照臂）实测 ${legacyMs}ms ≈ Σ 预算 3000ms —— 税的真实原值"
      )
      assert(
        concurrentMs < legacyMs * 0.8,
        s"并发 ${concurrentMs}ms 必须显著快于串行 ${legacyMs}ms（本批收益的机器可判形式）"
      )
    }
  }

  test("P1 fake legs: parallel candidates start before release, serial starts after release") {
    val port = closedPort()
    val eps = List(s"http://127.0.0.2:$port", s"http://127.0.0.3:$port")
    def check(schedule: PresenceDialSchedule, parallel: Boolean): IO[Unit] =
      val first = new java.util.concurrent.CountDownLatch(1)
      val second = new java.util.concurrent.CountDownLatch(1)
      val releaseA = new java.util.concurrent.CountDownLatch(1)
      val releaseB = new java.util.concurrent.CountDownLatch(1)
      val events = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val hook: (String, Int, Long) => Unit = (host, _, _) =>
        val a = host.endsWith(".2")
        events.add(if a then "start-a" else "start-b")
        (if a then first else second).countDown()
        if !(if a then releaseA else releaseB).await(8, java.util.concurrent.TimeUnit.SECONDS) then
          throw new IllegalStateException("test gate timed out")
        events.add(if a then "release-a" else "release-b")
        throw new java.util.concurrent.TimeoutException("fake timeout")
      withStack(port, schedule = schedule, dialHook = hook) { (_, ps) =>
        (for
          fiber <- ps.connect(peer("event-order", eps)).start
          _ <- IO.blocking(assert(first.await(5, java.util.concurrent.TimeUnit.SECONDS), "first leg did not start"))
          _ <- IO.blocking {
            if parallel then assert(second.await(5, java.util.concurrent.TimeUnit.SECONDS), "parallel leg did not start")
            else assertEquals(second.getCount, 1L, "serial second leg cannot start before first releases")
            releaseA.countDown()
            assert(second.await(5, java.util.concurrent.TimeUnit.SECONDS), "second leg did not start")
            releaseB.countDown()
          }
          _ <- fiber.joinWithNever
          round <- IO(roundOf(ps, "event-order"))
          _ <- IO {
            import scala.jdk.CollectionConverters.*
            val seen = events.iterator().asScala.toList
            if parallel then assert(seen.indexOf("start-b") < seen.indexOf("release-a"))
            else assert(seen.indexOf("release-a") < seen.indexOf("start-b"))
            assertEquals(round.failures.map(_._2), List(nebflow.neblink.relay.DialFailureClass.Timeout, nebflow.neblink.relay.DialFailureClass.Timeout))
          }
        yield ()).guarantee(IO { releaseA.countDown(); releaseB.countDown() })
      }
    check(PresenceDialSchedule.Production, true) *> check(PresenceDialSchedule.LegacySerial, false)
  }

  test("P1 并发度有界：6 候选一轮只拨 4，其余截断留痕（truncated=true）等下一拍") {
    assume(!windowsBlackholeUnsupported, "Windows 环回无黑洞语义 ⇒ 本组跳过")
    val port = closedPort()
    val eps = (2 to 7).map(i => s"http://127.0.0.$i:$port").toList
    withStack(port, budget = DialBudget(classify = _ => DialAddressClass.Lan)) { (_, ps) =>
      for
        _ <- ps.connect(peer("capped", eps))
        round <- awaitRoundWhere(ps, "capped", _.attempted.size == 4)
      yield round
    }.map { round =>
      assertEquals(round.attempted.size, 4, "一轮最多拨 4 个（并发度上限，绝不无界并发）")
      assertEquals(round.truncated, true, "超出上限的候选本轮截断（下一拍继续）")
      assertEquals(round.attempted, eps.take(4), "截断保持候选的择优序（只砍队尾，不乱序）")
    }
  }

  test("P1 首胜：死者在前 + 活者在后，活者拨通即赢，且不等黑洞候选付满预算") {
    val dead = closedPort()
    val srv = new NeblinkPresenceDialConcurrencySpec.FixtureServer(handshakeDelayMs = 400L)
    val epDead = s"http://127.0.0.2:$dead"
    val eps = List(epDead, srv.endpoint)
    withStack(srv.port, budget = DialBudget(classify = _ => DialAddressClass.Lan)) { (_, ps) =>
      val p = peer("firstwin", eps)
      for
        t0 <- IO.monotonic
        _ <- ps.connect(p)
        ms <- IO.monotonic.map(d => (d - t0).toMillis)
        round <- awaitRoundWhere(ps, "firstwin", _.attempted.nonEmpty)
        chosen <- IO(ps.chosenEndpoint("firstwin"))
        connected <- IO(ps.isConnected("firstwin"))
      yield (ms, round, chosen, connected)
    }.guarantee(IO(srv.close())).map { case (ms, round, chosen, connected) =>
      assertEquals(round.attempted, eps, "两个候选都拨了（并发全量起跑），且按择优序收账")
      assertEquals(chosen, Some(srv.endpoint), "C1 择优 = 拨通的那个（不是队首）")
      assert(connected, "连接已建立")
      assert(ms < 2_900L, s"首胜一轮实测 ${ms}ms —— 拨通即返回，不等黑洞候选付满预算（Σ 口径 ≈1900ms+）")
    }
  }

  // ===== P2 搬离 compute-blocker =====

  test("C3 收口确定性：并发全败时 dialStatus.endpoint 恒 = 择优序末尾候选（一轮写一次，不是竞速）") {
    assume(!windowsBlackholeUnsupported, "Windows 环回无黑洞语义 ⇒ 本组跳过")
    val port = closedPort()
    val epA = s"http://127.0.0.2:$port"
    val epB = s"http://127.0.0.3:$port"
    withStack(port, budget = DialBudget(classify = _ => DialAddressClass.Lan)) { (_, ps) =>
      val p = peer("c3-det", List(epA, epB))
      // 5 拍全败：并发口径下两个候选**同时**失败，若各自写 dialStatus 就是「谁最后写完谁算」。
      // 改前实测（全量套件）：`NeblinkDialStatusRouteSpec` C3 间歇性读到 epA 而红。
      (1 to 5).toList.traverse { i =>
        ps.connect(p) *> IO(ps.dialStatus("c3-det")).map(st => (i, st))
      }
    }.map { samples =>
      val endpoints = samples.map { case (_, st) => st.map(_.endpoint).getOrElse("<none>") }.distinct
      assertEquals(endpoints, List(epB), s"5 拍必须全部收口到择优序末尾（实测 ${endpoints.mkString(", ")}）")
      assert(
        samples.forall(_._2.exists(_.error.exists(_.nonEmpty))),
        "每一拍都必须留下失败原因（C3 归因面不得因并发化而丢）"
      )
    }
  }

  test("P2 实拨：生产口径拨号体落在 presence-dial-*；改前口径落在 io-compute-*（红证据）") {
    val port = closedPort()
    val ep = s"http://127.0.0.1:$port"

    def dialThreadOf(sched: PresenceDialSchedule): IO[(String, (Int, Int, Long, Long))] =
      withStack(port, schedule = sched) { (_, ps) =>
        for
          _ <- IO(ps.resetDialThreadStats())
          _ <- ps.connect(peer("thread", List(ep)))
          _ <- awaitRoundWhere(ps, "thread", _.attempted.nonEmpty)
          name <- IO(ps.lastDialThreadName)
          stats <- IO(ps.dialThreadStats)
        yield (name, stats)
      }

    (for
      prod <- dialThreadOf(PresenceDialSchedule.Production)
      legacy <- dialThreadOf(PresenceDialSchedule.LegacySerial)
    yield (prod, legacy)).map { case ((prodThread, prodStats), (legacyThread, legacyStats)) =>
      assert(
        PresenceDialPool.isDialThreadName(prodThread),
        s"生产口径拨号体线程 = '$prodThread'，必须在 presence-dial-* 专用池上（P2）"
      )
      assert(!PresenceDialPool.isComputeThreadName(prodThread), s"生产口径不得占用 io-compute-*：'$prodThread'")
      assertEquals(prodStats._1, 1, "恰好一个拨号体（单候选）")
      assertEquals(prodStats._2, 0, "生产口径「落在 compute 线程上的拨号次数」必须恒 0（读数面硬断言）")
      assertEquals(prodStats._3, 0L, "生产口径「compute 线程上阻塞的毫秒数」必须恒 0")
      assert(
        PresenceDialPool.isComputeThreadName(legacyThread),
        s"改前口径拨号体线程 = '$legacyThread'，应落在 io-compute-*（这就是 compute-blocker 被占用的病灶）"
      )
      assertEquals(legacyStats._1, 1, "改前口径同样只拨一次")
      assertEquals(legacyStats._2, 1, "🔴 改前口径的拨号体**必然**落在 compute 线程上（红证据的机器可判形式）")
    }
  }

  test("P2 断言本体：拨号体跑在非专用池线程 ⇒ PresenceDialThreadViolation 当场炸红") {
    val thrown = intercept[PresenceDialThreadViolation](PresenceDialPool.requireDialThread("spec-probe"))
    assert(thrown.getMessage.contains("presence-dial"), s"错误信息必须可判读: ${thrown.getMessage}")
    PresenceDialPool.run("spec-probe")(PresenceDialPool.requireDialThread("spec-probe-inside")).unsafeRunSync()
  }

  // ===== P3 单一可达性真源 =====

  test("P3 零成本轮：共用真源命中负缓存 ⇒ 不拨任何候选、0ms、不覆盖 dialStatus") {
    val ledger = new P2pReachability
    val port = closedPort()
    val ep = s"http://127.0.0.1:$port"
    withStack(port, reachLedger = Some(ledger)) { (_, ps) =>
      val p = peer("shared", List(ep))
      for
        n <- IO(ledger.noteFailure(p.deviceId, P2pPathDecision.addressKey(p), System.currentTimeMillis()))
        _ <- IO(ps.resetDialThreadStats())
        t0 <- IO.monotonic
        _ <- ps.connect(p)
        ms <- IO.monotonic.map(d => (d - t0).toMillis)
        round <- IO(roundOf(ps, "shared"))
        st <- IO(ps.dialStatus("shared"))
        stats <- IO(ps.dialThreadStats)
        ledgerStats <- IO(ledger.stats)
      yield (n, ms, round, st, stats, ledgerStats)
    }.map { case (n, ms, round, st, stats, ledgerStats) =>
      assertEquals(n, 1)
      assertEquals(round.attempted, Nil, "命中共用负缓存 ⇒ 一个字节都不拨")
      assert(ms < 200L, s"零成本轮实测 ${ms}ms（应到 0ms 量级；改前同结论要付满预算）")
      assertEquals(stats._1, 0, "零成本轮不得产生任何拨号体（读数面）")
      assertEquals(st, None, "没拨就不写 dialStatus（C3 语义：那条记录的含义是『拨过』）")
      assert(ledgerStats._3 >= 1, "旁路计数已入账（读数面）")
    }
  }

  test("P3 写面：真拨全不通 ⇒ 失败写回**共用**账本；地址集变化 ⇒ 判据失效（不跨地址集复用）") {
    val ledger = new P2pReachability
    val port = closedPort()
    val ep = s"http://127.0.0.1:$port"
    withStack(port, reachLedger = Some(ledger)) { (_, ps) =>
      val p = peer("writer", List(ep))
      val other = p.copy(address = "http://10.9.8.7:1", endpoints = List("http://10.9.8.7:1"))
      for
        _ <- ps.connect(p)
        _ <- awaitRoundWhere(ps, "writer", _.attempted.nonEmpty)
        hit <- IO(ledger.shouldSkip(p.deviceId, P2pPathDecision.addressKey(p), System.currentTimeMillis()))
        notHit <- IO(ledger.shouldSkip(p.deviceId, P2pPathDecision.addressKey(other), System.currentTimeMillis()))
      yield (hit, notHit)
    }.map { case (hit, notHit) =>
      assert(hit, "presence 拨号全不通的结论必须写进共用账本（文件腿零成本旁路的依据）")
      assert(!notHit, "地址集变化 ⇒ 负证据失效（与文件腿同一条规则）")
    }
  }

  test("P3 成功清账：任一成功 ⇒ 该设备负证据立刻清（含旧地址集的条目，noteSuccess 按 deviceId 清）") {
    val ledger = new P2pReachability
    val deadPort = closedPort()
    val srv = new NeblinkPresenceDialConcurrencySpec.FixtureServer()
    withStack(deadPort, reachLedger = Some(ledger)) { (_, ps) =>
      val deadPeer = peer("clearer", List(s"http://127.0.0.1:$deadPort"))
      val livePeer = deadPeer.copy(address = srv.endpoint, endpoints = List(srv.endpoint))
      for
        _ <- ps.connect(deadPeer) // ① 全不通 ⇒ 写负证据
        _ <- awaitRoundWhere(ps, "clearer", _.attempted.nonEmpty)
        before <- IO(ledger.shouldSkip("clearer", P2pPathDecision.addressKey(deadPeer), System.currentTimeMillis()))
        _ <- ps.connect(livePeer) // ② 地址集变了 ⇒ 判据不命中 ⇒ 真拨 ⇒ 拨通
        _ <- awaitRoundWhere(ps, "clearer", _ => ps.isConnected("clearer"))
        after <-
          IO(ledger.shouldSkip("clearer", P2pPathDecision.addressKey(deadPeer), System.currentTimeMillis()))
      yield (before, after)
    }.guarantee(IO(srv.close())).map { case (before, after) =>
      assert(before, "① 全不通 ⇒ 共用账本有条目")
      assert(!after, "② 拨通 ⇒ noteSuccess 清账（负证据不可跨成功存活）")
    }
  }

  // ===== P4 结构化日志（行数纪律）=====

  test("P4 结构化行：一条 [presence-dial] 带齐五项读数，决策不变只落 DEBUG 不再发 INFO") {
    val port = closedPort()
    val ep = s"http://127.0.0.1:$port"
    val action = withStack(port) { (_, ps) =>
      val p = peer("logged", List(ep))
      for
        _ <- ps.connect(p)
        first <- awaitRoundWhere(ps, "logged", _.attempted.nonEmpty)
        _ <- ps.connect(p) // 同决策第二拍（新轮）
        second <- awaitRoundWhere(ps, "logged", _.atMs > first.atMs)
      yield (first, second)
    }
    withPresenceLogs(action).map { case (_, lines) =>
      val structured = lines.filter(_.contains("[presence-dial]"))
      assertEquals(
        structured.count(_.contains("INFO")),
        1,
        s"决策迁移边恰一条 INFO；实际全部行：${structured.mkString(" | ")}"
      )
      val head = structured.find(_.contains("INFO")).getOrElse("")
      assert(head.contains("endpoints=1"), "端点数读数")
      assert(head.contains("dialed=1"), "实拨数读数")
      assert(head.contains("concurrent=true"), "调度口径读数")
      assert(head.contains("pool=presence-dial-*"), "线程归属读数")
      assert(head.contains("cacheHit=false"), "缓存命中读数")
      assert(head.contains("decision=relay-no-answer"), "结论读数")
      assert(head.contains("dialMs="), "并发耗时读数")
    }
  }

  test("P4 零成本轮也留一条结构化行（decision=relay-negative-cache），重复决策不再发 INFO") {
    val ledger = new P2pReachability
    val port = closedPort()
    val ep = s"http://127.0.0.1:$port"
    val action = withStack(port, reachLedger = Some(ledger)) { (_, ps) =>
      val p = peer("cachedlog", List(ep))
      for
        _ <- IO(ledger.noteFailure(p.deviceId, P2pPathDecision.addressKey(p), System.currentTimeMillis()))
        _ <- ps.connect(p)
        _ <- ps.connect(p) // 同决策第二拍（仍命中）
      yield ()
    }
    withPresenceLogs(action).map { case (_, lines) =>
      val structured = lines.filter(_.contains("[presence-dial]"))
      assertEquals(structured.count(_.contains("INFO")), 1, "决策不变 ⇒ 只有一条 INFO（首条），其余 DEBUG")
      val head = structured.find(_.contains("INFO")).getOrElse("")
      assert(head.contains("cacheHit=true"), "缓存命中读数")
      assert(head.contains("decision=relay-negative-cache"), "零成本轮结论")
      assert(head.contains("dialMs="), "零成本轮也有耗时读数（≈0）")
    }
  }

end NeblinkPresenceDialConcurrencySpec

/** 本 spec 的环回 WS 握手夹具（`PresenceWsFixture` 的同款最小形态；独立副本避免跨 spec 耦合）。 */
object NeblinkPresenceDialConcurrencySpec:

  final class FixtureServer(handshakeDelayMs: Long = 0L):
    private val serverSocket = new java.net.ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val clients = new CopyOnWriteArrayList[java.net.Socket]()
    private val handlers = new CopyOnWriteArrayList[Thread]()
    private val servedCount = new AtomicInteger(0)
    @volatile private var stopped = false

    private val acceptorThread: Thread =
      val body: Runnable = () =>
        while !stopped do
          try
            val s = serverSocket.accept()
            clients.add(s)
            val h = new Thread(() => serve(s))
            h.setDaemon(true)
            handlers.add(h)
            h.start()
          catch case _: Throwable => stopped = true
      val t = new Thread(body, "overhaul-fixture-accept")
      t.setDaemon(true)
      t.start()
      t

    def port: Int = serverSocket.getLocalPort
    def endpoint: String = s"http://127.0.0.1:$port"
    def served: Int = servedCount.get()

    private def serve(s: java.net.Socket): Unit =
      try
        val in = s.getInputStream
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
            case _         => 0
          if state < 4 then b = in.read()
        servedCount.incrementAndGet()
        if handshakeDelayMs > 0L then Thread.sleep(handshakeDelayMs)
        if !stopped then
          val key = sb.toString.linesIterator
            .map(_.trim)
            .find(_.toLowerCase.startsWith("sec-websocket-key:"))
            .map(_.substring(18).trim)
            .getOrElse("")
          val accept = Base64.getEncoder.encodeToString(
            java.security.MessageDigest
              .getInstance("SHA-1")
              .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8))
          )
          s.getOutputStream.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
              s"Sec-WebSocket-Accept: $accept\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)
          )
          s.getOutputStream.flush()
          val sink = new Array[Byte](2048)
          while !stopped && in.read(sink) >= 0 do ()
      catch case _: Throwable => ()
      finally
        try s.close()
        catch case _: Throwable => ()

    def close(): Unit =
      stopped = true
      try serverSocket.close()
      catch case _: Throwable => ()
      clients.forEach(s =>
        try s.close()
        catch case _: Throwable => ()
      )
      handlers.forEach(_.interrupt())
  end FixtureServer

end NeblinkPresenceDialConcurrencySpec
