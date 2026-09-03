package nebflow.core.project

import cats.effect.IO
import io.circe.syntax.*
import io.circe.Json
import nebflow.core.PathUtil

/**
 * Flow Map 调整事件审计日志（blocked 反馈重入设计 §4.4）。
 *
 * `<workspace>/.nebflow/flow-map-events.jsonl` 追加式 JSONL，每行一事件：
 * `{ts, type, project, nodeId, summary}`，
 * type ∈ blocked / held / reentry-triggered / reactivated / abandoned / escalated / cooldown-on / released。
 *
 * 0 schema 迁移（独立文件不碰 flow-map.json 契约）、append-only、重启保留、grep 友好。
 * 写入点：NodeEngine.blockedNode（blocked）/ heldNode（held）/ releaseNode（released）、
 * FeedbackRouter（reentry-triggered / escalated / cooldown-on）、NodeEditTool 重激活与
 * abandon 两分支（reactivated / abandoned）。
 */
object FlowMapEventLog:
  val FileName = "flow-map-events.jsonl"

  /** 追加一条审计事件。workspace 为项目工作区绝对路径；IO.blocking 隔离磁盘写。 */
  def append(workspace: String, project: String, nodeId: String, typ: String, summary: String): IO[Unit] =
    val line = Json
      .obj(
        "ts" -> System.currentTimeMillis().asJson,
        "type" -> typ.asJson,
        "project" -> project.asJson,
        "nodeId" -> nodeId.asJson,
        "summary" -> summary.asJson
      )
      .noSpaces
    IO.blocking(os.write.append(os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileName, line + "\n")).void
