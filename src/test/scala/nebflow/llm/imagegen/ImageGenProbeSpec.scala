package nebflow.llm.imagegen

import io.circe.parser.parse
import nebflow.llm.imagegen.ImageGenTypes.*
import nebflow.llm.imagegen.ImageGenAdapters.*
import nebflow.llm.imagegen.ImageGenProbe.*

/**
 * settings-panel-jev-imagegen 批 ③件（2026-10-29 作者令「然后 Jev/生图，也要
 * 像我们LLM这样，有个按钮来测试可用性」）spec——生图两级探针：
 *  1. 轻探活腿：models 端点判据（2xx = 可用；401/403 = 死 key 状态码原样可见；
 *     404/405 = models 面缺失 → 可达判 + modelsFaceAbsent 带回）；
 *  2. 实跑腿：最小生成体（size 256 / quality low 进 ImageGenRequest；body 由
 *     既有适配器纯函数拼装——零协议第二拼写点）；
 *  3. 🔴 产物纪律：临时目录落盘（os.temp / os.temp.dir 系统临时区），不进
 *     <dataRoot>/imagegen 正式归档；dataUrl 上限；
 *  4. 🔴 脱敏：token 值任何出现抹成 ***；
 *  5. resolveToken：覆写 key > 存储 keyRef > 派生名（凭据面无感化的引擎侧镜像）。
 */
