package nebflow.agent

import cats.effect.IO
import io.circe.Json
import nebflow.shared.*

/**
 * RestartLossLedger —— 收殓（restart-loss）通知的**持久提醒账**（中断恢复批 2026-11-04 ·
 * 审计 D1【致命】修复腿）。
 *
 * == 关闭的缺口（D1，作者实证 2026-11-04 09:09→09:16）==
 * 收殓通知**投了、也注入了**（09:09:47 持久邮箱 → 父会话 drain → 注入），但注入是一次性
 * user 消息：09:16:23 的 pingpong 压缩（347 → 13 条）把它从工作记忆里**整段抹掉** ⇒ 父
 * 会话对「两个 delegate 死于重启」零记忆，11:08 只能靠工具重查。推论：**投递链没断，
 * 断的是「记忆的持久性」**——终态通知需要「未确认」维度，在压缩后仍可被父会话重新感知。
 *
 * == 本对象 = 那一维度的载体 ==
 * `<dataRoot>/sessions/<parentSid>/restart-loss-ledger.json`：
 * {{{
 * { "v": 1,
 *   "entries": [ { "taskId": "delegate-…", "deliveredAt": 1793754587637,
 *                  "restatements": 1, "lastRestatedAt": 1793755000000,
 *                  "head": "Delegate \"…\" was still in flight when the process restarted." } ] }
 * }}}
 *
 * 写点 = [[SubAgentStartupRecovery.recoverOne]] 的**邮箱轨**投递成功处（生产唯一投递面；
 * F2 legacy 轨不落账——无载体场不产生账目）。
 *
 * 重述点 = **上下文压缩完成处**（这正是抹除发生的那一拍）：
 *  - 全量压缩：`AgentProcessing` 的 `CompactionComplete(Right)` 装态处；
 *  - 乒乓压缩：`AgentPingpong` 换入（`durableSwapMessages`）处。
 * 两处各一行：`compactedMessages ++ RestartLossLedger.restatementSync(sid).toList`——
 * 有账才多一条 `<system-reminder>`，无账零动作（`os.exists` 快路）。
 *
 * == 自清（不无限循环，机械可判）==
 *  - 每条最多重述 [[MaxRestatements]] 次（默认 2）——穷尽即静默；
 *  - 超过 [[RetentionMs]] 的条目在下次读/写时剪除（默认 24h）；
 *  - 同一 taskId 再次投递 = upsert（重述计数归零，旧账被新事实顶替）；
 *  - 本对象只在**同步读面**做小文件 IO（<4KB，且无账目时只是一次 `os.exists`），
 *    不引 fiber、不起定时器、零 wall-clock 轮询。
 */
