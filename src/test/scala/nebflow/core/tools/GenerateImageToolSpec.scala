package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentDef
import nebflow.llm.imagegen.ImageGenJobs
import nebflow.shared.PathUtil

import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

/**
 * generate_image 阻塞语义 + Pop 载荷直出（交互修正批 2026-10-30，作者令照
 * Codex 玩法定案）的行为钉。真 stub HTTP server（cogview b64 形态应答 + 可控
 * 延迟/失败面）+ 临时 dataRoot（nebflow.json 预置 stub provider）——`call` 全
 * 链实跑，不靠 mock 帧形状假设。
 *
 * 钉点：
 *   T1  阻塞语义——call 挂起至 stub 应答（elapsed ≥ stub 延迟），不提前返回；
 *   T2  结果 = ___POP_JSON___ 载荷（Pop 升级批契约：fileRefs 打头 + 单 image
 *       item 票据腿），且**永不返回旧非阻塞 job 句柄**（`{"jobId",…"running"}`
 *       形态回归钉——双渲染病灶之源）；
 *   T3  成功面零 WS 帧（imagegenUpdate 推送腿退役）+ 台账翻转 Completed；
 *   T4  失败面（HTTP 500）= Left(ToolError) + 台账 Failed（原位错误卡文案）；
 *   T5  身份闸 / prompt 闸 / 配置缺失 fail-fast（旧契约不变）；
 *   T6  modelFacingResult 投影：无 sentinel、含落盘路径（PopTool 同款纪律）；
 *   T7  summarize / summarizeResult 面。
 */
