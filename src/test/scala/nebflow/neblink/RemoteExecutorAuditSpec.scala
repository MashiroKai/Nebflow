package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.tools.{RelayExecAudit, RemoteExecutor, ToolContext}

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * T4（2026-09-11 Q3 裁定批）端到端：**真下发一条远端命令**后，relay 路径必须落
 * 一行审计（来源 deviceId / via=relay / redact 后的命令 / projectRoot）。
 *
 * 为什么这条链要端到端验：危险等级（`BashTool.dangerLevel`）只在 confirm-edits /
 * auto-edits 下渲染权限卡，**auto-all 下不参与决策**——「哪台设备被驱动、跑了
 * 什么」的唯一持久痕迹就是这一行审计。单元面（redact 矩阵）见
 * `nebflow.core.RelayExecAuditSpec`；这里验的是「接线真的接上了」：
 * 删掉 RemoteExecutor 里的 auditDispatch 调用 ⇒ 本用例必红。
 *
 * 传输用既有 relay 鉴权 fixture（RelayAuthFixtureServer）+ 真 relay 隧道——
 * 与 RemoteExecutorClientConvergenceSpec 同款 harness，只加审计断言。
 */
class RemoteExecutorAuditSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-remote-audit-spec")
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

  private def auditLines: List[String] =
    val p = RelayExecAudit.auditFile
    if !Files.exists(p) then Nil else Files.readAllLines(p).asScala.toList

  test("relay 远端下发必落审计行（来源 deviceId + via + redact 命令 + projectRoot）") {
    val secret = "sk-live-SUPERSECRETVALUE123456"
    val cmd = s"export API_TOKEN=$secret; ssh user@10.0.0.9 uptime"
    IO.blocking(new RelayAuthFixtureServer()).flatMap { fix =>
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          ms <- NeblinkService.create(0, dispatcher)
          client = new NeblinkClient(
            NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
            0,
            identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
          )
          _ <- client.login(Device, "qa-host", "macos", Nil)
          _ <- IO(client.currentSessionToken.getOrElse(fail("client must have a session")))
          // 2026-09-11（隧道常驻批 10ecea1f）：测试树的编译修复——`serverUrl` 构造参
          // 已从 NeblinkRelayTunnel 移除（URL 改为连接期从 config ref live 解析），
          // 这里仍按旧签名传 `fix.url` ⇒ main 上 Test/compile 直接失败。接线方式与
          // RemoteExecutorClientConvergenceSpec 对齐：fixture URL 写进 config。
          _ <- ms.updateConfig(
            _.copy(
              enabled = true,
              neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
            )
          )
          tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
          _ = ms.setRelayClient(Some(client))
          _ = ms.setRelayTunnel(tunnel)
          fiber <- tunnel.connect().start
          out <-
            (
              for
                up <- waitUntil(5.seconds)(IO(tunnel.isAlive))
                _ <- IO(assert(up, s"relay 隧道必须起来才走 relay 路径（${fix.relayAttempts}）"))
                _ <- IO(RemoteExecutor.initialize(ms, dispatcher, Some(client)))
                _ <- ms.upsertPeer(PeerInfo("peer-1", "peer-one", "macos", "http://127.0.0.1:9"))
                srcId <- ms.identity.map(_.deviceId)
                // ctx.projectRoot 必须在审计行里出现——它决定「哪个项目被控制了」
                res <- RemoteExecutor.current
                  .get
                  .execute(
                    "peer-one",
                    "Bash",
                    JsonObject("command" -> cmd.asJson),
                    Some(ToolContext(projectRoot = "/tmp/qa-relay-proj"))
                  )
                lines <- IO.blocking(auditLines)
                calls <- IO(fix.relayExecCalls.asScala.toList)
              yield (res, lines, calls.map(_._2), srcId)
            ).guarantee(fiber.cancel *> tunnel.stop())
        yield
          val (result, lines, accepted, srcId) = out
          assertEquals(result, Right("remote-ok"), s"relay 下发必须成功: $result")
          assertEquals(accepted, List(true), "且真的走了 relay（fixture 收到 live session 的 exec）")
          assertEquals(lines.length, 1, s"恰好一行审计（一次逻辑下发一行）: $lines")
          val c = parse(lines.head).fold(e => fail(s"invalid JSONL: $e"), identity).hcursor
          assertEquals(
            c.downField("deviceId").as[String].toOption,
            Some(srcId),
            "来源 deviceId = 本机 NebLink 身份（下发方）"
          )
          assertEquals(c.downField("targetDeviceId").as[String].toOption, Some("peer-1"))
          assertEquals(c.downField("via").as[String].toOption, Some("relay"))
          assertEquals(c.downField("action").as[String].toOption, Some("Bash"))
          assertEquals(c.downField("projectRoot").as[String].toOption, Some("/tmp/qa-relay-proj"))
          val shown = c.downField("command").as[String].toOption.getOrElse(fail("command missing"))
          assert(!shown.contains(secret), s"明文密钥落盘: $shown")
          assert(!shown.contains("SUPERSECRET"), s"明文密钥落盘: $shown")
          assert(shown.contains("ssh user@10.0.0.9"), s"非密钥部分保留可读: $shown")
          assert(shown.contains("API_TOKEN=[redacted"), s"密钥位已遮蔽: $shown")
      }.guarantee(IO.blocking(fix.close()))
    }
  }

end RemoteExecutorAuditSpec
