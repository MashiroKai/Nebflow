package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.JsonObject
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.tools.{RelayExecAudit, RemoteExecutor, ToolContext}

import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * F1（2026-09-11 砂箱收口批）——**p2p 审计接线点**（`RemoteExecutor.scala:431`
 * `auditDispatch(peer, toolName, params, projectRoot, "p2p") *> attempt(0)`）的回归网。
 *
 * WHY 要有它：T4 交付的 relay/P2P 审计只有 **relay 一半**被钉死——
 * `RemoteExecutorAuditSpec` 是 relay E2E（peer 地址写死死端点 `127.0.0.1:9`，走的是
 * relay 隧道），全测试树里唯一的 `via=p2p` 断言是 `RelayExecAuditSpec:152`，而它调的是
 * `RelayExecAudit.record(...)`（`:126`）——**审计模块自身**，不是接线点。后果：摘掉
 * `:431` 那行后全套 spec 仍全绿（上游独立复核实证 `01-红绿表与验红实证.md:70` / `:116` F1）。
 *
 * 本 spec 做了什么（真驱动，非降级形态）：
 *   - `NeblinkService.create(...)` + `setRelayClient(None)` ⇒ `executeViaBestPath` 落到
 *     「无 relay ⇒ P2P only」分支（`RemoteExecutor.scala:484-490`）⇒ 真调
 *     `p2pExecuteWithRetry` ⇒ 真发 HTTP `POST /api/neblink/remote-exec` 到 **in-process
 *     stub 对端**（`HttpServer`，127.0.0.1 ephemeral 端口，无外网、无真实设备）。
 *   - 断言对象 = **接线点产出**（该次下发后审计文件里恰 1 行、`via=p2p`、字段合契约），
 *     不是审计模块内部（redact 矩阵另有 `nebflow.core.RelayExecAuditSpec`）。
 *   - 两条关键语义：①一次逻辑下发**恰 1 行**（含真重试 4 次的下发，重试不重复记行）；
 *     ②**下发失败也记行**（`RemoteExecutor.scala:429-431` 注释口径：「审计关心试图驱动了
 *     哪台设备」）。
 *
 * 契约（沿用既有实现，**不改生产契约**）：审计行落 `PathUtil.dataRoot/logs/relay-exec-audit.jsonl`
 * （本 spec 把 dataRoot 钉到临时目录），字段 `deviceId`(来源=本机 Neblink 身份) /
 * `targetDeviceId`(对端) / `via` / `action` / `command`(redact 后) / `projectRoot` / `cwd`。
 *
 * 实现细节：`RemoteExecutor.initialize` 写的是进程内全局实例（与既有
 * `RemoteExecutorAuditSpec` / `RemoteExecutorClientConvergenceSpec` 同款用法；`Test /
 * parallelExecution := false` 保证串行，无跨 suite 竞态）。
 */
