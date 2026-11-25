package nebflow.agent

import io.circe.parser
import io.circe.syntax.*
import nebflow.core.AtomicJson

import java.nio.file.{Files, Path}

/** Durable per-session report identity reservation. Callers must provide a stable logical slot. */
private[agent] final class ChildReportJournal private (root: Path, session: String):

  private def protocolFile: os.Path =
    val encoded = java.util.Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    os.Path(root.toAbsolutePath) / s"child-requests-$encoded.json"

  private def readProtocol(): io.circe.Json =
    if os.exists(protocolFile) then parser.parse(os.read(protocolFile)).fold(throw _, identity)
    else io.circe.Json.obj("next" -> 1L.asJson, "entries" -> io.circe.Json.obj())

  private def writeProtocol(value: io.circe.Json): Unit =
    os.makeDir.all(protocolFile / os.up)
    AtomicJson.rotateSyncDurable(protocolFile, value.noSpaces)

  def beginRequest(): Long = ChildReportJournal.synchronized {
    val state = readProtocol()
    val next = state.hcursor.get[Long]("next").fold(throw _, identity)
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    require(
      !entries.values.exists(_.hcursor.get[String]("status").toOption.contains("open")),
      "open request must be recovered before allocation"
    )
    val entry = io.circe.Json.obj("status" -> "open".asJson)
    writeProtocol(
      io.circe.Json
        .obj("next" -> (next + 1).asJson, "entries" -> io.circe.Json.fromJsonObject(entries.add(next.toString, entry)))
    )
    next
  }

  def prepareReport(slot: Long, body: String): String = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    val status = entry.hcursor.get[String]("status").fold(throw _, identity)
    val id = s"child-report:$session:$slot"
    if status == "prepared" then
      require(entry.hcursor.get[String]("body").toOption.contains(body), "conflicting report body")
    else
      require(status == "open", "slot is closed")
      val replacement = io.circe.Json.obj("status" -> "prepared".asJson, "body" -> body.asJson, "id" -> id.asJson)
      writeProtocol(
        state.mapObject(_.add("entries", io.circe.Json.fromJsonObject(entries.add(slot.toString, replacement))))
      )
    id
  }

  def openRequests(): List[Long] = ChildReportJournal.synchronized {
    val entries = readProtocol().hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries.toList.collect {
      case (key, entry) if entry.hcursor.get[String]("status").toOption.contains("open") => key.toLong
    }.sorted
  }

  def pendingRecovery(): List[(String, String)] = ChildReportJournal.synchronized {
    val entries = readProtocol().hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries.toList.sortBy(_._1.toLong).flatMap { case (_, entry) =>
      val c = entry.hcursor
      if c.get[String]("status").toOption.exists(s => s == "prepared" || s == "parked") then
        Some((c.get[String]("id").fold(throw _, identity), c.get[String]("body").fold(throw _, identity)))
      else None
    }
  }

  def park(slot: Long): Unit = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    require(entry.hcursor.get[String]("status").toOption.contains("prepared"), "only prepared reports may park")
    writeProtocol(
      state.mapObject(
        _.add(
          "entries",
          io.circe.Json.fromJsonObject(entries.add(slot.toString, entry.mapObject(_.add("status", "parked".asJson))))
        )
      )
    )
  }

  def closeNoReport(slot: Long): Unit = closeNoReportIfOpen(slot)

  /**
   * 收口**本会话全部** open 槽（open ⇒ `no-report`），返回收走的槽号。
   *
   * 为什么需要（checkpoint-restart 批 2026-11-21）：同一个 sessionId 上会**换一个
   * actor 实例**继续跑 —— flow 层 retryable 失败走 checkpoint restart（
   * `FlowDagExecutor` 恢复消息后 `executeAgent` 派生新 actor）、BackoffSupervisor
   * respawn、supervisor RestartAgent 皆然。前一个 actor 的 turn 被取消时若已消费过
   * open 槽，就再没人会为它写报告 ⇒ 槽永久 open ⇒ 新 actor 首个 turn 的
   * [[beginRequest]] 的 require（"open request must be recovered before allocation"）
   * 直接把它打回 idle。
   *
   * 判据归属：本方法把「已在跑的收口」收成**一处实现**，供两条腿共用 ——
   * `AgentProcessing.closeAbandonedChildReports`（RestartAgent 腿，重启前收）与
   * [[nebflow.agent.AgentActor]] 的 spawn 腿（新 actor 起来时收上一个实例的残槽，
   * `initialMessages` 非空 = 恢复续跑，见该处注释）。逐槽幂等：并发收口时已落终态的
   * 槽返回 false，不是错误。
   *
   * fail-soft：整体异常不阻断重启/续跑（残槽会被下一次 beginRequest 再撞到，
   * 故调用方须自己决定是否容忍——两处调用点均按「日志后继续」处理）。
   */
  def closeAllOpenAsNoReport(): List[Long] = ChildReportJournal.synchronized {
    openRequests().filter(closeNoReportIfOpen)
  }

  /**
   * 幂等收口：槽仍 open 才落 `no-report`，已落终态（或此刻已被并发收口）则什么都不做。
   *
   * 为什么需要（child-report 双收口批 2026-11-21）：一个 turn 的收尾路径会**两处**收口
   * 同一个槽 —— [[nebflow.agent.AgentFinishTurn]] 的 `routeChildProgress`（正文不是进度件
   * ⇒ 本 turn 无报告）与 `handleLlmCompleteBranch` 的文本收尾分支（再收一次）。旧实现
   * 第二处直接抛 `only open requests can close without report`，把本已正常的 turn 打成
   * 「Agent error in processing, returning to idle」——现象正是：LoopGuard 的 turn 2 永远
   * 等不到冻结、wake 轮永远少一次 LLM 调用、AgentRoundRecovery 30s 超时，
   * **四条基线红同一根因**（base 实测日志逐条命中该 require 文本）。
   * 收口语义面本身是幂等的（终态之后没有第二个终态），故以本方法为唯一收口入口。
   */
  def closeNoReportIfOpen(slot: Long): Boolean = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries(slot.toString) match
      case Some(entry) if entry.hcursor.get[String]("status").toOption.contains("open") =>
        writeProtocol(
          state.mapObject(
            _.add(
              "entries",
              io.circe.Json.fromJsonObject(
                entries.add(slot.toString, entry.mapObject(_.add("status", "no-report".asJson)))
              )
            )
          )
        )
        true
      case Some(_) => false // 已落终态：幂等，什么都不做
      case None => throw new IllegalArgumentException("unknown slot")
  }

  def markFlushed(slot: Long): Unit = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    val status = entry.hcursor.get[String]("status").fold(throw _, identity)
    require(status == "prepared" || status == "parked" || status == "flushed", "only prepared reports may be flushed")
    writeProtocol(
      state.mapObject(
        _.add(
          "entries",
          io.circe.Json.fromJsonObject(entries.add(slot.toString, entry.mapObject(_.add("status", "flushed".asJson))))
        )
      )
    )
  }

  def reserve(slot: String, body: String): String = ChildReportJournal.synchronized {
    require(slot.nonEmpty && session.nonEmpty, "report slot and session are required")
    val encoded = java.util.Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val file = os.Path(root.toAbsolutePath) / s"child-report-$encoded.json"
    val prior =
      if os.exists(file) then parser.parse(os.read(file)).fold(throw _, identity)
      else io.circe.Json.obj("next" -> 1L.asJson, "entries" -> io.circe.Json.obj())
    val cursor = prior.hcursor
    val next = cursor.get[Long]("next").fold(throw _, identity)
    val entries = cursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries(slot) match
      case Some(value) =>
        val c = value.hcursor
        val existing = c.get[String]("body").fold(throw _, identity)
        require(existing == body, "conflicting body for report logical slot")
        c.get[String]("id").fold(throw _, identity)
      case None =>
        val id = s"child-report:$session:$next"
        val entry = io.circe.Json.obj("id" -> id.asJson, "body" -> body.asJson)
        val updated = io.circe.Json
          .obj("next" -> (next + 1).asJson, "entries" -> io.circe.Json.fromJsonObject(entries.add(slot, entry)))
        os.makeDir.all(file / os.up)
        AtomicJson.rotateSyncDurable(file, updated.noSpaces)
        id
  }

end ChildReportJournal

private[agent] object ChildReportJournal:
  def open(root: Path, session: String): ChildReportJournal = new ChildReportJournal(root, session)