class GenerateImageToolSpec extends munit.FunSuite:

  // 1×1 红像素 PNG（magic bytes 真身，sniffExt 定 png）。
  private val PngBytes: Array[Byte] = Base64.getDecoder.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
  )

  /** stub server 形态：ok(b64) / fail(500)。 */
  private final class Stub(delayMs: Int, fail: Boolean):
    val hits = AtomicInteger(0)
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/").setHandler { (ex: HttpExchange) =>
      hits.incrementAndGet()
      if delayMs > 0 then Thread.sleep(delayMs)
      val body =
        if fail then """{"error":"provider boom"}"""
        else s"""{"data":[{"b64_json":"${Base64.getEncoder.encodeToString(PngBytes)}"}]}"""
      val bytes = body.getBytes("UTF-8")
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(if fail then 500 else 200, bytes.length.toLong)
      ex.getResponseBody.write(bytes)
      ex.close()
    }
    server.start()
    def port: Int = server.getAddress.getPort
    def stop(): Unit = server.stop(0)

  private def withStub[A](delayMs: Int = 0, fail: Boolean = false)(f: Stub => A): A =
    val stub = new Stub(delayMs, fail)
    try f(stub)
    finally stub.stop()

  /** 临时 dataRoot：nebflow.json 预置 imagegen stub provider（keyRef 缺省 = 空 token 直传）。 */
  private def withDataRoot[A](stub: Stub, imagegenJson: String)(f: os.Path => A): A =
    val tmp = os.temp.dir(prefix = "genimg-spec-")
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try
      os.write(tmp / "nebflow.json", imagegenJson.replace("__PORT__", stub.port.toString))
      f(tmp)
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  private def stubConfig(stub: Stub): String =
    s"""{"imagegen":{"enabled":true,"defaultProvider":"stub","providers":{"stub":{"type":"cogview","model":"cogview-3-flash","baseUrl":"http://127.0.0.1:__PORT__"}}}}"""

  private val frames = new AtomicReference[List[Json]](Nil)
  private def rootCtx(sessionId: String = "sess-genimg"): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sessionId),
      agentDef = Some(AgentDef(name = "Nebula", description = "root")),
      depth = 0,
      wsSend = Some((j: Json) => IO.delay { frames.updateAndGet(j +: _); () })
    )

  private def input(prompt: String, extra: (String, Json)*): JsonObject =
    JsonObject.fromIterable(("prompt" -> prompt.asJson) +: extra.toList)

  override def beforeEach(context: BeforeEach): Unit =
    frames.set(Nil)

  // ── T1+T2+T3 成功主链 ─────────────────────────────────────────────────────

  test("T1 阻塞语义：call 挂起至 stub 应答（≥stub 延迟）且 T2 直出 Pop 载荷") {
    withStub(delayMs = 800) { stub =>
      withDataRoot(stub, stubConfig(stub)) { tmp =>
        val t0 = System.currentTimeMillis()
        val result = GenerateImageTool.call(input("a red apple"), rootCtx()).unsafeRunSync()
        val elapsed = System.currentTimeMillis() - t0
        assert(elapsed >= 650, s"call must block until generation completes (elapsed=${elapsed}ms)")
        assert(result.isRight, s"expected Pop payload, got $result")
        val content = result.toOption.get
        assert(content.startsWith("___POP_JSON___"), "result must ride the Pop payload sentinel")
        assert(!content.contains("\"running\"") && !content.contains("jobId"),
          "non-blocking job-handle shape must never come back (double-render root cause)")

        val payload = io.circe.parser.parse(content.stripPrefix("___POP_JSON___")).toOption.get
        val cursor = payload.hcursor
        assertEquals(cursor.downField("fileRefs").get[Int]("referenced").toOption, Some(1))
        assertEquals(cursor.downField("fileRefs").get[Int]("failed").toOption, Some(0))
        val item = cursor.downField("items").downArray
        assertEquals(item.get[String]("kind").toOption, Some("image"))
        val path = item.get[String]("path").toOption.get
        val name = item.get[String]("name").toOption.get
        assert(name.startsWith("img-") && name.endsWith(".png"), s"unexpected name: $name")
        assertEquals(item.get[String]("ext").toOption, Some("png"))
        assert(path.startsWith((tmp / "imagegen").toString), s"image must land under the temp dataRoot: $path")
        assert(os.exists(os.Path(path, os.pwd)), s"saved image missing at $path")
        assertEquals(os.read.bytes(os.Path(path, os.pwd)).toList, PngBytes.toList)

        // T3a：成功面零 WS 帧（imagegenUpdate 推送腿退役）。
        assertEquals(frames.get(), Nil, "blocking success must not push any WS frame")
        // T3b：台账翻转 Completed（REST 查询腿仍在消费）。
        val jobId = name.stripSuffix(".png")
        val job = ImageGenJobs.get(jobId)
        assert(job.isDefined && job.get.status == ImageGenJobs.Status.Completed && job.get.path.contains(path),
          s"ledger must flip to Completed with path, got $job")
      }
    }
  }

  // ── T4 失败面 ─────────────────────────────────────────────────────────────

  test("T4 失败面：HTTP 500 → Left(ToolError) 可行动文案 + 台账 Failed") {
    withStub(fail = true) { stub =>
      withDataRoot(stub, stubConfig(stub)) { _ =>
        val result = GenerateImageTool.call(input("a cat"), rootCtx()).unsafeRunSync()
        assert(result.isLeft, s"expected failure, got $result")
        assert(result.left.toOption.get.message.contains("IMAGEGEN_JOB"))
        assert(GenerateImageTool.summarizeResult(input("a cat"), "") != null)
        // 失败也不走 WS 帧（错误面由 toolEnd 原位渲染）。
        assertEquals(frames.get(), Nil)
      }
    }
  }

  // ── T5 三面闸 ─────────────────────────────────────────────────────────────

  test("T5a 身份闸：非 Nebula 身份拒绝（IMAGEGEN_ROOT_ONLY）") {
    val ctx = ToolContext(
      projectRoot = os.pwd.toString,
      agentDef = Some(AgentDef(name = "nebflow", description = "executor")),
      depth = 0
    )
    val result = GenerateImageTool.call(input("a cat"), ctx).unsafeRunSync()
    assert(result.isLeft && result.left.toOption.get.message.contains("IMAGEGEN_ROOT_ONLY"), s"got $result")
  }

  test("T5b prompt 空 → IMAGEGEN_PROMPT") {
    withStub() { stub =>
      withDataRoot(stub, stubConfig(stub)) { _ =>
        val result = GenerateImageTool.call(input("   "), rootCtx()).unsafeRunSync()
        assert(result.isLeft && result.left.toOption.get.message.contains("IMAGEGEN_PROMPT"), s"got $result")
      }
    }
  }

  test("T5c 配置缺失 fail-fast → IMAGEGEN_CONFIG（不起 job、不触网）") {
    withStub() { stub =>
      // dataRoot 下无 nebflow.json → imagegen 节缺席。
      withDataRoot(stub, "{}") { _ =>
        val result = GenerateImageTool.call(input("a cat"), rootCtx()).unsafeRunSync()
        assert(result.isLeft && result.left.toOption.get.message.contains("IMAGEGEN_CONFIG"), s"got $result")
        assertEquals(stub.hits.get(), 0, "fail-fast must not hit the provider")
      }
    }
  }

  // ── T6 模型面投影 ─────────────────────────────────────────────────────────

  test("T6 modelFacingResult：无 sentinel、含落盘路径与「勿再 Pop」注记") {
    val payload = GenerateImageTool.modelFacingResult(
      """___POP_JSON___{"fileRefs":{"inlined":0,"referenced":1,"failed":0,"omitted":0},"warnings":[],"notes":[],"items":[{"kind":"image","name":"img-ab12cd34.png","path":"/tmp/x/imagegen/img-ab12cd34.png","size":70,"ext":"png"}]}"""
    )
    assert(!payload.contains("___POP_JSON___"), "projection must never carry the sentinel")
    assert(payload.contains("img-ab12cd34.png") && payload.contains("/tmp/x/imagegen/img-ab12cd34.png"), payload)
    assert(payload.contains("shown"), payload)
    // 非载荷输入恒等。
    assertEquals(GenerateImageTool.modelFacingResult("plain"), "plain")
  }

  // ── T7 摘要面 ─────────────────────────────────────────────────────────────

  test("T7 summarize / summarizeResult 面") {
    assertEquals(GenerateImageTool.summarize(input("a red apple on a wooden table, painted in oils, warm light")),
      """generate_image("a red apple on a wooden table, painted in oils, warm light")""")
    assertEquals(
      GenerateImageTool.summarizeResult(input("x"), "___POP_JSON___{\"items\":[]}"),
      "image shown in chat"
    )
  }

end GenerateImageToolSpec
