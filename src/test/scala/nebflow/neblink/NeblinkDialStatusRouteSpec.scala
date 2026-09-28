package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.net.{InetAddress, ServerSocket}
import java.nio.file.Files

/**
 * C3 可见性回归网（2026-09-11 P2P 直连修复批，方案 §2 选项 C3 / §4 绿-4 / §反控-3）。
 *
 * 事故里 `directOnline=false` **不可归因**：拨号失败只写 `logger.debug`，而
 * `logback.xml` root level=INFO ⇒ 零留痕（方案 §1.1 环③ / E-5 / U-1）。于是
 * 「地址不可达」与「从没拨过」在观测面上完全同形。
 *
 * 本 spec 钉住闭环的两端，且**都走真实代码路径**（无 mock 决策）：
 *   · 拨号侧 —— `NeblinkPresenceService.connect` 对真实不可达端口拨号，必须
 *     记录带原因的结果，并证明 C1 的**候选轮转**真的发生（记录的是最后尝试的候选）；
 *   · 路由侧 —— `/neblink/status` 的 peers JSON 必须把这些字段暴露出来
 *     （改前根本没有这些键 ⇒ 本用例必红）。
 */
class NeblinkDialStatusRouteSpec extends CatsEffectSuite:

  private val TestToken = "test-token-dial-status"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-dial-status-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

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
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpDir, os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private def mkRoutes(ms: NeblinkService, ps: NeblinkPresenceService): RestApiRoutes =
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = Some(ms),
      neblinkDiscovery = Some(new NeblinkDiscovery(ms, 0, ps, None))
    )

  private def statusRequest: Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  /** 刚释放的本机端口 ⇒ 连接必被拒（`openConnection` 的失败类）。 */
  private def freeTcpPort(): Int =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  /** peers JSON 里名为 `deviceName` 的那一项。 */
  private def peerJson(body: Json, deviceName: String): Json =
    body.hcursor
      .downField("peers")
      .as[List[Json]]
      .toOption
      .getOrElse(fail(s"peers must be a JSON array: ${body.noSpaces}"))
      .find(_.hcursor.downField("deviceName").as[String].toOption.contains(deviceName))
      .getOrElse(fail(s"peer $deviceName missing from ${body.noSpaces}"))

  test("C3: 拨号失败必须留痕——connect 记录原因/时刻，/neblink/status 可读（改前零留痕不可归因）") {
    val dead1 = freeTcpPort()
    val dead2 = freeTcpPort()
    assert(dead1 != dead2, "两个候选必须是不同端口，否则轮转断言无意义")
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        _ = ms.setPresenceService(ps)
        peer = PeerInfo(
          deviceId = "kai-1",
          deviceName = "KAI",
          platform = "macos",
          address = s"http://127.0.0.1:$dead1",
          endpoints = List(s"http://127.0.0.1:$dead1", s"http://127.0.0.1:$dead2")
        )
        _ <- ms.upsertPeer(peer)
        // 真拨号（两个候选都不可达）——不是 mock 决策
        _ <- ps.connect(peer)
        status <- IO(ps.dialStatus("kai-1"))
        chosen <- IO(ps.chosenEndpoint("kai-1"))
        connected <- IO(ps.isConnected("kai-1"))
        resp <- mkRoutes(ms, ps).routes(statusRequest).value.map(_.getOrElse(fail("route fell through")))
        body <- resp.as[Json]
      yield (status, chosen, connected, resp.status, body, dead1, dead2)
    }.map { case (dialStatus, chosen, connected, httpStatus, body, dead1, dead2) =>
      assertEquals(httpStatus, Status.Ok)
      // —— 拨号侧：结果带原因 + 时刻 ——
      val st = dialStatus.getOrElse(fail("C3: 拨号结果必须被记录（改前只有不落盘的 logger.debug）"))
      assert(st.error.exists(_.nonEmpty), s"必须有失败原因: $st")
      assert(st.atMs > 0L, s"必须有时刻: $st")
      assertEquals(
        st.endpoint,
        s"http://127.0.0.1:$dead2",
        "C1: 记录的是**最后尝试**的候选 ⇒ 证明候选轮转真的发生（改前只拨 address 一个）"
      )
      assertEquals(chosen, None, "全部候选失败 ⇒ 无择优结果")
      assertEquals(connected, false, "未建立连接")
      // —— 路由侧：同一事实必须可从 /neblink/status 读到 ——
      val p = peerJson(body, "KAI")
      assertEquals(p.hcursor.downField("directOnline").as[Boolean].toOption, Some(false))
      assert(
        p.hcursor.downField("lastDialError").as[String].toOption.exists(_.nonEmpty),
        s"lastDialError 必须非空（改前无此键）: ${p.noSpaces}"
      )
      assert(
        p.hcursor.downField("lastDialAt").as[Long].toOption.exists(_ > 0L),
        s"lastDialAt 必须有值（改前无此键）: ${p.noSpaces}"
      )
      assertEquals(
        p.hcursor.downField("dialEndpoint").as[String].toOption,
        Some(s"http://127.0.0.1:$dead2")
      )
      assert(
        st.endpoint != s"http://127.0.0.1:$dead1",
        s"轮转证据：第一个候选被尝试过且失败后，记录前进到了第二个: $st"
      )
    }
  }

  test("C3: 从未拨过的 peer ⇒ lastDialError/lastDialAt 为 null（'没拨过' 与 '拨失敗' 可区分）") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        _ = ms.setPresenceService(ps)
        // endpoints 为空 + 空地址 ⇒ connect 不拨（无候选），但我们**不**调 connect：
        // 模拟「名册里有它、但拨号循环还没轮到」——这正是改前无法与失败态区分的状态。
        _ <- ms.upsertPeer(PeerInfo("kai-2", "KAI", "macos", "http://127.0.0.1:9"))
        resp <- mkRoutes(ms, ps).routes(statusRequest).value.map(_.getOrElse(fail("route fell through")))
        body <- resp.as[Json]
      yield (body, ps.dialStatus("kai-2"))
    }.map { case (body, st) =>
      assertEquals(st, None, "未拨号 ⇒ 无 dialStatus（区别于拨号失败）")
      val p = peerJson(body, "KAI")
      assertEquals(p.hcursor.downField("lastDialError").focus.map(_.noSpaces), Some("null"))
      assertEquals(p.hcursor.downField("lastDialAt").focus.map(_.noSpaces), Some("null"))
      assertEquals(p.hcursor.downField("dialEndpoint").focus.map(_.noSpaces), Some("null"))
      assertEquals(p.hcursor.downField("directOnline").as[Boolean].toOption, Some(false))
    }
  }

  test("C3/C1: peer.endpoints 空 ⇒ 退回单 address（单候选行为不回归）") {
    val dead = freeTcpPort()
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        peer = PeerInfo("kai-3", "KAI", "macos", s"http://127.0.0.1:$dead") // endpoints 缺省 = Nil
        _ <- ps.connect(peer)
        st <- IO(ps.dialStatus("kai-3"))
      yield (st, peer)
    }.map { case (st, peer) =>
      assertEquals(peer.endpoints, Nil, "legacy peer 形态")
      assertEquals(
        st.map(_.endpoint),
        Some(peer.address),
        "无 endpoints ⇒ 只拨 address 一次（pre-C1 行为，不回归）"
      )
      assert(st.exists(_.error.exists(_.nonEmpty)), "失败仍必须留痕")
    }
  }

  test("C3/C1: 空地址 peer ⇒ 留痕为 'no usable endpoint'，不再静默早返") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        peer = PeerInfo("kai-4", "KAI", "macos", "")
        _ <- ps.connect(peer)
        st <- IO(ps.dialStatus("kai-4"))
      yield st
    }.map { st =>
      // 改前 `extractHost(peer.address) == None ⇒ IO.unit`：完全静默，
      // status 上看起来与「拨号失败」一模一样。
      assertEquals(
        st.flatMap(_.error),
        Some("no usable endpoint (empty peer address)"),
        "空地址也必须可归因"
      )
    }
  }

end NeblinkDialStatusRouteSpec
