package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.service.ConfigService
import nebflow.shared.{NebflowLogger, NebflowServiceConfig, PathUtil, ServiceLlmConfig}

import scala.concurrent.duration.*

/**
 * `updateConfig` reply correlation. The gateway echoes the caller's `requestId` on the success frame
 * (`configUpdated`) and on the rejection frame (`error`), so a client can tell which save an answer
 * belongs to and an unrelated acknowledgement can never confirm a pending save. The rejection frame
 * also names the refused request (`msgType`), so a client does not mistake a refused save for a failed
 * conversation turn. A request without an id keeps working and is answered with a null id.
 *
 * Runs the real `WsDispatch.handlers("updateConfig")` against an isolated data root under `target/`.
 */
class WsUpdateConfigRequestIdSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val NullBackend = null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  private val validConfig: Json = Json.obj(
    "llm" -> Json.obj(
      "providers" -> Json.obj(
        "zz-p1" -> Json.obj(
          "baseUrl" -> "https://api.example.invalid/v1/".asJson,
          "apiKey" -> "sk-test-not-real".asJson,
          "protocol" -> "openai".asJson,
          "models" -> Json.arr(Json.obj("id" -> "m1".asJson, "contextWindow" -> 1000.asJson))
        )
      )
    )
  )

  /** A provider without a base URL is rejected by `ConfigService.validateConfig`. */
  private val rejectedConfig: Json = Json.obj(
    "llm" -> Json.obj(
      "providers" -> Json.obj(
        "zz-bad" -> Json.obj(
          "baseUrl" -> "".asJson,
          "apiKey" -> "sk-test-not-real".asJson,
          "protocol" -> "openai".asJson,
          "models" -> Json.arr(Json.obj("id" -> "m1".asJson))
        )
      )
    )
  )

  private def ctxFor(log: NebflowLogger, res: SharedResources): WsDispatchCtx =
    WsDispatchCtx(
      logger = log,
      sessionService = null,
      agentService = null,
      configService = ConfigService,
      configRef = null,
      sessionStore = res.sessionStore,
      sharedResources = res,
      mcpManager = null,
      sttServiceRef = null,
      textSearch = null,
      sessionTextBuffers = null,
      sessionThinkingBuffers = null,
      sessionTurnStarts = null,
      replayPendingAsksImpl = (_, _) => IO.unit,
      listAllPendingAsksImpl = null,
      applyPermissionUpgradeImpl = null,
      persistGlobalSafetyModeImpl = null,
      removeRootAgentImpl = null,
      stopChildDelegateActorsImpl = null,
      forwardInteractionAnswerImpl = null,
      isRootScopeSessionImpl = null,
      compactThresholdInfoImpl = null,
      ensureAgentImpl = null,
      maybeSessionKickImpl = null,
      broadcastServerConfigImpl = null,
      persistThinkingConfigImpl = null,
      persistWorkScheduleImpl = null,
      persistMcpServerEnabledImpl = null,
      broadcastMcpServersUpdateImpl = null,
      sendAgentSessionListImpl = null,
      sendAgentSessionListByNameImpl = null,
      sendMemoryStatusImpl = null,
      sendToolsListImpl = null,
      expandTildeImpl = null,
      wsBrowseEventImpl = null,
      browseResultFrameImpl = null,
      browseErrorFrameImpl = null,
      browseEntryCapImpl = 0,
      resolveExplorerBaseRootImpl = null,
      admitWorkOrRefuseImpl = null,
      handleUserTextImpl = null,
      deviceProvenanceImpl = null,
      skipCurrentFreezeWindowImpl = null,
      executeAskImpl = null,
      executeSkillImpl = null,
      resolveRootSessionIdImpl = null,
      projectCreateBroadcastImpl = null
    )

  /** Run one `updateConfig` request through the real handler and return every frame it sent. */
  private def updateConfig(config: Json, requestId: Option[String]): IO[List[Json]] =
    val root = os.Path(java.nio.file.Files.createTempDirectory("nb-update-config-rid"))
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    val system = ActorSystem(s"update-config-rid-${scala.util.Random.nextInt(100000)}")
    val request = Json
      .obj("type" -> "updateConfig".asJson, "config" -> config.noSpaces.asJson)
      .deepMerge(requestId.fold(Json.obj())(id => Json.obj("requestId" -> id.asJson)))
    val run =
      for
        base <- SpecResources.mkResources(system, root, new StubLlm().handle)
        configRef <- Ref.of[IO, NebflowServiceConfig](
          NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
        )
        // The save path hot-reloads the provider registry; the spec resources carry none.
        res = base.copy(providerRegistry = new nebflow.llm.ProviderRegistry(configRef, NullBackend))
        frames <- Ref.of[IO, List[Json]](Nil)
        log = NebflowLogger.forName("update.config.request.id.spec")
        watch = new ExplorerWatchSession(_ => IO.unit, log, live = false)
        _ <- WsDispatch.handlers("updateConfig")(
          ctxFor(log, res),
          request.noSpaces,
          (frame: Json) => frames.update(_ :+ frame),
          watch
        )
        out <- frames.get
      yield out
    run.guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *> IO.blocking {
        PathUtil.setDataRoot(original)
        os.remove.all(root)
      }
    )

  end updateConfig

  private def frameOfType(frames: List[Json], tpe: String): Json =
    frames
      .find(_.hcursor.get[String]("type").toOption.contains(tpe))
      .getOrElse(fail(s"no '$tpe' frame in: ${frames.map(_.noSpaces)}"))

  test("a saved config is acknowledged with the request id of that save"):
    updateConfig(validConfig, Some("provider-rid-ok")).map { frames =>
      val ack = frameOfType(frames, "configUpdated")
      assertEquals(ack.hcursor.get[Boolean]("success"), Right(true))
      assertEquals(ack.hcursor.get[String]("requestId"), Right("provider-rid-ok"))
    }

  test("a rejected config is answered with an error that carries the request id and names the request"):
    updateConfig(rejectedConfig, Some("provider-rid-bad")).map { frames =>
      val err = frameOfType(frames, "error")
      assertEquals(err.hcursor.get[String]("requestId"), Right("provider-rid-bad"))
      assertEquals(
        err.hcursor.get[String]("msgType"),
        Right("updateConfig"),
        "the rejection must say which request it refused, or the client treats it as a failed turn"
      )
      assert(
        err.hcursor.get[String]("message").exists(_.contains("Base URL is required")),
        s"the rejection reason must stay visible: ${err.noSpaces}"
      )
      assert(
        !frames.exists(_.hcursor.get[String]("type").toOption.contains("configUpdated")),
        "a rejected save is never acknowledged"
      )
    }

  test("a request without an id is still answered, with a null id"):
    updateConfig(validConfig, None).map { frames =>
      val ack = frameOfType(frames, "configUpdated")
      assertEquals(ack.hcursor.get[Boolean]("success"), Right(true))
      assertEquals(ack.hcursor.downField("requestId").focus, Some(Json.Null))
    }
end WsUpdateConfigRequestIdSpec
