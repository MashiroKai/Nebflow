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
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
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
    "toggleScheduledTask" -> handleToggleScheduledTask,
    "getTaskList" -> handleGetTaskList,
    "completeTask" -> handleCompleteTask,
    "listWorkspaceItems" -> handleListWorkspaceItems,
    "deleteWorkspaceItem" -> handleDeleteWorkspaceItem
  )

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
    hc.downField("path").as[String].toOption.filter(_.nonEmpty) match
      case None => IO.unit
      case Some(raw) =>
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
    if crSessionId.nonEmpty && crContent.nonEmpty && crTriggerAt > System.currentTimeMillis() then
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

  private def handleListScheduledTasks(
    ctx: WsDispatchCtx,
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    import ctx.*
    val lrSessionId = inboundEnvelope(text).sessionId
    if lrSessionId.nonEmpty then
      sharedResources.scheduledTaskStore.loadTasks(lrSessionId).flatMap { allTasks =>
        // Only return pending (untriggered) tasks — triggered tasks are
        // deleted from storage on firing, but filter as a safety net.
        val tasks = allTasks.filterNot(_.triggered)
        val taskJsons = tasks.map { t =>
          io.circe.Json.obj(
            "id" -> t.id.asJson,
            "content" -> t.content.asJson,
            "triggerAt" -> t.triggerAt.asJson,
            "createdAt" -> t.createdAt.asJson,
            "triggered" -> t.triggered.asJson,
            "triggeredAt" -> t.triggeredAt.asJson,
            "referencePath" -> t.referencePath.asJson,
            "repeat" -> t.repeat.asJson,
            "enabled" -> t.enabled.asJson
          )
        }
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
