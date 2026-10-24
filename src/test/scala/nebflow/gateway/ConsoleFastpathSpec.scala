package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.neblink.NeblinkService
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/**
 * 设备识别快速通道 + 请求打包 验收 spec（console-fastpath 批 2026-10-23，
 * 作者令腿①②）：
 *
 *  ① `GET /api/neblink/nearpath` 识别面（[[NeblinkRoutes]]）：
 *     · bound 命中本机身份 ⇒ enrolled=true + lanAddresses 数组 + port；
 *     · bound 未命中      ⇒ enrolled=false + lanAddresses 恒空（作者令「不是
 *       就走 relay」的服务端强制——LAN 拓扑面不给未登记设备）；
 *     · 匿名 ⇒ 401/403（引导腿不开——近路不是引导链的一部分）。
 *  ② `POST /api/console-assets` 打包面（[[ConsoleAssetBatchRoutes]]）：
 *     · 白名单路径 ⇒ assets 入账（contentType + base64 字节与 classpath 源
 *       逐字节一致）；traversal / 超前缀 / 超体积 ⇒ missing[]；
 *     · 请求数护栏（>64 截断）。
 *  ③ 隧道首开打包转换（[[StaticRoutes.packedIndexHtml]] / rewriteCssUrls，
 *    纯函数直钉）：stylesheet link 全部内联成 <style>、media 属性保留、
 *    vendor defer script 内联、module script 与 favicon 逐字保留、CSS 相对
 *    url() 重写为绝对（katex fonts 用例）。
 */
