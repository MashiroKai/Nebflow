package nebflow.core.tools

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.Defaults

/**
 * 后台任务终态通知负载的**纯组装面**（作者报障 2026-10-24「后台任务通知很不详细
 * 甚至有截断——这里是不是存在问题」修复批）。
 *
 * 报障根因（两处叠加）：
 *  1. **负载缺料**：完成通知只在 exit≠0 时才带退出码——成功任务（作者截图现场
 *     「Render v4.pptx to PDF then PNGs in background」）通知正文只剩描述行加一个
 *     悬空冒号，无退出码、无耗时、零输出时连一行正文都没有，作者无法判断任务到底
 *     成没成功；失败通知只带异常 message 一行。远程任务（RemoteExecutor）完成通知
 *     连退出码位都没有。
 *  2. **输出无界**：完成通知把 stdout 全量塞进 payload——与「通知面」定位相反；
 *     全文本就有 BgTaskOutputStore 落盘（面板行点击 → REST 详情卡回看，2026-09-09
 *     批），通知只该带尾段。
 *
 * 本对象是唯一的组装实现（判据纯函数单点，BgTaskRegistrySpec 同款纪律）：
 *  - 完成通知 = `[Background task completed] "<desc>" (exit <N> · <dur>):\n<尾段>`；
 *    **退出码恒在**（exit 0 = 显式成功信号，作者从通知一眼判定成败）；
 *  - 失败/取消通知 = `[Background task failed] "<desc>" (<dur>):\n<错误尾段>`；
 *  - 正文（stdout+[stderr] / 错误串）截到末尾 `Defaults.BgNotifyTailLines` 行
 *    （prop `nebflow.bg.notifyTailLines`，默认 20），截断时附标注行：总行数 +
 *    jobId 指回（面板行点击开详情卡看全文）；
 *  - 零输出正文 = `[no output]`（悬空冒号消失——截图现场形态不再出现）；
 *  - 耗时 = 注册 → 终态的墙钟差（`BgTaskRegistry.unregisterReturning` 供
 *    startedAtMs），缺席（注册行已被清）时段落省略。
 *
 * 消费方 = BashTool.makeNotifyCallback（本地）与 RemoteExecutor.notifyRemoteBgResult
 * （远程；无退出码 ⇒ exitCode=None）。metadata 面向后端/前端帧：既有键
 * （description/exitCode/output）语义不变，新增 durationMs/truncated/totalLines/
 * taskId 三个只增键（向后兼容）。
 */
object BgNotifyPayload:

  /** 组装产物：payload 注 ExternalEvent；metadata 深入事件与 WS 帧；logTag 进引擎日志。 */
  final case class Built(
    payload: String,
    metadata: JsonObject,
    /** 日志尾注，如 `exit 0 · 1m 12s`（无段时为空串）。 */
    logTag: String,
    truncated: Boolean,
    totalLines: Long
  )

  /** 通知行数上限（现读 prop，spec 可注入覆盖值走 tailN 形参）。 */
  def tailLines: Int = Defaults.BgNotifyTailLines

  /**
   * 完成通知。`exitCode` = 进程退出码（本地路径恒 Some；远程路径 None——HTTP 面
   * 不携带）；`stdout`/`stderr` 为已清洗文本（清洗仍归 BashTool，本面只管组装）。
   */
  def completed(
    description: String,
    exitCode: Option[Int],
    durationMs: Option[Long],
    stdout: String,
    stderr: String,
    jobId: String,
    tailN: Int = tailLines
  ): Built =
    val tags = tagParts(exitCode.map(c => s"exit $c"), durationMs.map(formatDuration))
    val body0 =
      if stderr.trim.nonEmpty then stdout + s"\n[stderr]:\n$stderr" else stdout
    val (body, truncated, totalLines) = tailWithMarker(body0, tailN, jobId)
    Built(
      payload = s"[Background task completed] \"$description\"${tagsSuffix(tags)}:\n$body",
      metadata = JsonObject(
        "description" -> description.asJson,
        "exitCode" -> exitCode.asJson,
        "durationMs" -> durationMs.asJson,
        "output" -> body.asJson,
        "truncated" -> truncated.asJson,
        "totalLines" -> totalLines.asJson,
        "taskId" -> jobId.asJson
      ),
      logTag = tags.mkString(" · "),
      truncated = truncated,
      totalLines = totalLines
    )
  end completed

  /** 失败通知：`errInfo` = 异常 message / 错误串，同样截尾段（多行错误栈保末尾）。 */
  def failed(
    description: String,
    errInfo: String,
    durationMs: Option[Long],
    jobId: String,
    tailN: Int = tailLines
  ): Built =
    val tags = tagParts(None, durationMs.map(formatDuration))
    val (body, truncated, totalLines) = tailWithMarker(errInfo, tailN, jobId)
    Built(
      payload = s"[Background task failed] \"$description\"${tagsSuffix(tags)}:\n$body",
      metadata = JsonObject(
        "description" -> description.asJson,
        "durationMs" -> durationMs.asJson,
        "output" -> body.asJson,
        "truncated" -> truncated.asJson,
        "totalLines" -> totalLines.asJson,
        "taskId" -> jobId.asJson
      ),
      logTag = tags.mkString(" · "),
      truncated = truncated,
      totalLines = totalLines
    )
  end failed

  // ── 内部 ─────────────────────────────────────────────────────────────

  /** `(exit 0 · 1m 12s)` 标签段：段全缺 ⇒ 空串（防御：注册行丢失且无退出码）。 */
  private def tagsSuffix(tags: Seq[String]): String =
    if tags.isEmpty then "" else s" (${tags.mkString(" · ")})"

  private def tagParts(exit: Option[String], dur: Option[String]): Seq[String] =
    Seq(exit, dur).flatten

  /**
   * 正文截尾：≤ tailN 行原样返回；超出 ⇒ 末尾 tailN 行 + 截断标注行（总行数 +
   * jobId 指回详情卡）。行数按 `\n` 切分（先去尾部换行，空正文不算行）。
   */
  private[tools] def tailWithMarker(body: String, tailN: Int, jobId: String): (String, Boolean, Long) =
    val trimmed = body.stripSuffix("\n")
    if trimmed.trim.isEmpty then ("[no output]", false, 0L)
    else
      val lines = trimmed.split("\n")
      if lines.length <= tailN then (trimmed, false, lines.length.toLong)
      else
        val marker =
          s"[truncated: showing last $tailN of ${lines.length} lines — full output: background-tasks panel, job $jobId]"
        (lines.takeRight(tailN).mkString("\n") + "\n" + marker, true, lines.length.toLong)
  end tailWithMarker

  /** 耗时文案：`0.8s` / `12s` / `1m 05s` / `1h 02m`（前端 formatDuration 同语义）。 */
  def formatDuration(ms: Long): String =
    if ms <= 0L then "0s"
    else if ms < 10_000L then f"${ms / 1000.0}%.1fs"
    else
      val s = ms / 1000L
      if s < 60L then f"${s}%ds"
      else if s < 3600L then f"${s / 60L}m ${s % 60L}%02ds"
      else f"${s / 3600L}h ${(s % 3600L) / 60L}%02dm"
  end formatDuration

end BgNotifyPayload
