package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * kaiauth 修法批 ①配套（2026-09-16 作者「治本」已批）—— **停摆门的「重登成功可解除」**。
 *
 * 缺陷形态（诊断报告 §9③）：停摆位的读侧有三条腿（隧道 connectLoop / `NeblinkClient`
 * 自动登录门 / `LogtoSilentRelogin` 的 register 前门），而清侧修前只有一条 —— **用户显式
 * 登录**。于是「自动路径刚铸成有效新凭据、却因为同一次 enroll 踢掉了自己的旧会话而被
 * 停摆」这一状态**没有任何自动出口**（死循环）。
 *
 * 本批新增的那条腿（判据 = 作者口径「新凭据已铸成**且**经一次成功交换证明有效」）：
 * `NeblinkEnrollment.persist`（**自动路径**，`explicitUserAction = false`）在**已停摆**时，
 * 用新 client 对新凭据做**一次**证明性会话交换；成功才解除，失败保持。
 *
 * 三条钉（可红可绿，全部走未改动的产品代码 + 真 HTTP 夹具）：
 *  1. **已铸成 + 交换成功 ⇒ 解除**（改前恒保持 ⇒ 本钉在基线上必红）；
 *  2. **交换失败 ⇒ 保持**（失败路径绝不解锁 = 防风暴回归钉），且**只试一次**；
 *  3. **未停摆 ⇒ 零额外请求**（wire 面不可观测 ⇒ 「零 wire 新增」的可测形态）。
 *
 * 显式路径的语义**逐字未动**（案 C：显式登录是无条件解除口）——
 * `EnrollGuardExplicitLoginSpec` 的停摆解除钉仍是那条语义的回归钉。
 */
class NeblinkKickParkReleaseSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private val Net = "qa-net"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-kickpark-release-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    // 进程级单飞登记表：跨 suite 隔离（本 spec 的 persist 会经过 enroll 闸）。
    NeblinkSingleFlight.resetForTest()

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def enrollJson(token: String, networkId: String): Json =
    parse(
      s"""{"deviceToken":"$token","networkId":"$networkId","avatarUrl":null,"githubUsername":null}"""
    ).toOption.get

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap { f =>
      f.enrollNetworkId = Net
      body(f).guarantee(IO.blocking(f.close()))
    }

  /**
   * 一条生产同形装配：`NeblinkService` + 隧道 + `NeblinkDiscovery`（hot-swap 目标）。
   * 返回 `(ms, discovery, tunnel, dev)`。
   */
  private def withStack[A](
    fix: RelayAuthFixtureServer
  )(body: (NeblinkService, NeblinkDiscovery, NeblinkRelayTunnel, String) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(8096, dispatcher, 15.seconds)
        id <- ms.identity
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        discovery = new NeblinkDiscovery(ms, 0, ps, None)
        tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(None: Option[String]))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        out <- body(ms, discovery, tunnel, id.deviceId)
      yield out
    }

  /** 自动路径 persist（`explicitUserAction = false`）。 */
  private def autoPersist(
    ms: NeblinkService,
    fix: RelayAuthFixtureServer,
    discovery: NeblinkDiscovery,
    tok: String
  ): IO[Either[String, String]] =
    NeblinkEnrollment.persist(
      ms,
      resolvedUrl = fix.url,
      json = enrollJson(tok, Net),
      logtoRefresh = None,
      discovery = Some(discovery),
      gatewayPort = 0,
      reloginHook = None,
      explicitUserAction = false
    )

  test("N3-a: 停摆中 + 新凭据已铸成且交换成功 ⇒ 停摆门解除（且隧道腿被唤醒）") {
    withFixture { fix =>
      withStack(fix) { (ms, discovery, tunnel, dev) =>
        for
          tok <- IO(fix.registerDevice(dev, Net)) // 服务端当前行 = 本次新铸凭据
          _ <- IO(ms.markKickParked())
          _ <- IO(assert(ms.kickParked, "前置：必须已停摆"))
          out <- autoPersist(ms, fix, discovery, tok)
          parked <- IO(ms.kickParked)
          tunnelParked <- IO(tunnel.parkedAfterKick)
          sessions <- IO(fix.loginCalls.size())
          start <- IO.monotonic
          _ <- tunnel.nap(5.seconds) // 唤醒是 pending 的 ⇒ 不该真的睡 5s
          elapsed <- IO.monotonic.map(_ - start)
          _ <- IO {
            assertEquals(out, Right(tok), "自动 persist 本身必须成功落地")
            assertEquals(
              parked,
              false,
              "🔴 判据①：新凭据已铸成且经一次成功交换证明有效 ⇒ 停摆门必须解除（改前恒 true）"
            )
            assertEquals(tunnelParked, false, "隧道腿读同一真值 ⇒ 解除后必须看到未停摆")
            assertEquals(sessions, 1, "证明性交换恰一次（有界，不新增重试腿）")
            assert(elapsed < 3.seconds, s"隧道腿必须被唤醒（nap 应被掐断），实际等了 $elapsed")
          }
        yield ()
      }
    }
  }

  test("N3-b: 停摆中但证明性交换失败 ⇒ 停摆门保持、且只试一次（失败绝不解锁）") {
    withFixture { fix =>
      withStack(fix) { (ms, discovery, tunnel, dev) =>
        for
          tok <- IO(fix.registerDevice(dev, Net))
          // 服务端当前行随即被换掉 ⇒ 客户端刚落地的那枚凭据**不是**服务端当前行
          _ <- IO(fix.registerDevice(dev, Net))
          _ <- IO(ms.markKickParked())
          out <- autoPersist(ms, fix, discovery, tok)
          parked <- IO(ms.kickParked)
          tunnelParked <- IO(tunnel.parkedAfterKick)
          rejections <- IO(fix.sessionRejections.get())
          registers <- IO(fix.registerCount)
          _ <- IO {
            assertEquals(out, Right(tok), "落地本身仍成功（停摆门与落地是两件事）")
            assertEquals(
              parked,
              true,
              "🔴 判据②：无正向证据 ⇒ 停摆门必须保持（这就是防风暴回归钉）"
            )
            assert(tunnelParked, "隧道腿仍停摆")
            assertEquals(rejections, 1, "证明性交换恰一次（失败不重试 ⇒ 不形成风暴）")
            assertEquals(registers, 0, "失败路径不得触发任何 register/enroll（零重复 enroll 风暴）")
          }
        yield ()
      }
    }
  }

  test("N3-c: 未停摆 ⇒ 证明步骤零额外请求（wire 面不可观测）") {
    withFixture { fix =>
      withStack(fix) { (ms, discovery, _, dev) =>
        for
          tok <- IO(fix.registerDevice(dev, Net))
          sessionsBefore <- IO(fix.loginCalls.size())
          out <- autoPersist(ms, fix, discovery, tok)
          sessionsAfter <- IO(fix.loginCalls.size())
          _ <- IO {
            assertEquals(out, Right(tok))
            assertEquals(ms.kickParked, false, "未停摆者不得被本步改动")
            assertEquals(
              sessionsAfter,
              sessionsBefore,
              "🔴 判据③：无门可解 ⇒ 本步在 wire 面上完全不可观测（不发证明性交换）"
            )
          }
        yield ()
      }
    }
  }

end NeblinkKickParkReleaseSpec
