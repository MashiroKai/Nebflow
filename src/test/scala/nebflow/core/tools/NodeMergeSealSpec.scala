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
    Json.obj(
      ("project" -> project.asJson) :: ("nodename" -> nodename.asJson) :: ("plugins" -> Json.arr()) :: extra.toList*
    )

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
      assert(
        msg.contains("Delegate tasks"),
        s"the error must state the behavioral replacement (dispatch as Delegate tasks), got: $msg"
      )
    end for
  }

  test("⑥ control: merge absent / merge=false creates still work — the seal is merge-only (zero legacy drift)") {
    val ws = tempRoot / "ws-seal-control"
    os.makeDir.all(ws)
    val system = ActorSystem(s"seal-2-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- mountProject("seal-2", ws, system, res)
      plain <- NodeEditTool.call(
        nodeInput(
          "seal-2",
          "plain-node",
          "description" -> "plain work".asJson,
          "task" -> "plain work".asJson
        ).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      explicitFalse <- NodeEditTool.call(
        nodeInput(
          "seal-2",
          "plain-node-2",
          "description" -> "more work".asJson,
          "task" -> "more work".asJson,
          "merge" -> false.asJson
        ).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(plain.isRight, s"no merge key ⇒ legacy create must succeed: $plain")
      assert(explicitFalse.isRight, s"merge=false ⇒ legacy create must succeed: $explicitFalse")
    end for
  }

  // E2E 实测断点（2026-10-07 n1-e2e rig）：schema 已删 merge 属性 ⇒ 宽容客户端（LLM
  // 工具调用实测如此）把 true 序列化成字符串 "true"，旧 `asBoolean` 判据对它返回
  // None ⇒ seal 被类型混淆绕过、create 静默吞键（节点建成而 merge 意图无声丢失）。
  // 封口判据收口后：字符串 "true"（含大小写/空白变体）一律拒绝，"false" 照旧容忍。
  test("⑥ seal string-form: merge=\"true\" (the type-confused legacy shape) is refused too — no silent drop") {
    val ws = tempRoot / "ws-seal-string"
    os.makeDir.all(ws)
    val system = ActorSystem(s"seal-3-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- mountProject("seal-3", ws, system, res)
      lower <- NodeEditTool.call(
        nodeInput("seal-3", "string-sink", "merge" -> "true".asJson, "in" -> Json.arr()).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      padded <- NodeEditTool.call(
        nodeInput("seal-3", "string-sink-2", "merge" -> "  TRUE ".asJson, "in" -> Json.arr()).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      falseString <- NodeEditTool.call(
        nodeInput(
          "seal-3",
          "plain-node-3",
          "description" -> "str work".asJson,
          "task" -> "str work".asJson,
          "merge" -> "false".asJson
        ).asObject.get,
        mkCtx(res, system, ws.toString)
      )
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(lower.isLeft, "merge=\"true\" (string) must be refused — the seal is not bypassable by type confusion")
      assert(
        lower.left.toOption.get.message.contains("NODE_MERGE_UNSUPPORTED"),
        "string form carries the same family code"
      )
      assert(padded.isLeft, "merge=\"  TRUE \" (padded/upper) must be refused the same way")
      assert(falseString.isRight, s"merge=\"false\" (string) stays on the tolerated legacy leg: $falseString")
    end for
  }

end NodeMergeSealSpec
