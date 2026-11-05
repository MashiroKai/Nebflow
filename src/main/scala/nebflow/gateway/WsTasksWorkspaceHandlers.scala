/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorSystem as RootActorSystem, sessionId}
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.entity.EntityLoader
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.gateway.NfFilePolicy.*
import nebflow.gateway.WsDispatch.{inboundEnvelope, parsedJson}
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

/** 任务与工作区域(tasks/workspace):定时任务、任务清单、工作区知识、目录选择链。 */
private[gateway] object WsTasksWorkspaceHandlers:

  private[gateway] val handlers: Map[String, WsDispatch.WsHandler] = Map(
    "pickWorkspaceDir" -> handlePickWorkspaceDir,
    "wsBrowse.list" -> handleWsBrowseList,
    "wsBrowse.mkdir" -> handleWsBrowseMkdir,
    "createScheduledTask" -> handleCreateScheduledTask,
    "listScheduledTasks" -> handleListScheduledTasks,
    "deleteScheduledTask" -> handleDeleteScheduledTask,
    "updateScheduledTask" -> handleUpdateScheduledTask,
    "toggleScheduledTask" -> handleToggleScheduledTask,
    "completeTodo" -> handleCompleteTodo,
    "uncompleteTodo" -> handleUncompleteTodo,
    "getTaskList" -> handleGetTaskList,
    "completeTask" -> handleCompleteTask,
    "listWorkspaceItems" -> handleListWorkspaceItems,
    "deleteWorkspaceItem" -> handleDeleteWorkspaceItem,
    "projectCreate" -> handleProjectCreate
  )

  /**
   * `projectCreate` — the direct frontend creation face of the projects panel
   * (projcreate-redesign `372b54d54`, main-side): the panel's create dialog
   * sends the workspace returned by the picker chain above, and this leg turns
   * it into a project through the ONE creation core
   * ([[nebflow.core.project.ProjectCreateService]]) the agent/tool face also
   * uses, so occupancy gates, idempotent re-mount and the per-file scaffold
   * report cannot drift between the two faces.
   *
   * IDENTITY IS VERIFIED, NEVER FABRICATED (freshinstall-rootsessionid ruling):
   * the frame's `sessionId` is only accepted after the server-side session index
   * confirms it exists, and the delivery root then comes from the server-side
   * agent registry via `resolveRootSessionId` — never from a frame field, never
   * a placeholder.
   */
  private def handleProjectCreate(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val hc = json.hcursor
    val pcSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val pcWorkspaceRaw = hc.downField("workspace").as[String].getOrElse("").trim
    val pcName = hc.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val pcDesc = hc.downField("description").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    // v2.1 §A.5（2026-10-09 实施批）：可选 `device` = 项目放哪台设备（缺省 self =
    // 现状零变化；对端设备 = 远端创建腿，G.13 半态回滚序，见 ProjectCreateService）。
    val pcDevice = hc.downField("device").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val pcWs = PathUtil.expandTilde(pcWorkspaceRaw)
    if pcSessionId.nonEmpty && pcWs.nonEmpty then
      (for
        // Fail-closed identity check (the session must really exist).
        metaOpt <- sharedResources.sessionStore.getSessionMeta(pcSessionId)
        _ <- IO.raiseUnless(metaOpt.isDefined)(
          new RuntimeException(
            s"unknown session '$pcSessionId' — refusing to attribute the project's delivery root"
          )
        )
        // Absolute-path check mirrors the tool face / the panel answer parser
        // (PathUtil.isAbsolute: POSIX, Windows drive, UNC). A project workspace
        // is by design unrestricted in location — the frontend picker hands
        // back an absolute directory — so the only shape rejected here is
        // "not an absolute path".
        _ <- IO.raiseUnless(PathUtil.isAbsolute(pcWs))(
          new RuntimeException(s"workspace must be an absolute path (got '$pcWorkspaceRaw')")
        )
        root <- resolveRootSessionId(pcSessionId)
        outcome <- nebflow.core.project.ProjectCreateService.create(
          pcName,
          pcWs,
          pcDesc,
          nebflow.core.project.ProjectCreateService.CreateIdentity(
            actorSystem = Some(sharedResources.actorSystem),
            sharedResources = Some(sharedResources),
            // The server-resolved root, not the raw frame field: the same
            // upline-root semantics the tool face has.
            rootSessionId = Some(root),
            sessionId = Some(pcSessionId),
            wsSend = Some(projectCreateBroadcast)
          ),
          device = pcDevice
        )
        _ <- outcome match
          case Right(msg) =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "projectCreateResult".asJson,
                "ok" -> true.asJson,
                "message" -> msg.asJson
              )
            )
          case Left(err) =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "projectCreateResult".asJson,
                "ok" -> false.asJson,
                "error" -> err.message.asJson
              )
            )
      yield ())
        .handleErrorWith { e =>
          logger.warn(s"projectCreate failed: ${e.getMessage}")
          wsSend(
            io.circe.Json.obj(
              "type" -> "projectCreateResult".asJson,
              "ok" -> false.asJson,
              "error" -> e.getMessage.asJson
            )
          )
        }
    else
      logger.warn(
        "projectCreate: dropped frame missing sessionId or workspace " +
          s"(sessionId=${if pcSessionId.nonEmpty then "present" else "absent"}, " +
          s"workspace=${if pcWorkspaceRaw.nonEmpty then "present" else "absent"})"
      ) *> IO.unit
    end if
  end handleProjectCreate

  private def handlePickWorkspaceDir(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ── 工作区目录选择链（2026-09-05 作者裁定，workspace-picker 批次）──
    // ProjectCreate「选择工作区」卡片点击 → 后端原生目录对话框（Route A）；
    // headless/异常时 WorkspaceDirPicker 回 fallback 事件 → 前端降级应用内
    // 浏览器（Route C，wsBrowse.list / wsBrowse.mkdir）。
    // 关键：WS 帧串行（evalMap），对话框阻塞必须 `.start` 独立 fiber，
    // 否则冻结整条连接（卡片后续 askUserAnswer 无法处理）。
    val hc = parsedJson(text).hcursor
    // 未走信封助手:sessionId/requestId 组成 Option 元组匹配(缺席走显式回退分支),非单值空串回退同形,保持原样(2026-09-24)
    (hc.downField("sessionId").as[String].toOption, hc.downField("requestId").as[String].toOption) match
      case (Some(sid), Some(rid)) =>
        WorkspaceDirPicker.pick(sid, rid, wsSend).start.void
      case _ =>
        logger.warn(s"pickWorkspaceDir: missing sessionId/requestId — dropped")
  end handlePickWorkspaceDir

  private def handleWsBrowseList(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val hc = parsedJson(text).hcursor
    // ui-device-faces 批（2026-10-10 · 设计稿 §6 后端①，开放题⑤）：可选 `device` 键
    // —— 缺省/缺键 = 本机腿逐字节（存量零回归）；对端设备 = 远端**只读**目录列举
    // （scope=browse 元数据，零字节流动、零 mkdir——G.13 序要求目录已存在）。
    // 解析复用 WsFileOpsHandlers.resolveBrowseTarget（listDir/readFile 同一单点）。
    val browseDevice = hc.downField("device").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    hc.downField("path").as[String].toOption.filter(_.nonEmpty) match
      case None => IO.unit
      case Some(raw) =>
        browseDevice match
          case Some(dev) => browseRemoteDirs(ctx, dev, raw, wsSend)
          case None =>
            IO.blocking {
              scala.util.Try(os.Path(expandTilde(raw), os.Path(sys.props("user.home")))).toOption match
                case None => (raw, None, List.empty[String], "invalid path")
                case Some(dir) =>
                  if !os.isDir(dir) then (dir.toString, None, Nil, "not a directory")
                  else
                    // 2026-09-09 作者裁定：默认显示隐藏文件夹（dot 目录不过滤）——
                    // 选目录模式须能进入 ~/.nebflow/projects/（隐藏目录嵌套路径）。
                    val entries = os
                      .list(dir)
                      .filter(os.isDir)
                      .map(_.last)
                      .toList
                      .sorted
                    // 根目录无上级（segmentCount==0）；其余经 os.up 规范化
                    val parent = if dir.segmentCount > 0 then Some((dir / os.up).toString) else None
                    (dir.toString, parent, entries, null: String)
            }.flatMap { case (path, parent, entries, err) =>
              wsSend(wsBrowseEvent("wsBrowseList", path, parent, entries, Option(err)))
            }.handleErrorWith { e =>
              wsSend(wsBrowseEvent("wsBrowseList", raw, None, Nil, Some(e.getMessage)))
            }
    end match
  end handleWsBrowseList

  /**
   * `wsBrowse.list` 远端腿（ui-device-faces 批 2026-10-10）：对端目录**元数据列举**
   * （direction=list scope=browse——零字节），帧形与本地腿同构 + additive `device`；
   * **只读面**：仅目录名（选目录模式语义）、无 mkdir 对应腿（G.13 目录须已存在）。
   * 不带 `home`（远端盘无本机 home 语义——前端面包屑自根展开 + 设备 chip 前缀）。
   * 任何失败 = 显式 error 帧（fail-closed，G.12：绝不静默落本机）。
   *
   * additive `resolvedPath`（2026-10-30 三修①）：对端解析后的**真实绝对路径**——
   * 初始 `~` 由接收端展开为它对家目录（如 `C:\Users\name`），前端面包屑/上级行
   * 以真实路径展开（老帧无该键 ⇒ 前端回退原 path，逐字节零回归）。
   */
  private def browseRemoteDirs(
    ctx: WsDispatchCtx,
    device: String,
    raw: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    import ctx.*
    def frame(entries: List[String], err: Option[String], resolved: Option[String] = None): io.circe.Json =
      val fields = scala.collection.mutable.ListBuffer[(String, io.circe.Json)](
        "type" -> io.circe.Json.fromString("wsBrowseList"),
        "path" -> io.circe.Json.fromString(raw),
        "entries" -> io.circe.Json.arr(entries.map(io.circe.Json.fromString)*),
        "device" -> io.circe.Json.fromString(device)
      )
      resolved.foreach(r => fields += "resolvedPath" -> io.circe.Json.fromString(r))
      err.foreach(e => fields += "error" -> io.circe.Json.fromString(e))
      io.circe.Json.obj(fields.toList*)
    WsFileOpsHandlers
      .resolveBrowseTarget(device)(using ctx)
      .flatMap {
        case Left(err) => wsSend(frame(Nil, Some(err)))
        case Right(None) =>
          // 防御分支（与 listDir/readFile 同语义）：self 目标应由调用方去掉 device 键。
          wsSend(frame(Nil, Some(s"'$device' resolved to this device — re-send without the device key")))
        case Right(Some(peer)) =>
          nebflow.core.tools.RemoteExecutor.current match
            case None =>
              wsSend(frame(Nil, Some("remote executor not initialized (NebLink disabled?)")))
            case Some(re) =>
              re.transferListScoped(peer, raw).flatMap {
                case Left(err) =>
                  logger.warn(s"wsBrowse.list remote leg failed for ${peer.deviceName}: $err")
                  wsSend(frame(Nil, Some(err)))
                case Right(json) =>
                  // 选目录模式语义 = 仅目录（本地腿同款 dirs-only），按名排序。
                  val names = json.hcursor
                    .downField("entries")
                    .as[List[io.circe.Json]]
                    .toOption
                    .getOrElse(Nil)
                    .flatMap { e =>
                      val isDir = e.hcursor.downField("type").as[String].toOption.contains("dir")
                      val name = e.hcursor.downField("name").as[String].toOption
                      if isDir then name else None
                    }
                    .sorted
                  // 对端解析后的真实绝对路径（其 handleList 响应体的 `path` 键）——
                  // additive 透传，初始 `~` 的面包屑展开点（见本函数头注）。
                  val resolved = json.hcursor.downField("path").as[String].toOption
                  wsSend(frame(names, None, resolved))
              }
      }
      .handleErrorWith { e =>
        logger.warn(s"wsBrowse.list remote leg failed: ${e.getMessage}")
        wsSend(frame(Nil, Some(e.getMessage)))
      }
  end browseRemoteDirs

  private def handleWsBrowseMkdir(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val hc = parsedJson(text).hcursor
    (hc.downField("path").as[String].toOption, hc.downField("name").as[String].toOption.map(_.trim)) match
      case (Some(raw), Some(name))
          if name.nonEmpty && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') =>
        IO.blocking {
          val base = os.Path(expandTilde(raw), os.Path(sys.props("user.home")))
          os.makeDir(base / name)
          base / name
        }.flatMap { created =>
          wsSend(
            Json.obj(
              "type" -> Json.fromString("wsBrowseMkdir"),
              "path" -> Json.fromString(created.toString),
              "ok" -> Json.fromBoolean(true)
            )
          )
        }.handleErrorWith { e =>
          wsSend(
            Json.obj(
              "type" -> Json.fromString("wsBrowseMkdir"),
              "path" -> Json.fromString(raw),
              "ok" -> Json.fromBoolean(false),
              "error" -> Json.fromString(Option(e.getMessage).getOrElse("mkdir failed"))
            )
          )
        }
      case _ => IO.unit
    end match
  end handleWsBrowseMkdir

  private def handleCreateScheduledTask(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Scheduled Task Management =====

    val json = parsedJson(text)
    val hc = json.hcursor
    val crSessionId = hc.downField("sessionId").as[String].getOrElse("")
    val crContent = hc.downField("content").as[String].getOrElse("")
    val crTriggerAt = hc.downField("triggerAt").as[Long].getOrElse(0L)
    val crRefPath = hc.downField("referencePath").as[Option[String]].getOrElse(None)
    val crRepeat = hc.downField("repeat").as[Option[String]].getOrElse(None)
    val crName = hc.downField("name").as[Option[String]].getOrElse(None).map(_.trim).filter(_.nonEmpty)
    // 待办分家批（2026-10-30）：kind="todo" = 待办面创建（侧边栏待办面板）——
    // 无固定触发时间，triggerAt 由引擎侧固定策略算（下一次 09:00 浮出），客户端
    // 时间不采信；repeat 无待办语义。缺省/其它值 = 定时任务面（现状逐字节不变）。
    // 改版批追加令（2026-10-31 23:14）：project = 事项归属标签（UI 分组/筛选维度）。
    val crKindTodo = hc.downField("kind").as[Option[String]].getOrElse(None)
      .map(_.trim.toLowerCase).contains(nebflow.core.scheduler.ScheduledTask.KindTodo)
    val crProject = hc.downField("project").as[Option[String]].getOrElse(None)
      .map(_.trim).filter(_.nonEmpty)
    // 待办体系统一批（2026-11-04）：priority 0..3（缺键/null/非数字 = 0；越界钳位
    // 在 ScheduledTask.clampPriority 单点，库内不出现 4/‑1）。定时任务面不消费。
    val crPriority = hc.downField("priority").as[Int].toOption
      .map(nebflow.core.scheduler.ScheduledTask.clampPriority).getOrElse(0)
    if crKindTodo && crSessionId.nonEmpty && crContent.nonEmpty then
      val surfaceAt = nebflow.core.scheduler.ScheduledTask.nextTodoSurfaceAt(
        System.currentTimeMillis(), java.time.ZoneId.systemDefault()
      )
      val todo = nebflow.core.scheduler.ScheduledTask.create(
        crSessionId, crContent, surfaceAt, None, None, crName,
        Some(nebflow.core.scheduler.ScheduledTask.KindTodo), None, crProject,
        crPriority
      )
      (for
        removed <- crName match
          case Some(_) => sharedResources.scheduledTaskStore.upsertTaskByName(todo)
          case None => sharedResources.scheduledTaskStore.addTask(todo).as(Nil)
        _ <- sharedResources.scheduledTaskService.traverse_(_.notifyTaskChange())
        _ <- removed.traverse_(old =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "scheduledTaskDeleted".asJson,
              "id" -> old.id.asJson,
              "sessionId" -> old.sessionId.asJson
            )
          )
        )
        _ <- wsSend(
          io.circe.Json.obj(
            "type" -> "scheduledTaskCreated".asJson,
            "sessionId" -> crSessionId.asJson,
            "task" -> io.circe.Json.obj(
              "id" -> todo.id.asJson,
              "content" -> todo.content.asJson,
              "triggerAt" -> todo.triggerAt.asJson,
              "createdAt" -> todo.createdAt.asJson,
              "referencePath" -> io.circe.Json.Null,
              "repeat" -> io.circe.Json.Null,
              "name" -> todo.name.asJson,
              "kind" -> nebflow.core.scheduler.ScheduledTask.KindTodo.asJson,
              "project" -> todo.project.asJson,
              // 待办体系统一批（2026-11-04）：纯增量三键（老前端零感知）。
              "priority" -> todo.priority.asJson,
              "completed" -> todo.isCompleted.asJson,
              "completedAt" -> todo.completedAt.asJson
            )
          )
        )
      yield ()).handleErrorWith(e =>
        logger.warn(s"createScheduledTask(kind=todo) failed: ${e.getMessage}")
      )
    else if crSessionId.nonEmpty && crContent.nonEmpty && crTriggerAt > System.currentTimeMillis() then
      val task =
        nebflow.core.scheduler.ScheduledTask.create(crSessionId, crContent, crTriggerAt, crRefPath, crRepeat)
      sharedResources.scheduledTaskStore.addTask(task).flatMap { _ =>
        // Creation audit trail: without this line a lost WS create (fire-and-forget
        // client, no ack retry) is indistinguishable from a persistence failure —
        // see the 2026-08-17 P0 where the file was never written and no server-side
        // trace existed to separate "message never arrived" from "arrived and broke".
        // NOTE: infoSync/warnSync — the IO-returning info/warn would be discarded
        // as bare statements (effect never runs).
        logger.infoSync(
          s"Scheduled task created: ${task.id} session=${task.sessionId} " +
            s"triggerAt=${task.triggerAt} content=${task.content.take(40)}"
        )
        sharedResources.scheduledTaskService.foreach(_.notifyTaskChange())
        wsSend(
          io.circe.Json.obj(
            "type" -> "scheduledTaskCreated".asJson,
            "task" -> io.circe.Json.obj(
              "id" -> task.id.asJson,
              "content" -> task.content.asJson,
              "triggerAt" -> task.triggerAt.asJson,
              "createdAt" -> task.createdAt.asJson,
              "referencePath" -> task.referencePath.asJson,
              "repeat" -> task.repeat.asJson
            )
          )
        )
      }
    else
      val reason =
        if crSessionId.isEmpty then "missing sessionId"
        else if crContent.isEmpty then "missing content"
        else if crTriggerAt <= System.currentTimeMillis() then "triggerAt must be in the future"
        else "unknown"
      // Rejections must be loud: the client treats creates as fire-and-forget with
      // an optimistic row, so a silent reject reads as "task set" until it never
      // fires. Log it, and tag the error with the originating msgType so the
      // frontend can route it back to the scheduled-task panel.
      logger.warnSync(
        s"Rejected createScheduledTask: $reason (session=$crSessionId " +
          s"triggerAt=$crTriggerAt content=${crContent.take(40)})"
      )
      wsSend(
        io.circe.Json.obj(
          "type" -> "error".asJson,
          "msgType" -> "createScheduledTask".asJson,
          "message" -> s"Invalid scheduled task: $reason".asJson
        )
      )
    end if
  end handleCreateScheduledTask

  /** `listScheduledTasks` — 单会话全量（现状）+ 改版批（2026-10-31）`scope:"all"`
   *  跨会话待办收件箱（仅 kind=todo 语义，用户面聚合，数据源 =
   *  store.getAllTodos）。all 路径逐行带 sessionId（行级操作帧 completeTodo /
   *  deleteScheduledTask 据此跨会话寻址；completeTodo 后端按 id 查库取真值，
   *  帧内值仅兜底）；响应回显 scope 供前端路由（收件箱列表 vs 提醒面列表分态，
   *  互不漏行）。单会话路径逐字节不变（无 scope 字段的旧前端零感知）。
   *
   *  待办体系统一批（2026-11-04）：scope:"all" 增两件——① 排序**服务端权威**
   *  （`ScheduledTask.todoSortKey`：未完成优先 → priority 降序 → triggerAt 升序）；
   *  ② 已完成行的返回策略 = **默认不出**（收件箱语义，等价改前「删行即消失」），
   *  需要看归档/撤销面时帧内显式传 `includeCompleted:true` opt-in（默认路径一字
   *  不改）。行 JSON 增 priority / completed / completedAt 三键（纯增量）。 */
  private def handleListScheduledTasks(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val lrSessionId = inboundEnvelope(text).sessionId
    val json = parsedJson(text)
    val scopeAll = json.hcursor.downField("scope").as[String].toOption
      .map(_.trim.equalsIgnoreCase("all")).getOrElse(false)
    if scopeAll then
      val includeCompleted = json.hcursor.downField("includeCompleted").as[Boolean].toOption
        .getOrElse(false)
      val todosIO =
        if includeCompleted then sharedResources.scheduledTaskStore.getAllTodosIncludingCompleted
        else sharedResources.scheduledTaskStore.getAllTodos
      todosIO.flatMap { todos =>
        // 排序服务端权威（可从每行数据算出）：前端只消费顺序，不许内存重排。
        val taskJsons = todos.sortBy(nebflow.core.scheduler.ScheduledTask.todoSortKey).map(listTaskJson)
        wsSend(
          io.circe.Json.obj(
            "type" -> "scheduledTaskList".asJson,
            "tasks" -> taskJsons.asJson,
            "sessionId" -> lrSessionId.asJson,
            "scope" -> "all".asJson,
            // 回显过滤口径（前端据此知道「没收到已完成行」是过滤而非空库）。
            "includeCompleted" -> includeCompleted.asJson
          )
        )
      }
    else if lrSessionId.nonEmpty then
      sharedResources.scheduledTaskStore.loadTasks(lrSessionId).flatMap { allTasks =>
        // Only return pending (untriggered) tasks — triggered tasks are
        // deleted from storage on firing, but filter as a safety net.
        val tasks = allTasks.filterNot(_.triggered)
        val taskJsons = tasks.map(listTaskJson)
        wsSend(
          io.circe.Json.obj(
            "type" -> "scheduledTaskList".asJson,
            "tasks" -> taskJsons.asJson,
            "sessionId" -> lrSessionId.asJson
          )
        )
      }
    else IO.unit
    end if
  end handleListScheduledTasks

  /** 列表行 JSON 单点（两条 list 路共用）：字段 = 现状全集 + 改版批行级
   *  sessionId（跨会话收件箱寻址面；单会话路径同帧回带，纯增量）。 */
  private def listTaskJson(t: nebflow.core.scheduler.ScheduledTask): io.circe.Json =
    io.circe.Json.obj(
      "id" -> t.id.asJson,
      "content" -> t.content.asJson,
      "triggerAt" -> t.triggerAt.asJson,
      "createdAt" -> t.createdAt.asJson,
      "triggered" -> t.triggered.asJson,
      "triggeredAt" -> t.triggeredAt.asJson,
      "referencePath" -> t.referencePath.asJson,
      "repeat" -> t.repeat.asJson,
      "enabled" -> t.enabled.asJson,
      // 双面化（2026-10-24）：kind ∈ null/"task"（定时）| "todo"（待办）
      "kind" -> t.kind.asJson,
      // 待办分家批（2026-10-30）：稳定 upsert 名随列表下发——前端据
      // ask-<requestId> 前缀识别「待答镜像行」（点击跳回提问卡，不走打卡）。
      "name" -> t.name.asJson,
      // 认知二期产品批③打卡缺卡调整流：缺卡计数随列表下发（UI 据此给
      // 连续未销卡条目的调整建议）。
      "missCount" -> t.missCount.asJson,
      // 改版批（2026-10-31）：行级会话归属（scope:all 收件箱的跨会话寻址面）。
      "sessionId" -> t.sessionId.asJson,
      // 改版批追加令（2026-10-31 23:14）：项目维度（UI 分组/筛选主维度；None=未分组）。
      "project" -> t.project.asJson,
      // 待办体系统一批（2026-11-04）：重要度 + 完成态（纯增量三键；老前端零感知）。
      // `completed` 是 `completedAt.isDefined` 的布尔镜像——避免前端各自判 null。
      "priority" -> t.priority.asJson,
      "completed" -> t.isCompleted.asJson,
      "completedAt" -> t.completedAt.asJson
    )

  private def handleDeleteScheduledTask(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val drSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val drId = json.hcursor.downField("id").as[String].getOrElse("")
    if drSessionId.nonEmpty && drId.nonEmpty then
      sharedResources.scheduledTaskStore.deleteTask(drSessionId, drId).flatMap { _ =>
        sharedResources.scheduledTaskService.foreach(_.notifyTaskChange())
        wsSend(
          io.circe.Json.obj(
            "type" -> "scheduledTaskDeleted".asJson,
            "id" -> drId.asJson,
            "sessionId" -> drSessionId.asJson
          )
        )
      }
    else IO.unit
  end handleDeleteScheduledTask

  private def handleToggleScheduledTask(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val tgSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val tgId = json.hcursor.downField("id").as[String].getOrElse("")
    if tgSessionId.nonEmpty && tgId.nonEmpty then
      sharedResources.scheduledTaskStore.toggleTask(tgSessionId, tgId).flatMap { newEnabled =>
        sharedResources.scheduledTaskService.foreach(_.notifyTaskChange())
        wsSend(
          io.circe.Json.obj(
            "type" -> "scheduledTaskToggled".asJson,
            "id" -> tgId.asJson,
            "sessionId" -> tgSessionId.asJson,
            "enabled" -> newEnabled.asJson
          )
        )
      }
    else IO.unit
  end handleToggleScheduledTask

  /**
   * `updateScheduledTask` — 改版批追加令（2026-10-31 23:14 ②「修改与删除都要有」）
   * 用户面编辑腿：字段级 patch（content / project / **priority**；project 传 "" =
   * 清空归属，priority 传 0 = 清空重要度）。帧内 sessionId 仅兜底——按 id 全库
   * 寻址（completeTodo 同法）。广播 scheduledTaskUpdated 帧型（全量 task JSON，
   * 前端就地替换/重渲）。
   *
   * 为什么「改重要度」走本帧而不新开 `setTodoPriority`（待办体系统一批 2026-11-04）：
   * 一行一字段的修改已经有唯一入口（同一 findTaskById → updateTask → notify →
   * 全量行广播序列），新开帧等于把同一条副作用链复制两遍——两条链未来必然漂移
   * （一条改了广播字段、另一条没改）。扩 patch 面 = 零新增副作用路径：既有
   * 「行不在 ⇒ 回 deleted 帧收敛」的幂等宽容、跨会话寻址、全量广播全部原样复用。
   * 🔴 priority 只对待办面生效（`t.isTodo`），定时任务面传 priority 不改任何字段
   * ——定时任务面语义零改动。
   */
  private def handleUpdateScheduledTask(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val upId = json.hcursor.downField("id").as[String].getOrElse("").trim
    val upSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val contentOpt = json.hcursor.downField("content").as[String].toOption
      .map(_.trim).filter(_.nonEmpty)
    // project 提供即生效（"" 清空）；缺席不改。
    val projectOpt: Option[Option[String]] =
      json.hcursor.downField("project").as[String].toOption.map(raw => Some(raw.trim).filter(_.nonEmpty))
    // priority 提供即生效（钳位 0..3；0 = 清空重要度）；缺席不改。
    val priorityOpt: Option[Int] = json.hcursor.downField("priority").as[Int].toOption
      .map(nebflow.core.scheduler.ScheduledTask.clampPriority)
    if upId.nonEmpty && (contentOpt.isDefined || projectOpt.isDefined || priorityOpt.isDefined) then
      sharedResources.scheduledTaskStore.findTaskById(upId).flatMap {
        case Some(t) =>
          val updated = t.copy(
            content = contentOpt.getOrElse(t.content),
            project = projectOpt.getOrElse(t.project),
            priority = if t.isTodo then priorityOpt.getOrElse(t.priority) else t.priority
          )
          sharedResources.scheduledTaskStore.updateTask(t.sessionId, upId)(_ => updated).flatMap { changed =>
            if !changed then IO.unit
            else
              for
                _ <- sharedResources.scheduledTaskService.traverse_(_.notifyTaskChange())
                // 单点行 JSON（listTaskJson）：新增三键自动随广播带出，且与 list
                // 面逐字段同形——两处各自手写 JSON 是漂移源。
                _ <- wsSend(
                  io.circe.Json.obj(
                    "type" -> "scheduledTaskUpdated".asJson,
                    "sessionId" -> updated.sessionId.asJson,
                    "task" -> listTaskJson(updated)
                  )
                )
              yield ()
          }
        case None =>
          // 幂等宽容：行已不在 ⇒ 回 deleted 帧让 UI 收敛到同态。
          wsSend(
            io.circe.Json.obj(
              "type" -> "scheduledTaskDeleted".asJson,
              "id" -> upId.asJson,
              "sessionId" -> upSessionId.asJson
            )
          )
      }
    else IO.unit
  end handleUpdateScheduledTask

  /**
   * `completeTodo` — 打卡 UI 销账腿（认知二期产品批③）：与 delete 的区别 =
   * **留 done 痕**（done-records JSONL，周报数据源）。
   *
   * 待办体系统一批（2026-11-04）改语义：**软删**——不再 `deleteTask`，而是置
   * `completedAt`。理由：① 取消完成（`uncompleteTodo`）要能一键回退，行删了就
   * 无从回退；② DoneRecord 是周报数据源，但「完成」这件事本身也必须留在真源里
   * （完成态、完成时刻、归档视图都要它），删行等于把唯一副本销毁；③ 已完成行被
   * 读口（getAllTodos/getAllDueTodos/getAllPendingTasks）排除 ⇒ 不进收件箱、不被
   * scheduler 浮出，用户面与改前「行消失」表现一致。
   *
   * 广播兼容策略 = **新帧 + 老帧同发**：
   *   - `scheduledTaskCompleted {id, sessionId, completedAt, task}` 新帧——新前端
   *     据此区分「完成」（可撤销、可进归档）与「删除」（不可逆）；
   *   - `scheduledTaskDeleted {id, sessionId}` 老帧照发——`js/scheduled-task.js`
   *     现有消费点是「把行从收件箱摘掉」，对完成语义**恰好同形**，故老前端零改动
   *     即正确（双发不会二次摘行：摘行幂等）。老帧不删的原因：删了就要求所有前端
   *     同一批升级，跨版本实例会把完成行留在面上。
   * 幂等：重复打卡不重复记 done 痕、不改已记录的完成时刻；行已不在（他窗已硬
   * 删）⇒ 照发 deleted 帧收敛（改前口径不变）。
   */
  private def handleCompleteTodo(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val cpSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val cpId = json.hcursor.downField("id").as[String].getOrElse("")
    if cpSessionId.nonEmpty && cpId.nonEmpty then
      sharedResources.scheduledTaskStore.findTaskById(cpId).flatMap {
        case Some(t) if t.isTodo =>
          val now = System.currentTimeMillis()
          // 幂等：已完成的再次打卡沿用首次完成时刻，不重复写 done 痕（周报不虚涨）。
          val completedAt = t.completedAt.getOrElse(now)
          for
            _ <- if t.isCompleted then IO.unit
                 else
                   sharedResources.doneRecordStore.record(
                     nebflow.core.scheduler.DoneRecord(
                       taskId = t.id,
                       name = t.name,
                       content = t.content,
                       sessionId = t.sessionId,
                       doneAt = now,
                       via = nebflow.core.scheduler.DoneRecord.ViaWs
                     )
                   )
            _ <- sharedResources.scheduledTaskStore
              .updateTask(t.sessionId, t.id)(_.copy(completedAt = Some(completedAt))).void
            completed = t.copy(completedAt = Some(completedAt))
            _ <- sharedResources.scheduledTaskService.traverse_(_.notifyTaskChange())
            // 新帧（完成语义，可撤销）
            _ <- wsSend(
              io.circe.Json.obj(
                "type" -> "scheduledTaskCompleted".asJson,
                "id" -> completed.id.asJson,
                "sessionId" -> completed.sessionId.asJson,
                "completedAt" -> completed.completedAt.asJson,
                "task" -> listTaskJson(completed)
              )
            )
            // 老帧（既有前端唯一消费点：摘行；与完成语义同形，双发零冲突）
            _ <- wsSend(
              io.circe.Json.obj(
                "type" -> "scheduledTaskDeleted".asJson,
                "id" -> completed.id.asJson,
                "sessionId" -> completed.sessionId.asJson
              )
            )
          yield ()
        case Some(t) =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "error".asJson,
              "msgType" -> "completeTodo".asJson,
              "message" -> s"Id ${t.id} is a scheduled task, not a todo — use deleteScheduledTask.".asJson
            )
          )
        case None =>
          // 幂等宽容：行已不在（他窗已销/已删）⇒ 照发 deleted 帧，UI 收敛到同态。
          wsSend(
            io.circe.Json.obj(
              "type" -> "scheduledTaskDeleted".asJson,
              "id" -> cpId.asJson,
              "sessionId" -> cpSessionId.asJson
            )
          )
      }
    else IO.unit
  end handleCompleteTodo

  /**
   * `uncompleteTodo` — 取消完成（待办体系统一批 2026-11-04 新增帧）：清
   * `completedAt`（行回到未完成面），DoneRecord 追加一条 `via:"undo"` 反向事件
   * 抵销（**只增不改**契约不变，见 [[nebflow.core.scheduler.DoneRecord.ViaUndo]]；
   * 周报按净额解释）。
   *
   * 幂等语义：
   *   - 已未完成 ⇒ 不再写 undo 痕（避免重复抵销把周报算歪），仍回
   *     `scheduledTaskUpdated` 让各端收敛到同态；
   *   - 行已被硬删 ⇒ 回 `scheduledTaskDeleted`（与 update 腿同款宽容，UI 自行收敛）；
   *   - 定时任务面 ⇒ 显式 error 帧（本帧只接受待办）。
   */
  private def handleUncompleteTodo(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val ucSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val ucId = json.hcursor.downField("id").as[String].getOrElse("")
    if ucSessionId.nonEmpty && ucId.nonEmpty then
      sharedResources.scheduledTaskStore.findTaskById(ucId).flatMap {
        case Some(t) if t.isTodo =>
          val restored = t.copy(completedAt = None)
          if !t.isCompleted then
            // 幂等：本来就未完成 ⇒ 无 undo 痕，只回全量行让各端收敛。
            wsSend(
              io.circe.Json.obj(
                "type" -> "scheduledTaskUpdated".asJson,
                "sessionId" -> t.sessionId.asJson,
                "task" -> listTaskJson(t)
              )
            )
          else
            for
              _ <- sharedResources.scheduledTaskStore
                .updateTask(t.sessionId, t.id)(_.copy(completedAt = None)).void
              _ <- sharedResources.doneRecordStore.record(
                nebflow.core.scheduler.DoneRecord(
                  taskId = t.id,
                  name = t.name,
                  content = t.content,
                  sessionId = t.sessionId,
                  doneAt = System.currentTimeMillis(),
                  via = nebflow.core.scheduler.DoneRecord.ViaUndo
                )
              )
              _ <- sharedResources.scheduledTaskService.traverse_(_.notifyTaskChange())
              _ <- wsSend(
                io.circe.Json.obj(
                  "type" -> "scheduledTaskUpdated".asJson,
                  "sessionId" -> restored.sessionId.asJson,
                  "task" -> listTaskJson(restored)
                )
              )
            yield ()
        case Some(t) =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "error".asJson,
              "msgType" -> "uncompleteTodo".asJson,
              "message" -> s"Id ${t.id} is a scheduled task, not a todo.".asJson
            )
          )
        case None =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "scheduledTaskDeleted".asJson,
              "id" -> ucId.asJson,
              "sessionId" -> ucSessionId.asJson
            )
          )
      }
    else IO.unit
  end handleUncompleteTodo

  private def handleGetTaskList(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Task List (fetch on session switch / reconnect) =====

    val tlsSessionId = inboundEnvelope(text).sessionId
    if tlsSessionId.nonEmpty then
      sharedResources.taskStore.listVisible(tlsSessionId).flatMap { tasks =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "taskListUpdate".asJson,
            "sessionId" -> tlsSessionId.asJson,
            "tasks" -> tasks.asJson
          )
        )
      }
    else IO.unit
  end handleGetTaskList

  private def handleCompleteTask(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Complete Task (user clicks the todos-panel circle, todo-panel §7.1) =====

    val ctSessionId = inboundEnvelope(text).sessionId
    val ctTaskId = parse(text).flatMap(_.hcursor.downField("taskId").as[String]).getOrElse("")
    if ctSessionId.nonEmpty && ctTaskId.nonEmpty then
      sharedResources.taskStore.complete(ctSessionId, ctTaskId, by = "user").attempt.flatMap {
        case Right(Some(_)) =>
          // Return updated task list — completed items vanish from the
          // panel ([U3] complete = disappear), so the authoritative
          // refresh is what converges every client.
          sharedResources.taskStore.listVisible(ctSessionId).flatMap { tasks =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "taskListUpdate".asJson,
                "sessionId" -> ctSessionId.asJson,
                "tasks" -> tasks.asJson
              )
            )
          }
        case Right(None) =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Task not found: $ctTaskId".asJson))
        case Left(err) =>
          // IllegalStateException — terminal task cannot be re-completed
          wsSend(
            io.circe.Json.obj(
              "type" -> "taskError".asJson,
              "error" -> s"Cannot complete: ${err.getMessage}".asJson,
              "taskId" -> ctTaskId.asJson
            )
          )
      }
    else IO.unit
    end if
  end handleCompleteTask

  private def handleListWorkspaceItems(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    // ===== Workspace Knowledge =====

    val wsSessionId = inboundEnvelope(text).sessionId
    if wsSessionId.nonEmpty then
      sharedResources.knowledgeStore.loadItems(wsSessionId).flatMap { items =>
        val itemJsons = items.map { it =>
          io.circe.Json.obj(
            "id" -> it.id.asJson,
            "sessionId" -> it.sessionId.asJson,
            "title" -> it.title.asJson,
            "itemType" -> it.itemType.asJson,
            "content" -> it.content.asJson,
            "createdAt" -> it.createdAt.asJson
          )
        }
        wsSend(
          io.circe.Json.obj(
            "type" -> "workspaceItemList".asJson,
            "items" -> itemJsons.asJson,
            "sessionId" -> wsSessionId.asJson
          )
        )
      }
    else IO.unit
    end if
  end handleListWorkspaceItems

  private def handleDeleteWorkspaceItem(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val json = parsedJson(text)
    val delSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
    val delId = json.hcursor.downField("id").as[String].getOrElse("")
    if delSessionId.nonEmpty && delId.nonEmpty then
      sharedResources.knowledgeStore.deleteItem(delSessionId, delId).flatMap { _ =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "workspaceItemDeleted".asJson,
            "id" -> delId.asJson,
            "sessionId" -> delSessionId.asJson
          )
        )
      }
    else IO.unit
  end handleDeleteWorkspaceItem

end WsTasksWorkspaceHandlers
