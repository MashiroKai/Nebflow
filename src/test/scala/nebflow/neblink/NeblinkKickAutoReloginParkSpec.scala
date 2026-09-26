package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * 踢旧批 r2（2026-09-14，作者 17:07 裁定 C+B·客户端一刀）—— **「零自动重注册」真钉**。
 *
 * 复核位判词（`.nebflow/reports/20260914_190048_kickold-cb-verify__chain-n-49531f98.md`
 * 第三节②）判 fail 的唯一失败点：r1 的钉只钉到**隧道腿**，而 kick 后 ~24s 的
 * **自动重注册腿**照样把 `POST /api/device/register` 打到服务端。r1 的钉观测窗只有
 * 8s，结构上观测不到这条腿。
 *
 * 本钉的三条硬要求（返工面 #4）逐条落实：
 *   ① **真帧驱动**：`{"type":"disconnect"}` 由夹具写进**真 WS socket**（RFC 6455 文本
 *      帧，形态与生产逐字节同形，零新字段），被测算走生产 listener 的 `onText` 分派；
 *   ② **观测窗 ≥ 60s**：自愈腿实测在 kick 后 ~24s 起（心跳周期驱动），故窗口取
 *      **65s**（+5 / +25 / +45 / +65s 四次驱动，覆盖 ~24s 的起点）；
 *   ③ **前红后绿**：钉只使用**基线可编译**的 API（`NeblinkClient` 4 参构造、
 *      `discover` / `ensureFreshSession` / `ensure`），因此在 **main 基线树**、
 *      r1 树与本支新树上都能编译；红侧 = register 计数增长，绿侧 = 恒为零。
 *
 * 判据面 = 夹具侧的真实请求计数（`registers` / `loginCalls` / `sessionRejections`），
 * 不是被测算的内部方法读数。
 *
 * 夹具语义（见 `RelayAuthFixtureServer` 注释）：`register` = 覆盖式签发新 deviceToken
 * 并踢掉旧会话（`store.rs enroll_device` 语义）；`session` 只在 deviceToken 是**当前**
 * 凭据时发会话（旧凭据 ⇒ 401，= `verify_device_credential` 恒 false）。这样才能在
 * 本地复现「被踢后自动重登录 → 401 → silent re-login → register」这条链。
 */