class RemoteExecutorP2pAuditSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-p2p-audit-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def auditLines: List[String] =
    val p = RelayExecAudit.auditFile
    if !Files.exists(p) then Nil else Files.readAllLines(p).asScala.toList

  /**
   * 对端设备 stub：只实现 p2p 下发的落点 `POST /api/neblink/remote-exec`
   * （返回 `{"output":…,"error":""}`，`RemoteExecutor.p2pExecute` 的应答契约）。
   * 记录每次收到的 (path, X-Neblink-Device 头, body) —— 「p2p 路径真被走」的旁证。
   */
  private final class StubPeerServer:
    private val pool = Executors.newFixedThreadPool(2)
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val requests = new ConcurrentLinkedQueue[(String, String, String)]()

    server.setExecutor(pool)
    server.createContext(
      "/api/neblink/remote-exec",
      (ex: HttpExchange) =>
        val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val dev = Option(ex.getRequestHeaders.getFirst(Protocol.DeviceHeader)).getOrElse("")
        requests.add((ex.getRequestURI.getPath, dev, body))
        val bytes = """{"output":"p2p-ok","error":""}""".getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()

    /** `PeerInfo.address` 形态（RemoteExecutor 直接拼 `${address}/api/neblink/remote-exec`）。 */
    def address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

    def close(): Unit =
      server.stop(0)
      pool.shutdownNow()
      ()

  /** 刚释放的本机端口（绑定即关闭）⇒ 连接必被拒（`isTransientError` 的可重试类失败）。 */
  private def freeTcpPort(): Int =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  // ── ① 真 p2p 下发（成功）⇒ 接线点恰 1 行 via=p2p ──────────────────────────

  test("p2p 真下发：接线点恰落 1 行 via=p2p 审计（来源/对端/redact 命令/projectRoot）") {
    val secret = "sk-live-P2PSECRETVALUE123456"
    val cmd = s"export API_TOKEN=$secret; ssh user@10.1.1.7 uptime"
    IO.blocking(new StubPeerServer()).flatMap { stub =>
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          ms <- NeblinkService.create(0, dispatcher)
          // 无 relay client ⇒ executeViaBestPath 走「P2P only」⇒ 真驱动 p2pExecuteWithRetry
          _ = ms.setRelayClient(None)
          _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
          // 对端地址指向 in-process stub：无真实设备、无外网、无 relay 隧道
          _ <- ms.upsertPeer(PeerInfo("peer-1", "peer-one", "macos", stub.address))
          srcId <- ms.identity.map(_.deviceId)
          res <- RemoteExecutor.current
            .get
            .execute(
              "peer-one",
              "Bash",
              JsonObject("command" -> cmd.asJson),
              Some(ToolContext(projectRoot = "/tmp/qa-p2p-proj"))
            )
          reqs <- IO.blocking(stub.requests.asScala.toList)
          lines <- IO.blocking(auditLines)
        yield (res, reqs, lines, srcId)
      }.guarantee(IO.blocking(stub.close()))
    }.map { out =>
      val (result, reqs, lines, srcId) = out
      // —— 先证「真走了 p2p」：stub 对端真收到那次下发 ——
      assertEquals(result, Right("p2p-ok"), s"p2p 下发必须由 stub 对端应答: $result")
      assertEquals(reqs.length, 1, s"stub 对端恰好收到 1 次 p2p 下发: $reqs")
      val (hitPath, devHeader, body) = reqs.head
      assertEquals(hitPath, "/api/neblink/remote-exec", "p2p 落点路径")
      assertEquals(devHeader, srcId, "p2p 请求携带本机 deviceId 头（证明是本机直连下发）")
      assertEquals(
        parse(body).toOption.flatMap(_.hcursor.downField("action").as[String].toOption),
        Some("Bash"),
        s"下发体里的 action: $body"
      )
      // —— 再断言接线点产出：恰 1 行、via=p2p、字段合契约 ——
      assertEquals(lines.length, 1, s"一次逻辑下发恰 1 行审计: $lines")
      val c = parse(lines.head).fold(e => fail(s"invalid JSONL: $e"), identity).hcursor
      assertEquals(c.downField("via").as[String].toOption, Some("p2p"), "审计行必须来自 p2p 接线点")
      assertEquals(c.downField("deviceId").as[String].toOption, Some(srcId), "来源 = 本机 NebLink 身份")
      assertEquals(c.downField("targetDeviceId").as[String].toOption, Some("peer-1"))
      assertEquals(c.downField("action").as[String].toOption, Some("Bash"))
      assertEquals(c.downField("projectRoot").as[String].toOption, Some("/tmp/qa-p2p-proj"))
      val shown = c.downField("command").as[String].toOption.getOrElse(fail("command missing"))
      assert(!shown.contains(secret), s"明文密钥落盘: $shown")
      assert(!shown.contains("P2PSECRET"), s"明文密钥落盘: $shown")
      assert(shown.contains("API_TOKEN=[redacted"), s"密钥位已遮蔽: $shown")
      assert(shown.contains("ssh user@10.1.1.7"), s"非密钥部分保留可读: $shown")
    }
  }

  // ── ② 真 p2p 下发（失败 + 真重试）⇒ 仍恰 1 行 via=p2p ────────────────────

  test("p2p 下发失败且真重试 4 次：接线点仍恰 1 行 via=p2p（失败也记、重试不重复记行）") {
    val deadPort = freeTcpPort()
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        _ = ms.setRelayClient(None)
        _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
        // 死端点：连接必被拒 ⇒ 「Cannot reach …」= isTransientError ⇒ 走满 3 次重试（1+2+3s）
        _ <- ms.upsertPeer(PeerInfo("peer-1", "peer-one", "macos", s"http://127.0.0.1:$deadPort"))
        t0 <- IO.monotonic
        res <- RemoteExecutor.current
          .get
          .execute(
            "peer-one",
            "Bash",
            JsonObject("command" -> "uname -a".asJson),
            Some(ToolContext(projectRoot = "/tmp/qa-p2p-proj"))
          )
        elapsed <- IO.monotonic.map(_ - t0)
        lines <- IO.blocking(auditLines)
      yield (res, elapsed, lines)
    }.map { out =>
      val (result, elapsed, lines) = out
      val msg = result.fold(e => e.message, ok => s"unexpected success: $ok")
      assert(msg.startsWith("Cannot reach"), s"期望可重试类连接失败: $result")
      // 重试 3 次（1s+2s+3s 退避）⇒ 4 次网络尝试；耗时下界证明重试链真的跑了
      assert(elapsed >= 5.seconds, s"重试必须真的发生（退避 1+2+3s）: elapsed=$elapsed")
      assertEquals(lines.length, 1, s"失败 + 重试后仍恰 1 行审计（不按尝试次数记）: $lines")
      val c = parse(lines.head).fold(e => fail(s"invalid JSONL: $e"), identity).hcursor
      assertEquals(c.downField("via").as[String].toOption, Some("p2p"), "审计行必须来自 p2p 接线点")
      assertEquals(c.downField("targetDeviceId").as[String].toOption, Some("peer-1"))
      assertEquals(c.downField("action").as[String].toOption, Some("Bash"))
      assertEquals(c.downField("projectRoot").as[String].toOption, Some("/tmp/qa-p2p-proj"))
    }
  }

end RemoteExecutorP2pAuditSpec
