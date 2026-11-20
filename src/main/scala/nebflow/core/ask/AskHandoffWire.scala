package nebflow.core.ask

import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AskHandoffPort

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas Ask · 转交请求的 wire 单点（ask 写面收窄批 2026-11-18）
 *
 * 作者令：「复杂任务转交主会话：产出结构化转交请求（做什么、涉及哪些路径、为什么
 * 需要主会话、期望产出），走既有主会话消息面把它交出去」「转交必须机器可读 +
 * UI 可见（Ask 面板要有明确状态，如「已转交主会话」+ 转交内容摘要），不许静默」。
 *
 * 本对象是这三处的**同一份编码**：
 *   ① 注入主会话的 ExternalEvent metadata（`handoff` 对象——机器可读面）；
 *   ② ask 会话 WS 面 `askHandoffAck` 帧的 `handoff` 字段（面板状态面）；
 *   ③ spec 断言面（一个函数，禁写第二份字段名）。
 * ═══════════════════════════════════════════════════════════════════════════ */
object AskHandoffWire:

  /** 结构化四件 + 身份四件（leg/会话/文件）。
   *  gitviz 批（2026-11-19 第二轮）：`git` 非空时同时携带分支清单/目标线/意图（机器
   *  可读面与面板帧同一单源 —— 面板多选合并与问答交办走同一份 request）。 */
  final case class Handoff(
    askSessionId: String,
    file: String,
    fileName: String,
    leg: String,
    task: String,
    paths: List[String],
    reason: String,
    expected: String,
    git: Option[AskHandoffPort.GitRequest] = None
  )

  def of(req: AskHandoffPort.Request): Handoff =
    Handoff(
      askSessionId = req.askSessionId,
      file = req.file,
      fileName = req.fileName,
      leg = req.leg,
      task = req.task,
      paths = req.paths,
      reason = req.reason,
      expected = req.expected,
      git = req.git
    )

  /** git 件的机器可读形（字段名固定）。 */
  def gitJson(g: AskHandoffPort.GitRequest): Json = Json.obj(
    "project" -> g.project.asJson,
    "branches" -> g.branches.asJson,
    "target" -> g.target.asJson,
    "intent" -> g.intent.asJson,
    "notes" -> g.notes.asJson,
    "source" -> g.source.asJson
  )

  /** 机器可读形（字段名固定：注入 metadata 与面板帧共用）。 */
  def json(h: Handoff): Json =
    val base = Json.obj(
      "askSessionId" -> h.askSessionId.asJson,
      "file" -> h.file.asJson,
      "fileName" -> h.fileName.asJson,
      "leg" -> h.leg.asJson,
      "task" -> h.task.asJson,
      "paths" -> h.paths.asJson,
      "reason" -> h.reason.asJson,
      "expected" -> h.expected.asJson
    )
    h.git match
      case Some(g) => base.deepMerge(Json.obj("git" -> gitJson(g)))
      case None => base

  /** 面板状态摘要（人读单行；UI 显示「已转交主会话」+ 本摘要）。 */
  def summary(h: Handoff): String =
    val head = if h.task.trim.nonEmpty then h.task.trim else "（未填 task）"
    val pathNote = if h.paths.isEmpty then "" else s" · ${h.paths.length} 个路径"
    val tail = if h.expected.trim.nonEmpty then s" · 期望：${h.expected.trim}" else ""
    h.git match
      // gitviz：摘要要说清「哪几支 → 哪条线」（作者口径：分支清单 · 目标线 · 意图）
      case Some(g) =>
        val gv = s"${intentLabel(g.intent)} ${g.branches.mkString("、")} → ${if g.target.nonEmpty then g.target else "（未定目标线）"}"
        val notes = if g.notes.isEmpty then "" else s" · ${g.notes.length} 条备注"
        head + (if head.contains(g.target) || g.target.isEmpty then "" else s"（$gv$notes）")
      case None => head + pathNote + tail

  private def intentLabel(intent: String): String = intent match
    case "merge" => "合并"
    case "branch" => "建支"
    case "delete" => "删支"
    case "push" => "推送"
    case "rebase" => "变基"
    case "tag" => "打 tag"
    case other if other.trim.isEmpty => "交办"
    case other => other

  /**
   * 主会话注入正文（人读面）：结构化块 + 续聊地址。主会话的 LLM 同时拿到
   * 人读正文与 `handoff` metadata（机器可读），两条腿同一份数据。
   */
  def injectedBody(h: Handoff, mailAddress: String): String =
    val sb = new StringBuilder
    sb ++= s"来自文件问答 · ${h.fileName}\n\n"
    sb ++= "【结构化转交请求】\n"
    sb ++= s"· 做什么：${if h.task.trim.nonEmpty then h.task.trim else "（未填）"}\n"
    sb ++= s"· 涉及路径：${if h.paths.isEmpty then "（未指定）" else h.paths.mkString(", ")}\n"
    sb ++= s"· 为什么需要主会话：${if h.reason.trim.nonEmpty then h.reason.trim else "（未填）"}\n"
    sb ++= s"· 期望产出：${if h.expected.trim.nonEmpty then h.expected.trim else "（未填）"}\n"
    // gitviz 批：git 件按四件展开（分支清单 · 目标线 · 意图 · 理由），并显式声明
    // 「这是请求不是执行」——主会话按落支规则自行派发。
    h.git.foreach { g =>
      sb ++= "\n【git 交办件】（分支管理问答 · 只读面板产出，写操作唯一执行者 = 本会话）\n"
      sb ++= s"· 项目：${g.project}\n"
      sb ++= s"· 分支清单：${if g.branches.isEmpty then "（未指定）" else g.branches.mkString("、")}\n"
      sb ++= s"· 目标线：${if g.target.nonEmpty then g.target else "（未指定）"}\n"
      sb ++= s"· 意图：${g.intent}\n"
      if g.notes.nonEmpty then sb ++= s"· 逐项备注：${g.notes.mkString(" / ")}\n"
      sb ++= s"· 入口来源：${g.source}\n"
    }
    sb ++= s"· 来源文件：${h.file}\n"
    sb ++= s"· 来源腿：${h.leg}\n"
    sb ++= s"\n（续聊该问答会话：Mail(address=\"$mailAddress\")）"
    sb.result()

  /**
   * 面板状态帧（ask 会话 WS 面）：`askHandoffAck`。两条腿（工具腿 / dock 腿）同帧，
   * 前端同一处消费——面板显示「已转交主会话」+ [[summary]]；失败腿 `ok=false` +
   * `error`（显式可读，不许静默）。
   */
  def ackFrame(sessionId: String, ok: Boolean, error: String, h: Handoff): Json =
    val base = Json.obj(
      "type" -> "askHandoffAck".asJson,
      "sessionId" -> sessionId.asJson,
      "ok" -> ok.asJson,
      "question" -> h.task.asJson,
      "summary" -> summary(h).asJson,
      "handoff" -> json(h)
    )
    if error.trim.isEmpty then base else base.deepMerge(Json.obj("error" -> error.asJson))

end AskHandoffWire
