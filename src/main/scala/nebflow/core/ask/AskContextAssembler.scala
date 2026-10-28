package nebflow.core.ask

import cats.effect.IO
import cats.syntax.all.*

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas 文件 ask mode · 上下文组装（机制稿 §3，实施批 2026-10-28）
 *
 * 组装管线（§3.2）：`文件全文（截断上限 = 单路预算）→ 图谱邻接段（1-2 跳，去重）
 * → 记忆检索 top-N 命中（含 ±1 邻句）→ 系统提示（身份章 §6）`，四段拼接为一次性
 * 系统上下文块；每路带来源标签（文件/图谱/记忆）。
 *
 * 三路检索源经 [[AskContextSources]] 端口注入——core 只认端口，活体实现
 * （os 读盘 + WikiService graph + stand-recall CLI）在 gateway 装配层
 * （AskGateway.liveSources），spec 注入假源穷举拼装/预算/去重。
 * ═══════════════════════════════════════════════════════════════════════════ */

/** 单路预算（机制稿 §3.2「截断上限 = 单路预算」）。 */
object AskBudgets:
  /** 文件全文入上下文上限（chars）。超出截断 + 标注。 */
  val FileMaxChars: Int = 16000

  /** 图谱邻接段条数上限（1-2 跳去重后）。 */
  val NeighborMax: Int = 6

  /** 记忆检索命中上限（top-N）。 */
  val RecallMax: Int = 5

  /** 单邻段摘要上限（chars）。 */
  val NeighborSnippetMax: Int = 600

  /** 单条记忆命中摘录上限（chars）。 */
  val RecallExcerptMax: Int = 600
end AskBudgets

/** 文档图谱邻接（1-2 跳，去重）。 */
final case class AskNeighbor(title: String, rel: String, snippet: String)

/** 记忆检索命中（主会话历史的建库检索，±1 邻句由 CLI --context 承担）。 */
final case class AskRecallHit(session: String, ts: String, excerpt: String)

/** 三路检索源端口（活体实现在 gateway 装配层；spec 注入假源）。 */
trait AskContextSources:
  /** 文件 stat 读数（触发源②：文件版本变化 = mtime+size，§3.3）。缺文件 = None。 */
  def fileStat(absPath: String): IO[Option[(Long, Long)]]

  /** 文件全文 + (mtime, size)（版本化读数，§3.1 检索源①）。缺文件 = None。 */
  def fileText(absPath: String): IO[Option[(String, Long, Long)]]

  /** 文档图谱邻接（§3.1 检索源③，1-2 跳去重）。 */
  def graphNeighbors(absPath: String): IO[List[AskNeighbor]]

  /** 记忆检索命中（§3.1 检索源②）。 */
  def recallHits(query: String): IO[List[AskRecallHit]]

  /** 触发源读数：记忆库增量（§3.3 触发源①）。 */
  def recallDbMtime: IO[Long]

  /** 触发源读数：图谱版本（§3.3 触发源③；任意可比较串）。 */
  def graphVersion: IO[String]
end AskContextSources

/** 组装产物：上下文块（进 system prompt）+ 来源标签面 + 指纹读数。 */
final case class AssembledAskContext(
  block: String,
  sources: List[String],
  fingerprint: AskFingerprint
)

