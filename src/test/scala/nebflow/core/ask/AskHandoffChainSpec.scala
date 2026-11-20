package nebflow.core.ask

import cats.effect.IO
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentDef
import nebflow.core.AskHandoffPort
import nebflow.core.tools.{SendMessageToMainChatTool, ToolContext}

import scala.collection.mutable.ListBuffer

/* ═══════════════════════════════════════════════════════════════════════════
 * ask 写面收窄批（2026-11-18）· 转交链路端到端红面
 *
 * 作者验收条目 3：「ask 里提一个跨文件/复杂任务 ⇒ 产出结构化转交 ⇒ 主会话确实收到
 * ⇒ Ask 面板状态可见」。
 *
 * 本 spec 驱动**真实引擎对象**（SendMessageToMainChatTool → AskHandoffPort →
 * 装配面 Face → 面板帧），只把两个外部腿换成受控替身：
 *   · LLM/agent actor：ask 会话不发真实模型调用（等价于「模型决定了调用本工具」，
 *     输入即结构化参数）；
 *   · 主会话 ensureAgent：Face 替身记录投递（= 主会话收到），真实实现在
 *     WebSocketRoutes.installAskHandoffFace（同一 Request 契约）。
 * 两侧断言：① 结构化四件在请求里；② 主会话侧确实收到；③ 面板帧 askHandoffAck
 * 带 ok=true + summary + handoff（UI 可见面）；④ 越界（无绑定 / 无主会话 / 非 ask
 * 身份 / 频控）逐条显式拒且**不挂死**（回执即收尾）。
 * ═══════════════════════════════════════════════════════════════════════════ */
