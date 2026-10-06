package nebflow.core.tools

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.project.{FlowMapStore, NodeEngine, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * ⑥ merge 参数先行封口判读（worktree-judge-fifo 批 #1224 增补 §3.7，作者裁定⑥
 * 「判定机就位即封，不等 #1229」）：
 *
 *  - merge=true 新建 → NODE_MERGE_UNSUPPORTED（*_UNSUPPORTED 族错误码）；
 *  - merge=false / 不传 → 既有创建链零变化（对照腿照常建成）；
 *  - 编辑路径传 merge（create-only 静默忽略）的既有语义零变化——封口只堵**新建**。
 */
class NodeMergeSealSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-merge-seal"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge seal spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[Unit] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield ()

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("seal-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> project.asJson) :: ("nodename" -> nodename.asJson) :: ("plugins" -> Json.arr()) :: extra.toList*)

  test("⑥ seal: merge=true create is refused with NODE_MERGE_UNSUPPORTED (dispatch as Delegate tasks instead)") {
    val ws = tempRoot / "ws-seal"
    os.makeDir.all(ws)
    val system = ActorSystem(s"seal-1-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- mountProject("seal-1", ws, system, res)
      sealRes <- NodeEditTool.call(
        nodeInput("seal-1", "merge-sink", "merge" -> true.asJson, "in" -> Json.arr()).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(sealRes.isLeft, "merge=true create must be refused")
      val msg = sealRes.left.toOption.get.message
      assert(msg.contains("NODE_MERGE_UNSUPPORTED"), s"error must carry the *_UNSUPPORTED family code, got: $msg")
      assert(msg.contains("Delegate tasks"), s"the error must state the behavioral replacement (dispatch as Delegate tasks), got: $msg")
  }

  test("⑥ control: merge absent / merge=false creates still work — the seal is merge-only (zero legacy drift)") {
    val ws = tempRoot / "ws-seal-control"
    os.makeDir.all(ws)
    val system = ActorSystem(s"seal-2-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- mountProject("seal-2", ws, system, res)
      plain <- NodeEditTool.call(
        nodeInput("seal-2", "plain-node", "description" -> "plain work".asJson, "task" -> "plain work".asJson).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      explicitFalse <- NodeEditTool.call(
        nodeInput("seal-2", "plain-node-2", "description" -> "more work".asJson, "task" -> "more work".asJson, "merge" -> false.asJson).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(plain.isRight, s"no merge key ⇒ legacy create must succeed: $plain")
      assert(explicitFalse.isRight, s"merge=false ⇒ legacy create must succeed: $explicitFalse")
  }

end NodeMergeSealSpec
