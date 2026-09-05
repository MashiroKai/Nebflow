package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import io.circe.JsonObject

/** Stored flow-node report payload: verdict + output + structured slots.
  *
  * 2026-09-06（工具面裁撤批）：FlowReportTool 随 flow 三工具退役删除，本数据
  * 面从 FlowReportTool.scala 原样抽出保留——FlowDagExecutor（引擎红线零触碰）
  * 仍在节点完成后消费 get/remove。阶段 3 引擎侧数据迁移时随引擎一并处置。
  */
case class FlowReportData(
  verdict: String,
  output: String,
  slots: JsonObject = JsonObject.empty
)

/**
 * In-memory store for flow-node report results, keyed by session ID.
 * FlowDagExecutor reads the report after agent completion to get verdict + output + slots.
 *
 * 唯一写入方 FlowReportTool 已退役——运行时新写入为零（存量 flow 的 verdict
 * 通道自然失效，switch 节点回落 legacy 文本路由）；保留读取/清除面供引擎。
 */
object FlowReportStore:
  private val reports: Ref[IO, Map[String, FlowReportData]] = Ref.unsafe(Map.empty)

  def set(sessionId: String, data: FlowReportData): IO[Unit] =
    reports.update(_.updated(sessionId, data))

  def get(sessionId: String): IO[Option[FlowReportData]] =
    reports.get.map(_.get(sessionId))

  def remove(sessionId: String): IO[Unit] =
    reports.update(_ - sessionId)
end FlowReportStore
