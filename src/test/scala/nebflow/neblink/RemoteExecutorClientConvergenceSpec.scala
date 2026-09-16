package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.tools.RemoteExecutor

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Cross-device dispatch must follow the LIVE client (2026-09-10 隧道鉴权自愈批,
 * F1 convergence sweep — registered as a leftover by the friend-search batch).
 *
 * Failure being nailed: `RemoteExecutor` received `client₀` in its constructor
 * (`GatewayMain:680 RemoteExecutor.initialize(..., neblinkClient)`), and the
 * enrollment/UI re-login hot-swap only re-points
 * `NeblinkService.relayClientOpt` + `NeblinkDiscovery.clientRef` — so the
 * executor kept dispatching over a session the server had kicked. That path is
 * the incident's business leg ("control the other computer"): relay dispatch is
 * exactly how a remote device is driven.
 *
 * Observable: the fixture records the Bearer token of every
 * `/api/relay/<deviceId>/exec` call. After an enrollment-equivalent hot-swap
 * (a NEW client becomes the live one) the FIRST dispatch must already carry the
 * new client's token and succeed — not the stale one (whose 401/403 would now
 * be healed by relayExec's own self-heal, masking the leak as a retry).
 */
class RemoteExecutorClientConvergenceSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-remote-exec-spec")
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

  private def mkClient(fix: RelayAuthFixtureServer): NeblinkClient =
    new NeblinkClient(
      NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
      0,
      identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
    )

  test("relay dispatch uses the hot-swapped live client, not the constructor-time snapshot") {
    IO.blocking(new RelayAuthFixtureServer()).flatMap { fix =>
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          ms <- NeblinkService.create(0, dispatcher)
          clientA = mkClient(fix)
          _ = ms.setRelayClient(Some(clientA))
          _ <- clientA.login(Device, "qa-host", "macos", Nil)
          tokenA <- IO(clientA.currentSessionToken.getOrElse(fail("clientA must have a session")))
          // 2026-09-11：URL 改为连接期 live 解析（构造参已移除）⇒ 写进 config ref。
          _ <- ms.updateConfig(_.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
          ))
          tunnel = new NeblinkRelayTunnel(ms, () => IO(clientA.currentSessionToken))(dispatcher)
          _ = ms.setRelayTunnel(tunnel)
          fiber <- tunnel.connect().start
          out <-
            (
              for
                up <- waitUntil(5.seconds)(IO(tunnel.isAlive))
                _ <- IO(assert(up, s"tunnel must be up so the relay path is selectable (${fix.relayAttempts})"))
                // Enrollment-equivalent hot-swap: a NEW client becomes the live
                // one (NeblinkEnrollment.persist does exactly this +
                // setRelayClient).
                clientB = mkClient(fix)
                _ <- clientB.login(Device, "qa-host", "macos", Nil)
                tokenB <- IO(clientB.currentSessionToken.getOrElse(fail("clientB must have a session")))
                _ <- IO(assert(tokenA != tokenB, "the hot-swap must produce a different session"))
                _ <- IO(ms.setRelayClient(Some(clientB)))
                // The executor is deliberately handed the STALE client.
                _ <- IO(RemoteExecutor.initialize(ms, dispatcher, Some(clientA)))
                _ <- ms.upsertPeer(PeerInfo("peer-1", "peer-one", "macos", "http://127.0.0.1:9"))
                res <- RemoteExecutor.current
                  .get
                  .execute("peer-one", "Bash", JsonObject("command" -> "echo hi".asJson))
                calls <- IO(fix.relayExecCalls.asScala.toList)
                // 批 2（2026-09-16 · C06）：**请求体级**锚 —— 把「2 次下发」拆成
                // 「探针 1 + 业务 1」，而不是只对一个合计数「2」下判据。
                probes <- IO(fix.relayExecProbeCount)
                business <- IO(fix.relayExecBusinessCount)
              yield (res, calls.map(_._1), calls.map(_._2), fix.logins.get(), tokenB, probes, business)
            ).guarantee(fiber.cancel *> tunnel.stop())
        yield
          val (result, tokens, accepted, logins, tokenB, probes, business) = out
          assertEquals(result, Right("remote-ok"), s"relay dispatch must succeed: $result")
          // xdev 批（2026-09-15）：`execute` 首触新增**只读画像探针**（`kind=probe`，
          // `RemoteExecutor.scala:308`）——探针同走 p2p→relay 链（本用例 peer 的
          // `http://127.0.0.1:9` 恒不可达 ⇒ 探针的 relay 腿成功），故 fixture 收到
          // **2 次** `/api/relay/<id>/exec`：探针 + 业务。口径与兄弟 spec
          // `RemoteExecutorAuditSpec.scala:123-126` 逐字一致（那里是同一根因）。
          // 判据本体不变：**两次下发都必须带活 client 的 token** —— 若构造期快照
          // client₀ 被捕获，第 1 条会是 `tok-1`（且被 403 拒 ⇒ `accepted` 出现 false
          // + 自愈补登录）。
          assertEquals(
            tokens,
            List(tokenB, tokenB),
            "BOTH dispatches (profile probe + business) must carry the LIVE client's token — a stale token means client₀ was captured"
          )
          assertEquals(accepted, List(true, true), "and each must be accepted on the first try (no heal retry)")
          assertEquals(logins, 2, "no extra re-login: the stale session must never be used at all")
          // 批 2（2026-09-16 · C06）**请求体级锚**（只增强，不改既有三条判据）：
          // 夹具按请求体把 2 次下发分判（判别字面与 `StubPeerServer.probeHitCount`
          // 逐字同源）⇒ 「2 次 = 探针 + 业务」不再只靠计数与兄弟 spec 口径支撑。
          // 批 3（2026-09-16 · 同族治本 A）：`relayExecBusinessCount` 改为**独立**按请求体
          // 统计（旧形态 = `总次数 − 探针数` 派生量，探针计数器被改成常量时派生值会自动
          // 跟着对 ⇒ 存活变异）。两个读数现在各自独立：判别谓词被改坏 ⇒ 对应用例红。
          // ⚠ 可检验性边界：把**两个**计数器同时改成「恒 1」时读数与本场景真值（1 探针 +
          // 1 业务）重合，逻辑上不可检 —— 夹具型判据的固有边界，非本批判据弱化（详见
          // `RelayAuthFixtureServer.relayExecBusinessCount` 注释）。
          assertEquals(probes, 1, s"must be exactly ONE read-only profile probe on the relay path (bodies=${fix.relayExecBodies.asScala.toList})")
          assertEquals(business, 1, s"and exactly ONE business dispatch (probe/business split read from the bodies, not from the count)")
      }.guarantee(IO.blocking(fix.close()))
    }
  }

end RemoteExecutorClientConvergenceSpec