class ImageGenProbeSpec extends munit.FunSuite:

  // ── 1. 轻探活（真实 HTTP 一击，本地 stub）────────────────────────

  private def withStub[A](handler: com.sun.net.httpserver.HttpExchange => Unit)(f: String => A): A =
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", (exchange) => { handler(exchange); exchange.close() })
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)

  test("lightCall: GET {base}/models + 适配器鉴权头；2xx = Ok") {
    withStub({ ex =>
      assert(ex.getRequestMethod == "GET", s"轻探活必须 GET models 面，got ${ex.getRequestMethod}")
      assert(ex.getRequestURI.getPath.endsWith("/models"), s"必须打 /models, got ${ex.getRequestURI.getPath}")
      val auth = ex.getRequestHeaders.getFirst("Authorization")
      assert(auth == "Bearer k-live-1", s"适配器鉴权头在位: $auth")
      val b = "{\"data\":[]}".getBytes
      ex.sendResponseHeaders(200, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      lightCall(CogView, s"$base/", "k-live-1") match
        case Verdict.Ok(latency, absent) =>
          assert(latency >= 0)
          assertEquals(absent, None)
        case other => fail(s"expected Ok, got $other")
    }
  }

  test("lightCall: 401 = Failed（死 key，状态码原样可见 + 🔴 脱敏）") {
    withStub({ ex =>
      val b = "{\"error\":\"bad key k-live-1 (1002)\"}".getBytes
      ex.sendResponseHeaders(401, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      lightCall(CogView, base, "k-live-1") match
        case Verdict.Failed(detail) =>
          assert(detail.startsWith("HTTP 401"), s"状态码原样: $detail")
          assert(!detail.contains("k-live-1"), s"错误面泄漏 key: $detail")
          assert(detail.contains("***"), "key 出现处一律 ***")
        case Verdict.Ok(_, _)                       => fail("401 must not read as Ok")
        case Verdict.Generated(_, _, _, _, _)       => fail("lightCall never generates")
    }
  }

  test("lightCall: 404/405 = models 面缺失 → Ok + modelsFaceAbsent 带回（不谎报鉴权已验证）") {
    withStub({ ex =>
      ex.sendResponseHeaders(404, 0)
      ex.getResponseBody.close()
    }) { base =>
      lightCall(OpenAiImages, base, "k") match
        case Verdict.Ok(_, absent) => assertEquals(absent, Some(404))
        case other                 => fail(s"expected Ok(modelsFaceAbsent), got $other")
    }
  }

  // ── 2. 实跑腿（真实 HTTP 一击 + 产物纪律）────────────────────────

  private val cogViewOkBody =
    """{"data":[{"url":"__TEST__"}]}"""

  test("generateCall: 最小生成体经适配器拼装（cogview size 吸附枚举、quality 进请求面预留）") {
    val body = CogView.buildBody("cogview-4", ImageGenRequest(prompt = "ping", size = Some("256x256"), quality = Some("low")))
    val s = body.noSpaces
    assert(s.contains("\"size\":\"256x256\""), s"cogview-4 任意分辨率原样传: $s")
    assert(s.contains("\"prompt\":\"ping\""), s"prompt 在位: $s")
    val flash = CogView.buildBody("cogview-3-flash", ImageGenRequest(prompt = "ping", size = Some("256x256"), quality = Some("low"))).noSpaces
    assert(flash.contains("\"size\":\"1024x1024\""), s"flash 系吸附最近枚举: $flash")
  }

  test("generateCall: 2xx + url 产物 = Generated（真实字节 + 临时目录落盘 + dataUrl）") {
    // 最小合法 PNG 头 8 字节，经 stub 的 /img 路径以真实 http(s) URL 伺服
    // （真实 provider 给 http url——materialize 的下载腿不吃 data: scheme）。
    val png = Array[Byte](0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte, 0x0d.toByte, 0x0a.toByte, 0x1a.toByte, 0x0a.toByte)
    withStub({ ex =>
      if ex.getRequestURI.getPath == "/img" then
        ex.sendResponseHeaders(200, png.length)
        ex.getResponseBody.write(png)
      else
        // 图引用 = stub 同源的 http URL（getLocalAddress 运行期读端口，handler 内直拼）。
        val real = cogViewOkBody.replace("__TEST__", s"http://127.0.0.1:${ex.getLocalAddress.getPort}/img")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, real.getBytes.length)
        ex.getResponseBody.write(real.getBytes)
    }) { base =>
      generateCall(CogView, base, "cogview-3-flash", "k-shaped-like-real") match
        case Verdict.Generated(latency, bytes, ext, tempPath, dataUrl) =>
          assert(latency >= 0)
          assertEquals(bytes, 8)
          assertEquals(ext, "png") // magic-bytes 嗅探（不臆造格式）
          // 🔴 产物纪律：只落系统临时区（nebflow-imagegen-test- 前缀），不进数据根。
          assert(tempPath.contains("nebflow-imagegen-test-"), s"产物路径在临时区: $tempPath")
          assert(!tempPath.contains("/imagegen/"), s"产物绝不进正式归档: $tempPath")
          val path = os.Path(tempPath)
          assert(os.exists(path), "产物真落了盘")
          assert(os.read.bytes(path).length == 8)
          dataUrl match
            case Some(u) => assert(u.startsWith("data:image/png;base64,"), s"dataUrl 形态: $u")
            case None    => fail("小产物必须带 dataUrl 预览")
        case other => fail(s"expected Generated, got $other")
    }
  }

  test("generateCall: 非 2xx = Failed 状态码原样（🔴 脱敏）") {
    withStub({ ex =>
      val b = "{\"error\":\"quota exceeded for key k-dead-7\"}".getBytes
      ex.sendResponseHeaders(429, b.length)
      ex.getResponseBody.write(b)
    }) { base =>
      generateCall(CogView, base, "cogview-3-flash", "k-dead-7") match
        case Verdict.Failed(detail) =>
          assert(detail.startsWith("HTTP 429"), s"状态码原样: $detail")
          assert(!detail.contains("k-dead-7") && detail.contains("***"), s"脱敏: $detail")
        case _ => fail("429 must not read as Ok/Generated")
    }
  }

  // ── 3+5. dataUrl 上限 / mime 表 / resolveToken ────────────────────

  test("dataUrlOf: ≤ 上限带 dataUrl，超上限只报字节（None）") {
    val small = Array.fill(16)(1.toByte)
    assert(ImageGenProbe.dataUrlOf(small, "png").exists(_.startsWith("data:image/png;base64,")))
    assert(ImageGenProbe.dataUrlOf(small, "jpeg").exists(_.startsWith("data:image/jpeg;base64,")))
    val big = Array.fill(ImageGenProbe.PreviewCapBytes + 1)(1.toByte)
    assertEquals(ImageGenProbe.dataUrlOf(big, "png"), None)
  }

  test("resolveToken: 覆写 key > 存储 keyRef > 派生名（缺件 = Left 带可行动文案）") {
    // 覆写腿：表单现值直通。
    assertEquals(ImageGenProbe.resolveToken(Some(" sk-ovr "), None, "p"), Right("sk-ovr"))
    // 存储/派生腿：keyRef 名下无 secret 件 = Left（MissingCredential 文案）。
    ImageGenProbe.resolveToken(None, Some("no-such-secret-anywhere"), "p") match
      case Left(msg)  => assert(msg.contains("no-such-secret-anywhere"), s"文案带 keyRef 名: $msg")
      case Right(tok) => fail(s"absent secret must be Left, got token len=${tok.length}")
    // 派生名：存储 keyRef 缺席 → imagegen-<name>-key（前端 keyRefFor 镜像）。
    ImageGenProbe.resolveToken(None, None, "no-such-provider-either") match
      case Left(msg)  => assert(msg.contains("imagegen-no-such-provider-either-key"), s"派生名镜像: $msg")
      case Right(tok) => fail(s"derived absent secret must be Left, got token len=${tok.length}")
  }

  test("mimeOf: 嗅探扩展名 → data URL mime（未知扩展照 image/* 透传）") {
    assertEquals(ImageGenProbe.mimeOf("png"), "image/png")
    assertEquals(ImageGenProbe.mimeOf("jpeg"), "image/jpeg")
    assertEquals(ImageGenProbe.mimeOf("webp"), "image/webp")
    assertEquals(ImageGenProbe.mimeOf("gif"), "image/gif")
    assertEquals(ImageGenProbe.mimeOf("avif"), "image/avif")
  }
end ImageGenProbeSpec