object RestartLossLedger:

  private val logger = NebflowLogger.forName("nebflow.agent.restart-loss-ledger")

  /** 台账文件名（`<dataRoot>/sessions/<parentSid>/` 下）。 */
  val FileName: String = "restart-loss-ledger.json"

  /** 单条最多重述次数（穷尽即静默——防「每拍压缩都灌一遍」的无限循环）。 */
  val MaxRestatements: Int = 2

  /** 条目保留期（超期剪除；epoch ms）。 */
  val RetentionMs: Long = 24L * 3600 * 1000

  /** 一条已投递但未被父会话「消化」的收殓通知。 */
  final case class Entry(
    taskId: String,
    deliveredAt: Long,
    restatements: Int = 0,
    lastRestatedAt: Option[Long] = None,
    head: String = ""
  ):

    def json: Json =
      Json.obj(
        "taskId" -> Json.fromString(taskId),
        "deliveredAt" -> Json.fromLong(deliveredAt),
        "restatements" -> Json.fromInt(restatements),
        "lastRestatedAt" -> lastRestatedAt.fold(Json.Null)(Json.fromLong),
        "head" -> Json.fromString(head)
      )

  end Entry

  def pathFor(parentSessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / parentSessionId / FileName

  private def decodeEntry(c: io.circe.HCursor): Option[Entry] =
    for
      taskId <- c.get[String]("taskId").toOption
      deliveredAt <- c.get[Long]("deliveredAt").toOption
    yield Entry(
      taskId = taskId,
      deliveredAt = deliveredAt,
      restatements = c.get[Int]("restatements").toOption.getOrElse(0),
      lastRestatedAt = c.get[Long]("lastRestatedAt").toOption,
      head = c.get[String]("head").toOption.getOrElse("")
    )

  /** 读账（fail-soft：坏文件 / 缺席 = 空账）。 */
  def readSync(parentSessionId: String): List[Entry] =
    try
      val p = pathFor(parentSessionId)
      if !os.exists(p) then Nil
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.get[List[Json]]("entries").toOption)
          .map(_.flatMap(j => decodeEntry(j.hcursor)))
          .getOrElse(Nil)
    catch case _: Throwable => Nil

  private def writeSync(parentSessionId: String, entries: List[Entry]): Unit =
    try
      val p = pathFor(parentSessionId)
      os.makeDir.all(p / os.up)
      val json = Json.obj(
        "v" -> Json.fromInt(1),
        "entries" -> Json.fromValues(entries.map(_.json))
      )
      nebflow.core.AtomicJson.writeSync(p, json.noSpaces)
    catch
      case e: Throwable =>
        logger.warn(s"[restart-loss-ledger] write failed for $parentSessionId: ${e.getMessage}")

  private def live(e: Entry, now: Long): Boolean =
    e.restatements < MaxRestatements && now - e.deliveredAt <= RetentionMs

  /**
   * 记账（收殓通知**投递成功**后调用；幂等 upsert：同 taskId 的旧账被新投递顶替，
   * 重述计数归零）。顺带剪除过期条目。
   */
  def record(parentSessionId: String, taskId: String, head: String, at: Long = System.currentTimeMillis()): IO[Unit] =
    if parentSessionId.trim.isEmpty || taskId.trim.isEmpty then IO.unit
    else
      IO.blocking {
        val kept = readSync(parentSessionId).filter(e => e.taskId != taskId && live(e, at))
        writeSync(parentSessionId, kept :+ Entry(taskId, at, restatements = 0, None, head.take(240)))
      }.handleErrorWith(e =>
        logger.warn(s"[restart-loss-ledger] record failed for $taskId under $parentSessionId: ${e.getMessage}")
      )

  /**
   * **压缩完成处的重述**（同步读面；与压缩同一拍，无需第二套调度）：有未确认账目
   * ⇒ 产一条 `<system-reminder>` 并把重述计数 +1（落盘）；无账 ⇒ None（零动作）。
   */
  def restatementSync(parentSessionId: Option[String], at: Long = System.currentTimeMillis()): Option[Message] =
    parentSessionId.filter(_.trim.nonEmpty).flatMap { sid =>
      val entries = readSync(sid)
      if entries.isEmpty then None
      else
        val liveEntries = entries.filter(e => live(e, at))
        if liveEntries.isEmpty then
          // 全数穷尽/过期 ⇒ 剪除（自清；不再注入）。
          writeSync(sid, Nil)
          None
        else
          val lines = liveEntries.map { e =>
            val when = java.time.Instant.ofEpochMilli(e.deliveredAt).toString
            s"- ${e.taskId} — notice delivered ${when}${if e.head.nonEmpty then s": ${e.head}" else ""}"
          }
          val text =
            s"""<system-reminder>
               |[restart-loss recap] ${liveEntries.size} delegate lost-notice(s) were delivered to this session but may have been
               |compacted out of the working context — restating the fact so it is not forgotten:
               |${lines.mkString("\n")}
               |The tasks were killed by an engine restart (not by their own failure). Their partial results live in their
               |session transcripts and the task ledger (AgentControl shows the rows); re-delegate a task if its result is
               |needed. This recap is repeated at most $MaxRestatements time(s) per notice.
               |</system-reminder>""".stripMargin
          // 记账（重述 +1；落盘失败不影响本轮注入）。
          writeSync(
            sid,
            liveEntries.map(e => e.copy(restatements = e.restatements + 1, lastRestatedAt = Some(at))) ++
              entries.filterNot(e => liveEntries.exists(_.taskId == e.taskId)).filter(e => live(e, at))
          )
          Some(Message(MessageRole.User, Left(text), at))
        end if
      end if
    }

  /**
   * 尾部安全闸（压缩装态点用）：把一条 user 消息追加到 `existing` 尾部是否会破坏
   * 工具配对？**是**（末条 = 带 ToolUse 的 assistant，等它的 tool_result）= 本次不重述
   * （不消耗重述预算，下一拍压缩再试）。provider 侧 tool_use/tool_result 必须相邻。
   */
  def appendSafeAtTail(existing: List[Message]): Boolean =
    existing.lastOption.forall { m =>
      m.role != MessageRole.Assistant || !m.content.toOption.exists(_.exists {
        case _: nebflow.shared.ContentBlock.ToolUse => true
        case _ => false
      })
    }

  /** 尾部安全版重述（保序追加；不安全 = None 且**不**消耗预算）。 */
  def restatementForTail(
    parentSessionId: Option[String],
    existing: List[Message],
    at: Long = System.currentTimeMillis()
  ): Option[Message] =
    if appendSafeAtTail(existing) then restatementSync(parentSessionId, at) else None

end RestartLossLedger
