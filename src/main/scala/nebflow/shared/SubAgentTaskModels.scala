/* 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-G/M4):本文件为 shared 承载件——SubAgentTask
 * 案例类与伴生 codec 自 nebflow/agent/SubAgentTaskStore.scala 剪出下沉(整块逐字,含字段注释;
 * 零 nebflow 运行时依赖,先例 PeerModels/DropboxModels)。斩断 core→agent 边;agent/core 内
 * 引用改指本包。 */
package nebflow.shared

import io.circe.{Codec, Decoder, Encoder}

/**
 * Persisted metadata for a sub-agent task (Delegate / SubTask).
 *
 * Written at spawn time, updated on completion/failure, used for:
 *  - Crash recovery: if the process restarts, tasks in `Running` status
 *    can be resumed or their parent agent notified.
 *  - Observability: the frontend can show sub-agent task status.
 *  - Auto-retry: the parent agent can look up the original prompt to
 *    re-delegate after a retryable failure.
 */
case class SubAgentTask(
  taskId: String, // = subagentId (delegate-xxx / subtask-xxx)
  parentSessionId: String,
  agentName: String,
  prompt: String,
  description: String,
  status: String, // "running" | "completed" | "failed" | "restarting" | "ready-to-merge" | "cancelled"
  retryCount: Int, // how many times restarted by supervisor
  spawnedAt: Long, // epoch millis
  completedAt: Option[Long], // epoch millis
  lastError: Option[String], // last error message if failed
  source: String, // "delegate" | "subtask"
  /**
   * #692（网络不可达退避等待修批，2026-10-21 作者令）：**当前 status 的写入时刻**
   * （epoch millis）。重启屏障 watchdog 的判据输入——status=restarting 且 statusAt
   * 距今超阈值且 registry 无活体 ⇒ 重启屏障僵死（10-21 实测 7.5h 僵尸：引擎在
   * respawn 半途重启，"restarting" 行既无 actor 接管也无人终态化）。每次
   * updateStatus 刷新。Option + 缺键容忍解码（旧盘文件无此键 = None ⇒ watchdog
   * 跳过该行，保守不误杀）。
   */
  statusAt: Option[Long] = None,
  /**
   * 监督链自动恢复批（2026-10-22 作者令）：**boot 复活腿已用次数**。引擎重启时在飞
   * 的 delegate 由 SubAgentStartupRecovery 复活腿从 transcript checkpoint 续跑（同
   * id），每次复活 +1；达 `Defaults.DelegateRevivalMaxPerTask` 后不再复活、落既有
   * 收殓链（running → failed + 父通知）。Option + 缺键容忍解码（旧盘文件无此键 =
   * None ⇒ 视为 0，存量行全额预算）。
   */
  revivalCount: Option[Int] = None,
  /** 最近一次 boot 复活时刻（epoch millis；observability 读数面，判据不用）。 */
  revivedAt: Option[Long] = None
)

object SubAgentTask:
  given Encoder[SubAgentTask] = Encoder.derived

  /**
   * 手写 Decoder（不用 derived）：`statusAt` 是后加字段，derived 解码对**缺键**
   * 会判失败（circe Scala 3 派生不吃 Scala 默认值）⇒ 旧盘任务文件整文件解析失败
   * = 该父会话全部任务行被静默丢弃（readTasksSync 失败回 Nil）。`c.get[Option[...]]`
   * 对缺键天然得 None（DelegateRegistry 手写游标同款先例）——旧文件零迁移照读。
   */
  given Decoder[SubAgentTask] = Decoder.instance { c =>
    for
      taskId <- c.get[String]("taskId")
      parentSessionId <- c.get[String]("parentSessionId")
      agentName <- c.get[String]("agentName")
      prompt <- c.get[String]("prompt")
      description <- c.get[String]("description")
      status <- c.get[String]("status")
      retryCount <- c.get[Int]("retryCount")
      spawnedAt <- c.get[Long]("spawnedAt")
      completedAt <- c.get[Option[Long]]("completedAt")
      lastError <- c.get[Option[String]]("lastError")
      source <- c.get[String]("source")
      statusAt <- c.get[Option[Long]]("statusAt")
      revivalCount <- c.get[Option[Int]]("revivalCount")
      revivedAt <- c.get[Option[Long]]("revivedAt")
    yield SubAgentTask(
      taskId,
      parentSessionId,
      agentName,
      prompt,
      description,
      status,
      retryCount,
      spawnedAt,
      completedAt,
      lastError,
      source,
      statusAt,
      revivalCount,
      revivedAt
    )
  }
end SubAgentTask
