package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{NebflowLogger, PathUtil, SubAgentTask}

/**
 * MailboxBootSweep —— 邮箱的 **boot 对账合成腿**（终态通知 actor 邮箱化批 P1/P2；
 * 设计 §3.4 唤醒点⑥「引擎 boot：全会话扫箱注入 + 账面合成缺失条目」+ §3.3
 * 「boot sweep 以账面真源合成补投条目——账与信的最终对账」）。
 *
 * == 三步 ==
 *
 *   1. **tmp 残片清理**（kill -9 窗口1「入队半写」恢复规则）：`pending` 目录下
 *      的 `*.tmp` 中间态文件是 AtomicJson 原子写的中间态，永不构成可见条目——
 *      boot 无条件清除。
 *   2. **账面合成补投**：`SubAgentTaskStore` 终态行（delegate 轨 × completed/
 *      failed/cancelled）在**上一进程存活窗**内落终态、而父会话邮箱既无该条目
 *      又无消费墓碑 ⇒ 以账面行合成一条 delegate-terminal 补投条目（payload 诚实
 *      标注合成来源；全量结果仍在子会话转录）。覆盖：mailOnce 重试穷尽的落盘
 *      失败窗、进程在该窗内的崩口——「账已终、信没到」的最终对账。boot 期会话
 *      actor 尚未 spawn ⇒ **不发门铃**：条目由各会话首次 spawn 的 W1 扫箱消费。
 *   3. **汇总日志**：有积压的会话箱数（积压可见性 = 深度即告警，零墙钟不设 TTL
 *      不自动丢弃，开放决策⑤）。
 *
 * == 对账窗口与防重（合成腿的两道闸）==
 *
 *   - **boot 窗闸**：候选 = `completedAt ∈ (lastBootMs, thisBootMs]`——boot 纪元
 *     文件 `<dataRoot>/mailbox-reconcile.json` 双时间戳滚动。本批激活时刻之前的
 *     旧账**不追**（激活前交付的终态没有墓碑也没有条目，追了 = 全量假重投 =
 *     事故）；上一窗内已消费的终态有墓碑兜住（unlinkTaken 同点落账）。
 *   - **墓碑闸**：`<box>/delivered.json`（cap 1024 drop-oldest）。残余风险窗口 =
 *     「上一窗落终 + 当窗已消费 + 墓碑已被后续 1024 次消费挤出 + 本拍才跑对账」
 *     ——同一条目至多假重投一次，且其内容是「已完成的终态通知」（幂等键防在箱
 *     内重排）。诚实有界，零墙钟纪律不破。
 */
