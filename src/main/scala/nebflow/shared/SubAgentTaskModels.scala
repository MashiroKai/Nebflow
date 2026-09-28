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
  status: String, // "running" | "completed" | "failed" | "restarting"
  retryCount: Int, // how many times restarted by supervisor
  spawnedAt: Long, // epoch millis
  completedAt: Option[Long], // epoch millis
  lastError: Option[String], // last error message if failed
  source: String // "delegate" | "subtask"
)

object SubAgentTask:
  given Encoder[SubAgentTask] = Encoder.derived
  given Decoder[SubAgentTask] = Decoder.derived
