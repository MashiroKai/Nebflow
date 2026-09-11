package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.core.tools.MemoryQueue
import nebflow.shared.*

/**
 * Pre-compaction hook for the Root agent (Nebula, depth 0).
 *
 * Replaces DreamMode's idle timer: instead of a 5-minute polling cycle,
 * durable facts are extracted from the conversation right before it is
 * compacted.
 *
 * **2026-09-12 记忆改造批（memq / spec §5 R7(4) O-A）——抽取保留、直写关闭**：
 * 抽取仍是同一次 LLM 调用（成本不变），但 facts **不再**经
 * `DreamMode.updateMemory` 直写 `~/.nebflow/User.md`（那条直写通道 W2 是队列化
 * 之后的绕过口），改为逐条 `note{target:"user", action:"append",
 * section:"## Dream Extract", source.trigger:"dream"}` 入队（[[MemoryQueue]]），
 * 由下一次压缩的记忆整理 agent 消费。
 *
 * **竞态（显式接受）**：本 hook 走 `ctx.forkTurn(preHookIO)` fire-and-forget、无
 * join 句柄 ⇒ 记忆轨可能在 hook 入队前就读完队列。按 R7(4) 取 **(ii) 接受
 * 「dream facts 下一次压缩才被消费」**（零状态位改动、只延迟一个周期）。
 *
 * **已知后果（如实登记）**：原直写路径里的 T3 淘汰（14 天 TTL + 60 条 FIFO，
 * `DreamMode.t3Evolve`）不再由本 hook 机械执行——facts 入队后由整理 agent 按
 * `memory-consolidation` 方法论的 T3 规则并入稳定节（提示词纪律，非机制闸）。
 */
object NebulaMemoryHook extends PreCompactionHook:
  private val logger = NebflowLogger.forName("nebflow.prehook.nebula")

  /** 入队 actor 标识（引擎侧派生：抽取者身份）。 */
  val Actor: String = "dream"

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    if messages.size < 20 then IO.unit
    else
      for
        facts <- extractFacts(messages, agentName, sessionId, resources)
        _ <-
          if facts.nonEmpty then enqueueFacts(facts, sessionId)
          else IO.unit
        // 生命周期触发（§6.2-2.5）：压缩抽取完成后置位整理提醒信号 —— 消费方
        // ContextRefresher.buildMemoryBlock 在压缩后的首个 Nebula 注入里带
        // 「T2/T3 清扫提示」。仅置位一行，不改本 hook 的抽取逻辑。
        _ = nebflow.agent.MemoryHygieneSignal.markCompacted()
      yield ()

  /** facts 逐条入队（`## Dream Extract` 节 append）。类别以 in-band 形式写进条目
    * （`- [CATEGORY] text`）：note 只有 `section` 一个定位字段，`### CATEGORY`
    * 分组由整理 agent 按 T3 规则落位；类别信息不丢。入队失败只 WARN（hook 契约：
    * 失败不得阻塞压缩）——失败即 facts 未入队，如实落日志不静默。 */
  private[nebflow] def enqueueFacts(
    facts: List[String],
    sessionId: Option[String]
  ): IO[Unit] =
    IO.blocking {
      facts.flatMap(DreamMode.parseFact).distinct.foreach { (cat, text) =>
        MemoryQueue.enqueue(
          target = "user",
          action = "append",
          section = Some(DreamMode.DreamSectionHeader),
          matchText = None,
          content = Some(s"- [$cat] $text"),
          sessionId = sessionId,
          trigger = MemoryQueue.TriggerDream,
          actor = Actor
        ) match
          case Right(_) => ()
          case Left(reason) =>
            logger.warn(s"Dream facts enqueue failed: $reason")
      }
    }

  private def extractFacts(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    resources: SharedResources
  ): IO[List[String]] =
    val request = LlmRequest(
      messages = messages.takeRight(60) ++ List(Message(MessageRole.User, Left(DreamMode.DreamPrompt))),
      sessionId = sessionId.getOrElse("prehook"),
      agentId = agentName,
      tools = None,
      maxTokens = Some(4096),
      systemStable = Some("You are a memory extraction assistant.")
    )
    resources.llm
      .send(request)
      .map(resp => DreamMode.parseResponse(resp.reply))
      .handleErrorWith(e => logger.warn(s"Fact extraction LLM failed: ${e.getMessage}").as(Nil))
  end extractFacts
end NebulaMemoryHook