class NeblinkKickAutoReloginParkSpec extends CatsEffectSuite:

  /**
   * 观测窗 65s + 装配 ⇒ munit 默认超时必须放宽（否则钉的窗口永远够不到
   * ~24s 起的自愈腿 —— r1 的钉正是「窗口太短 ⇒ 结构上观测不到该腿」）。
   */
  override def munitIOTimeout: Duration = 200.seconds

  private val Net = "qa-net"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-kick-autorelogin-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

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
   * 一条完整生产装配（隔离 home + 夹具 + 真隧道）。返回被测算用的 deviceId——
   * 必须与 `ms.identity.deviceId` **同值**，否则 silent re-login 的 register 会打到
   * 另一个 (deviceId, network) 维度上，夹具的凭据表就对不上（夹具按真服务端的
   * (deviceId, networkId) 维度建表）。
   */
  private def withStack[A](
    fix: RelayAuthFixtureServer
  )(body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel, String) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        id <- ms.identity
        dev = id.deviceId
        // 本实例的 deviceToken = 夹具当前有效凭据（模拟「已入网、正在跑」的常态）。
        dtok = fix.registerDevice(dev, Net)
        cfg = NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret", deviceToken = Some(dtok))
        client = new NeblinkClient(
          cfg,
          0,
          // 真 silent re-login 钩子（register 的最近前驱）：本钉要测的正是它的入口门。
          onDeviceTokenRejected = Some(
            // 案 b①：接缝改 `IO[Option[String]]`（目标缺席 ⇒ 降级不注册）；测试腿给显式目标。
            LogtoSilentRelogin.make(ms, IO.pure(Option.empty[NeblinkDiscovery]), 0, IO.pure(Some(fix.url)))
          ),
          identity = Some(IO.pure(DeviceIdentity(dev, "qa-host", "macos")))
        )
        _ = ms.setRelayClient(Some(client))
        _ <- ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(cfg),
            // refresh 腿的 provider 端点指向夹具：`LogtoAuthCode.refreshTokenRequest` 打
            // `{endpoint}/oidc/token`（夹具回 200 + access_token）。
            logto = Some(LogtoConfig(endpoint = fix.url, clientId = "", pkceClientId = Some("mock-pkce")))
          )
        )
        // 存量凭据里的 pre-O5 refresh token —— silent re-login 的唯一 token 来源。
        _ <- DeviceCredential.save(
          DeviceCredential(fix.url, Net, dev, dtok, LogtoRefresh.of(Some("mock-refresh"), None))
        )
        _ <- client.login(dev, "qa-host", "macos", Nil)
        tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        fiber <- tunnel.connect().start
        out <- body(ms, client, tunnel, dev).guarantee(fiber.cancel *> tunnel.stop())
      yield out
    }

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap { f =>
      f.enrollNetworkId = Net
      body(f).guarantee(IO.blocking(f.close()))
    }

  /**
   * 自动腿的两个真实入口（= 生产里 `NeblinkDiscovery` 心跳与会话自愈走的同一对）：
   *   - `discover` = 心跳失败后的 re-login（`NeblinkClient.discover`）；
   *   - `ensureFreshSession` = API/隧道升级自愈的 single-flight 入口。
   */
  private def driveAutoLeg(client: NeblinkClient, dev: String, tag: String): IO[Unit] =
    client.discover(dev, "qa-host", "macos", Nil).attempt.void *>
      client.ensureFreshSession(s"spec-$tag").attempt.void

  // ── 钉：真帧 ⇒ 零自动重注册（观测窗 65s）────────────────────────

  test("kick: a real `disconnect` frame ⇒ the automatic leg performs ZERO re-registration (65s window)") {
    withFixture { fix =>
      withStack(fix) { (ms, client, tunnel, dev) =>
        for
          up <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(up, s"baseline: the relay tunnel must be up (${fix.relayAttempts})"))
          regBefore <- IO(fix.registerCount)
          loginsBefore <- IO(fix.loginCalls.size())
          rejectionsBefore <- IO(fix.sessionRejections.get())
          // ── 被踢：生产同形 —— 同 (deviceId, network) 的新注册（覆盖凭据 + 踢会话）
          //    然后服务端主动推 `disconnect` 帧并断开 WS（relay.rs disconnect_device）。
          _ <- IO(fix.registerDevice(dev, Net))
          written <- IO(fix.sendTextToRelay("""{"type":"disconnect"}"""))
          _ <- IO(assert(written >= 1, "the fixture must have an open relay socket to push into"))
          _ <- IO(fix.closeRelaySocketsGracefully())
          _ <- IO.sleep(2.seconds)
          // ── 观测窗：+5 / +25 / +45 / +65s 各驱动一次自动腿（覆盖实测 ~24s 的自愈起点）
          _ <- IO.sleep(3.seconds)
          _ <- driveAutoLeg(client, dev, "t5")
          _ <- IO.sleep(20.seconds)
          _ <- driveAutoLeg(client, dev, "t25")
          _ <- IO.sleep(20.seconds)
          _ <- driveAutoLeg(client, dev, "t45")
          _ <- IO.sleep(20.seconds)
          _ <- driveAutoLeg(client, dev, "t65")
          _ <- IO.sleep(2.seconds)
          regAfter <- IO(fix.registerCount)
          loginsAfter <- IO(fix.loginCalls.size())
          rejectionsAfter <- IO(fix.sessionRejections.get())
          _ <- IO(
            println(
              s"[kick-nail] registers $regBefore→$regAfter | loginCalls $loginsBefore→$loginsAfter | " +
                s"sessionRejections $rejectionsBefore→$rejectionsAfter | registerCalls=${fix.registerCalls.toArray.toList}"
            )
          )
          _ <- IO(
            assertEquals(
              regAfter,
              regBefore,
              s"a kicked device must not auto-re-register; registerCalls=${fix.registerCalls.toArray.toList}"
            )
          )
        yield ()
      }
    }
  }

  // ── 对照（非空钉证明）：无 `disconnect` 帧时同一条腿**确实**会重注册 ──────

  test("control: without a server-forced disconnect the SAME automatic leg re-registers (the nail is not vacuous)") {
    withFixture { fix =>
      withStack(fix) { (_, client, _, dev) =>
        for
          up <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(up, s"baseline: the relay tunnel must be up (${fix.relayAttempts})"))
          regBefore <- IO(fix.registerCount)
          // 只作废凭据（= 服务端把旧 deviceToken 换掉），**不推** disconnect 帧 ⇒ 不停摆。
          _ <- IO(fix.revokeDeviceCredential(dev, Net))
          _ <- driveAutoLeg(client, dev, "control")
          registered <- waitUntil(20.seconds)(IO(fix.registerCount > regBefore))
          _ <- IO(
            assert(
              registered,
              s"control failed: the automatic leg must be ABLE to re-register when not parked " +
                s"(registers=${fix.registerCount}, sessionRejections=${fix.sessionRejections.get()})"
            )
          )
          _ <- IO(
            assert(
              fix.sessionRejections.get() >= 1,
              "control: the revoked deviceToken must be rejected with 401 (the chain's entry condition)"
            )
          )
          _ <- IO(
            println(
              s"[kick-nail/control] registers=$regBefore→${fix.registerCount} sessionRejections=${fix.sessionRejections.get()}"
            )
          )
        yield ()
      }
    }
  }

end NeblinkKickAutoReloginParkSpec