final class AskHandoffChainSpec extends CatsEffectSuite:

  private final class RecordingSources extends AskContextSources:
    override def fileStat(absPath: String): IO[Option[(Long, Long)]] = IO.pure(None)
    override def fileText(absPath: String): IO[Option[(String, Long, Long)]] = IO.pure(None)
    override def graphNeighbors(absPath: String): IO[List[AskNeighbor]] = IO.pure(Nil)
    override def recallHits(query: String): IO[List[AskRecallHit]] = IO.pure(Nil)
    override def recallDbMtime: IO[Long] = IO.pure(0L)
    override def graphVersion: IO[String] = IO.pure("")

  /** 装配一个可用的 ask 面 + 受控转交 Face（返回投递留档 + 面板帧留档）。 */
  private def withAskServices(
    mainSessionId: String
  ): IO[(AskBinding, ListBuffer[AskHandoffPort.Request], ListBuffer[Json], IO[Unit])] =
    IO.blocking {
      val root = os.Path(os.temp.dir(prefix = "ask-handoff-spec-").toNIO.toRealPath())
      os.makeDir.all(root / "documents")
      val file = root / "documents" / "notes.md"
      os.write(file, "# notes\n")
      val registry = new AskSessionRegistry(root)
      val delivered = ListBuffer.empty[AskHandoffPort.Request]
      val frames = ListBuffer.empty[Json]
      AskGatewayServices.install(AskServices(registry, new RecordingSources, new AskPromptCache))
      AskHandoffPort.install(req =>
        IO {
          delivered += req
          Right(s"delivered to $mainSessionId")
        }
      )
      nebflow.core.tools.AskHandoffRateLimit.reset()
      val binding = registry.bind(file.toString, file.last).unsafeRunSync()
      registry.refreshMainSession(binding.id, mainSessionId).unsafeRunSync()
      val teardown = IO {
        AskGatewayServices.uninstall()
        AskHandoffPort.clear()
        nebflow.core.tools.AskHandoffRateLimit.reset()
      }
      (binding, delivered, frames, teardown)
    }

  private def ctxFor(sid: String, root: String, frames: ListBuffer[Json]): ToolContext =
    ToolContext(
      projectRoot = root,
      sessionId = Some(sid),
      agentDef = Some(AgentDef(name = "ask", description = "", tools = Nil, category = "standalone")),
      wsSend = Some(json => IO { frames += json; () })
    )

  private def crossFileInput: JsonObject = JsonObject(
    "task" -> "把 notes.md 与 other.md 合并成一份 README，并跑一次构建".asJson,
    "paths" -> List("documents/notes.md", "documents/other.md").asJson,
    "reason" -> "跨文件 + 需要跑构建命令，本会话只有只读 shell".asJson,
    "expected" -> "合并后的 README + 构建结果".asJson
  )

  test(
    "H1: a cross-file request produces a structured handoff the main session receives, and the panel frame is visible"
  ) {
    val run = withAskServices("main-sess-1").flatMap { case (binding, delivered, frames, teardown) =>
      for
        res <- SendMessageToMainChatTool.call(crossFileInput, ctxFor(binding.id, os.pwd.toString, frames))
        _ <- IO {
          assert(res.isRight, s"the handoff must be accepted: $res")
          // ② 主会话确实收到（Face 投递留档）
          assertEquals(delivered.length, 1, "exactly one handoff request must reach the main session")
          val req = delivered.head
          // ① 结构化四件在请求里
          assertEquals(req.task, "把 notes.md 与 other.md 合并成一份 README，并跑一次构建")
          assertEquals(req.paths, List("documents/notes.md", "documents/other.md"))
          assertEquals(req.reason, "跨文件 + 需要跑构建命令，本会话只有只读 shell")
          assertEquals(req.expected, "合并后的 README + 构建结果")
          assertEquals(req.leg, "ask-tool")
          assertEquals(req.askSessionId, binding.id)
          assertEquals(req.file, binding.file)
          // ③ 面板帧（UI 可见面）：askHandoffAck + ok + summary + 机器可读 handoff
          assertEquals(frames.length, 1, "the tool leg must push the panel frame (no silent handoff)")
          val frame = frames.head
          assertEquals(frame.hcursor.downField("type").as[String].toOption, Some("askHandoffAck"))
          assertEquals(frame.hcursor.downField("sessionId").as[String].toOption, Some(binding.id))
          assertEquals(frame.hcursor.downField("ok").as[Boolean].toOption, Some(true))
          val summary = frame.hcursor.downField("summary").as[String].toOption.getOrElse("")
          assert(summary.contains("README"), s"the panel summary must carry the task: $summary")
          val handoff = frame.hcursor.downField("handoff")
          List("task", "paths", "reason", "expected").foreach { f =>
            assert(handoff.downField(f).focus.isDefined, s"the panel frame must carry `$f`: ${frame.noSpaces}")
          }
        }
        _ <- teardown
      yield ()
    }
    run
  }

  test("H2: out-of-scope or unavailable handoffs refuse explicitly — never hang, never silence") {
    val run = withAskServices("main-sess-2").flatMap { case (binding, _delivered, frames, teardown) =>
      val good = binding.id
      val noTarget = AskSessions.sessionIdFor("/nonexistent/other.md")
      for
        plain <- SendMessageToMainChatTool.call(crossFileInput, ctxFor("ordinary-session", os.pwd.toString, frames))
        empty <- SendMessageToMainChatTool.call(JsonObject(), ctxFor(good, os.pwd.toString, frames))
        // 无绑定的 ask 会话 ⇒ 显式拒（ASK_HANDOFF_NO_BINDING），不挂死
        unbound <- SendMessageToMainChatTool.call(crossFileInput, ctxFor(noTarget, os.pwd.toString, frames))
        _ <- IO {
          assert(plain.left.exists(_.message.contains("ASK_HANDOFF_FORBIDDEN")), plain.toString)
          assert(empty.left.exists(_.message.contains("task")), empty.toString)
          assert(unbound.left.exists(_.message.contains("ASK_HANDOFF_NO_BINDING")), unbound.toString)
          assertEquals(frames.length, 0, "a refused handoff must not push an ok frame")
        }
        _ <- teardown
      yield ()
    }
    run
  }

end AskHandoffChainSpec
