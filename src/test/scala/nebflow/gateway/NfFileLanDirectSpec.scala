package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.shared.PathUtil
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Response, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * 大文件 LAN 直传 服务端验收 spec（console-fastpath 批 2026-10-23，作者令腿③）：
 *
 *  ① `POST /api/nf-ticket` 铸票响应并键 `size`（= 文件真实字节数）——浏览端
 *    LAN 直传阈值的判定键；缺失文件照旧进 rejected，行为零变化。
 *  ② `GET /api/nf-file` 全出向 CORS/PNA 头（ACAO `*` + Allow-Private-Network
 *    + 读数头 Expose）——LAN 跨源直传（门户 https 页 → http://<lan-ip> 网关）
 *    的浏览器硬门槛；`OPTIONS` 预检腿 204。
 *  ③ 鉴权语义零变化：CORS 头不替代 ticket——无票仍 401，坏票仍 403。
 *
 * 形态对齐 `NfTicketRoutesSpec`：companion 纯路由直调 + 临时目录 P1/P3 注入。
 */
class NfFileLanDirectSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token-lan-direct"
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized
  private var env: Env = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-nf-lan-direct-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    env = new Env(tmpDir)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private final class Env(root: Path):
    val workspace: Path = Files.createDirectories(root.resolve("workspace"))
    val file: Path = workspace.resolve("big.bin")
    val bytes: Array[Byte] = ("LAN-DIRECT-" * 1000).getBytes(StandardCharsets.UTF_8)
    Files.write(file, bytes)

    lazy val policy: NfFilePolicy.NfPathPolicy =
      NfFilePolicy.NfPathPolicy(
        root,
        workspace,
        NfFilePolicy.NfPathPolicy.scanCredentialInodes(root)
      )

    val store: NfTicketStore = NfTicketStore.unsafeCreate(1800)

    def read(query: String): Response[IO] =
      val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-file?$query"))
      NfFileRoutes
        .nfFileRoutes(store, policy)(req)
        .value
        .map(_.getOrElse(fail("route fell through")))
        .unsafeRunSync()

    def options(): Response[IO] =
      NfFileRoutes
        .nfFileRoutes(store, policy)(
          Request[IO](Method.OPTIONS, Uri.unsafeFromString("/api/nf-file"))
        )
        .value
        .map(_.getOrElse(fail("route fell through")))
        .unsafeRunSync()

    def mint(): Json =
      val req = SpecRequests
        .withRemote(
          Request[IO](Method.POST, Uri.unsafeFromString("/api/nf-ticket"))
            .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, gatewayToken))),
          Some(SpecRequests.LoopbackV4)
        )
        .withEntity(
          Json.obj("sessionId" -> Json.fromString("lan-spec"), "paths" -> Json.arr(Json.fromString(file.toString)))
        )
      NfFileRoutes
        .nfTicketRoutes(TokenRef, store, policy, Sessions)(req)
        .value
        .map(_.getOrElse(fail("route fell through")))
        .map { resp =>
          assertEquals(resp.status, Status.Ok)
          parse(new String(resp.body.compile.toVector.unsafeRunSync().toArray, StandardCharsets.UTF_8))
            .getOrElse(fail("mint body not json"))
        }
        .unsafeRunSync()

    end mint

  end Env

  test("① 铸票并键 size = 文件真实字节数") {
    val json = env.mint()
    val cursor = json.hcursor.downField("tickets").downField(env.file.toString)
    assert(cursor.succeeded, "ticket must be minted for the workspace file")
    assertEquals(cursor.downField("size").as[Long].getOrElse(-1L), env.bytes.length.toLong)
    assert(cursor.downField("t").as[String].exists(_.nonEmpty))
  }

  test("② OPTIONS 预检腿：204 + ACAO `*` + PNA 放行") {
    val resp = env.options()
    assertEquals(resp.status, Status.NoContent)
    assertEquals(
      resp.headers.headers.find(_.name.toString.equalsIgnoreCase("Access-Control-Allow-Origin")).map(_.value),
      Some("*")
    )
    assert(
      resp.headers.headers.exists(h =>
        h.name.toString.equalsIgnoreCase("Access-Control-Allow-Private-Network") && h.value == "true"
      )
    )
  }

  test("② 全出向 CORS：无票 401 也带 ACAO（错误腿不断直传）") {
    val resp = env.read(s"path=${env.file.getFileName}&ticket=")
    assertEquals(resp.status, Status.Unauthorized)
    assertEquals(
      resp.headers.headers.find(_.name.toString.equalsIgnoreCase("Access-Control-Allow-Origin")).map(_.value),
      Some("*")
    )
    assert(resp.headers.headers.exists(_.name.toString.equalsIgnoreCase("Access-Control-Allow-Private-Network")))
    // Expose 面（前端进度/seek/退避读数头）在正向上可见。
    val q = java.net.URLEncoder.encode(env.file.toString, "UTF-8")
    val ok = env.read(
      s"path=$q&ticket=${java.net.URLEncoder.encode(env.store.issue("s", env.file.toRealPath().toString).unsafeRunSync().token, "UTF-8")}"
    )
    assertEquals(ok.status, Status.Ok)
    val expose = ok.headers.headers
      .find(_.name.toString.equalsIgnoreCase("Access-Control-Expose-Headers"))
      .map(_.value)
      .getOrElse("")
    assert(
      expose.contains("content-length") && expose.contains("content-range"),
      s"Expose-Headers must carry range/length reads: $expose"
    )
  }

  test("③ 鉴权语义零变化：坏票仍 403 + CORS 头在场（CORS 不替代 ticket）") {
    val resp = env.read(s"path=${java.net.URLEncoder.encode(env.file.toString, "UTF-8")}&ticket=deadbeef")
    assertEquals(resp.status, Status.Forbidden)
    assertEquals(
      resp.headers.headers.find(_.name.toString.equalsIgnoreCase("Access-Control-Allow-Origin")).map(_.value),
      Some("*")
    )
  }

end NfFileLanDirectSpec