object MailboxBootSweep:

  private val logger = NebflowLogger.forName("nebflow.agent.mailbox-boot-sweep")

  final case class Report(tmpSwept: Int = 0, synthesized: List[String] = Nil, boxesNonEmpty: Int = 0):
    def summary: String =
      s"tmpSwept=$tmpSwept synthesized=${synthesized.size} (${synthesized.mkString(",")}) boxesNonEmpty=$boxesNonEmpty"

  // 非 private：given（伴生隐式作用域）的调用点在本 object 方法体内，
  // private 类会让 public given 触发泄露检查、private given 又对调用点不可见——
  // 类保持嵌套 public 即两端成立。
  final case class BootEpoch(v: Int = 1, lastBootMs: Long = 0L, thisBootMs: Long = 0L)
  object BootEpoch:
    given io.circe.Encoder[BootEpoch] = io.circe.Encoder.instance { e =>
      io.circe.Json.obj(
        "v" -> e.v.asJson,
        "lastBootMs" -> e.lastBootMs.asJson,
        "thisBootMs" -> e.thisBootMs.asJson
      )
    }
    given io.circe.Decoder[BootEpoch] = io.circe.Decoder.instance { c =>
      for
        v <- c.get[Option[Int]]("v")
        last <- c.get[Option[Long]]("lastBootMs")
        thisBoot <- c.get[Option[Long]]("thisBootMs")
      yield BootEpoch(v.getOrElse(1), last.getOrElse(0L), thisBoot.getOrElse(0L))
    }
  end BootEpoch

  private def epochFile: os.Path = PathUtil.dataRoot / "mailbox-reconcile.json"

  /** boot 纪元滚动（本次 boot 时间入窗；上次 boot 时间成为对账窗左沿）。 */
  private def rollEpoch(now: Long): IO[BootEpoch] =
    IO.blocking {
      val prev =
        if !os.exists(epochFile) then BootEpoch()
        else decode[BootEpoch](os.read(epochFile)).getOrElse(BootEpoch())
      val next = BootEpoch(lastBootMs = prev.thisBootMs, thisBootMs = now)
      os.makeDir.all(PathUtil.dataRoot)
      nebflow.core.AtomicJson.writeSync(epochFile, next.asJson.noSpaces)
      next
    }.handleErrorWith { e =>
      logger
        .warn(s"[mailbox-boot-sweep] epoch roll failed (${e.getMessage}) — reconcile window disabled")
        .as(BootEpoch(lastBootMs = 0L, thisBootMs = 0L)) // 0/0 = 空窗 = 零合成（安全侧）
    }

  /** boot sweep 入口（GatewayMain 装配链；失败只 WARN 绝不阻塞启动）。 */
  def run(store: SubAgentTaskStore, sessionStore: nebflow.core.SessionStore): IO[Report] =
    for
      swept <- SessionMailbox.sweepTmpAll()
      epoch <- rollEpoch(System.currentTimeMillis())
      rows <- terminalDelegateRows(store)
      candidates =
        if epoch.lastBootMs <= 0 || epoch.thisBootMs <= 0 then Nil // 空窗安全侧
        else rows.filter(t => t.completedAt.exists(c => c > epoch.lastBootMs && c <= epoch.thisBootMs))
      _ <-
        if candidates.nonEmpty then
          logger.info(
            s"[mailbox-boot-sweep] terminal delegate row(s) in reconcile window (${epoch.lastBootMs}..${epoch.thisBootMs}]: ${candidates.map(_.taskId).mkString(", ")}"
          )
        else IO.unit
      synthesized <- candidates.traverseFilter(synthesizeOne(sessionStore, _))
      boxes <- nonEmptyBoxCount
      report = Report(tmpSwept = swept.size, synthesized = synthesized, boxesNonEmpty = boxes)
      _ <-
        if report.tmpSwept > 0 || report.synthesized.nonEmpty || report.boxesNonEmpty > 0 then
          logger.info(s"[mailbox-boot-sweep] ${report.summary}")
        else IO.unit
    yield report

  /** 终态 delegate 行全量（直接扫 store 目录——store 无 list-all 面，findRunningTasks 同款读法）。 */
  private def terminalDelegateRows(store: SubAgentTaskStore): IO[List[SubAgentTask]] =
    IO.blocking {
      val baseDir = store.baseDir
      if !os.exists(baseDir) then Nil
      else
        os.list(baseDir)
          .toList
          .filter(_.toString.endsWith(".json"))
          .flatMap { f =>
            decode[List[SubAgentTask]](os.read(f)) match
              case Right(list) =>
                list.filter(t =>
                  t.source != "subtask" && Set("completed", "failed", "cancelled").contains(t.status)
                )
              case Left(_) => Nil
          }
    }.handleErrorWith(e => logger.warn(s"[mailbox-boot-sweep] terminal row scan failed: ${e.getMessage}").as(Nil))

  /**
   * 单行合成判定：父会话在场 ∧ 邮箱无该条目（pending/taken 都无）∧ 无消费墓碑
   * ⇒ 合成补投条目（identity = taskId，与主投递腿同一幂等键）。返回 Some(taskId)
   * = 已合成落箱。
   */
  private def synthesizeOne(
    sessionStore: nebflow.core.SessionStore,
    t: SubAgentTask
  ): IO[Option[String]] =
    if t.parentSessionId.trim.isEmpty then IO.pure(None)
    else
      sessionStore.listSessions.flatMap { sessions =>
        if !sessions.exists(_.id == t.parentSessionId) then IO.pure(None)
        else
          for
            pending <- SessionMailbox.scanPending(t.parentSessionId)
            taken <- SessionMailbox.scanTaken(t.parentSessionId)
            tombstoned <- SessionMailbox.deliveredContains(t.parentSessionId, Set(t.taskId))
            accounted =
              pending.exists(_.identity == t.taskId) || taken.exists(_.identity == t.taskId) || tombstoned.nonEmpty
            result <-
              if accounted then IO.pure(None) // 条目在盘（待消费/消费中）或已消费——账信相符，零动作
              else
                val head =
                  s"[mailbox boot reconcile] delegate \"${t.description}\" (agent=${t.agentName}, session=${t.taskId}) " +
                    s"reached '${t.status}' in the previous engine lifetime but no mailbox entry survived — this notice is " +
                    s"synthesized from the task ledger; the full result lives in the delegate session transcript."
                val payload = t.lastError match
                  case Some(err) if err.nonEmpty => s"$head\n$err"
                  case _ => head
                val event = AgentCommand.ExternalEvent(
                  source = "delegate",
                  eventType = t.status,
                  payload = payload,
                  metadata = JsonObject(
                    "taskId" -> t.taskId.asJson,
                    "agentName" -> t.agentName.asJson,
                    "description" -> t.description.asJson,
                    "recoveredBy" -> "mailbox-boot-reconcile".asJson
                  ),
                  correlationId = Some(t.taskId)
                )
                SessionMailbox
                  .mailOnce(
                    t.parentSessionId,
                    event,
                    kind = "delegate-terminal",
                    identity = t.taskId,
                    coalesce = false,
                    producer = "mailbox-boot-reconcile"
                  )
                  .flatMap {
                    case SessionMailbox.MailResult.Enqueued(_) =>
                      logger
                        .warn(
                          s"[mailbox-boot-sweep] synthesized missing terminal entry for ${t.taskId} → parent ${t.parentSessionId}"
                        )
                        .as(Some(t.taskId))
                    case other =>
                      logger
                        .warn(s"[mailbox-boot-sweep] synthesis enqueue for ${t.taskId} did not land: $other")
                        .as(None)
                  }
          yield result
      }
  end synthesizeOne

  /** 有积压的会话箱数（可见性面）。 */
  private def nonEmptyBoxCount: IO[Int] =
    IO.blocking {
      val sessionsDir = PathUtil.dataRoot / "sessions"
      if !os.exists(sessionsDir) then 0
      else
        os.list(sessionsDir).count { s =>
          val pend = s / "mailbox" / "pending"
          os.exists(pend) && os.list(pend).exists(p => p.ext == "json" && !p.last.endsWith(".tmp"))
        }
    }.handleErrorWith(_ => IO.pure(0))
end MailboxBootSweep
