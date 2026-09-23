package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.tools.{RemoteExecutor, ToolError}

import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

/**
 * C1 执行层回归网（2026-09-11 P2P 直连修复批，方案 §2 选项 C1 / §3.3 目标口径）。
 *
 * 这一层才是 10× 收益真正兑现的地方：方案 §1.2「关键推论」指出，只把 `skipP2p`
 * 负证据化而不修地址，行为只会从「relay」退化成「P2P 打到不可达地址 → relay」。
 * `p2pExecute` 因此也必须**按候选顺序短超时串行短路**。
 *
 * 驱动方式是**真下发**（不是 mock 决策）：in-process `HttpServer` 桩对端应答
 * `POST /api/neblink/remote-exec`，死端点用「刚释放的本机端口 ⇒ 连接必被拒」制造。
 * 断言对象 = 桩对端**实际收到几次**请求 ⇒ 同时钉住「短路」与「轮转」两侧。
 *
 * 与既有的 `RemoteExecutorP2pAuditSpec` 互补：那条钉 p2p 审计接线点（单端点形态），
 * 这条钉多端点择优。`Test / parallelExecution := false` 保证串行，无竞态。
 */
class RemoteExecutorEndpointCandidateSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-endpoint-candidate-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** 只实现 p2p 下发落点的对端桩（应答契约同 `RemoteExecutor.p2pExecuteAt`）。 */
  private final class StubPeerServer:
    private val pool = Executors.newFixedThreadPool(2)
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = new ConcurrentLinkedQueue[String]()
    private val bodies = new ConcurrentLinkedQueue[String]()

    server.setExecutor(pool)

    server.createContext(
      "/api/neblink/remote-exec",
      (ex: HttpExchange) =>
        requests.add(ex.getRequestURI.getPath)
        bodies.add(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val bytes = """{"output":"p2p-ok","error":""}""".getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()

    def address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

    def hitCount: Int = requests.size()

    /**
     * 只读画像探针的命中数（xdev 批 2026-09-15）。判别锚 = 探针请求体里的字面
     * `xdev read-only profile probe`（生产侧 `RemoteExecutor.scala:306` 的
     * `"description" -> "xdev read-only profile probe"`，随 `p2pExecuteAt`
     * `RemoteExecutor.scala:732-735` 的 `{"action":…,"params":…}` 信封下发）。
     * 探针与业务走**同一**候选轮转/短路逻辑 ⇒ 用它能把这 2 次下发拆开核对，
     * 而不是只对一个合计数。
     */
    def probeHitCount: Int =
      bodies.stream().filter(_.contains("xdev read-only profile probe")).count().toInt

    def close(): Unit =
      server.stop(0)
      pool.shutdownNow()
      ()

  end StubPeerServer

  private def freeTcpPort(): Int =
    val s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  /**
   * 真下发一次。`mkPeer` 拿桩端点的真地址来构造 peer（含候选顺序），返回
   * (结果, 桩命中数, 桩收到的探针数)。无 relay ⇒ 落到 `executeViaBestPath`
   * 的 P2P-only 分支。
   *
   * 命中数口径（xdev 批 2026-09-15）：`execute` 首触先发**一条只读画像探针**
   * （`kind=probe`，`RemoteExecutor.scala:308`；`RemoteExecutor.initialize` 每次
   * 都 new 一个实例 ⇒ `profileMem` 进程内无跨用例残留 ⇒ 每个用例恰 1 次探针），
   * 探针**同走候选轮转/短路**——故一次逻辑下发 = 「探针 + 业务」共 2 次桩命中。
   */
  private def dispatch(mkPeer: String => PeerInfo): IO[(Either[ToolError, String], Int, Int)] =
    IO.blocking(new StubPeerServer()).flatMap { stub =>
      Dispatcher
        .parallel[IO]
        .use { dispatcher =>
          for
            ms <- NeblinkService.create(0, dispatcher)
            _ = ms.setRelayClient(None)
            _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
            _ <- ms.upsertPeer(mkPeer(stub.address))
            res <- RemoteExecutor.current.get
              .execute("peer-one", "Bash", JsonObject("command" -> "uname -a".asJson), None)
            hits <- IO.blocking(stub.hitCount)
            probes <- IO.blocking(stub.probeHitCount)
          yield (res, hits, probes)
        }
        .guarantee(IO.blocking(stub.close()))
    }

  test("C1: 首候选不可达、次候选可达 ⇒ 轮转后成功（改前只打 peer.address ⇒ 必失败）") {
    val dead = freeTcpPort()
    dispatch(stub =>
      PeerInfo(
        deviceId = "peer-1",
        deviceName = "peer-one",
        platform = "macos",
        address = s"http://127.0.0.1:$dead", // 事故形态：head 是不可达的那一个
        endpoints = List(s"http://127.0.0.1:$dead", stub)
      )
    ).map { case (res, hits, probes) =>
      assertEquals(res, Right("p2p-ok"), s"次候选必须被尝试并成功: $res")
      assertEquals(probes, 1, "首触只读画像探针恰 1 次，且探针自身也完成了一次轮转（死 head 被跳过）")
      assertEquals(hits, 2, "可达候选恰收到 2 次下发 = 探针 + 业务各 1 次（轮转后每逻辑下发恰 1 次）")
    }
  }

  test("C1: 首候选可达 ⇒ 短路，不再打扰后续候选") {
    val dead = freeTcpPort()
    dispatch(stub =>
      PeerInfo(
        deviceId = "peer-1",
        deviceName = "peer-one",
        platform = "macos",
        address = stub,
        endpoints = List(stub, s"http://127.0.0.1:$dead")
      )
    ).map { case (res, hits, probes) =>
      assertEquals(res, Right("p2p-ok"))
      assertEquals(probes, 1, "首触只读画像探针恰 1 次（探针同样首候选即成功）")
      assertEquals(hits, 2, "首个成功者即返回（串行短路，不遍历剩余候选）——探针 + 业务各 1 次")
    }
  }

  test("C1: peer.endpoints 为空 ⇒ 退回单 address（legacy peer 行为不回归）") {
    dispatch(stub => PeerInfo(deviceId = "peer-1", deviceName = "peer-one", platform = "macos", address = stub)).map {
      case (res, hits, probes) =>
        assertEquals(res, Right("p2p-ok"), s"无 endpoints 时仍按 address 下发: $res")
        assertEquals(probes, 1, "首触只读画像探针恰 1 次（单端点形态同样下发探针）")
        assertEquals(hits, 2, "退回单 address 后仍是探针 + 业务各 1 次")
    }
  }

end RemoteExecutorEndpointCandidateSpec