class ConsoleFastpathSpec extends CatsEffectSuite:

  private val TestToken = "test-token-console-fastpath"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-console-fastpath-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── ① nearpath 识别面 ────────────────────────────────────

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpDir, os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private def mkStack(dispatcher: Dispatcher[IO]): IO[(NeblinkService, RestApiRoutes)] =
    NeblinkService.create(0, dispatcher).map { ms =>
      val routes = new RestApiRoutes(
        tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
        configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
          NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
        ),
        sharedResources = mkResources,
        sessionStore = null,
        wsRoutes = null,
        neblinkService = Some(ms)
      )
      (ms, routes)
    }

  private def nearpathReq(bound: String): Request[IO] =
    SpecRequests.withRemote(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/neblink/nearpath?bound=$bound"))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, TestToken))),
      Some(SpecRequests.Lan)
    )

  test("① nearpath：bound 命中本机身份 ⇒ enrolled=true + lanAddresses 数组 + port") {
    Dispatcher.parallel[IO].use { d =>
      mkStack(d).flatMap { case (ms, routes) =>
        ms.identity.flatMap { id =>
          routes.routes(nearpathReq(id.deviceId)).value.map {
            case None => fail("route fell through")
            case Some(resp) =>
              assertEquals(resp.status, Status.Ok)
              val json = parse(new String(resp.body.compile.toVector.unsafeRunSync().toArray, StandardCharsets.UTF_8))
                .getOrElse(fail("body not json"))
              assertEquals(json.hcursor.get[Boolean]("enrolled").getOrElse(false), true)
              assertEquals(json.hcursor.get[String]("matchedDeviceId").getOrElse(""), id.deviceId)
              assert(json.hcursor.get[List[String]]("lanAddresses").isRight, "lanAddresses must be an array")
              assert(json.hcursor.get[Int]("port").getOrElse(0) > 0, "port must be a positive int")
          }
        }
      }
    }
  }

  test("① nearpath：bound 未命中 ⇒ enrolled=false 且 lanAddresses 恒空（不走快速通道，服务端强制）") {
    Dispatcher.parallel[IO].use { d =>
      mkStack(d).flatMap { case (_, routes) =>
        routes.routes(nearpathReq("no-such-device-xyz")).value.map {
          case None => fail("route fell through")
          case Some(resp) =>
            assertEquals(resp.status, Status.Ok)
            val json = parse(new String(resp.body.compile.toVector.unsafeRunSync().toArray, StandardCharsets.UTF_8))
              .getOrElse(fail("body not json"))
            assertEquals(json.hcursor.get[Boolean]("enrolled").getOrElse(true), false)
            assertEquals(json.hcursor.get[List[String]]("lanAddresses").getOrElse(List("x")), List.empty)
        }
      }
    }
  }

  test("① nearpath：匿名 ⇒ 401/403（近路识别面不开引导腿）") {
    Dispatcher.parallel[IO].use { d =>
      mkStack(d).flatMap { case (_, routes) =>
        val anon = SpecRequests.withRemote(
          Request[IO](Method.GET, Uri.unsafeFromString("/neblink/nearpath?bound=x")),
          Some(SpecRequests.LoopbackV4)
        )
        routes.routes(anon).value.map {
          case None => fail("route fell through")
          case Some(resp) => assert(Set(Status.Unauthorized, Status.Forbidden).contains(resp.status))
        }
      }
    }
  }

  // ── ② console-assets 打包面 ───────────────────────────────

  private def batchReq(paths: List[String]): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/console-assets"))
      .withEntity(Json.obj("paths" -> Json.fromValues(paths.map(Json.fromString))))

  private def batchJson(paths: List[String]): Json =
    ConsoleAssetBatchRoutes
      .routes(batchReq(paths))
      .value
      .map {
        case None => fail("route fell through")
        case Some(resp) =>
          assertEquals(resp.status, Status.Ok)
          parse(new String(resp.body.compile.toVector.unsafeRunSync().toArray, StandardCharsets.UTF_8))
            .getOrElse(fail("body not json"))
      }
      .unsafeRunSync()

  private def classpathBytes(path: String): Array[Byte] =
    val in = getClass.getClassLoader.getResourceAsStream(path)
    assert(in != null, s"classpath resource missing: $path")
    try in.readAllBytes()
    finally in.close()

  test("② console-assets：白名单 css 入账，字节与 classpath 源逐字节一致，contentType 正确") {
    val json = batchJson(List("css/base.css"))
    val assets = json.hcursor.downField("assets")
    assert(assets.downField("css/base.css").succeeded, "css/base.css must be packed")
    assertEquals(
      assets.downField("css/base.css").downField("contentType").as[String].getOrElse(""),
      "text/css"
    )
    val b64 = assets.downField("css/base.css").downField("body").as[String].getOrElse("")
    assertEquals(Base64.getDecoder.decode(b64).toList, classpathBytes("web/css/base.css").toList)
    assertEquals(json.hcursor.downField("missing").as[List[String]].getOrElse(Nil), Nil)
  }

  test("② console-assets：traversal / 超前缀 / 超体积一律 missing，无存在性预言面") {
    val json = batchJson(List("../secret.txt", "api/nf-ticket", "js/../../etc/passwd", "vendor/xlsx.full.min.js"))
    assertEquals(
      json.hcursor.downField("missing").as[List[String]].getOrElse(Nil).toSet,
      Set("../secret.txt", "api/nf-ticket", "js/../../etc/passwd", "vendor/xlsx.full.min.js")
    )
    assert(json.hcursor.downField("assets").focus.exists(_.asObject.exists(_.isEmpty)))
  }

  test("② console-assets：请求数护栏 >64 截断") {
    val many = (1 to 70).map(i => s"css/nonexistent-$i.css").toList
    val json = batchJson(many)
    // 前 64 个进判据（全部 404 → missing），后 6 个被截断、无声消失。
    assertEquals(json.hcursor.downField("missing").as[List[String]].getOrElse(Nil).length, 64)
  }

  test("② console-assets：vendor 脚本与根 favicon 也在白名单内") {
    val json = batchJson(List("vendor/marked.min.js", "favicon.svg"))
    val assets = json.hcursor.downField("assets")
    assert(assets.downField("vendor/marked.min.js").succeeded)
    assert(assets.downField("favicon.svg").succeeded)
    assertEquals(assets.downField("favicon.svg").downField("contentType").as[String].getOrElse(""), "image/svg+xml")
  }

  // ── ③ 首开打包转换（纯函数）──────────────────────────────

  private lazy val indexHtml: String =
    new String(classpathBytes("web/index.html"), StandardCharsets.UTF_8)

  test("③ packedIndexHtml：stylesheet link 全部内联、media 保留、url() 重写、module/favicon 逐字保留") {
    val packed = StaticRoutes.packedIndexHtml(indexHtml)
    assert(!packed.contains("<link rel=\"stylesheet\""), "no stylesheet link may survive packing")
    // media 查询保留（highlight 双主题语义）。
    assert(
      packed.contains("<style media=\"(prefers-color-scheme: light)\">"),
      "media attr must be preserved on the style tag"
    )
    // katex 字体相对引用重写为 /vendor/ 绝对基（内联 <style> 的解析基是文档）。
    assert(
      packed.contains("url(/vendor/fonts/KaTeX_AMS-Regular.woff2)"),
      "relative css url() must be rewritten absolute against the asset dir"
    )
    // module 脚本与 favicon 链接逐字保留。
    assert(packed.contains("<script type=\"module\" src=\"js/main.js\"></script>"))
    assert(packed.contains("favicon-32.png"))
    // 打包后仍是合法 HTML 骨架。
    assert(packed.startsWith("<!DOCTYPE html>"))
    assert(packed.contains("</head>") && packed.contains("</html>"))
  }

  test("③ packedIndexHtml：5 个 vendor defer script 全部内联（首开瀑布 45→1 的机械保证）") {
    val packed = StaticRoutes.packedIndexHtml(indexHtml)
    for f <- List("lucide.min.js", "lottie.min.js", "marked.min.js", "katex.min.js", "highlight.min.js") do
      assert(!packed.contains(s"""<script defer src="vendor/$f"></script>"""), s"$f must be inlined")
    assert(packed.length > indexHtml.length, "packed body must grow (bytes inlined)")
    // 内联脚本不得含未转义的 </script 字面量（突破加固）。
    val inlineScripts = "<script>".r.findAllIn(packed).length
    assert(inlineScripts >= 5, s"expected >=5 inline scripts, got $inlineScripts")
  }

  test("③ rewriteCssUrls：相对引用重写、绝对/data/http 引用不碰") {
    val css = "a{background:url(fonts/x.woff2)}b{background:url(\"fonts/y.woff#iefix\")}" +
      "c{background:url(/abs/z.png)}d{background:url(data:image/png;base64,AA)}e{background:url(https://e/x.css)}"
    val out = StaticRoutes.rewriteCssUrls(css, "/vendor/")
    assert(out.contains("url(/vendor/fonts/x.woff2)"))
    assert(out.contains("url(\"/vendor/fonts/y.woff#iefix\")"))
    assert(out.contains("url(/abs/z.png)"))
    assert(out.contains("url(data:image/png;base64,AA)"))
    assert(out.contains("url(https://e/x.css)"))
  }

end ConsoleFastpathSpec
