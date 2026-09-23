package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.llm.{NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.{Headers, HttpRoutes, Method, Request, Response, Status, Uri}
import java.nio.file.Files

/**
 * panelscheme 批（2026-09-21，作者令）——面板模型方案收敛 REST 闸 spec：
 * PUT /api/agents/:name/preset 与 PUT /api/agents/:name/model 仅对可设两类
 * （Nebula / project-dispatcher）放行；其余 agent 拒写（400 + 可行动错误），
 * 盘上既有引用零触碰（数据留盘）。
 *
 * 红线：两类的写路径行为不变（Nebula 写入 200 updated）。
 */
class PanelSchemeRoutesSpec extends CatsEffectSuite:

  private val TestToken = "panelscheme-routes-token"

  private def withFixture[A](test: () => IO[A]): A =
    val tmp = os.temp.dir(dir = os.Path(Files.createTempDirectory("nb-panelscheme-spec").toString))
    val originalRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try
      os.makeDir.all(tmp / "agents" / "kernel")
      os.write(
        tmp / "agents" / "kernel" / "agent.json",
        """{"name":"kernel","description":"fixture kernel","preset":"stale"}"""
      )
      os.write(tmp / "agents" / "kernel" / "system.md", "k")
      os.makeDir.all(tmp / "agents" / "Nebula")
      os.write(tmp / "agents" / "Nebula" / "agent.json", """{"name":"Nebula","description":"fixture nebula"}""")
      os.write(tmp / "agents" / "Nebula" / "system.md", "n")
      os.write(
        tmp / "model-presets.json",
        """{"defaultPreset":"general","presets":{"general":{"name":"general","description":"","preferred":"m/g1","fallbacks":[]},"vision":{"name":"vision","description":"","preferred":"m/v1","fallbacks":[]}}}"""
      )
      test().unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

    end try

  end withFixture

  private def mkRoutes: HttpRoutes[IO] =
    val inner = new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    )
    // 生产挂载形（GatewayMain: Router("/api" -> routes.routes <+> presenceWsRoutes(wsb))）：
    // agents/model、agents/preset 两条臂位于 presenceWsRoutes 块（DeviceFaceHardeningRoutesSpec
    // 同款发现），直呼 routes.routes 会假性 fall-through。wsb 仅被闭包捕获、本 spec 的臂
    // 不触 WS ⇒ 占位 builder 安全（同 DeviceFaceHardeningRoutesSpec 先例）。
    val wsb = null.asInstanceOf[org.http4s.server.websocket.WebSocketBuilder2[IO]]
    inner.routes <+> inner.presenceWsRoutes(wsb)

  end mkRoutes

  private def putJson(path: String, body: io.circe.Json): IO[Response[IO]] =
    val req = Request[IO](Method.PUT, Uri.unsafeFromString(path))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      .withEntity(body)
    mkRoutes(req).value.map(_.getOrElse(fail("route fell through")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  test("PUT /agents/kernel/preset → 400（非可设 agent 拒写，盘上 stale 引用零触碰）"):
    withFixture { () =>
      putJson("/agents/kernel/preset", io.circe.Json.obj("preset" -> "vision".asJson)).flatMap { resp =>
        assertEquals(resp.status, Status.BadRequest)
        resp.as[io.circe.Json].map { json =>
          val err = json.hcursor.downField("error").as[String].getOrElse("")
          assert(err.contains("does not accept a model-scheme setting"), s"error must be actionable: $err")
        }
      } *> IO {
        val raw = os.read(PathUtil.dataRoot / "agents" / "kernel" / "agent.json")
        assert(raw.contains("\"stale\""), "existing stored ref must stay untouched (data kept on disk)")
      }
    }

  test("PUT /agents/kernel/model → 400（legacy model 写路径同闸）"):
    withFixture { () =>
      putJson(
        "/agents/kernel/model",
        io.circe.Json.obj("preferred" -> "m/x".asJson, "fallbacks" -> io.circe.Json.arr())
      ).flatMap { resp =>
        for
          _ <- IO(assertEquals(resp.status, Status.BadRequest))
          json <- resp.as[io.circe.Json]
          _ <- IO(
            assert(
              json.hcursor.downField("error").as[String].getOrElse("").contains("does not accept a model config"),
              "model write rejection must be actionable"
            )
          )
        yield ()
      }
    }

  test("PUT /agents/Nebula/preset → 200 updated（可设两类写路径行为不变——红线）"):
    withFixture { () =>
      putJson("/agents/Nebula/preset", io.circe.Json.obj("preset" -> "vision".asJson)).flatMap { resp =>
        for
          _ <- IO(assertEquals(resp.status, Status.Ok))
          json <- resp.as[io.circe.Json]
          _ <- IO(assertEquals(json.hcursor.downField("updated").as[Boolean].toOption, Some(true)))
        yield ()
      } *> IO {
        val raw = os.read(PathUtil.dataRoot / "agents" / "Nebula" / "agent.json")
        assert(raw.contains("\"vision\""), "settable agent must accept the write (red line unchanged)")
      }
    }

  test("PUT /agents/Nebula/preset preset=null → 200（清除引用路径同样放行）"):
    withFixture { () =>
      putJson("/agents/Nebula/preset", io.circe.Json.obj("preset" -> io.circe.Json.Null)).flatMap { resp =>
        IO(assertEquals(resp.status, Status.Ok))
      }
    }

end PanelSchemeRoutesSpec
