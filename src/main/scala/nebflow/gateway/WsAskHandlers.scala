package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AskHandoffPort
import nebflow.core.ask.{AskCooldown, AskGatewayServices, AskNoteSnapshot, AskNoteStore, AskNoteWrite, AskSessions}

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas ask mode · WS 域（机制稿 §2/§3.3/§4/§7，实施批 2026-10-28）
 *
 * 入站四帧（WsDispatch 注册；出站流式帧沿用 AgentStreamEvent 通道——sessionId
 * = `ask-<hash>`，前端 ask 拦截面路由进气泡列，与主会话视图零交集）：
 *   askOpen     {file:{absPath,name}, mainSessionId?} → askState（绑定 + 历史
 *               回放 + 冷却组装腿；§4「按文件路径+会话键持久化，跨重启复现」）
 *   askQuestion {sessionId, content} → dispatchUserText（真实用户文本链路，
 *               source="ask-dock"；textDelta/done 流式回填气泡）
 *   askHandoff  {sessionId, question} → §7 转交（dock 输入条腿；与工具腿同
 *               限频 1/min/文件，同一 AskHandoffPort 投递面）
 *   askReply    {sessionId, content} → §7 回问（主会话侧 → ask 会话，
 *               ExternalEvent(source="main")，「主会话回问：」前缀在注入文本）
 * ═══════════════════════════════════════════════════════════════════════════ */
