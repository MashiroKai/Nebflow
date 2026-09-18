package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.io.InputStream
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.*

/**
 * F-D 批（2026-09-17 presence 层拨号抖动）· 预算放宽 + 死端点剔除 + 重连梯兜底。
 *
 * 上游结论源 = `.nebflow/reports/20260917_devosc-diag.md` §7-F-D（现状：单候选 1500ms、
 * 余量 ≈2.7×RTT；消费点 `syncPeers` 每 45s 对无连接 peer 发 `IO.blocking` connect）。
 * 作者 2026-09-17 放行口径：**拨号预算放宽（按地址类别）+ 死端点剔除 + 重连梯兜底**。
 *
 * 判据与读法（逐条对应任务书的必达判据）：
 *  - ① 预算放宽可证 —— 纯映射读数 + **真拨号**读数（夹具把 WS 握手延迟到 2200ms 模拟
 *    RTT 放大：3000ms 档拨通、1500ms 档超时 ⇒ 现状「慢候选被误判为死」形态复现）；
 *  - ② 死端点剔除可证 —— 拒绝类 3 连击后该候选不再被拨（第 4 拍 `skippedSuppressed`）；
 *    负控 = 活端点在任何一拍都不得被剔除、且每拍都要被拨；
 *  - ③ 重连梯兜底可证 —— 退避曲线/封顶/收手时长上界读数 + 收手复位（恢复路径③）+
 *    名册变化复位（恢复路径②）+ TTL 到期复位（恢复路径①，用注入的短 TTL 实测）+
 *    **双候选全超时 ≠ 判死**（2 拍全超时后零剔除、peer 仍在册）；
 *  - ④ sync 拍一致性 —— 拍子本体不阻塞（拨号在 fork 的腿里）+ 拨号腿实测 ≈Σ 预算，
 *    且机器可判不变量 `单轮预算 12s < 45s 拍` ⇒ 不跨拍重叠。
 *
 * 🔴 本 spec **不引入真实拨号**：只打本机环回（127.0.0.1 上的夹具 / 127.0.0.2·3 的
 * 环回别名黑洞）。拨号目标端口 = **候选串自带的端口**（F-1，presdial 批 2026-09-19；
 * 改前是「丢端口 ⇒ 用本服务的 `serverPort`」）。本 spec 的夹具一律把候选端口与
 * `serverPort` 取同一个值（`srv.port`）⇒ 两种形态下读数相同、断言不变；
 * 「候选端口 ≠ serverPort」这条面由 `NeblinkPresenceServiceSpec` 钉住。
 *
 * 已实测的口径（本轮现读，非转述）：
 *  - 环回连通性探针：`127.0.0.1` 无监听 = **立刻拒绝**（`ECONNREFUSED`）；
 *    `127.0.0.2` / `127.0.0.3` = **永不回包**（2.5s 超时）⇒ 分别充当拒绝类与超时类夹具；
 *  - JDK WS 建连失败的**异常链**（`WsRefuseProbe`，拒绝类）：
 *    `ExecutionException → ConnectException(msg=null) → ClosedChannelException(msg=null)`
 *    ⇒ ① 判定必须看**整条链**（只看最深处会把强信号降级成 Other）；
 *      ② `ConnectException.getMessage` 在 macOS + JDK 23 上是 `null`，所以「原因可判读」
 *      的判据只能断言**可读类名/族**，不能断言 `"Connection refused"` 字样。
 *
 * 🔴 等待原语纪律：本 spec 的 `awaitConnected` / `awaitRoundWhere` 一律 `IO.defer` 包体 +
 * `flatMap(_ => loop)` 递归——`IO.sleep(x) *> loop` 的 `*>` 实参在**构造期**求值，会同步
 * 自递归出 `StackOverflowError`（`VirtualMachineError` 不是 `NonFatal` ⇒ 穿透 munit 挂死
 * sbt test 任务，本批首轮实测 900s 超时）。
 */
class NeblinkPresenceDialBudgetSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-fdpres-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ===== harness =====

  private def withStack[A](
    serverPort: Int,
    budget: DialBudget = DialBudget.Default,
    eviction: EvictionPolicy = EvictionPolicy.Default
  )(use: (NeblinkService, NeblinkPresenceService) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(serverPort, dispatcher, 300.millis)
        ps = new NeblinkPresenceService(ms, serverPort, budget, eviction)(dispatcher)
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

  /** 无监听的环回端口 ⇒ 立刻拒绝（拒绝类失败）。 */
  private def closedPort(): Int =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  private def roundOf(ps: NeblinkPresenceService, id: String): PresenceDialRound =
    ps.lastDialRound(id).getOrElse(fail(s"未记录到 $id 的拨号轮（F-D 读数面缺失）"))

  /**
   * 轮询等待「判据满足」。
   *
   * 🔴 三个必须（全部是实测踩出来的，不是预防性洁癖）：
   * ① 判定与递归都包在 `IO.defer` 里——`IO.sleep(x) *> loop` 的 `*>` 实参在**构造期**
   *    就被求值，`loop` 会同步自递归（`StackOverflowError` 属 `VirtualMachineError`，
   *    `NonFatal` 兜不住 ⇒ 穿透 munit 把 sbt 的 test 任务挂死，本批首轮实测 900s 超时）；
   * ② 判定必须在 IO **运行期**做——构造期求值会把「还没发生的事」当成已成立
   *    （先例：`onClosed` 之前的 `isConnected` 读到 true，等待被静默跳过）；
   * ③ **deadline 也必须在运行期取**——上游 `*>` 会在构造期把整条 `driveReconnectRounds`
   *    的 5 次迭代一次性建好，若 deadline 在构造期算，第 5 轮的窗口在它开始跑之前就已经
   *    耗尽（实测：「未在 8000ms 内接通」而日志显示那一轮其实接通了）。
   */
  private def awaitConnected(ps: NeblinkPresenceService, id: String, timeoutMs: Long = 8_000L): IO[Unit] =
    IO.defer {
      val deadline = System.currentTimeMillis() + timeoutMs
      def loop: IO[Unit] =
        IO.defer {
          if ps.isConnected(id) then IO.unit
          else if System.currentTimeMillis() > deadline then
            IO.raiseError(new AssertionError(s"$id 未在 ${timeoutMs}ms 内接通"))
          else IO.sleep(20.millis).flatMap(_ => loop)
        }
      loop
    }

  /** 等轮记录满足判据（同步拍/梯子腿是异步的 ⇒ 用内容等，不赌 atMs 单调）。惰性口径同上。 */
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
                  new AssertionError(s"$id 的轮记录未在 ${timeoutMs}ms 内满足判据；最后读到 ${other.map(_.toString).getOrElse("None")}")
                )
              else IO.sleep(25.millis).flatMap(_ => loop)
        }
      loop
    }

  /** 触发「连接意外关闭 ⇒ 梯子立即重拨」若干轮并逐轮收记录。 */
  private def driveReconnectRounds(
    ps: NeblinkPresenceService,
    p: PeerInfo,
    acc: scala.collection.mutable.ListBuffer[PresenceDialRound],
    n: Int
  ): IO[Unit] =
    if n <= 0 then IO.unit
    else
      IO(ps.onClosed(p.deviceId, p)) *> awaitConnected(ps, p.deviceId) *>
        IO.sleep(80.millis) *>
        IO(acc += roundOf(ps, p.deviceId)) *>
        driveReconnectRounds(ps, p, acc, n - 1)

  // ===== ① 预算放宽：映射读数 =====

  test("F-D ① 地址类别 → 预算映射 + 单轮上界（纯读数；与现状 1500ms 对照）") {
    val b = DialBudget.Default
    assertEquals(b.budgetMsFor("http://100.64.0.5:8080"), 3_000L, "tailnet(CGNAT 100.64/10) = 放宽档")
    assertEquals(b.budgetMsFor("http://100.127.255.1:8080"), 3_000L, "CGNAT 上沿仍在 /10 内")
    assertEquals(b.budgetMsFor("http://100.128.0.1:8080"), 2_500L, "100.128 已出 /10 ⇒ 未知类别档")
    assertEquals(b.budgetMsFor("http://192.168.1.7:8080"), 1_500L, "RFC1918 维持现状（事故里被黑洞的正是 LAN 候选）")
    assertEquals(b.budgetMsFor("http://10.0.0.9:8080"), 1_500L, "RFC1918 (10/8) 维持现状")
    assertEquals(b.budgetMsFor("http://172.16.3.4:8080"), 1_500L, "RFC1918 (172.16/12) 维持现状")
    assertEquals(b.budgetMsFor("http://127.0.0.1:8080"), 1_500L, "环回维持现状")
    assertEquals(b.budgetMsFor("http://203.0.113.9:8080"), 2_500L, "公网 IPv4 ⇒ 未知类别档")
    assertEquals(b.budgetMsFor("http://peer.example:8080"), 2_500L, "主机名 ⇒ 未知类别档")
    // 量化：诊断报告给的现状「1500ms，余量 ≈2.7×RTT」⇒ RTT ≈ 555ms；
    // 一次 presence 拨号 ≈ 2~3×RTT（TCP + upgrade + 对端路由）。
    assert(3_000.0 / 555.0 >= 5.0, "tailnet 放宽档 ≥5×RTT(555ms)")
    assert(1_500.0 / 555.0 < 3.0, "现状 1500ms < 3×RTT ⇒ 2~3×RTT 的握手悬在临界点上（本次抖动的直接形态）")
    // 判据④的机器可判不变量
    assertEquals(b.roundUpperBoundMs(List("http://100.64.0.5:1", "http://192.168.1.7:1")), 4_500L)
    assertEquals(b.roundUpperBoundMs(List.fill(6)("http://100.64.0.5:1")), 12_000L, "Σ 超上界 ⇒ 封顶")
    assert(b.roundBudgetMs < NeblinkPresenceService.SyncBeatFloorMs, "单轮预算必须 < 同步拍 45s")
  }

  // ===== ① 预算放宽：真拨号读数 =====

  test("F-D ① 实拨：3000ms 档内握手 2200ms 的候选**不再被误判为死**（改前 1500ms 档必判死）") {
    val srv = new PresenceWsFixture(handshakeDelayMs = 2_200L)
    withStack(srv.port, budget = DialBudget(classify = _ => DialAddressClass.Tailnet)) { (_, ps) =>
      val ep = srv.endpoint
      val p = peer("slow", List(ep))
      for
        // 预热（**不参与断言**）：JVM 内**首次** WS 建连把 java.net.http/WebSocket 栈的类加载
        // 放进了 `buildAsync` 的窗口里（实测 ~1.4s），而 3s 预算中只有 2.2s 留给握手 ⇒ 冷启动
        // 会把这条读数打成与「预算放宽」无关的 flaky timeout（首轮实测就红在这里）。
        _ <- ps.connect(peer("warmup", List(ep)))
        t0 <- IO.monotonic
        _ <- ps.connect(p)
        elapsed <- IO.monotonic.map(d => (d - t0).toMillis)
        round <- IO(roundOf(ps, "slow"))
        st <- IO(ps.dialStatus("slow"))
        chosen <- IO(ps.chosenEndpoint("slow"))
        connected <- IO(ps.isConnected("slow"))
      yield (ep, round, st, chosen, connected, elapsed)
    }.guarantee(IO(srv.close())).map { case (ep, round, st, chosen, connected, elapsed) =>
      assertEquals(round.attempted, List(ep), "该候选被拨（未被剔除）")
      assertEquals(round.skippedSuppressed, Nil)
      assertEquals(round.truncated, false)
      assertEquals(st.map(_.error), Some(None), "拨通 ⇒ 无错误（放宽前的 1500ms 档此处必是 timeout）")
      assertEquals(chosen, Some(ep), "择优结果 = 该候选")
      assert(connected, "连接已建立")
      assert(elapsed >= 2_000L, s"实测 $elapsed ms 应反映 2200ms 握手（不是秒退）")
      // 预热之后这条上界就是**真读数**：整次拨号（含建连开销）必须落在 3000ms 预算内。
      // 预算真的生效这件事还有**同夹具的配对读数**做证（下一个用例：1500ms 档 ⇒ timeout）。
      assert(elapsed <= 3_000L, s"实测 $elapsed ms 应落在 3000ms 预算内")
    }
  }

  test("F-D ① 对照：同一夹具在 1500ms 档（LAN 现状档）下被判死 —— 改前形态复现") {
    val srv = new PresenceWsFixture(handshakeDelayMs = 2_200L)
    withStack(srv.port, budget = DialBudget(classify = _ => DialAddressClass.Lan)) { (_, ps) =>
      val ep = srv.endpoint
      val p = peer("slow1500", List(ep))
      for
        t0 <- IO.monotonic
        _ <- ps.connect(p)
        elapsed <- IO.monotonic.map(d => (d - t0).toMillis)
        round <- IO(roundOf(ps, "slow1500"))
        st <- IO(ps.dialStatus("slow1500"))
        chosen <- IO(ps.chosenEndpoint("slow1500"))
        connected <- IO(ps.isConnected("slow1500"))
      yield (round, st, chosen, connected, elapsed)
    }.guarantee(IO(srv.close())).map { case (round, st, chosen, connected, elapsed) =>
      assertEquals(round.attempted.size, 1)
      assertEquals(st.flatMap(_.error), Some("timeout after 1500ms"), "预算 1500ms ⇒ 超时（候选其实活着）")
      assertEquals(chosen, None, "判死 ⇒ 无择优结果（正是本次抖动的观测形态）")
      assertEquals(connected, false)
      assert(elapsed >= 1_400L && elapsed <= 2_100L, s"实测 $elapsed ms ≈ 1500ms 预算")
    }
  }

  // ===== ② 死端点剔除 + 负控 =====

  test("F-D ② 剔除：拒绝类 3 连击后该候选不再被拨；全部剔除时只半开探测最优候选") {
    val dead = closedPort()
    val dead2 = closedPort()
    assert(dead != dead2, "两个候选必须是不同端点，否则剔除断言无意义")
    val epA = s"http://127.0.0.1:$dead"
    val epB = s"http://127.0.0.1:$dead2"
    withStack(dead) { (_, ps) =>
      val p = peer("evict", List(epA, epB))
      for
        _ <- ps.connect(p)
        r1 <- IO(roundOf(ps, "evict"))
        st <- IO(ps.dialStatus("evict"))
        _ <- ps.connect(p)
        r2 <- IO(roundOf(ps, "evict"))
        _ <- ps.connect(p)
        r3 <- IO(roundOf(ps, "evict"))
        _ <- ps.connect(p)
        r4 <- IO(roundOf(ps, "evict"))
      yield (r1, r2, r3, r4, st)
    }.map { case (r1, r2, r3, r4, st) =>
      // 判定面 = 失败分类（不是平台措辞）：实测 macOS + JDK 23 下拒绝链是
      // `ExecutionException → ConnectException(msg=null) → ClosedChannelException`，
      // 只看最深处会把强信号降级成 Other ⇒ 剔除永不触发。
      assertEquals(
        r1.failures.map(_._2),
        List(DialFailureClass.Refused, DialFailureClass.Refused),
        s"拒绝类失败必须被判成强信号（否则剔除用错档）：${r1.failures}"
      )
      assert(
        st.flatMap(_.error).exists(e => e.contains("Connect") || e.toLowerCase.contains("refused")),
        s"拒绝类原因必须可判读（JDK 的 ConnectException msg=null ⇒ 退化为类名）:${st.flatMap(_.error)}"
      )
      assertEquals(r1.attempted, List(epA, epB), "首拍两个候选都拨")
      assertEquals(r1.skippedSuppressed, Nil, "首拍不得剔除（未达判据）")
      assertEquals(r2.attempted, List(epA, epB), "第 2 拍仍在拨")
      assertEquals(r2.skippedSuppressed, Nil, "第 2 拍仍未剔除（门槛 = 3 连击）")
      assertEquals(r3.attempted, List(epA, epB), "第 3 拍（凑满 3 连击）")
      assertEquals(r4.skippedSuppressed, List(epB), "第 4 拍：次优候选被剔除 ⇒ 本轮不再拨它")
      assertEquals(r4.attempted, List(epA), "全部被剔除 ⇒ 只半开探测最优候选（不空转、也不判死）")
      assertEquals(r4.truncated, false)
    }
  }

  test("F-D ② 负控：健康端点跨多拍从不被剔除，且每拍都必须被拨（黑洞候选第 5 次超时后才剔除）") {
    val srv = new PresenceWsFixture() // 立即握手 ⇒ 活着
    withStack(srv.port) { (_, ps) =>
      val deadEp = s"http://127.0.0.2:${srv.port}" // 环回别名黑洞：TCP 永不回包（超时类）
      val liveEp = srv.endpoint
      val p = peer("mix", List(deadEp, liveEp))
      val acc = scala.collection.mutable.ListBuffer.empty[PresenceDialRound]
      for
        _ <- ps.connect(p)
        _ <- awaitConnected(ps, "mix")
        _ <- IO.sleep(80.millis)
        _ <- IO(acc += roundOf(ps, "mix"))
        _ <- driveReconnectRounds(ps, p, acc, 5)
      yield (liveEp, deadEp, acc.toList)
    }.guarantee(IO(srv.close())).map { case (liveEp, deadEp, all) =>
      assertEquals(all.size, 6, "首拍 + 5 拍重连 = 6 轮读数")
      assert(all.forall(r => !r.skippedSuppressed.contains(liveEp)), "🔴 活端点在任何一拍都不得被剔除（负控：不误剔除）")
      assert(all.forall(r => r.attempted.contains(liveEp)), "活端点每拍都必须被拨（含剔除生效后的拍）")
      assert(
        all.exists(_.skippedSuppressed.contains(deadEp)),
        s"黑洞端点在第 5 次超时后必须被剔除（时间轴读数）：${all.map(r => r.skippedSuppressed.size).mkString(",")}"
      )
      assertEquals(all.last.attempted, List(liveEp), "剔除生效后本轮只拨活端点")
      assertEquals(all.last.skippedSuppressed, List(deadEp))
    }
  }

  // ===== ③ 重连梯兜底 + 恢复语义 =====

  test("F-D ③ 语义：双候选全超时（2 拍 / 每候选 2 次超时）≠ 判死 —— 不剔除、仍在册、仍在拨") {
    val port = closedPort()
    val epA = s"http://127.0.0.2:$port"
    val epB = s"http://127.0.0.3:$port"
    withStack(port) { (ms, ps) =>
      val p = peer("blackhole", List(epA, epB))
      for
        // 先入册：判据「peer 仍在名册里」要有意义，前提是它本来就在册（connect 只在成功时
        // upsert，只拨不通不足以入册——首轮实测这条断言红在「压根没入册」而不是「被剔除」）。
        _ <- ms.upsertPeer(p)
        _ <- ps.connect(p)
        r1 <- IO(roundOf(ps, "blackhole"))
        _ <- ps.connect(p)
        r2 <- IO(roundOf(ps, "blackhole"))
        st <- IO(ps.dialStatus("blackhole"))
        listed <- ms.peers
        connected <- IO(ps.isConnected("blackhole"))
      yield (r1, r2, st, listed, connected)
    }.map { case (r1, r2, st, listed, connected) =>
      assertEquals(r1.attempted, List(epA, epB), "双候选全超时：两个都拨了")
      assertEquals(r1.skippedSuppressed, Nil, "🔴 一轮全超时不得判死")
      assertEquals(r2.attempted, List(epA, epB), "第 2 拍继续拨（每候选累计 2 次超时）")
      assertEquals(r2.skippedSuppressed, Nil, "🔴 两轮全超时（每候选 2 次）仍不得判死")
      assertEquals(r1.truncated, false)
      assertEquals(r2.truncated, false)
      assertEquals(st.flatMap(_.error), Some("timeout after 1500ms"), "超时类原因读数（LAN 档 1500ms）")
      assert(listed.exists(_.deviceId == "blackhole"), "peer 仍在名册里（无 peer 级「判死」语义）")
      assertEquals(connected, false)
      assertEquals(EvictionPolicy.Default.timeoutStrikes, 5, "超时类门槛（5 连击 ≈3.75min@45s 拍）远高于 2 拍 ⇒ 歧义信号不判死")
    }
  }

  test("F-D ③ 梯子参数/收手复位：退避曲线 + 真实时长上界读数 + 收手后死端点判定复位（恢复路径③）") {
    assertEquals(
      (0 to 7).map(NeblinkPresenceService.reconnectDelayMs).toList,
      List(0L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
      "退避曲线（0/1/2/4/8/16/30 秒，封顶）"
    )
    assert(
      NeblinkPresenceService.ReconnectDelayCapMs <= NeblinkPresenceService.SyncBeatFloorMs,
      "延迟封顶 ≤ 同步拍 ⇒ 梯子不比拍子慢（收手后最多再等 1 拍）"
    )
    assertEquals(
      NeblinkPresenceService.ladderDrainUpperBoundMs(20, 12_000L),
      451_000L + 240_000L,
      "收手时长上界 = Σ退避(451s) + 20×单轮预算(240s)（原注释『~5 min』已失真）"
    )
    val dead = closedPort()
    val dead2 = closedPort()
    val epA = s"http://127.0.0.1:$dead"
    val epB = s"http://127.0.0.1:$dead2"
    withStack(dead) { (_, ps) =>
      val p = peer("giveup", List(epA, epB))
      for
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        before <- IO(roundOf(ps, "giveup"))
        _ <- ps.connect(p)
        suppressed <- IO(roundOf(ps, "giveup"))
        _ <- ps.reconnectGaveUp(p, NeblinkPresenceService.ReconnectMaxAttempts)
        _ <- ps.connect(p)
        after <- IO(roundOf(ps, "giveup"))
      yield (before, suppressed, after)
    }.map { case (before, suppressed, after) =>
      assertEquals(before.skippedSuppressed, Nil, "剔除前：全候选都在轮转")
      assertEquals(suppressed.skippedSuppressed, List(epB), "第 4 拍：剔除生效")
      assertEquals(after.skippedSuppressed, Nil, "🔴 梯子收手复位 ⇒ 死端点判定清零（恢复路径③）")
      assertEquals(after.attempted, List(epA, epB), "复位后下一拍重新拨全部候选")
    }
  }

  test("F-D ②/③ 恢复路径②：名册候选集合变化 ⇒ 死端点判定复位（设备换网/换地址/换择优序）") {
    val dead = closedPort()
    val dead2 = closedPort()
    val epA = s"http://127.0.0.1:$dead"
    val epB = s"http://127.0.0.1:$dead2"
    withStack(dead) { (_, ps) =>
      val p = peer("rename", List(epA, epB))
      for
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        _ <- ps.syncPeers(List(p)) // 同集合 ⇒ 指纹不变
        same <- awaitRoundWhere(ps, "rename", _.skippedSuppressed == List(epB))
        _ <- ps.syncPeers(List(peer("rename", List(epB, epA)))) // 名册变了（择优序改变）
        after <- awaitRoundWhere(ps, "rename", _.skippedSuppressed.isEmpty)
      yield (same, after)
    }.map { case (same, after) =>
      assertEquals(same.skippedSuppressed, List(epB), "同集合 ⇒ 判定保持")
      assertEquals(after.skippedSuppressed, Nil, "名册变化 ⇒ 判定复位")
      assertEquals(after.attempted, List(epB, epA), "复位后按新名册序拨全部候选")
    }
  }

  test("F-D ② 恢复路径①：剔除有存活期（TTL 到期自动复位，注入 400ms 短 TTL 实测）") {
    val dead = closedPort()
    val dead2 = closedPort()
    val epA = s"http://127.0.0.1:$dead"
    val epB = s"http://127.0.0.1:$dead2"
    val shortTtl = EvictionPolicy(refusalStrikes = 3, timeoutStrikes = 5, suppressedTtlMs = 400L)
    withStack(dead, eviction = shortTtl) { (_, ps) =>
      val p = peer("ttl", List(epA, epB))
      for
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        _ <- ps.connect(p)
        suppressed <- IO(roundOf(ps, "ttl"))
        _ <- IO.sleep(600.millis) // TTL 到期
        _ <- ps.connect(p)
        revived <- IO(roundOf(ps, "ttl"))
      yield (suppressed, revived, shortTtl.suppressedTtlMs)
    }.map { case (suppressed, revived, ttl) =>
      assertEquals(ttl, 400L, "TTL 有界（判据：🔴 禁永久剔除）")
      assertEquals(suppressed.skippedSuppressed, List(epB), "剔除生效时：次优候选被跳过")
      assertEquals(revived.skippedSuppressed, Nil, "🔴 TTL 到期 ⇒ 自动复位并清零计数（恢复路径①）")
      assertEquals(revived.attempted, List(epA, epB))
    }
  }

  // ===== ④ sync 拍一致性 =====

  test("F-D ④ sync 拍一致性：拍子本体不阻塞、拨号腿上界 ≤12s < 45s 拍 ⇒ 不跨拍重叠（实拨读数）") {
    val port = closedPort()
    val epA = s"http://127.0.0.2:$port"
    val epB = s"http://127.0.0.3:$port"
    withStack(port) { (_, ps) =>
      val p = peer("beat", List(epA, epB))
      for
        t0 <- IO.monotonic
        _ <- ps.syncPeers(List(p)) // 真实同步拍入口（拨号在 fork 的腿里）
        beatMs <- IO.monotonic.map(d => (d - t0).toMillis)
        t1 <- IO.monotonic
        round <- awaitRoundWhere(ps, "beat", _.attempted.size == 2)
        dialMs <- IO.monotonic.map(d => (d - t1).toMillis)
      yield (beatMs, dialMs, round)
    }.map { case (beatMs, dialMs, round) =>
      assertEquals(round.attempted, List(epA, epB))
      assert(beatMs < 500L, s"syncPeers 本体必须立刻返回（拨号 fork 出去）: ${beatMs}ms")
      val bound = DialBudget.Default.roundUpperBoundMs(List(epA, epB))
      assertEquals(bound, 3_000L)
      assert(dialMs >= 2_500L, s"拨号腿实测 $dialMs ms ≈ 2×1500ms（预算真的被用满）")
      assert(dialMs <= bound + 1_600L, s"拨号腿实测 $dialMs ms 必须落在单轮上界 ${bound}ms 附近")
      assert(bound < NeblinkPresenceService.SyncBeatFloorMs, "单轮上界 < 45s 拍 ⇒ 一个 peer 的拨号腿不会跨到下一拍")
    }
  }

  // ===== ⑤ 零回归：成功路径 =====

  test("F-D ⑤ 零回归：首候选即通的成功路径行为与耗时不变（预算只作用于超时上界）") {
    val srv = new PresenceWsFixture()
    withStack(srv.port) { (_, ps) =>
      val ep = srv.endpoint
      val p = peer("ok", List(ep))
      for
        t0 <- IO.monotonic
        _ <- ps.connect(p)
        elapsed <- IO.monotonic.map(d => (d - t0).toMillis)
        round <- IO(roundOf(ps, "ok"))
        st <- IO(ps.dialStatus("ok"))
        chosen <- IO(ps.chosenEndpoint("ok"))
        connected <- IO(ps.isConnected("ok"))
        served <- IO(srv.served)
      yield (ep, round, st, chosen, connected, elapsed, served)
    }.guarantee(IO(srv.close())).map { case (ep, round, st, chosen, connected, elapsed, served) =>
      assertEquals(round.attempted, List(ep), "只拨首候选（C1 短路语义不变）")
      assertEquals(round.skippedSuppressed, Nil)
      assertEquals(round.truncated, false)
      assertEquals(st.map(_.error), Some(None), "C3 语义不变：成功记为无错误")
      assertEquals(chosen, Some(ep), "C1 择优写回不变")
      assert(connected)
      assertEquals(served, 1, "只发生一次拨号")
      assert(elapsed < 1_500L, s"成功路径耗时 $elapsed ms 与改前同量级")
    }
  }

end NeblinkPresenceDialBudgetSpec

/**
 * 最小 WS 握手夹具（**只在本机环回上**）：接受 TCP 后延迟 `handshakeDelayMs` 再回
 * `101 Switching Protocols`。
 *
 * 用途 = 在不引入真实跨机拨号的前提下模拟「RTT 放大 / 中继路径变慢」：
 * 延迟 < 候选预算 ⇒ 候选**活着**（拨通）；延迟 > 预算 ⇒ 走 `.get(budget)` 的超时分支。
 * 握手应答按 RFC 6455 计算 `Sec-WebSocket-Accept`（JDK HttpClient 会校验），否则
 * 建连会失败而不是成功——那会让「预算内不再误判为死」的读数变成假的。
 */
private final class PresenceWsFixture(handshakeDelayMs: Long = 0L, closeAfterHandshakeMs: Long = -1L):
  private val serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
  private val clients = new CopyOnWriteArrayList[Socket]()
  private val handlers = new CopyOnWriteArrayList[Thread]()
  private val connectionsServed = new AtomicInteger(0)
  @volatile private var stopped = false

  def port: Int = serverSocket.getLocalPort
  def endpoint: String = s"http://127.0.0.1:$port"
  def served: Int = connectionsServed.get()

  private val acceptor: Thread =
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
    val t = new Thread(body, "ws-fixture-accept")
    t.setDaemon(true)
    t.start()
    t

  private def serve(s: Socket): Unit =
    try
      val in = s.getInputStream
      val request = readHeaders(in)
      connectionsServed.incrementAndGet()
      if handshakeDelayMs > 0L then Thread.sleep(handshakeDelayMs)
      if !stopped then
        s.getOutputStream.write(handshakeResponse(request).getBytes(StandardCharsets.ISO_8859_1))
        s.getOutputStream.flush()
        if closeAfterHandshakeMs >= 0L then
          Thread.sleep(closeAfterHandshakeMs)
          s.close()
        else
          val sink = new Array[Byte](2048)
          while !stopped && in.read(sink) >= 0 do () // 保持连接（吞掉对端 ping）
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
        case _         => 0
      if state < 4 then b = in.read()
    sb.toString

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
    clients.forEach(s => try s.close() catch case _: Throwable => ())
    handlers.forEach(t => t.interrupt())
