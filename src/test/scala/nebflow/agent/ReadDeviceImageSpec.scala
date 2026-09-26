package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.shared.ToolExecResult
import nebflow.core.tools.{RemoteExecutor, ToolContext}
import nebflow.neblink.{NeblinkService, PeerInfo}
import nebflow.shared.ToolCall

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Base64
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

/**
 * 设备腿（remote-exec）读图 ⇒ 视觉块注入 —— A1（作者裁定 2026-09-16）的引擎面回归网。
 *
 * 缺陷（`devread-forensic` 判词，两跳并发必要）：设备腿的结果域恒为 `String`
 * （L2/L3 执行侧序列化 + L4/L5 调用侧解析），而调用侧远端分支
 * （`AgentCore.scala:1692-1700`）从不置 `imageBlocks`（对照本地分支 `:1763/:1774`）
 * ⇒ 跨设备 `Read` 只把 `[image: …]` 标记文本交给 LLM，**视觉块从未产生**。
 *
 * 本 spec 钉的判据（**驱动真引擎路径**，不是直接调 helper）：`AgentCore.executeTool`
 * 的远端分支完整走一遍 —— 解析 → `RemoteExecutor.execute` → P2P 下发（桩对端）
 * → 结果回传 → 视觉块注入。故注入点那行被还原/注释时本 spec 必红（判词基准的
 * 「变异验红」）。
 *
 * 离线纪律：桩对端 = 进程内 `HttpServer`（`127.0.0.1` 临时端口），**零真机、
 * 零 8080、零动作端点调用**；`FileTransfer` 回拉面由桩承载（钉5）。
 */
class ReadDeviceImageSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-read-device-image")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** 引擎面探针：暴露 `executeTool`（trait 内 protected）。 */
  private final class TestCore extends AgentCore:
    def exec(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] = executeTool(call, ctx)

  /**
   * 桩对端（`POST /api/neblink/remote-exec`，报文契约与 `RemoteExecutor.p2pExecuteAt`
   * 一致）：`readOutput` 是 Read 动作的 `output`；`transferJson` 是 FileTransfer 动作
   * 返回的**工具结果字符串**（外层再包 `output` —— 与 `NeblinkRoutes.scala`
   * remote-exec 出口 `Json.obj("output" -> …)` 的既有信封同形）。
   */
  private final class StubPeerServer(readOutput: String, transferJson: String):
    private val pool = Executors.newFixedThreadPool(4)
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private val bodies = new ConcurrentLinkedQueue[String]()

    server.setExecutor(pool)

    server.createContext(
      "/api/neblink/remote-exec",
      (ex: HttpExchange) =>
        val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        bodies.add(body)
        val action = io.circe.parser
          .parse(body)
          .toOption
          .flatMap(_.hcursor.get[String]("action").toOption)
          .getOrElse("?")
        val envelope = action match
          case "Read" => Json.obj("output" -> readOutput.asJson, "error" -> "".asJson)
          case "FileTransfer" => Json.obj("output" -> transferJson.asJson, "error" -> "".asJson)
          // 画像探针（Read 前的那一条只读 Bash）与其它动作：空成功即可。
          case _ => Json.obj("output" -> "".asJson, "error" -> "".asJson)
        val bytes = envelope.noSpaces.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()

    def address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

    /** 收到的动作名（按到达顺序）——「有没有为纯文本结果白发一次回拉」的读数面。 */
    def actions: List[String] =
      bodies.stream().toArray.toList.map(_.toString).map { b =>
        io.circe.parser
          .parse(b)
          .toOption
          .flatMap(_.hcursor.get[String]("action").toOption)
          .getOrElse("?")
      }

    def close(): Unit =
      server.stop(0)
      pool.shutdownNow()
      ()

  end StubPeerServer

  /** 建一个可源件比对的 PNG（ImageIO 可解码 ⇒ `prepareImage` 原样返回）。 */
  private def writePng(name: String): (String, Array[Byte]) =
    val img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try
      g.setColor(java.awt.Color.RED)
      g.fillRect(0, 0, 4, 4)
    finally g.dispose()
    val path = tmpDir.resolve(name)
    javax.imageio.ImageIO.write(img, "png", path.toFile)
    (path.toString, Files.readAllBytes(path))

  /** 真下发一次 `Read(device=…)`：桩对端 + 真 NeblinkService + 真 RemoteExecutor。 */
  private def readOnDevice(
    readOutput: String,
    transferJson: String,
    filePath: String
  ): IO[(ToolExecResult, List[String])] =
    IO.blocking(new StubPeerServer(readOutput, transferJson)).flatMap { stub =>
      Dispatcher
        .parallel[IO]
        .use { dispatcher =>
          for
            ms <- NeblinkService.create(0, dispatcher)
            _ = ms.setRelayClient(None)
            _ <- IO(RemoteExecutor.initialize(ms, dispatcher, None))
            _ <- ms.upsertPeer(PeerInfo("peer-1", "peer-one", "macos", stub.address))
            ctx = ToolContext(projectRoot = tmpDir.toString)
            call = ToolCall(
              id = "call-1",
              name = "Read",
              input = JsonObject(
                "file_path" -> filePath.asJson,
                "device" -> "peer-one".asJson
              )
            )
            result <- new TestCore().exec(call, ctx)
            seen <- IO.blocking(stub.actions)
          yield (result, seen)
        }
        .guarantee(IO.blocking(stub.close()))
    }

  test("设备腿读图：远端 `[image: …]` 标记 ⇒ 经既有 FileTransfer 回拉 ⇒ imageBlocks 非空且块与源件一致"):
    val (pngPath, pngBytes) = writePng("shot.png")
    val b64 = Base64.getEncoder.encodeToString(pngBytes)
    val marker = "[image: shot.png | image/png | 1KB]"
    val transfer = Json.obj("size" -> pngBytes.length.asJson, "content" -> b64.asJson).noSpaces

    readOnDevice(marker, transfer, pngPath).map { case (result, actions) =>
      assert(
        actions.contains("FileTransfer"),
        s"设备腿读图必须走既有 FileTransfer 回拉通道，实际动作序列: $actions"
      )
      assert(!result.isError, s"设备腿读图不得报错: ${result.content}")
      // 原文本标记保留（与本地腿同形：标记 + 块并存）
      assert(result.content.startsWith("[image:"), s"标记文本必须保留: ${result.content}")
      val blocks = result.imageBlocks.getOrElse(fail("设备腿读图的 imageBlocks 必须非空（A1 注入点）"))
      assertEquals(blocks.length, 1, s"恰一块，实际: $blocks")
      assertEquals(blocks.head.mediaType, "image/png", "mime 必须与源件一致")
      assertEquals(blocks.head.data, b64, "块内容必须与源件字节一致（base64 等值）")
    }

  test("设备腿读图降级纪律：回拉超限 ⇒ 零抛错、零中断、保留原文本标记、imageBlocks 为空"):
    val (pngPath, pngBytes) = writePng("huge.png")
    val b64 = Base64.getEncoder.encodeToString(pngBytes)
    // 超出既有大小闸 CapturePull.MaxCaptureBytes（10 MiB）——判据取自既有常量，非自造阈值
    val oversized = Json.obj("size" -> (11L * 1024 * 1024).asJson, "content" -> b64.asJson).noSpaces
    val marker = "[image: huge.png | image/png | 11000KB]"

    readOnDevice(marker, oversized, pngPath).map { case (result, actions) =>
      assert(actions.contains("FileTransfer"), s"回拉腿必须被尝试过: $actions")
      assertEquals(result.isError, false, s"回拉失败不得让工具调用整体失败: ${result.content}")
      assertEquals(result.content, marker, "原文本标记必须逐字保留（同本地腿 catch 兜底口径）")
      assertEquals(result.imageBlocks, None, "降级时不得注入半个块")
    }

  test("负控：纯文本结果 ⇒ 不产生视觉块，且不为它白发一次回拉"):
    val txt = "1\tpackage nebflow.demo\n2\tobject Demo"
    readOnDevice(txt, "{}", "/remote/notes.txt").map { case (result, actions) =>
      assertEquals(result.imageBlocks, None, "文本结果不得注入块")
      assertEquals(result.content, txt, "文本结果逐字保留")
      assert(!actions.contains("FileTransfer"), s"文本结果不得触发回拉: $actions")
    }

end ReadDeviceImageSpec
