package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.tools.{AskUserQuestionTool, ImageInject, ToolContext}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * AskUserQuestion **非阻塞模式的回投桥**（工具面按角色分化批 B5/L9，2026-09-13）。
 *
 * 职责（规格 §2.2 D3/D4）：非阻塞模式下，hub 槽位的 `reply` 不指向「等着工具结果的
 * 工具 fiber 回执」，而指向本桥。用户答复 → hub `complete`（`InteractionHub` 零改动，
 * `AskUserReply(Option[ActorRef[List[String]]])` 本就是任意 ref）→ `bridge ! answers`
 * → 格式化（与阻塞模式**同一实现** `AskUserQuestionTool.formatAnswer`）→ 向请求方
 * **会话本体**发 `AgentCommand.ImmediateInput(text, fromUser = true)`——会话 Idle 则
 * 唤醒新 turn，在跑则进 `pendingUserInputs`，在**下一个 turn 边界**注入（D5：答案
 * 不是工具结果，是**用户输入**）。
 *
 * ## 为什么是「一次性引用」而不是 spawn 一个 Actor（设计 §9 未证项 5 的显式处理）
 *
 * 设计建议落点写着「一次性桥接 Actor（`Behaviors.setup` → 收答案 → `Behaviors.stopped`）」，
 * 同时把「**桥接 Actor 若在答复前已停止 ⇒ 答案进 dead letters 静默丢**」列为未证项，
 * 要求实施批给显式处理。本实现直接**消掉该风险面**：
 *
 *  - 本桥**不是 actor**，没有 mailbox、没有生命周期、不占线程：它就是一个
 *    `ActorRef[List[String]]` 实现（树内先例 `SendConfirm.oneShotReply:188-193`，
 *    同款「一次性引用」形态）。⇒ 「桥在答复前已停止」这种状态**不存在**——
 *    只要 hub 槽位还在（槽位生命周期 = 规格 §3.4 D6 复用），桥就可达；
 *    槽位被撤（答复 / `CleanupForSession` 源会话死亡 / `CloseRequest`）时，
 *    对它的引用自然被丢弃，**零泄漏**（一个 Actor 反而会常驻到答复为止）。
 *  - 残余风险只剩一种：**目标会话本体已消失**而槽位尚未回收。改前此处显式 WARN
 *    后放弃投递（答案终点站）；answer-landing 实施批（2026-10-21）把该分支升级为
 *    **三臂着陆面**（[[AskAnswerLanding]]）：会话亡＋归因在＋宿主在 ⇒ 引擎重入
 *    （ProjectActor.AskAnswerReentry，ReenterDispatcher 族管线）／委托宿主在 ⇒
 *    注入委托会话；归因缺席/宿主不在 ⇒ 失败出口（审计 + 桌面 toast + 渠道失败
 *    回执「问题已作废+原因」）。**任何出口不静默**（原 WARN 面保留为失败出口的
 *    日志腿）。
 *
 * 线程/IO 归属：本对象不自己跑 IO——`!` 返回的 IO 由**调用方**（hub 的
 * `handleAnswered` fiber）执行。真正的 Actor 层（
 * `AgentActor`）仍是唯一的状态机；本桥只做「一条消息 → 一条注入」的搬运，符合
 * 「Keep your Actors out of your cats-effect」铁律。
 */
