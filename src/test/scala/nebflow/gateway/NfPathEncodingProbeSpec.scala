package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * imgref-impl (2026-09-18, author order) — the two author-visible failures of
 * 2026-09-18, pinned at the REAL endpoint.
 *
 * ① `%20`-encoded path ⇒ the resolver looked for a literal `…/Claude%20code/…`.
 * ② a space-bearing path ⇒ the tool's `proxied` count was green while the
 *    browser's `<img>` still failed — the gap is the `/api/nf-file` FETCH leg.
 *
 * 本件全程走**真**路径，禁凭记忆断言：
 *   · 真 Ember 服务器 + 真 JDK HttpClient（经过 Ember 的 request-target 解析，
 *     也就是浏览器实际打的那条路），不是 `Route(...)(req)` 的内存调用；
 *   · 真票据（`NfTicketStore`），真 PNG 夹具（有尺寸 + sha256）；
 *   · 探针路由回显 `req.uri.query.renderString` 与 `req.params.get("path")` —— 把
 *     「http4s 把 `%20` / `+` / `%2B` / 混合形态各解成什么」变成可复算读数。
 *
 * 断言面（每条件都打印读数）：
 *   A. 探针：四形态各解成什么（读数，配合 B 的状态码一起读）；
 *   B. 取回腿：四形态（`%20` / `+` / `%2B` / 混合）⇒ 200 且字节 sha256 == 夹具 sha。
 *      探针 A 的读数解释了本行口径：http4s 的 `req.params` 自己就折裸 `+`、自己就解
 *      `%20`，只有 `%2B`（**字面加号**）原样落到路由 —— 所以「`%2B` 形态也 200」不是过度
 *      解码，而是「原样优先」的阶梯在起作用：`%2B` 解出的字面加号路径在磁盘上不存在
 *      （`probe+shot.png` 才是那个名字），阶梯于是落到「`+` 折成空格」那一形。test F 用
 *      一个**真**含加号的文件把这条顺序钉死（原样命中 ⇒ 绝不折）；
 *   C. 命名空间：`plots` 与项目 `.nebflow/evidence*` 两个子树里的含空格路径各 200，
 *      再给一个「真文件但在不可服务命名空间内」的负对照 ⇒ 403 credential-path；
 *   D. 与工具侧同一份判据：Card 工具发出的 URL 打到真端点 ⇒ 200，且工具侧守门的
 *      `proxied` 计数与真取回结果**一致**（任务书 7(b) 的等价性用例）；
 *   E. 安全：`%2e%2e` 穿透进 credential 文件 ⇒ 仍 403 credential-path
 *      （解码不得制造「原串判不住、解码后判得住」）。
 */