private[gateway] object WsAskHandlers:

  private val pdfLogger = nebflow.shared.NebflowLogger.forName("nebflow.pdf.asktrigger")

  val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "askOpen" -> handleAskOpen,
    "askQuestion" -> handleAskQuestion,
    "askHandoff" -> handleAskHandoff,
    "askReply" -> handleAskReply,
    "askNoteGet" -> handleAskNoteGet,
    "askNotePut" -> handleAskNotePut,
    // canvas-inline-diff-review：canvas 内联审阅 resolve 三腿（接受/拒绝可携
    // hunkId 逐块；dismiss = 冲突逃生口）。
    "askEditAccept" -> handleAskEditAccept,
    "askEditReject" -> handleAskEditReject,
    "askEditDismiss" -> handleAskEditDismiss
  )

  private def send(wsSend: Json => IO[Unit], payload: Json): IO[Unit] = wsSend(payload)

  private def askError(wsSend: Json => IO[Unit], sessionId: String, message: String): IO[Unit] =
    send(
      wsSend,
      Json.obj(
        "type" -> "askError".asJson,
        "sessionId" -> sessionId.asJson,
        "message" -> message.asJson
      )
    )

  // ── askOpen：绑定 + 冷却组装 + 历史回放 ──────────────────────────────────

  private def handleAskOpen(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val raw = WsDispatch.parsedJson(text)
    val absPath = raw.hcursor
      .downField("file")
      .downField("absPath")
      .as[String]
      .getOrElse(
        raw.hcursor.downField("absPath").as[String].getOrElse("")
      )
    val fileName = raw.hcursor
      .downField("file")
      .downField("name")
      .as[String]
      .getOrElse(
        raw.hcursor.downField("name").as[String].getOrElse("file")
      )
    val mainSessionId = raw.hcursor.downField("mainSessionId").as[String].getOrElse("")
    // ── 分支管理问答批（gitviz，2026-11-19 第二轮）──────────────────────────
    // 同一个 askOpen 帧承载第二域：`kind="gitviz"` 时作用域 = **项目仓库根**（服务端
    // 由 ProjectStore 解析，不吃客户端报的路径 —— 与 git-graph 端点同一个解析面），
    // 不走文件类型白名单（它本来就不是文件），也不做文件上下文组装（提示词由
    // AskGatewayServices.agentDefFor 的 gitviz 分支给）。会话 id 仍落 `ask-` 族：
    // 前端给确定性 id、后端只校验形状（防任意键进 index.json），形状不合法则后端派生。
    val kindReq = raw.hcursor.downField("kind").as[String].getOrElse("").trim
    val projectReq = raw.hcursor.downField("project").as[String].getOrElse("").trim

    if kindReq == "gitviz" then
      val clientSid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
      val sid0 =
        if AskSessions.isGitVizSessionIdShape(clientSid) then clientSid
        else AskSessions.gitVizSessionIdFor(projectReq)
      if projectReq.isEmpty then askError(wsSend, sid0, "askOpen(gitviz): project is required")
      else
        AskGatewayServices.instance match
          case None => askError(wsSend, sid0, "ask mode unavailable on this instance")
          case Some(svc) =>
            nebflow.core.project.ProjectStore.load(projectReq).flatMap {
              case None => askError(wsSend, sid0, s"askOpen(gitviz): project '$projectReq' not found")
              case Some(pd) =>
                val root = nebflow.core.project.ProjectStore.brainWorkspace(pd)
                for
                  binding <- svc.registry.bind(
                    root,
                    projectReq,
                    explicitId = sid0,
                    kind = nebflow.core.ask.AskBinding.KindGitViz
                  )
                  sid = binding.id
                  _ <- if mainSessionId.nonEmpty then svc.registry.refreshMainSession(sid, mainSessionId) else IO.unit
                  msgs <- sessionStore.getUiMessages(sid, 0, 200).map(_._1)
                  _ <- send(
                    wsSend,
                    Json.obj(
                      "type" -> "askState".asJson,
                      "sessionId" -> sid.asJson,
                      "kind" -> "gitviz".asJson,
                      "project" -> projectReq.asJson,
                      "file" -> binding.file.asJson,
                      "fileName" -> binding.fileName.asJson,
                      "mailAddress" -> (nebflow.core.delegate.MailAddress.AskPrefix + sid).asJson,
                      "messages" -> encodeTranscript(msgs).asJson
                    )
                  )
                yield ()
                end for
            }
      end if
    else if absPath.trim.isEmpty then askError(wsSend, "", "askOpen: file.absPath is required")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, "", "ask mode unavailable on this instance")
        case Some(svc) =>
          val canonical = ctx.expandTilde(absPath.trim)
          // 可问面硬闸（设计稿 §2；调研稿 §7-A2；批⑩ 2026-10-31 扩列电子书
          // epub/mobi/azw3）：canonicalize 之后、bind 之前拒非白名单——既有
          // askError 通道，绑定表零写入（仅闸新问题：存量绑定不清理，自然
          // 衰减 §9-8）。
          if !AskSessions.isAskable(canonical) then
            askError(
              wsSend,
              "",
              s"askOpen: file type not askable (allowed: ${(AskSessions.AskableExtensions ++ AskSessions.AskableEbookExtensions).toList.sorted.mkString(",")}): $fileName"
            )
          else
            for
              binding <- svc.registry.bind(canonical, fileName)
              sid = binding.id
              _ <- if mainSessionId.nonEmpty then svc.registry.refreshMainSession(sid, mainSessionId) else IO.unit
              freshBinding <- svc.registry.byId(sid).map(_.getOrElse(binding))
              _ <- svc
                .assembleIfDue(sid, freshBinding)
                .handleErrorWith(e => logger.warn(s"ask assemble failed (non-fatal,问答继续): ${e.getMessage}"))
              msgs <- sessionStore.getUiMessages(sid, 0, 200).map(_._1)
              _ <- send(
                wsSend,
                Json.obj(
                  "type" -> "askState".asJson,
                  "sessionId" -> sid.asJson,
                  "sessionKey" -> AskSessions.displayKey(canonical).asJson,
                  "file" -> freshBinding.file.asJson,
                  "fileName" -> freshBinding.fileName.asJson,
                  // ask-mail 批（2026-11-16）：会话的 Mail 续聊地址（单源 =
                  // MailAddress.AskPrefix + sid）——dock 头部渲染可复制地址行，
                  // 编排方/用户可经 Mail(ask:<会话id>) 续聊。
                  "mailAddress" -> (nebflow.core.delegate.MailAddress.AskPrefix + sid).asJson,
                  "messages" -> encodeTranscript(msgs).asJson,
                  // canvas-inline-diff-review：在列审阅态随回放下发（重载/换端后
                  // canvas 审阅页签重建；引擎为权威——服务端重启即清零，前端本
                  // 地态让位）。
                  "review" -> encodePendingReview(sid)
                )
              )
            yield ()
            end for
          end if
      end match
    end if
  end handleAskOpen

  /**
   * .ui.json 转录 → dock 气泡面（injected 族 = sys；回问行带前缀照常入 sys）。
   *
   * Ask 面板历史丢失批（2026-11-03，作者 15:26 报障「Ask 的历史对话记录会消失，
   * 主要是 AI 回复的内容，我提问的内容还存在」）：旧实现只把 `UiMessage.Agent`
   * 投成 ai 泡——而 ask 会话的 ai 行由 root 帧族落盘点
   * （`WebSocketRoutes.makeRecordingWsSend` 的 toolStart/roundComplete/done）写成
   * `UiMessage.Ai`（typeName=`"ai"`），`UiMessage.Agent`（typeName=`"agent"`）在
   * ask 会话从不出现 ⇒ **AI 回复行在 askOpen 回放里恒被静默丢弃**（collect 不
   * 匹配即跳过），user 行照常下发——正是「问还在、答没了」。盘上数据一直完好
   * （`.ui.json` 两族俱在），丢的是这条回放投影腿；前端 localStorage write-through
   * 与「永不缩水」合并律在同一浏览器里掩住了它，本地账缺席/被淘汰/换设备时回放
   * 是唯一真源 ⇒ 整批 AI 回复消失。
   *
   * 修法：ai 族接 `UiMessage.Ai`。相邻 ai 段（同轮被 toolStart/roundComplete 切开
   * 的多段 flush；tool 行透明不隔断）并回**一**行，正文 = 各段原文**直连**——
   * 与 live 腿同形（textDelta 跨轮续同一 ai 泡 = 原文拼接，无分隔符）。这同时是
   * dock「永不缩水」合并律的正确性前提：不并则服务端快照行数 > 本地 live 行数，
   * 回放采纳后单轮多泡（重开面板与 live 形态漂移）。纯 thinking 段（正文空白）
   * 不出空泡（live 同样无泡）。
   */
  private[gateway] def encodeTranscript(msgs: List[nebflow.shared.UiMessage]): List[Json] =
    val rows = scala.collection.mutable.ListBuffer.empty[Json]
    msgs.foreach {
      case u: nebflow.shared.UiMessage.User if u.injected =>
        rows += Json.obj("role" -> "sys".asJson, "text" -> u.text.asJson, "source" -> u.source.getOrElse("").asJson)
      case u: nebflow.shared.UiMessage.User =>
        // 附图批：附件行件随回放下发（{name,type,path}；前端 path → /uploads 路由取图）。
        // 「图+无正文」行保留（text 可空，行不因正文空被丢弃）。
        val base = Json.obj("role" -> "user".asJson, "text" -> u.text.asJson)
        rows += (if u.attachments.isEmpty then base
                 else base.deepMerge(Json.obj("attachments" -> u.attachments.asJson)))
      case a: nebflow.shared.UiMessage.Ai if a.text.trim.nonEmpty =>
        rows.lastOption.flatMap(_.hcursor.downField("role").as[String].toOption) match
          case Some("ai") =>
            // 同轮相邻段并回一泡（原文直连 = live delta 拼接同形；tool 行透明不隔断，
            // user/sys 行已落行故天然隔断）。
            val prev = rows.last.hcursor.downField("text").as[String].getOrElse("")
            rows(rows.length - 1) = Json.obj("role" -> "ai".asJson, "text" -> (prev + a.text).asJson)
          case _ =>
            rows += Json.obj("role" -> "ai".asJson, "text" -> a.text.asJson)
      case _ => () // tool / agent / ask / system：dock 透明纪律（live 腿同样不进泡列）
    }
    rows.toList
  end encodeTranscript

  // ── askQuestion：真实用户文本链路（与输入框同腿 dispatchUserText）────────

  private def handleAskQuestion(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val content = raw.hcursor.downField("content").as[String].getOrElse("")
    // 附图批：附件清单（主会话 userMessage.attachments 同形）；缺席 = Nil ⇒ 纯文本提问逐字现状。
    val attsJson = raw.hcursor.downField("attachments").as[List[Json]].getOrElse(Nil)

    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askQuestion: sessionId must be an ask- session")
    // 有图不算空：正文空白但带图 ⇒ 合法提问（「图+无正文」）。
    else if content.trim.isEmpty && attsJson.isEmpty then askError(wsSend, sid, "askQuestion: empty content")
    else
      AskAttachmentIntake.parse(attsJson) match
        case Left(why) => askError(wsSend, sid, s"askQuestion: $why")
        case Right(prepared) =>
          AskGatewayServices.instance match
            case None => askError(wsSend, sid, "ask mode unavailable on this instance")
            case Some(svc) =>
              svc.registry.byId(sid).flatMap {
                case None => askError(wsSend, sid, "askQuestion: no file binding for this session")
                case Some(binding) =>
                  // 冷却组装腿（§3.3：冷却期满后的下一次交互——发问——前重组）。
                  svc
                    .assembleIfDue(sid, binding)
                    .handleErrorWith(e => logger.warn(s"ask assemble failed (non-fatal): ${e.getMessage}")) *>
                    // 交互前刷新转交目标（主会话可能已切换）。
                    refreshMainIfReported(svc, sid, raw) *>
                    // PDF note/ask 批（2026-11-18）· 懒解析触发点：给 ask 发的消息是
                    // 解析的唯一触发器。绑定 PDF 时在后台起（或 join）解析——
                    // askOpen / note 腿都不碰 PdfCache.ensure，这里不 await（大文档
                    // 不许挡住提问；模型侧 PdfPage 调用自会 join 同一单飞）。
                    triggerPdfParseIfNeeded(binding) *>
                    // 附图批：落盘（uploads/<askSid>，所有权同主会话）→ 行件 + 模型块 → 同一派发腿。
                    AskAttachmentIntake.save(sid, prepared, ctx.askOwnerAccess).flatMap { saved =>
                      ctx.handleAskUserText(sid, content, saved.map(_.uiJson), saved.flatMap(_.blocks))
                    }
              }
    end if
  end handleAskQuestion

  private def refreshMainIfReported(svc: nebflow.core.ask.AskServices, sid: String, raw: Json): IO[Unit] =
    raw.hcursor.downField("mainSessionId").as[String].toOption.filter(_.nonEmpty) match
      case Some(m) => svc.registry.refreshMainSession(sid, m)
      case None => IO.unit

  /**
   * PDF note/ask 批（2026-11-18）：绑定为 PDF 时后台起（或 join）解析——fire
   * and forget，结果只落日志（失败可读：下一次 askQuestion / PdfPage 调用会再
   * 试并如实报错）。非 PDF 绑定 = IO.unit（零开销直通）。单飞语义由 PdfCache
   * 自带：并发 ask / askQuestion+PdfPage 同文档只解析一遍。
   */
  private def triggerPdfParseIfNeeded(binding: nebflow.core.ask.AskBinding): IO[Unit] =
    if !nebflow.core.ask.AskSessions.isPdf(binding.file) then IO.unit
    else
      val file = os.Path(binding.file, os.pwd)
      if !os.exists(file) then IO.unit // PdfPage 侧会给出可读错误
      else
        nebflow.core.pdf.PdfCache
          .sharedForDataRoot(nebflow.shared.PathUtil.dataRoot)
          .ensure(file, binding.fileName)
          .flatMap {
            case Right(m) => IO.unit // 命中缓存 / 解析完成（含本调用起飞并完成的小文档）
            case Left(f) =>
              pdfLogger.warn(s"pdf parse triggered by ask message failed sha=? code=${f.code}: ${f.message}")
          }
          .start
          .void

  // ── askHandoff：dock 输入条转交腿（§7；与工具腿同限频同投递面）────────────

  private def handleAskHandoff(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val task = raw.hcursor
      .downField("task")
      .as[String]
      .toOption
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(raw.hcursor.downField("question").as[String].toOption.map(_.trim).filter(_.nonEmpty))
      .getOrElse("")
    val paths = raw.hcursor.downField("paths").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
    val reason = raw.hcursor.downField("reason").as[String].toOption.getOrElse("")
    val expected = raw.hcursor.downField("expected").as[String].toOption.getOrElse("")
    // gitviz 批（2026-11-19 第二轮）· 结构化 git 件：面板「多选 + 合并」钮与问答
    // agent 的对话交办**产出同一份字段**（作者令「两个入口收敛到同一个出口」）。
    // 它是**请求**不是执行凭据：本处理器只把四件投进主会话，零 git 操作。
    val gitReq = raw.hcursor.downField("git").focus.flatMap { j =>
      val c = j.hcursor
      c.downField("branches").as[List[String]].toOption.filter(_.nonEmpty).map { branches =>
        AskHandoffPort.GitRequest(
          project = c.downField("project").as[String].toOption.getOrElse(""),
          branches = branches.map(_.trim).filter(_.nonEmpty),
          target = c.downField("target").as[String].toOption.getOrElse("").trim,
          intent = c.downField("intent").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("merge"),
          notes = c.downField("notes").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty),
          source = c.downField("source").as[String].toOption.getOrElse("panel").trim
        )
      }
    }

    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askHandoff: sessionId must be an ask- session")
    else if task.isEmpty then askError(wsSend, sid, "askHandoff: empty task")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, sid, "ask mode unavailable on this instance")
        case Some(svc) =>
          svc.registry.byId(sid).flatMap {
            case None => askError(wsSend, sid, "askHandoff: no file binding for this session")
            case Some(binding) =>
              // ask-scope 批（2026-11-18）：dock 腿与工具腿同一份结构化请求 + 同一帧。
              val req = AskHandoffPort.Request(
                askSessionId = sid,
                file = binding.file,
                fileName = binding.fileName,
                question = AskHandoffPort.handoffSummary(task, paths, reason, expected),
                leg = "ask-dock",
                task = task,
                paths = paths,
                reason = reason,
                expected = expected,
                git = gitReq
              )
              AskHandoffPort.deliver(req).flatMap {
                case Right(_) =>
                  send(
                    wsSend,
                    nebflow.core.ask.AskHandoffWire
                      .ackFrame(sid, ok = true, "", nebflow.core.ask.AskHandoffWire.of(req))
                  )
                case Left(err) =>
                  send(
                    wsSend,
                    nebflow.core.ask.AskHandoffWire
                      .ackFrame(sid, ok = false, err.message, nebflow.core.ask.AskHandoffWire.of(req))
                  )
              }
          }
    end if
  end handleAskHandoff

  // ── askReply：主会话回问腿（§7 反向；前缀由本层注入文本携带）──────────────

  private def handleAskReply(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val content = raw.hcursor.downField("content").as[String].getOrElse("")

    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askReply: sessionId must be an ask- session")
    else if content.trim.isEmpty then askError(wsSend, sid, "askReply: empty content")
    else
      AskGatewayServices.instance match
        case None => askError(wsSend, sid, "ask mode unavailable on this instance")
        case Some(svc) =>
          svc.registry.byId(sid).flatMap {
            case None => askError(wsSend, sid, "askReply: no file binding for this session")
            case Some(_) =>
              val prefixed = s"主会话回问：${content.trim}"
              val metadata = io.circe.JsonObject(
                "askSession" -> sid.asJson,
                "leg" -> "main-reply".asJson
              )
              ctx.ensureAgent(sid)(ref => ref ! AgentCommand.ExternalEvent("main", "inject", prefixed, metadata, None))
          }
    end if
  end handleAskReply

  // ── canvas-inline-diff-review：审阅 resolve 腿 ─────────────────────────────
  //   askEditAccept {sessionId, hunkId?}   hunkId 缺省 = 全部接受（清账零写盘）；
  //     Some(h) = 该块标记已审（基线前移，零写盘）。
  //   askEditReject {sessionId, hunkId?}   hunkId 缺省 = 全部拒绝（回写 base，
  //     整体回退）；Some(h) = 该块回退（回写 current 去掉该块）。
  //   askEditDismiss {sessionId}           保留盘上现状、清账（冲突逃生口）。
  // 应答：askEditResolved {sessionId, action, hunkId?, outcome:"done"|"error",
  //   error?, review?}——review 为余下审阅态（了结时 null）；error（stale 等）
  // 时审阅保持，前端冲突条给重试/保留现状二路。

  /**
   * 在列审阅态 wire 形（与 AskEditTools.reviewJson 同构；gateway 不引 tools
   * 的私有伴生，此处独立编码保持层界）。
   */
  private def encodePendingReview(sid: String): Json =
    nebflow.core.ask.AskEditReviews.reviewFor(sid) match
      case None => Json.Null
      case Some(r) =>
        Json.obj(
          "path" -> r.path.asJson,
          "fileName" -> r.fileName.asJson,
          "baseText" -> r.baseText.asJson,
          "currentText" -> r.currentText.asJson,
          "hunks" -> r.hunks
            .map(hu =>
              Json.obj(
                "id" -> hu.id.asJson,
                "oldStart" -> hu.oldStart.asJson,
                "oldCount" -> hu.oldCount.asJson,
                "newStart" -> hu.newStart.asJson,
                "newCount" -> hu.newCount.asJson,
                "added" -> hu.added.asJson,
                "removed" -> hu.removed.asJson
              )
            )
            .asJson,
          "count" -> r.changes.length.asJson,
          "updatedAt" -> r.updatedAt.asJson
        )

  private def handleAskEditResolve(
    action: String,
    text: String,
    wsSend: Json => IO[Unit]
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val hunkId = raw.hcursor.downField("hunkId").as[String].toOption.filter(_.trim.nonEmpty)
    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askEdit: sessionId must be an ask- session")
    else
      val outcome: IO[Either[String, nebflow.core.ask.AskEditReviews.ResolveResult]] = action match
        case "accept" => nebflow.core.ask.AskEditReviews.accept(sid, hunkId)
        case "reject" => nebflow.core.ask.AskEditReviews.reject(sid, hunkId)
        case "dismiss" => nebflow.core.ask.AskEditReviews.dismiss(sid)
      outcome.flatMap {
        case Right(_) =>
          wsSend(
            Json.obj(
              "type" -> "askEditResolved".asJson,
              "sessionId" -> sid.asJson,
              "action" -> action.asJson,
              "outcome" -> "done".asJson,
              "review" -> encodePendingReview(sid)
            )
          )
        case Left(err) =>
          wsSend(
            Json.obj(
              "type" -> "askEditResolved".asJson,
              "sessionId" -> sid.asJson,
              "action" -> action.asJson,
              "outcome" -> "error".asJson,
              "error" -> err.asJson,
              "review" -> encodePendingReview(sid)
            )
          )
      }
    end if
  end handleAskEditResolve

  private def handleAskEditAccept(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] = handleAskEditResolve("accept", text, wsSend)

  private def handleAskEditReject(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] = handleAskEditResolve("reject", text, wsSend)

  private def handleAskEditDismiss(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] = handleAskEditResolve("dismiss", text, wsSend)

  // ── Note persistence ───────────────────────────────────────────────────
  // Two inbound frames and two outbound frames, with the same file identity as the ask session
  // (sid = ask-<8 hex>); the store is `AskNoteStore` (versions, clears, size limit: see its header).
  //   askNoteGet {sessionId}
  //     → askNoteState {sessionId, text, ts, exists, version, cleared}   (`ts` equals `version`)
  //     → askNoteState {sessionId, error:"read-failed", detail}           (the note cannot be read)
  //   askNotePut {sessionId, text, baseVersion, writeId?}
  //     → askNoteAck {sessionId, ok:true, ts, version, overwritten:false}
  //     → askNoteAck {sessionId, ok:false, error:"conflict"|"version-required", text, ts, version, exists, cleared}
  //     → askNoteAck {sessionId, ok:false, error:"read-failed"|"too-large"|"bad-session-id"|"write-failed", …}
  //   `writeId` is echoed verbatim so a client can match a receipt to one write. A put without
  //   `baseVersion` (a page loaded before versions existed) is always refused: it cannot tell a note
  //   that never existed from one cleared before clears were versioned, and overwriting is the failure.
  // The note is not carried by askOpen: it is a separate data lifecycle from the transcript replay.

  private def noteStateFields(snap: AskNoteSnapshot): List[(String, Json)] =
    List(
      "text" -> snap.text.asJson,
      "ts" -> snap.ts.asJson,
      "version" -> snap.version.asJson,
      "exists" -> snap.exists.asJson,
      "cleared" -> snap.cleared.asJson
    )

  private def noteStateFrame(sid: String, snap: AskNoteSnapshot): Json =
    snap.error match
      case Some(why) =>
        Json.obj(
          "type" -> "askNoteState".asJson,
          "sessionId" -> sid.asJson,
          "error" -> "read-failed".asJson,
          "detail" -> why.asJson
        )
      case None =>
        val head = List("type" -> "askNoteState".asJson, "sessionId" -> sid.asJson)
        Json.obj((head ++ noteStateFields(snap))*)

  private def noteAckFrame(sid: String, writeId: Option[Json], result: AskNoteWrite): Json =
    def ack(fields: (String, Json)*): Json =
      val head = List("type" -> "askNoteAck".asJson, "sessionId" -> sid.asJson)
      Json.obj((head ++ writeId.map("writeId" -> _).toList ++ fields)*)
    result match
      case AskNoteWrite.Ok(version, _) =>
        // overwritten stays for older clients: a conditional write never replaces an unseen version.
        ack("ok" -> true.asJson, "ts" -> version.asJson, "version" -> version.asJson, "overwritten" -> false.asJson)
      case AskNoteWrite.Conflict(current) =>
        ack((List("ok" -> false.asJson, "error" -> "conflict".asJson) ++ noteStateFields(current))*)
      case AskNoteWrite.VersionRequired(current) =>
        ack((List("ok" -> false.asJson, "error" -> "version-required".asJson) ++ noteStateFields(current))*)
      case AskNoteWrite.ReadFailed(msg) =>
        ack("ok" -> false.asJson, "error" -> "read-failed".asJson, "detail" -> String.valueOf(msg).asJson)
      case AskNoteWrite.TooLarge(bytes, limit) =>
        ack("ok" -> false.asJson, "error" -> "too-large".asJson, "bytes" -> bytes.asJson, "limit" -> limit.asJson)
      case AskNoteWrite.BadSessionId(id) =>
        ack("ok" -> false.asJson, "error" -> "bad-session-id".asJson, "detail" -> id.asJson)
      case AskNoteWrite.Failed(msg) =>
        ack("ok" -> false.asJson, "error" -> "write-failed".asJson, "detail" -> String.valueOf(msg).asJson)

    end match

  end noteAckFrame

  private def handleAskNoteGet(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askNoteGet: sessionId must be an ask- session")
    else AskNoteStore.current.read(sid).map(noteStateFrame(sid, _)).flatMap(send(wsSend, _))

  private def handleAskNotePut(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: Json => IO[Unit],
    watch: ExplorerWatchSession
  ): IO[Unit] =
    val raw = WsDispatch.parsedJson(text)
    val sid = raw.hcursor.downField("sessionId").as[String].getOrElse("")
    val body = raw.hcursor.downField("text").as[String].toOption
    // A missing, null, negative or non-numeric base is "no base version".
    val baseVersion = raw.hcursor.downField("baseVersion").as[Long].toOption.filter(_ >= 0L)
    val writeId = raw.hcursor.downField("writeId").focus
    if !AskSessions.isAskSession(sid) then askError(wsSend, sid, "askNotePut: sessionId must be an ask- session")
    else
      body match
        case None => send(wsSend, noteAckFrame(sid, writeId, AskNoteWrite.Failed("text is required")))
        case Some(value) =>
          AskNoteStore.current.put(sid, value, baseVersion).map(noteAckFrame(sid, writeId, _)).flatMap(send(wsSend, _))
  end handleAskNotePut
end WsAskHandlers