object AskUserAnswerBridge:

  private val logger = NebflowLogger.forName("nebflow.agent.askuser")

  /**
   * 取消通告的注入来源定名（askucancel 批，2026-10-08）。`fromUser=false` +
   * 本标注 ⇒ 前端按注入来源呈现（不是 user 气泡）——取消通告是引擎侧结论，
   * 永不伪装成用户发言。
   */
  val CancelSource = "askUserCancelled"

  /**
   * 桥的唯一构造点。`requestId` 进 path（日志/诊断可归因），`items` 用于把
   * 答复格式化成模型可读文本（与阻塞模式**同一** `formatAnswer`）。
   */
  def ref(
    target: ActorRef[AgentCommand],
    items: List[AskItem],
    requestId: String,
    ctx: ToolContext
  ): ActorRef[List[String]] =
    new ActorRef[List[String]]:
      val path: ActorPath = ActorPath("__askuser-bridge", List(requestId))

      def !(answers: List[String]): IO[Unit] =
        deliver(target, items, requestId, answers, ctx)

      def ?[R](makeMsg: ActorRef[R] => List[String], t: Option[FiniteDuration]): IO[R] =
        IO.raiseError(
          new UnsupportedOperationException(
            "the one-shot askUser answer bridge does not accept asks"
          )
        )

  /**
   * 答复 → 注入（D4）。`fromUser = true`（裁定 T6=(a)：真人点了卡）——该标志使
   * `AgentActor.injectionSourceFor` 把 `source` 折成 None（呈现为普通 user 气泡）
   * ⇒ 不需要前端登记面、零前端改动。
   *
   * **派发失败信号不注入**（本批）：派发腿在承接面缺席时给出
   * [[nebflow.shared.AskUserDispatch]] 信号（可判读失败），它**不是**用户答复
   * ⇒ 这里显式分支，只留 WARN、**不**向会话注入任何内容（把失败信号当答复注入
   * 会在会话里凭空多出一条空用户消息，并让模型把「提问失败」读成「用户没答」）。
   */
  private def deliver(
    target: ActorRef[AgentCommand],
    items: List[AskItem],
    requestId: String,
    answers: List[String],
    ctx: ToolContext
  ): IO[Unit] =
    if nebflow.shared.AskUserCancelled.isCancelled(answers) then
      // askucancel 批（2026-10-08）：取消通告**要**注入（与派发失败信号相反）——
      // 非阻塞 ack 曾向模型承诺「答案稍后作为消息到达」；问题被取消后若不注入，
      // 模型就在等一条永不来的消息。通告文本 = 信号本体（自描述：requestId/reason/
      // 与用户答复的判别句），fromUser=false + CancelSource 标注。
      val text = answers.head
      sessionAlive(ctx).flatMap {
        case false =>
          logger.warn(
            s"askUser answer bridge: requestId=$requestId — cancel notice dropped, the requesting session is gone " +
              s"(sessionId=${ctx.sessionId.getOrElse("<unknown>")})"
          )
        case true =>
          logger.info(
            s"askUser answer bridge: requestId=$requestId → cancellation notice injected into " +
              s"session=${ctx.sessionId.getOrElse("<unknown>")}"
          ) *>
            (target ! AgentCommand.ImmediateInput(
              text = text,
              source = Some(CancelSource),
              eventType = Some(CancelSource),
              fromUser = false
            ))
      }
    else if AskUserDispatch.isFailure(answers) then
      logger.warn(
        s"askUser answer bridge: requestId=$requestId — dispatch FAILED " +
          s"(${AskUserDispatch.UnavailableCode}); the question was never shown, so this is not a user " +
          s"answer and nothing is injected into session=${ctx.sessionId.getOrElse("<unknown>")}"
      )
    else
      // askuser-dragdrop 实施批（2026-10-19，批 #551）：剥取尾随**答复附件清单槽**
      // （hub `complete` 只在帧带非空 attachments 时追加；无附件 ⇒ take 恒等透传，
      // 注入文本与改前逐字节一致）。
      val (slots, attachments) = AskUserAnswerAttachments.take(answers)
      val text = AskUserQuestionTool.formatAnswer(items, slots)
      sessionAlive(ctx).flatMap {
        case false =>
          // answer-landing 实施批（2026-10-21）：**最后一公里断裂点接三臂着陆面**
          // （设计批 reports/20261020_123548_askuser-social-answer-inject §3–§5 原案）。
          // 改前此分支 = WARN + 静默丢（答案终点站）；现按构造时归因快照（ctx 的
          // project/flowNodeId）分流：项目宿主在 ⇒ 引擎重入（ReenterDispatcher 族
          // AskAnswerReentry）；委托宿主在 ⇒ 注入委托会话；归因缺席/宿主不在 ⇒
          // 失败出口（审计 + 桌面 toast + 渠道失败回执「问题已作废+原因」——任何
          // 出口不静默）。附件清单段随答案同走重入（同一把 formatAnswer 尺，设计
          // §3「答案语义不变」）。
          attachmentInjection(attachments, requestId, ctx).flatMap { case (attachText, _, _) =>
            val fullText = if attachText.isEmpty then text else text + attachText
            AskAnswerLanding.land(requestId, items, fullText, ctx)
          }
        case true =>
          attachmentInjection(attachments, requestId, ctx).flatMap { case (attachText, imageBlocks, uiLines) =>
            val injectText = if attachText.isEmpty then text else text + attachText
            val uiText = if uiLines.isEmpty then injectText else injectText + uiLines
            // blocks 在场 ⇒ LLM 消息取 blocks（Path 见 AgentProcessing drain）；UI 气泡
            // 取 text。无附件 ⇒ blocks 缺席，与改前逐字节同形。
            val blocksOpt =
              if attachText.isEmpty then None
              else Some(ContentBlock.Text(injectText) +: imageBlocks)
            logger.info(
              s"askUser answer bridge: requestId=$requestId → injected as a user message into " +
                s"session=${ctx.sessionId.getOrElse("<unknown>")} (${slots.size} answer slot(s)" +
                (if attachments.isEmpty then "" else s", ${attachments.size} attachment(s)") + ")"
            ) *>
              (target ! AgentCommand.ImmediateInput(
                text = uiText,
                blocks = blocksOpt,
                source = Some(AskUserAnswerSource.AnswerSource),
                eventType = Some(AskUserAnswerSource.AnswerSource),
                fromUser = true
              ))
          }
      }

  /**
   * 答复附件的**双通道注入物构造**（askuser-dragdrop 实施批，规格 §3.4）：
   *
   *   · 文本通道（全部附件）：路径清单行 —— 模型可对任意一条 `Read`（与出向
   *     attachments 的 `FileRefs.describe` 口径同族：路径引用面）。
   *   · vision 块（图片子集）：`mime` 以 `image/` 开头且 ≤10MB 的**前 5 张** →
   *     `ContentBlock.Image`(base64) —— 数值对齐 `ImageInject` 既有上限（单源常量，
   *     禁第二把尺）；>10MB 图片仅路径文本（模型可 Read / 开 Canvas）。
   *
   * 纯读盘 + 纯串构造，零写面；文件缺失 / 读失败 ⇒ 该件降级为「不可读」清单行，
   * 绝不让一次读失败炸掉整个答复注入。
   *
   * @return (模型清单文本, 图片块, UI 气泡附加行)
   */
  private def attachmentInjection(
    attachments: List[Json],
    requestId: String,
    ctx: ToolContext
  ): IO[(String, List[ContentBlock.Image], String)] =
    if attachments.isEmpty then IO.pure(("", Nil, ""))
    else
      val parsed = attachments.map { a =>
        val c = a.hcursor
        val path = c.downField("path").as[String].toOption.getOrElse("")
        val name = c.downField("name").as[String].toOption.getOrElse(path.split('/').lastOption.getOrElse("file"))
        val mime = c
          .downField("mime")
          .as[String]
          .toOption
          .orElse(c.downField("mimeType").as[String].toOption)
          .getOrElse("application/octet-stream")
        val size = c.downField("size").as[Long].toOption.getOrElse(0L)
        val sha = c.downField("sha256").as[String].toOption.getOrElse("")
        (path, name, mime, size, sha)
      }
      val maxImages = ImageInject.MAX_ATTACHMENTS
      val maxImageBytes = ImageInject.MAX_IMAGE_BYTES
      def isFileOk(path: String, size: Long): Boolean =
        try os.isFile(os.Path(path)) && os.size(os.Path(path)) == size
        catch case _: Throwable => false
      def readImage(path: String): IO[Option[String]] =
        if !isFileOk(path, -1L) then IO.pure(None)
        else
          IO.blocking(java.util.Base64.getEncoder.encodeToString(os.read.bytes(os.Path(path))))
            .map(Some(_))
            .handleErrorWith(e =>
              logger
                .warn(s"askUser answer bridge: requestId=$requestId — image read failed ($path): ${e.getMessage}")
                .as(None)
            )
      // vision 子集：图片、文件仍在、≤10MB 的**前 5 张**（ImageInject 单源上限）。
      val imageCandidates =
        parsed
          .collect { case e @ (p, _, m, s, _) if m.startsWith("image/") && s > 0 && s <= maxImageBytes => e }
          .take(maxImages)
      val imageReads = imageCandidates.traverse { case (p, _, _, _, _) => readImage(p) }
      imageReads.map { imageB64s =>
        val lines = parsed.map { case (path, name, mime, size, sha) =>
          val shaNote = if sha.nonEmpty then s", sha256=$sha" else ""
          val unreadNote = if isFileOk(path, size) then "" else " (file missing or changed since upload)"
          s"- $path — $name, $mime, $size bytes$shaNote$unreadNote"
        }
        val imageBlocks = imageCandidates.zip(imageB64s).collect { case ((_, _, mime, _, _), Some(b64)) =>
          ContentBlock.Image(b64, mime)
        }
        val attachText =
          "\n\nUser attached " + parsed.size + " file(s) with this answer:\n" + lines.mkString("\n") +
            "\n(Read any of these paths for full content" +
            (if imageBlocks.nonEmpty then "; the attached images are already visible to you" else "") +
            ".)"
        val uiLines = "\n\n" + parsed
          .map { case (path, name, mime, size, _) =>
            s"- $name (${humanSize(size)})" + (if isFileOk(path, size) then "" else " · unavailable")
          }
          .mkString("\n")
        (attachText, imageBlocks, uiLines)
      }
  end attachmentInjection

  /** 人可读大小（注入气泡行用；纯展示，禁进任何判据）。 */
  private def humanSize(bytes: Long): String =
    if bytes >= 1_048_576 then f"${bytes / 1048576.0}%.1f MB"
    else if bytes >= 1024 then s"${bytes / 1024} KB"
    else s"$bytes B"

  /**
   * 目标会话是否还在（注册表行存在 = 活着）。无 sharedResources / 无 sessionId
   * （spec harness、非 agent 上下文）⇒ 不阻拦投递（保持可独立单测），但那条
   * 路径在生产里不存在（工具只在 agent 会话里被调用）。
   */
  private def sessionAlive(ctx: ToolContext): IO[Boolean] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) => res.agentRegistry.get.map(_.contains(sid))
      case _ => IO.pure(true)

end AskUserAnswerBridge
