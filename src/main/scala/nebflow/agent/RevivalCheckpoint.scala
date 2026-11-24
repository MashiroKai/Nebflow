package nebflow.agent

import cats.effect.IO
import nebflow.shared.*

/**
 * RevivalCheckpoint —— 复活断点台账（9.19 版中断恢复批 · 2026-11-04 · 审计 D6 的机械封口）。
 *
 * == 关闭的缺口 ==
 * 复活腿（[[DelegateRevival.reviveImpl]]）与 Mail 续命腿只把**整段转录**喂回，外加一句
 * 人话「continue / do not redo」（`SessionResumption` 同款）：模型拿不到任何机器可读的
 * 「已完成到哪」，含副作用的步骤（提交 / 合并 / 文件写）可被重做。审计 D6 的判据是
 * 「复活时把已完成步骤的结构化读数带进上下文」。
 *
 * == 台账形态（可从盘上机械求值）==
 * `<dataRoot>/sessions/<sid>/revival-checkpoint.json`（**只由本对象写**）：
 * {{{
 * {
 *   "type": "revival-checkpoint",
 *   "sessionId": "delegate-...",
 *   "checkpointAt": 1793754587648,      // 本次复活时刻（epoch ms；关键字 checkpoint）
 *   "revivalCount": 2,                  // 第几次复活（任务行同值）
 *   "cursor": 42,                       // 转录游标 = 复活时转录条数（已完成回合的边界）
 *   "completedTurns": 12,               // 已完成的 assistant 回合数（非日报文）
 *   "completedToolRounds": 31,          // 已完成的工具结果块数（tool_result 计数）
 *   "completedSteps": ["…", "…"],       // 最近几个已完成回合的首行摘要（≤5 条，各 ≤160 字）
 *   "lastAssistantHead": "…"            // 最后一条 assistant 文本首行（≤200 字）
 * }
 * }}}
 *
 * **判据依据选择**（任务要求「选一个可机械校验的」）：以**转录游标 + 已完成回合数**为
 * 唯一断点真源。理由：转录是复活腿本来就要逐字节回放的同一份材料（`initialMessages =
 * transcript`），因此「断点」与「现场」永不漂移——不需要第二份状态机（旧
 * `TurnStateStore.save/load` 假恢复链正是被审计移除的前车之鉴）。工具回合数只是同一份
 * 转录的第二个切面（tool_result 块），供模型区分「说过」与「做过」。
 *
 * == 纪律 ==
 *  - **纯加性**：写失败 = WARN + None，绝不影响复活本身（复活是救命腿）。
 *  - **幂等/单调**：每次复活重写整件（转录只增长 ⇒ 游标单调不减）；同日多读零副作用。
 *  - 不复活 `TurnStateStore` 老壳（审计判定的假恢复链），不复用其任何 API。
 */
object RevivalCheckpoint:

  private val logger = NebflowLogger.forName("nebflow.agent.revival-checkpoint")

  /** 目录名（`<dataRoot>/sessions/<sid>/`；与 SessionStore 的 `<sid>.json` 平级不冲突）。 */
  val FileName: String = "revival-checkpoint.json"

  /** `completedSteps` 保留条数（最近 N 个已完成回合的摘要）。 */
  val StepHeadLimit: Int = 5

  /** 单条摘要长度（步骤 / 末条 assistant）。 */
  val HeadChars: Int = 160

  def pathFor(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / FileName

  /** 机器可读断点读数（纯函数；spec 判红锚）。 */
  final case class Facts(
    cursor: Int,
    completedTurns: Int,
    completedToolRounds: Int,
    completedSteps: List[String],
    lastAssistantHead: Option[String]
  ):

    /** 一句话断点事实（nudge 用；模型可见）。 */
    def describe: String =
      s"$cursor transcript messages, $completedTurns completed assistant turn(s) and " +
        s"$completedToolRounds completed tool round(s) are already DONE"

    /** 台账 JSON（落盘面）。 */
    def json(sessionId: String, revivalCount: Int, at: Long): io.circe.Json =
      io.circe.Json.obj(
        "type" -> io.circe.Json.fromString("revival-checkpoint"),
        "sessionId" -> io.circe.Json.fromString(sessionId),
        "checkpointAt" -> io.circe.Json.fromLong(at),
        "revivalCount" -> io.circe.Json.fromInt(revivalCount),
        "cursor" -> io.circe.Json.fromInt(cursor),
        "completedTurns" -> io.circe.Json.fromInt(completedTurns),
        "completedToolRounds" -> io.circe.Json.fromInt(completedToolRounds),
        "completedSteps" -> io.circe.Json.fromValues(completedSteps.map(io.circe.Json.fromString)),
        "lastAssistantHead" -> lastAssistantHead.fold(io.circe.Json.Null)(io.circe.Json.fromString)
      )

  end Facts

  /** 首行摘要（去换行、截断；空文本 = None）。 */
  private def headOf(text: String, limit: Int = HeadChars): Option[String] =
    val line = text.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")
    if line.isEmpty then None else Some(line.take(limit))

  /** 转录 → 断点读数（纯函数，无 IO）。 */
  def factsOf(transcript: List[Message]): Facts =
    val assistantTurns = transcript.filter(m => m.role == MessageRole.Assistant && m.textContent.trim.nonEmpty)
    val toolRounds = transcript
      .flatMap(_.content.toOption.getOrElse(Nil))
      .count {
        case _: ContentBlock.ToolResult => true
        case _ => false
      }
    Facts(
      cursor = transcript.size,
      completedTurns = assistantTurns.size,
      completedToolRounds = toolRounds,
      completedSteps = assistantTurns.reverse.flatMap(m => headOf(m.textContent)).reverse.takeRight(StepHeadLimit),
      lastAssistantHead = assistantTurns.reverse.headOption.flatMap(m => headOf(m.textContent, 200))
    )

  end factsOf

  /**
   * 写断点台账（复活腿调用；fail-soft —— 任何故障只 WARN，复活照常继续）。
   * 返回 Some(facts) = 已落盘（调用方据此把断点句拼进复活 nudge）。
   */
  def write(sessionId: String, transcript: List[Message], revivalCount: Int, at: Long): IO[Option[Facts]] =
    if sessionId.trim.isEmpty then IO.pure(None)
    else
      IO.blocking {
        val facts = factsOf(transcript)
        val path = pathFor(sessionId)
        os.makeDir.all(path / os.up)
        nebflow.core.AtomicJson.writeSync(path, facts.json(sessionId, revivalCount, at).noSpaces)
        facts
      }.map(Some(_))
        .handleErrorWith(e =>
          logger
            .warn(s"[revival-checkpoint] checkpoint write failed for $sessionId (revival continues): ${e.getMessage}")
            .as(None)
        )

end RevivalCheckpoint
