package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.tools.{BgTaskOutputStore, BgTaskRegistry}
import nebflow.llm.ModelCandidate
import nebflow.shared.NebflowServiceConfig
import nebflow.shared.ServiceLlmConfig
import org.http4s.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

import java.nio.file.Files

/**
 * 后台任务输出查看补全批（作者令 2026-10-25「远端任务看不到 / 本地细节看不到」）
 * · bg-tasks 输出面 REST 线上契约 spec（真实 http4s 路由，wire 级断言）。
 *
 * 覆盖臂（设备腿的「测试桩」= 不初始化 RemoteExecutor 的单例面——路由的降级
 * 分支逐臂可断言；真设备回环属集成面，不在单测）：
 *   - `/bg-tasks/:id/output` —— 留存命中时响应携带来源/落盘面（kind/deviceName/
 *     outputPath/startedAtMs）；未知任务 404；
 *   - `/bg-tasks/:id/remote-output` —— 未知/已终态（注销）404；非 remote 404；
 *     无 outputPath = 200 available:false + reasonKind=noOutputPath（永久态，
 *     前端停轮询）；有 outputPath 但本测试环境无 executor 单例 = 200
 *     available:false + reasonKind=noExecutor。
 */
class BgRemoteOutputRoutesSpec extends FunSuite:

  private val token = "bg-remote-out-test-token"

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  private def withRoutes(f: HttpRoutes[IO] => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("bg-remote-routes-spec").toRealPath().toString)
    val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try
      val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      val inner = new RestApiRoutes(
        tokenRef = cats.effect.Ref.unsafe[IO, String](token),
        configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](cfg),
        sharedResources = SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = null,
          projectRoot = os.pwd,
          thinkingConfigRef = null,
          rateLimiter = null,
          fileChangeTracker = null,
          contextWindow = 100_000,
          agentLibrary = null,
          taskStore = null,
          historyArchiver = null,
          fileLockManager = null,
          sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = null,
          voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false)
        ),
        sessionStore = null,
        wsRoutes = null
      )
      val wsb = null.asInstanceOf[WebSocketBuilder2[IO]]
      f(inner.routes <+> inner.presenceWsRoutes(wsb))
    finally
      releaseDispatcher.unsafeRunSync()
      try os.remove.all(root)
      catch case _: Throwable => ()

  private def call(
    routes: HttpRoutes[IO],
    method: Method,
    path: String,
    bearer: Option[String] = Some(token)
  ): (Status, Option[Json]) =
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val authed = bearer.fold(base)(t => base.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $t")))
    routes(authed).value.unsafeRunSync() match
      case None => fail(s"route fell through: $method $path")
      case Some(resp) =>
        val text = resp.body.compile.toVector.unsafeRunSync().map(_.toChar).mkString
        (resp.status, if text.isEmpty then None else parse(text).toOption)

  // ── /output：留存命中带来源面；未知 404 ──────────────────────────────

  test("output face: retained entry carries kind/deviceName/outputPath/startedAtMs; unknown id = 404") {
    val id = s"rt-out-${System.nanoTime().toString.take(8)}"
    withRoutes { routes =>
      val (st404, _) = call(routes, Method.GET, s"/bg-tasks/$id/output")
      assertEquals(st404.code, 404)

      BgTaskOutputStore
        .fillRemote(
          id,
          "boot lines\nmore lines\n",
          "completed",
          Some(0),
          None,
          BgTaskOutputStore.Origin("remote", "KAI", "/c/Users/Kai/stand-win-test5.log", "[KAI] full test", 777L)
        )
        .unsafeRunSync()
      val (st, body) = call(routes, Method.GET, s"/bg-tasks/$id/output")
      assertEquals(st.code, 200)
      val b = body.get
      assertEquals(b.hcursor.get[String]("status").toOption, Some("completed"))
      assertEquals(b.hcursor.get[String]("kind").toOption, Some("remote"))
      assertEquals(b.hcursor.get[String]("deviceName").toOption, Some("KAI"))
      assertEquals(b.hcursor.get[String]("outputPath").toOption, Some("/c/Users/Kai/stand-win-test5.log"))
      assertEquals(b.hcursor.get[Long]("startedAtMs").toOption, Some(777L))
      assertEquals(b.hcursor.get[Int]("exitCode").toOption, Some(0))
    }
  }

  test("output face requires auth (no bearer = 403)") {
    withRoutes { routes =>
      val (st, _) = call(routes, Method.GET, "/bg-tasks/whatever/output", bearer = None)
      assertEquals(st.code, 403)
    }
  }

  // ── /remote-output：四臂 ─────────────────────────────────────────────

  test("remote-output: unknown/finished job = 404 (frontend falls back to /output)") {
    withRoutes { routes =>
      val (st, body) = call(routes, Method.GET, "/bg-tasks/no-such-job/remote-output")
      assertEquals(st.code, 404)
      assert(body.get.hcursor.get[String]("error").toOption.exists(_.contains("not running")))
    }
  }

  test("remote-output: local-kind job = 404 (remote face is remote-only)") {
    val id = s"rt-local-${System.nanoTime().toString.take(8)}"
    try
      withRoutes { routes =>
        BgTaskRegistry
          .register(id, "sess-x", "local build", "local", rootSessionId = "sess-x")
          .unsafeRunSync()
        val (st, _) = call(routes, Method.GET, s"/bg-tasks/$id/remote-output")
        assertEquals(st.code, 404)
      }
    finally BgTaskRegistry.unregister(id).unsafeRunSync()
  }

  test("remote-output: no outputPath = 200 available:false reasonKind=noOutputPath (permanent — frontend stops polling)") {
    val id = s"rt-nopath-${System.nanoTime().toString.take(8)}"
    try
      withRoutes { routes =>
        BgTaskRegistry
          .register(id, "sess-y", "[KAI] interactive cmd", "remote", rootSessionId = "sess-y", origin = "device", originLabel = "KAI", deviceName = "KAI")
          .unsafeRunSync()
        val (st, body) = call(routes, Method.GET, s"/bg-tasks/$id/remote-output")
        assertEquals(st.code, 200)
        val b = body.get
        assertEquals(b.hcursor.get[Boolean]("available").toOption, Some(false))
        assertEquals(b.hcursor.get[String]("reasonKind").toOption, Some("noOutputPath"))
        assertEquals(b.hcursor.get[String]("status").toOption, Some("running"))
        assertEquals(b.hcursor.get[String]("deviceName").toOption, Some("KAI"))
        assert(b.hcursor.get[String]("reason").toOption.exists(_.contains("redirect")), "原因须可读（不再「暂不支持」）")
      }
    finally BgTaskRegistry.unregister(id).unsafeRunSync()
  }

  test("remote-output: outputPath known but no executor singleton = 200 available:false reasonKind=noExecutor") {
    val id = s"rt-noexec-${System.nanoTime().toString.take(8)}"
    try
      withRoutes { routes =>
        BgTaskRegistry
          .register(
            id, "sess-z", "[KAI] sbt full test", "remote", rootSessionId = "sess-z",
            origin = "device", originLabel = "KAI", deviceName = "KAI",
            outputPath = "/c/Users/Kai/stand-win-test5.log"
          )
          .unsafeRunSync()
        val (st, body) = call(routes, Method.GET, s"/bg-tasks/$id/remote-output")
        assertEquals(st.code, 200)
        val b = body.get
        // 测试环境 RemoteExecutor.current = None（单例未初始化）——路由如实降级，
        // 不猜路径、不假报 available。
        assertEquals(b.hcursor.get[Boolean]("available").toOption, Some(false))
        assertEquals(b.hcursor.get[String]("reasonKind").toOption, Some("noExecutor"))
        assertEquals(b.hcursor.get[String]("outputPath").toOption, Some("/c/Users/Kai/stand-win-test5.log"))
      }
    finally BgTaskRegistry.unregister(id).unsafeRunSync()
  }

end BgRemoteOutputRoutesSpec