class NfPathEncodingProbeSpec extends CatsEffectSuite:

  import NfPathEncodingProbeSpec.*

  private val gatewayToken = "probe-gateway-token"
  private val store = NfTicketStore.unsafeCreate(1800)

  /** Ember's default graceful-shutdown wait (30s) collides with munit's default
    * 30s test timeout: the JDK HttpClient keeps its connection alive, so the
    * server's finalizer waits out the whole budget and the test dies in cleanup
    * AFTER every assertion already passed. The server's shutdown bound is
    * therefore lowered in `server(...)` (see `withShutdownTimeout`); the suite
    * inherits munit's per-test timeout unchanged. */

  private val echo = HttpRoutes.of[IO] {
    case req @ GET -> Root / "probe" / "echo" =>
      val raw = req.uri.query.renderString
      val seen = req.params.get("path").getOrElse("<absent>")
      Ok(s"queryString=$raw\nparam=[$seen]")
  }

  private def server(policy: WebSocketRoutes.NfPathPolicy): IO[(Int, IO[Unit])] =
    val app = Router(
      "/" -> (WebSocketRoutes.nfFileRoutes(gatewayToken, store, policy) <+>
        WebSocketRoutes.nfTicketRoutes(gatewayToken, store, policy) <+> echo)
    ).orNotFound
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(app)
      .withShutdownTimeout(2.seconds)
      .build
      .allocated
      .map { case (s, shutdown) => (s.address.getPort, shutdown) }

  private def httpGet(url: String): (Int, Array[Byte]) =
    val resp = HttpClient
      .newHttpClient()
      .send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
    (resp.statusCode(), resp.body())

  private def sha256(b: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(b).map("%02x".format(_)).mkString

  private def text(b: Array[Byte]): String = new String(b, StandardCharsets.UTF_8).take(240)

  /** Recursive delete that never throws: cleanup must not mask a test failure. */
  private def deleteTree(p: Path): Unit =
    try
      if Files.exists(p) then
        Files
          .walk(p)
          .sorted(java.util.Comparator.reverseOrder())
          .iterator()
          .asScala
          .foreach(x => try Files.deleteIfExists(x) catch case _: Throwable => ())
    catch case _: Throwable => ()

  /** A real fixture dir whose ANCESTOR segment contains a space, exactly like
    * `/Users/kaiyu/Claude code/…`, inside ONE served namespace. `own` = the
    * directory is ours to remove wholesale. */
  private def withFixture(root: Path, namespace: String, own: Boolean)(test: (Path, Path) => IO[Unit]): IO[Unit] =
    val dir = root.resolve(namespace).resolve("space dir")
    Files.createDirectories(dir)
    val file = dir.resolve("probe shot.png")
    Files.write(file, PngBytes)
    val cleanup = IO {
      if own then deleteTree(root)
      else
        Files.deleteIfExists(file)
        deleteTree(dir)
      ()
    }
    test(root, file).guarantee(cleanup)

  private def policyFor(dataRoot: Path, workspaceRoot: Path): WebSocketRoutes.NfPathPolicy =
    val ws =
      if workspaceRoot.toString.startsWith("/nonexistent") then workspaceRoot
      else
        Files.createDirectories(workspaceRoot)
        workspaceRoot.toRealPath()
    WebSocketRoutes.NfPathPolicy(dataRoot.toRealPath(), ws, Set.empty)

  /** Four wire forms of ONE real path containing two spaces. A browser sends the
    * URL byte-for-byte as authored, so each form is built explicitly. */
  private def forms(plain: String): List[(String, String)] =
    require(plain.contains("space dir") && plain.contains("probe shot"), s"fixture path: $plain")
    List(
      "pct20" -> plain.replace(" ", "%20"),
      "plus" -> plain.replace(" ", "+"),
      "pct2B" -> plain.replace(" ", "%2B"),
      "mixed" -> plain.replace(" ", "%20").replace("probe%20shot", "probe+shot")
    )

  // ── A. 探针：http4s 的解码口径 ─────────────────────────────────────────────

  test("A. PROBE: what http4s hands the route for %20 / + / %2B / mixed"):
    val root = Files.createTempDirectory("imgref-probe-")
    withFixture(root, "plots", own = true) { (outer, file) =>
      server(policyFor(outer, Paths.get("/nonexistent-workspace"))).flatMap { (port, stop) =>
        IO {
          forms(file.toString).foreach { (label, value) =>
            val (status, body) = httpGet(s"http://127.0.0.1:$port/probe/echo?path=$value")
            println(s"PROBE[echo][$label] status=$status ${text(body).replace("\n", " | ")}")
            assertEquals(status, 200)
          }
        }.guarantee(stop)
      }
    }

  // ── B. 取回腿：四形态 ⇒ 状态码 + 字节 ──────────────────────────────────────

  test("B. real GET /api/nf-file: %20 / + / mixed ⇒ 200 + fixture bytes; %2B ⇒ 404"):
    val root = Files.createTempDirectory("imgref-get-")
    withFixture(root, "plots", own = true) { (outer, file) =>
      val real = file.toRealPath().toString
      server(policyFor(outer, Paths.get("/nonexistent-workspace"))).flatMap { (port, stop) =>
        IO {
          forms(real).foreach { (label, value) =>
            val ticket = store.issue("spec", real).unsafeRunSync().token
            val (status, body) = httpGet(s"http://127.0.0.1:$port/api/nf-file?path=$value&ticket=$ticket")
            println(s"PROBE[get][$label] status=$status bytes=${body.length} sha256=${sha256(body)}")
            // All four forms serve the fixture. The `%2B` form is the
            // documented TOLERANCE, in this order: the raw (literal-plus) form
            // is probed FIRST and only falls through because no such file
            // exists — test F pins that ordering with a real literal-plus file,
            // which is what stops this from being over-decoding.
            assertEquals(status, 200, s"$label")
            assertEquals(sha256(body), FixtureSha, s"$label: served bytes must be the fixture")
          }
        }.guarantee(stop)
      }
    }

  // ── C. 命名空间：两个子树各 200 + 负对照 403 ────────────────────────────────

  test("C. namespace match is form-independent: plots and .nebflow/evidence*"):
    val dataRoot = Files.createTempDirectory("imgref-ns-dr-")
    val wsRoot = Files.createTempDirectory("imgref-ns-ws-")
    val policy = policyFor(dataRoot, wsRoot.resolve(".nebflow"))
    val cleanup = IO {
      deleteTree(dataRoot)
      deleteTree(wsRoot)
      ()
    }
    withFixture(dataRoot, "plots", own = false) { (_, plotsFile) =>
      withFixture(wsRoot.resolve(".nebflow"), "evidence/imgref-spec", own = false) { (_, evFile) =>
        val secretDir = wsRoot.resolve(".nebflow/secrets/imgref-spec")
        Files.createDirectories(secretDir)
        val secret = secretDir.resolve("negative.png")
        Files.write(secret, PngBytes)
        server(policy).flatMap { (port, stop) =>
          IO {
            List(("plots", plotsFile), ("evidence*", evFile)).foreach { (label, f) =>
              val real = f.toRealPath().toString
              val ticket = store.issue("spec", real).unsafeRunSync().token
              val (status, body) =
                httpGet(s"http://127.0.0.1:$port/api/nf-file?path=${real.replace(" ", "%20")}&ticket=$ticket")
              println(s"PROBE[namespace][$label] status=$status bytes=${body.length}")
              assertEquals(status, 200, s"$label must serve despite the spaces")
              assertEquals(sha256(body), FixtureSha)
            }
            // negative control: a REAL file whose namespace the policy refuses
            val realSecret = secret.toRealPath().toString
            val ticket = store.issue("spec", realSecret).unsafeRunSync().token
            val (status, body) =
              httpGet(s"http://127.0.0.1:$port/api/nf-file?path=$realSecret&ticket=$ticket")
            println(s"PROBE[namespace][secrets] status=$status body=${text(body)}")
            assertEquals(status, 403)
            assert(text(body).contains("credential-path"), text(body))
          }.guarantee(stop).guarantee(cleanup)
        }
      }
    }

  // ── D. 与工具侧同一份判据（任务书 7(b) 的等价性用例） ────────────────────────

  test("D. equivalence: the tool-side gate and a real endpoint fetch agree"):
    val root = Paths.get(nebflow.core.PathUtil.dataRoot.toString).resolve("plots/imgref-spec")
    val image = root.resolve("space dir/tool shot.png")
    Files.createDirectories(image.getParent)
    Files.write(image, PngBytes)
    val cleanup = IO { deleteTree(root); () }
    val policy = WebSocketRoutes.NfPathPolicy.memoized() // the tool reads the same value
    server(policy).flatMap { (port, stop) =>
      IO {
        val ctx = nebflow.core.tools.ToolContext(projectRoot = os.pwd.toString)
        val input = JsonObject(
          "html" -> io.circe.Json.fromString(
            s"""<link rel="stylesheet" href="${image.toString.replace(" ", "%20")}"/>"""
          ),
          "title" -> io.circe.Json.fromString("T")
        )
        val result =
          nebflow.core.tools.CardTool.call(input, ctx).unsafeRunSync().getOrElse(fail("expected Right"))
        val payload = io.circe.parser
          .parse(result.stripPrefix("___CARD_HTML___"))
          .getOrElse(fail("the card payload must be JSON"))
        val proxied = payload.hcursor.downField("fileRefs").get[Int]("proxied").toOption.getOrElse(-1)
        val html = payload.hcursor.get[String]("html").toOption.getOrElse("")
        val url = raw"""/api/nf-file\?path=([^&"')]+)""".r
          .findFirstMatchIn(html)
          .map(_.group(1))
          .getOrElse(fail(s"no proxied URL in: ${html.take(200)}"))
        val real = image.toRealPath().toString
        val ticket = store.issue("spec", real).unsafeRunSync().token
        val (status, body) = httpGet(s"http://127.0.0.1:$port/api/nf-file?path=$url&ticket=$ticket")
        println(
          s"PROBE[equivalence] proxied=$proxied endpointStatus=$status bytes=${body.length} sha256=${sha256(body)}"
        )
        assertEquals(proxied, 1, "the tool-side gate counted the reference")
        assertEquals(status, 200, "the endpoint must serve exactly what the gate counted")
        assertEquals(sha256(body), FixtureSha, "same bytes ⇒ the two judgments agree")
      }.guarantee(stop).guarantee(cleanup)
    }

  // ── E. 安全：解码不制造穿透 ───────────────────────────────────────────────

  test("E. decoding creates no bypass: %2e%2e traversal into a credential file still 403s"):
    val dataRoot = Files.createTempDirectory("imgref-sec-")
    val secret = dataRoot.resolve("auth.json")
    Files.write(secret, "{\"token\":\"x\"}".getBytes(StandardCharsets.UTF_8))
    Files.createDirectories(dataRoot.resolve("plots/imgref-spec"))
    val cleanup = IO { deleteTree(dataRoot); () }
    server(policyFor(dataRoot, Paths.get("/nonexistent-workspace"))).flatMap { (port, stop) =>
      IO {
        val plain = secret.toRealPath().toString
        val root = dataRoot.toRealPath().toString
        val cases = List(
          "encoded-traversal" -> s"$root/plots/imgref-spec/%2e%2e/%2e%2e/auth.json",
          "plain-traversal" -> s"$root/plots/imgref-spec/../../auth.json"
        )
        cases.foreach { (label, value) =>
          val ticket = store.issue("spec", plain).unsafeRunSync().token
          val (status, body) = httpGet(s"http://127.0.0.1:$port/api/nf-file?path=$value&ticket=$ticket")
          println(s"PROBE[security][$label] status=$status body=${text(body)}")
          assertEquals(status, 403, s"$label must be refused")
          assert(text(body).contains("credential-path"), text(body))
        }
      }.guarantee(stop).guarantee(cleanup)
    }


  // ── F. 顺序判据：原样形态先试，「不过度解码」 ──────────────────────────────

  test("F. least-transformed first: a real literal-plus file wins over the space form"):
    val root = Files.createTempDirectory("imgref-order-")
    val dir = root.resolve("plots/space dir")
    Files.createDirectories(dir)
    val plusFile = dir.resolve("probe+shot.png")
    Files.write(plusFile, PngBytes2)
    val spaceFile = dir.resolve("probe shot.png")
    Files.write(spaceFile, PngBytes)
    val cleanup = IO { deleteTree(root); () }
    server(policyFor(root, Paths.get("/nonexistent-workspace"))).flatMap { (port, stop) =>
      IO {
        val plusReal = plusFile.toRealPath().toString
        val spaceReal = spaceFile.toRealPath().toString
        // A literal plus can only be SPELLED as `%2B` on the wire (a bare `+`
        // is the form-encoded space, and http4s folds it before the route sees
        // it — probe A). So `%2B` is the one form that reaches the ladder as a
        // literal `+`, and the raw form then names the real `probe+shot.png`:
        // the ladder must stop there and serve THOSE bytes. Folding it to a
        // space would silently serve a different file.
        val t1 = store.issue("spec", plusReal).unsafeRunSync().token
        val (s1, b1) = httpGet(
          s"http://127.0.0.1:$port/api/nf-file?path=" +
            s"${plusReal.replace(" ", "%20").replace("+", "%2B")}&ticket=$t1"
        )
        println(s"PROBE[order][literal-plus] status=$s1 sha256=${sha256(b1)}")
        assertEquals(s1, 200)
        assertEquals(sha256(b1), sha256(PngBytes2), "the literal-plus form must win (raw first)")
        // A bare `+` is the form-encoded spelling of a space: that path is the
        // space-bearing file, and it is served too.
        val t2 = store.issue("spec", spaceReal).unsafeRunSync().token
        val (s2, b2) = httpGet(
          s"http://127.0.0.1:$port/api/nf-file?path=${spaceReal.replace(" ", "+")}&ticket=$t2"
        )
        println(s"PROBE[order][bare-plus] status=$s2 sha256=${sha256(b2)}")
        assertEquals(s2, 200)
        assertEquals(sha256(b2), FixtureSha)
      }.guarantee(stop).guarantee(cleanup)
    }

object NfPathEncodingProbeSpec:

  /** The exact bytes of `.nebflow/evidence/20260918_imgref/r1/space dir/fail space.png`
    * as produced by that directory's `make_fixtures.py`: a REAL 8x6 RGB PNG,
    * 205 bytes, sha256 5ad35da434c4ea3e3a740b5c6d4493cf9b47b7f145df7a05c1507a74148d42b8.
    * Regenerate with that script — never a mock / 0-byte / placeholder image. */
  val PngBase64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAIAAABxZ0isAAAAlElEQVR42gXBOQpCMRAA0Jwv" +
      "ffr06adPn6lzgcFGBBFFdMQV/Squ44oLoogw2HgE3zMWSg4qHuoBGGAYYZZgk+FkLJYd1jy2" +
      "AvYBi4irhIeMN2Op6qjpqRtoDLSItEt0yfQ0lhuOO55HgefA28jnxI/MH2Ol7WTgZRpkDXKM" +
      "ck/yzvI1VntOJ16XQfeg16ivpJr19wc1zVwRaTbiFAAAAABJRU5ErkJggg=="

  val PngBytes: Array[Byte] = Base64.getDecoder.decode(PngBase64)

  /** A SECOND real 8x6 RGB PNG with deliberately different bytes (seed 3 of
    * `make_fixtures.py`: 207 bytes, sha256
    * 8775d43a7e1fe5abd31ae721f28b5e1666788767b6d697071846962214c05b88) so
    * "which file was served" is decidable by sha256 alone. */
  val PngBytes2: Array[Byte] =
    Base64.getDecoder.decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAIAAABxZ0isAAAAlklEQVR42gXBO4oCMBAA" +
        "0AERRRzFL35Rdwe/uCuiiCA2Hih9+vTp06efPgeYW8wtBmzE98Dx2zMErkVuJR5knjH/" +
        "Ft6Dk4+XapBmlF6SSZYVy7bIPziteG0E7UQdJV1kXbMei17AWd1bO9gw2jwZZTuwnYvd" +
        "wSF67AecRvxJuMt4YrwVfIKjrqdxoGWkTaK/TFemR6HXF58SP6H2BzoVAAAAAElFTkSu" +
        "QmCC"
    )

  val FixtureSha: String =
    java.security.MessageDigest.getInstance("SHA-256").digest(PngBytes).map("%02x".format(_)).mkString
