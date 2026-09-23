package nebflow.neblink

import cats.effect.{Deferred, IO}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * kaiauth 修法批 ③（2026-09-16 作者「治本」已批）—— **单飞闸跨 hot-swap 加固 +
 * enroll 串行化**的机制级红/绿构造。
 *
 * 诊断报告把本条标注为**推断**（§6 最强候选机制）：`reloginGate` 是**实例级** `Ref`，
 * 而 `NeblinkEnrollment.persist` 的 hot-swap 会建新 client ⇒ 闸被重置；服务端
 * `enroll_device` 是 `INSERT OR REPLACE`（跨仓只读 `store.rs:2951-2969`）⇒ **每次 enroll
 * 立刻作废上一次**，客户端对「谁是最后一次」没有任何保证 ⇒「enroll 成功」与「发送值
 * 有效」可以不重合。
 *
 * 本 spec 给**机制级**构造（不求现场复现，作者口径）：
 *  - **N4-a（跨 hot-swap 的会话闸）**：两个**不同实例**（= hot-swap 前后的两个 client，
 *    同 `url + networkId`）并发触发自愈 —— 真夹具下 `POST /api/device/register`（enroll
 *    的唯一线上形态）必须**恰一次**。修前实例级闸 ⇒ 两次 register（必红）。
 *  - **N4-b（enroll 单飞闸本身）**：闸的持有者**根本不是任何 client 实例**（测试自己持闸），
 *    两条并发 `NeblinkEnrollment.persist` 仍必须**都**等在闸上并复用同一结果 ⇒
 *    「闸是进程级、按 (deviceId, networkId)」这一形态被直接证明（与实例生命周期解耦）。
 *
 * 边界（诚实登记）：跨**进程**与跨**实例**（另一台机器）面**不做**中央协调、零 wire 新增 ——
 * 本批只把「同一 JVM 内」的单飞做满（与 `NeblinkService.kickParked` 的进程内口径同源）。
 */
class NeblinkEnrollSingleFlightSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private val Net = "qa-net"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-enroll-singleflight-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    NeblinkSingleFlight.resetForTest()

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def enrollJson(token: String): Json =
    parse(
      s"""{"deviceToken":"$token","networkId":"$Net","avatarUrl":null,"githubUsername":null}"""
    ).toOption.get

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap { f =>
      f.enrollNetworkId = Net
      body(f).guarantee(IO.blocking(f.close()))
    }

  /** 一个 client 工厂（每次调用一个新实例 = hot-swap 前后的两个实例）。 */
  private def mkClient(
    ms: NeblinkService,
    cfg: NeblinkServerConfig,
    dev: String,
    fix: RelayAuthFixtureServer
  ): NeblinkClient =
    new NeblinkClient(
      cfg,
      0,
      onDeviceTokenRejected = Some(
        // 案 b①：接缝改 `IO[Option[String]]`（目标缺席 ⇒ 降级不注册）；测试腿给显式目标。
        LogtoSilentRelogin.make(ms, IO.pure(Option.empty[NeblinkDiscovery]), 0, IO.pure(Some(fix.url)))
      ),
      identity = Some(IO.pure(DeviceIdentity(dev, "qa-host", "macos")))
    )

  // ===== N4-a：跨 hot-swap（两个不同实例）的会话单飞 =====

  test("N4-a: hot-swap 前后两个 client 并发自愈 ⇒ enroll 线上形态（register）恰一次") {
    withFixture { fix =>
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          ms <- NeblinkService.createForTest(8097, dispatcher, 15.seconds)
          id <- ms.identity
          dev = id.deviceId
          dtok = fix.registerDevice(dev, Net)
          cfg = NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret", deviceToken = Some(dtok))
          _ <- ms.updateConfig(
            _.copy(
              enabled = true,
              neblinkServer = Some(cfg),
              logto = Some(LogtoConfig(endpoint = fix.url, clientId = "", pkceClientId = Some("mock-pkce")))
            )
          )
          // 存量 pre-O5 refresh 凭据（silent re-login 的唯一 token 来源）。
          _ <- DeviceCredential.save(
            DeviceCredential(fix.url, Net, dev, dtok, LogtoRefresh.of(Some("mock-refresh"), None))
          )
          // 服务端把当前凭据作废（= 被新注册覆盖后的现场形态）⇒ 会话交换 401 ⇒ 进自愈腿。
          _ <- IO(fix.revokeDeviceCredential(dev, Net))
          // 让赢家在闸上停留得久一点，保证第二个调用**真的**并发到达（确定性旋钮）。
          _ <- IO(fix.loginDelayMs = 600L)
          // 两个**不同实例** = hot-swap 前后的两个 client（同 url + networkId）。
          oldClient <- IO(mkClient(ms, cfg, dev, fix))
          newClient <- IO(mkClient(ms, cfg, dev, fix))
          _ <- IO(assert(oldClient ne newClient, "前置：必须是两个不同实例（hot-swap 的语义）"))
          outs <- IO.parSequenceN(2)(
            List(oldClient.ensureFreshSession("pre-hotswap"), newClient.ensureFreshSession("post-hotswap"))
          )
          registers <- IO(fix.registerCount)
          _ <- IO {
            assert(outs.forall(identity), s"两个自愈入口都必须报告成功：$outs")
            assertEquals(
              registers,
              1,
              "🔴 判据：并发两次 enroll ⇒ **恰一次** register（修前实例级闸 ⇒ 2 次互相作废）"
            )
          }
        yield ()
      }
    }
  }

  // ===== N4-b：enroll 单飞闸本身（持有者与实例无关）=====

  test("N4-b: 闸的持有者不是任何 client 实例 ⇒ 两条并发 persist 仍必须复用同一结果") {
    withFixture { fix =>
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          ms <- NeblinkService.createForTest(8098, dispatcher, 15.seconds)
          id <- ms.identity
          dev = id.deviceId
          ps = new NeblinkPresenceService(ms, 0)(dispatcher)
          discovery = new NeblinkDiscovery(ms, 0, ps, None)
          key = NeblinkSingleFlight.key("enroll", dev, Net)
          _ <- IO(assertEquals(key, s"enroll|$dev|$Net", "键 = 命名空间 + (deviceId, networkId)"))
          hold <- Deferred[IO, Unit]
          releaseHold <- Deferred[IO, Unit]
          // 测试自己占住闸（**不经过任何 client 实例**）——直接证明闸与实例生命周期解耦。
          holder <- NeblinkSingleFlight
            .serialize(key)(hold.complete(()).void *> releaseHold.get *> IO.pure(Right("tok-primed")))
            .start
          _ <- hold.get
          held <- IO(NeblinkSingleFlight.inFlightKeys.contains(key))
          _ <- IO(assert(held, s"闸必须在册：$key（在册 = 后续调用会加入而不是另开一个）"))
          both <- IO
            .parSequenceN(2)(
              List(
                NeblinkEnrollment.persist(
                  ms,
                  resolvedUrl = fix.url,
                  json = enrollJson("tok-A"),
                  logtoRefresh = None,
                  discovery = Some(discovery),
                  gatewayPort = 0,
                  reloginHook = None
                ),
                NeblinkEnrollment.persist(
                  ms,
                  resolvedUrl = fix.url,
                  json = enrollJson("tok-B"),
                  logtoRefresh = None,
                  discovery = Some(discovery),
                  gatewayPort = 0,
                  reloginHook = None
                )
              )
            )
            .start
          _ <- IO.sleep(400.millis) // 给两条 persist 充分的机会去「另开一个闸」（若有的话）
          _ <- releaseHold.complete(())
          results <- both.join.flatMap(_.embedNever)
          holderResult <- holder.join.flatMap(_.embedNever)
          cfgWritten <- IO(os.exists(os.Path(tmpDir, os.pwd) / "neblink" / "config.json"))
          _ <- IO {
            assertEquals(holderResult, Right("tok-primed"))
            assertEquals(
              results,
              List(Right("tok-primed"), Right("tok-primed")),
              "🔴 两条并发 enroll 必须**复用**同一个赢家结果（修前/无闸 ⇒ 各返回自己的 token）"
            )
            assert(
              !cfgWritten,
              "单飞语义：两条都**没有**跑自己的临界区（否则 config.json 会被写两次/被写）"
            )
          }
        yield ()
      }
    }
  }

end NeblinkEnrollSingleFlightSpec
