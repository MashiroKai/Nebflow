package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord}
import nebflow.agent.SharedResources
import nebflow.core.tools.{MailTool, ToolContext}
import nebflow.llm.ModelCandidate
import nebflow.shared.ThinkingConfig

import scala.concurrent.duration.*

/**
 * evfmt 批 A 收尾 R-A 补（2026-09-15 ③ root 裁定，engine 面）· **双向钉**：
 * **气泡四段式 header 第 2 段（`PROJECT`）= 发送方实际项目域**。
 *
 * ## project-trigger 拆除批（2026-10-18）重锚
 * 原 T1/T2（腿① `MailTool.call("project:<mounted>")` → 分发器收件面 WS 帧）钉的是
 * Mail→project 触发腿的置位面；该腿已整体拆除（Mail = 纯通信原语，项目地址显式
 * 拒绝——`MailProjectTriggerDemolitionSpec` 钉拆除本身），置位点随之消失，两用例
 * **随腿退役**。分配器收件面的 PROJECT 段合同由触发侧生产者（DispatchNotify 等
 * 引擎触发方）的呈现面另行承载，不在本 spec 域内。
 * 本 spec 现钉 = **T3 腿③（分发器 → root）**：`ImmediateInput.project` 同源置位
 * （发送方项目 / 根域回落 None）——通信面（`Mail(address="Nebula")`）不受拆除影响。
 *
 * 读取点 = **置位字段 + 发射点回落链复刻**（`project.orElse(sessionProject).orElse(RootProject)`）。
 *
 * **本 spec 不证明的**（如实标注）：真实宿主重启后的浏览器 DOM 渲染；腿③ 的整串 header
 * （T3 止于 `ImmediateInput.project` 字段 + 发射点回落链的逐字复刻，root 会话非真 AgentActor）。
 */
class MailSenderProjectSegmentSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private def callMail(ctx: ToolContext, address: String, message: String): IO[String] =
    MailTool
      .call(
        Json
          .obj("address" -> Json.fromString(address), "message" -> Json.fromString(message))
          .asObject
          .get,
        ctx
      )
      .map {
        case Right(s) => s"RIGHT: $s"
        case Left(err) => s"LEFT: ${err.message}"
      }

  /**
   * 发射点 `AgentActor#emitInjectedUserEvent` 的 PROJECT 段回落链**逐字复刻**
   * （`project.orElse(sessionProject).orElse(RootProject)`）。
   */
  private def emitReduction(project: Option[String], sessionProject: Option[String]): String =
    NotificationHeader
      .header(
        "mail",
        Some("mail"),
        project.orElse(sessionProject).orElse(Some(NotificationHeader.RootProject)),
        Some("project-dispatcher"),
        None,
        Some("info")
      )
      .getOrElse("<absent>")

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  /**
   * 腿③（分发器 → root）：真 actor 收录 `ImmediateInput`，读**置位字段** `project`
   * （= 气泡 PROJECT 段链首级）+ 发射点回落链复刻读数。
   */
  private def runLeg3(tag: String, senderProject: Option[String]): IO[(String, Option[String], String)] =
    val system = ActorSystem(s"mail-projseg-$tag-${scala.util.Random.nextInt(100000)}")
    for
      record <- Ref.of[IO, List[AgentCommand]](Nil)
      rootRef <- system.spawn(mkRecordingActor(record), s"root-$tag-${scala.util.Random.nextInt(100000)}")
      resources = SharedResources(
        llm = null,
        dispatcher = null,
        sessionStore = null,
        projectRoot = os.pwd,
        thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
        rateLimiter = null,
        fileChangeTracker = null,
        contextWindow = 100_000,
        agentLibrary = null,
        taskStore = null,
        historyArchiver = null,
        fileLockManager = null,
        sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
        providerRegistry = null,
        healthMonitor = null,
        actorSystem = null,
        agentRegistry = Ref.unsafe[IO, Map[String, AgentRecord]](
          Map("nebula-root-sid" -> AgentRecord("nebula-root-sid", rootRef, AgentKind.Root, "nebula-root-sid"))
        ),
        voiceMutedRef = Ref.unsafe[IO, Boolean](false)
      )
      ctx = ToolContext(
        projectRoot = os.pwd.toString,
        sessionId = Some("dispatcher-sid"),
        rootSessionId = Some("nebula-root-sid"),
        agentDef = Some(AgentDef(name = "project-dispatcher", description = "", category = "standalone")),
        sharedResources = Some(resources),
        actorSystem = Some(system),
        projectName = senderProject,
        isDispatcher = true
      )
      out <- callMail(ctx, "Nebula", s"R-A 补 leg3 $tag")
      cmds <- record.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val imm = cmds.collect { case i: AgentCommand.ImmediateInput => i }
      val got = imm.headOption.flatMap(_.project)
      println(s"[$tag-CALL] $out | ImmediateInput#=${imm.size} project=${got.getOrElse("<None>")}")
      // ⑥ 发射点回落链复刻（会话侧 = root 会话：无项目上下文 ⇒ sessionProject=None）
      (out, got, emitReduction(got, None))

    end for

  end runLeg3

  test("T3 同源置位：腿③（分发器 → root）`ImmediateInput.project` = 发送方项目 / 根域回落 None"):
    for
      withProject <- runLeg3("t3a", Some("PROJ-P6-SRC"))
      rootDomain <- runLeg3("t3b", None)
    yield
      val (outA, gotA, emittedA) = withProject
      assert(outA.startsWith("RIGHT:"), s"腿③ 投递必须成功（可解析 root）：$outA")
      assertEquals(gotA, Some("PROJ-P6-SRC"), "腿③ 未置位发送方项目 ⇒ 气泡 PROJECT 段落到 root 会话侧")
      assertEquals(emittedA, "MAIL · PROJ-P6-SRC · PROJECT-DISPATCHER · INFO", "发射点回落链复刻成串")
      val (outB, gotB, emittedB) = rootDomain
      assert(outB.startsWith("RIGHT:"), s"腿③ 根域发送方投递必须成功：$outB")
      assertEquals(gotB, None, "无项目上下文时不得臆造项目名（禁静默填空）")
      assertEquals(emittedB, "MAIL · NEBULA · PROJECT-DISPATCHER · INFO", "根域场景第二段 = NEBULA 逐字不变")

end MailSenderProjectSegmentSpec