object AskContextAssembler:

  /**
   * 组装一次上下文块。任一路失败 = 该路空过（best-effort 三路，单路故障不毒化
   * 问答——fail-open 语义与样张 ghost 行「上下文 = 三路」的自描述一致，缺路时
   * 来源标签如实缺）。
   */
  def assemble(sources: AskContextSources, absPath: String, fileName: String, now: Long): IO[AssembledAskContext] =
    val fileLeg = sources.fileText(absPath).attempt.map(_.toOption.flatten)
    val graphLeg = sources.graphNeighbors(absPath).attempt.map(_.toOption.getOrElse(Nil))
    val recallQuery = recallQueryFor(fileName)
    val recallLeg = sources.recallHits(recallQuery).attempt.map(_.toOption.getOrElse(Nil))
    val fpLeg = (sources.recallDbMtime, sources.graphVersion)
      .mapN { (r, g) => (r, g) }
      .attempt
      .map(_.toOption.getOrElse((0L, "")))

    for
      fileOpt <- fileLeg
      neighbors <- graphLeg
      hits <- recallLeg
      (recallMtime, graphVer) <- fpLeg
      fp = fileOpt
        .map(f => AskFingerprint(f._2, f._3, recallMtime, graphVer))
        .getOrElse(AskFingerprint(recallDbMtime = recallMtime, graphVersion = graphVer))
      block = renderBlock(fileName, fileOpt.map(_._1), neighbors, hits)
      sources2 = scala.collection.mutable.ListBuffer[String]()
      _ = if fileOpt.isDefined then sources2 += "文件"
      _ = if neighbors.nonEmpty then sources2 += "图谱"
      _ = if hits.nonEmpty then sources2 += "记忆"
    yield AssembledAskContext(block, sources2.toList, fp)
  end assemble

  /** 检索查询词：文件基名（去扩展名）——主会话讨论该文件时名词即基名。 */
  def recallQueryFor(fileName: String): String =
    val base = fileName.lastIndexOf('.') match
      case i if i > 0 => fileName.take(i)
      case _ => fileName
    if base.trim.isEmpty then fileName else base.trim

  /** 四段拼接（§3.2 管线顺序；来源标签逐段内联）。 */
  def renderBlock(
    fileName: String,
    fileText: Option[String],
    neighbors: List[AskNeighbor],
    hits: List[AskRecallHit]
  ): String =
    val sb = new StringBuilder
    sb ++= s"【问答上下文 · 目标文件：${fileName}】\n"
    sb ++= "（三路组装：文件全文 → 文档图谱邻接 → 主会话记忆检索；不含主会话最近对话历史）\n\n"

    // ── 路① 文件全文 ──
    fileText match
      case Some(text) =>
        val capped =
          if text.length > AskBudgets.FileMaxChars then text.take(AskBudgets.FileMaxChars) + "\n…（超预算截断）"
          else text
        sb ++= "═══ 来源【文件】全文 ═══\n"
        sb ++= capped
        sb ++= "\n\n"
      case None =>
        sb ++= "═══ 来源【文件】全文 ═══\n（文件暂不可读——回答时如实说明无法读取原文）\n\n"

    // ── 路② 图谱邻接（1-2 跳去重后）──
    val deduped = neighbors
      .groupBy(n => n.rel)
      .values
      .map(_.head)
      .toList
      .take(AskBudgets.NeighborMax)
    if deduped.nonEmpty then
      sb ++= "═══ 来源【图谱】邻接文档（引用边 1-2 跳）═══\n"
      deduped.foreach { n =>
        val snippet =
          if n.snippet.length > AskBudgets.NeighborSnippetMax then n.snippet.take(AskBudgets.NeighborSnippetMax) + "…"
          else n.snippet
        sb ++= s"· 《${n.title}》（${n.rel}）：$snippet\n"
      }
      sb ++= "\n"

    // ── 路③ 记忆检索命中 ──
    val cappedHits = hits.take(AskBudgets.RecallMax)
    if cappedHits.nonEmpty then
      sb ++= "═══ 来源【记忆】主会话检索命中 ═══\n"
      cappedHits.foreach { h =>
        val excerpt =
          if h.excerpt.length > AskBudgets.RecallExcerptMax then h.excerpt.take(AskBudgets.RecallExcerptMax) + "…"
          else h.excerpt
        sb ++= s"· [${h.session} · ${h.ts}] $excerpt\n"
      }
      sb ++= "\n"

    sb.result().trim
  end renderBlock

  /**
   * 系统提示（机制稿 §6 草案全文 + 上下文块）。{file_name} 占位在此渲染；
   * 终稿评审（§9-6 开放项）落地时只改本函数。
   */
  def promptFor(fileName: String, contextBlock: String): String =
    val persona =
      s"""你是 Stand 主会话 Agent 的文件问答分身（ask mode）。你与主会话同源同身份，但：

【使命】只服务当前文件《$fileName》的问答。用户看到的每一句话，你都要对得起
「这份文件」四个字：答案优先出自文件本身；出自记忆检索或文档图谱邻接的，必须
带来源标注（文件 §节名 / 主会话 日期 / 图谱·文档名）。

【上下文】你的上下文由三路组装（文件全文、文档图谱邻接段、主会话记忆检索命中），
不含主会话的最近对话历史；上下文每 10 分钟随记忆与文档更新重组——被问到「刚才」
类问题时，以检索结果为准，不臆造连续性。

【边界】
1. 你没有编辑、执行、编排类工具；绝不能承诺「我去改/我去跑/我去派任务」。
2. 对主会话只有一条通道：send_message_to_main_chat（转交请求）。仅当用户明确要
   「让主会话处理/跟进/执行」时使用；转交后告知用户「已发送，它可回问」，然后留在
   本窗继续服务。
3. 主会话的回问会以用户消息形态出现（前缀「主会话回问：」），照常作答即可。

【透明】只以消息气泡说话：不描述工具调用过程、不展示检索流水；检索引用以来源
标注内联在答案里。答不出时直说「这份文件与检索范围内没有依据」，并给出最接近
的出处。

【口吻】与主会话一致：简体中文、直接、给结论再给依据；长答案先一句话结论。"""
    persona + "\n\n" + contextBlock
  end promptFor
end AskContextAssembler
