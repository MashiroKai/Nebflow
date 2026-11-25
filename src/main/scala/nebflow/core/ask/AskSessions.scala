package nebflow.core.ask

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.core.wiki.MdHtmlGate
import nebflow.shared.{NebflowLogger, PathUtil}

import java.security.MessageDigest

import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas 文件 ask mode · 会话身份与文件绑定（机制稿 §2/§4，实施批 2026-10-28）
 *
 * 设计稿 docs/design/20261028_canvas-ask-mode-design.zh-CN.md：
 *   - 每文件独立 session，会话键 = `ask:<hash(文件路径)>`（作者令⑦：切卡即切、
 *     收起不销毁）；本单点把键收敛为引擎侧 sessionId `ask-<8hex>`（sha256 前 8
 *     位，避免 4 位碰撞面），展示键 `ask:<4hex>` 与样张 v2 徽标同形。
 *   - 会话历史持久化口径沿用主会话面（`<dataRoot>/sessions/<askId>.json` +
 *     `.ui.json`，AgentFinishTurn 逐轮落盘）——ask 会话不进 `_index.json` 索引
 *     （同 delegate 口径：listSessionsIncludeUnindexed 已排除非 UUID 形态 id），
 *     文件绑定表单独落 `<dataRoot>/ask/index.json`（跨重启复现绑定的最小状态）。
 *   - 重组冷却（§3.3）：触发源置待重组标记（合并语义 = 单布尔），10 分钟冷却
 *     期满后的下一次交互（askOpen / askQuestion）前重组，不主动打断。纯函数
 *     `AskCooldown.plan` 是唯一判定面（spec 可穷举）。
 * ═══════════════════════════════════════════════════════════════════════════ */

object AskSessions:

  /** 会话 id 前缀单点——工具面/提示词/spawn 判据全部经本判据，禁写第二份。 */
  val Prefix = "ask-"

  /**
   * Only these tools are stripped from non-Ask roles; shared lookup tools and Bash are not exclusive.
   * canvas-inline-diff-review（2026-11-16）：ask_edit/ask_write = 直接落盘的受限编辑
   * （仅绑定文件；落盘即入账，canvas 原文档内联 diff 逐块审阅）。
   */
  val AskExclusiveTools: Set[String] =
    // PDF note/ask 批（2026-11-18）：PdfPage = 绑定 PDF 的按需取页件（只读缓存
    // 条目，不整篇载入；答案引用页码的取数面）。ask 专属同 ask_edit。
    // card-tool-revive 批（2026-11-18，作者令「找到我们 git 历史中的 card 工具 让
    // canvas ask 可以使用 card 工具」）：Card = 在对话流里渲一张沙箱 iframe 卡片
    // （HTML 源，本地文件引用走 FileRefs 共享策略）。**本集是它唯一的授权单点**：
    // 既由 [[AllowedTools]] 带上 ask 面，又由 `buildAllowedToolSet` 尾段的
    // `-- AskExclusiveTools` 从其余一切身份剥净；工具内侧另有
    // `isAskSession` 身份闸兜底（ask_edit/ask_write/PdfPage 同款双保险）。
    // gitviz 分支管理问答批（2026-11-19 第二轮）：gitviz_graph = 分支图只读取数件
    // （实现在 core/tools/GitVizGraphTool.scala；数据面 = 既有 GitGraph.build）。
    // 进本集即同时得到两件事：① GitViz 会话按身份追加；② 由
    // buildAllowedToolSet 尾段的 `-- AskExclusiveTools` 从其余一切身份剥净；
    // 工具内侧另有 isGitVizSession 身份闸（双保险，PdfPage 同款）。
    Set(
      "message_record_search",
      "send_message_to_main_chat",
      "ask_edit",
      "ask_write",
      "PdfPage",
      "Card",
      "gitviz_graph",
      "gitviz_handoff"
    )

  /** Historical extra/read tool catalogue; not the runtime authorization face. */
  val AllowedTools: Set[String] =
    AskExclusiveTools ++ Set("Read", "Glob", "Grep", "WebSearch", "WebFetch", "Bash")

  /**
   * Resolve only registered bindings; other roles retain their existing cwd selection.
   *
   * 读面裁定（2026-11-23，作者原话「ask 的读取面不做系统层约束 让它能正常读取各文件 分支 做调研等」）：
   * file 域 ask 的会话 cwd（Bash 初始目录 / 只读执行器 cwd）= **工作区根**（调用方给出的
   * `workspaceRoot`，与主会话读面同源），不再收窄到「绑定文件父目录」。`workspaceRoot`
   * 缺省（无工作区信息的调用方）时回落到文件父目录（旧行为，fail-narrow）。
   * 写面不受影响：AskPathBoundary 的相对拼写仍按绑定文件目录解析（独立判决面）。
   */
  def sessionCwd(
    sessionId: String,
    registry: Option[AskSessionRegistry],
    workspaceRoot: Option[String] = None
  ): IO[Option[String]] =
    if !isAskSession(sessionId) then IO.pure(None)
    else
      registry match
        case None => IO.raiseError(new IllegalStateException(s"ask session $sessionId: ask services unavailable"))
        case Some(r) =>
          r.byId(sessionId).flatMap {
            case None => IO.raiseError(new IllegalArgumentException(s"ask session $sessionId has no file binding"))
            case Some(binding) if binding.isGitViz =>
              // gitviz（2026-11-19 第二轮）：作用域 = 项目仓库根本身（git 的 cwd 在仓库里），
              // 不是「文件所在目录」；文件类型白名单不适用，但仓库必须真实存在（fail-closed）。
              IO.blocking {
                val root = os.Path(binding.file)
                require(os.isDir(root), s"ask session $sessionId: gitviz scope is not a directory")
                Some(binding.file)
              }
            case Some(binding) =>
              IO.blocking {
                val file = os.Path(binding.file)
                require(isAskable(binding.file) && os.isFile(file), s"ask session $sessionId: invalid bound file")
                workspaceRoot.map(_.trim).filter(_.nonEmpty) match
                  case Some(ws) =>
                    val root = os.Path(ws)
                    require(os.isDir(root), s"ask session $sessionId: workspace root is not a directory")
                    Some(root.toString)
                  case None => Some(file / os.up).map(_.toString)
              }
          }

  /** 重组冷却档（§3.3：10 分钟）。 */
  val ReassemblyCooldownMs: Long = 10L * 60L * 1000L

  /** 判据单点：`ask-` 前缀 = ask 会话（delegate- 前缀同款先例 DelegateJudge）。 */
  def isAskSession(sessionId: String): Boolean =
    sessionId != null && sessionId.startsWith(Prefix)

  /** Option 面（AgentSessionExecution 调用点与 DelegateSessions.isDelegateSession 同形）。 */
  def isAskSession(sessionId: Option[String]): Boolean =
    sessionId.exists(isAskSession)

  /**
   * md/html 类型硬约束闸（设计稿 §2「类型硬约束闸」行，调研稿 §5.6/§9-7）。
   * **判据单点 = `MdHtmlGate`（core/wiki）**——ask 作用面与词条作用面共用同
   * 一份四扩展名枚举（「作用面收缩」是同一个裁定，不是两个白名单）。
   * `AskableExtensions` / `isAskable` 保留为 ask 侧门面（既有调用点与 spec
   * 引用面），实现一行委托，禁写第二份。
   */
  val AskableExtensions: Set[String] = MdHtmlGate.Extensions

  /**
   * ask 可问面电子书扩列（Canvas 改造批⑩，2026-10-31 作者令「对话按钮扩到
   * 电子书格式（原仅 md/html）」）：epub/mobi/azw3 并入 ask 可问面；词条
   * /wikilink 面不跟（仍 `MdHtmlGate` 四扩展名——扩列是 ask 侧单裁定）。
   * 前端镜像 = `web/js/mdHtmlGate.js` 的 `ASKABLE_EBOOK_EXTENSIONS`（逐字对齐）。
   */
  val AskableEbookExtensions: Set[String] = Set(".epub", ".mobi", ".azw3")

  /**
   * ask 可问面 PDF 扩列（PDF note/ask 批，2026-11-18 作者令「让 pdf 支持 note
   * 和 ask」）：.pdf 并入 ask 可问面。与电子书扩列同形：ask 侧单裁定，词条
   * /wikilink 面不跟。差异在上下文组装——PDF **不整篇载入**（懒解析：首条
   * askQuestion 才触发解析；提示词走 PDF 专属补章，取页走 PdfPage 工具），
   * 见 [[AskContextAssembler.promptFor]] 的 PDF 模式段。
   * 前端镜像 = `web/js/mdHtmlGate.js` 的 `ASKABLE_PDF_EXTENSIONS`（逐字对齐）。
   */
  val AskablePdfExtensions: Set[String] = Set(".pdf")

  /** PDF 判据门面（与 [[isAskable]] 同一 extOf 纪律；供取页工具/组装腿复用）。 */
  def isPdf(path: String): Boolean =
    AskablePdfExtensions.contains(MdHtmlGate.extOf(path))

  /**
   * ask 侧判据门面：仅白名单扩展名可问（大小写不敏感，`?query`/`#frag` 由
   * [[MdHtmlGate.extOf]] 先剥离）。askOpen 拒答（WsAskHandlers）与 spawn 面
   * 深防（AskServices.agentDefFor/assembleNow 复问）全部经本判据——§9-8
   * 「仅闸新问题、不清理」：本判据只拒新问，不触碰既有绑定表。
   */
  def isAskable(path: String): Boolean =
    MdHtmlGate.isMdHtml(path) ||
      AskableEbookExtensions.contains(MdHtmlGate.extOf(path)) ||
      isPdf(path)

  /** sha256 前 8 位 hex（会话 id 用；展示再截前 4 位）。 */
  def fileHash(canonicalPath: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(canonicalPath.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    bytes.take(4).map(b => f"${b & 0xff}%02x").mkString

  /** 会话 id 单点：`ask-<8hex>`。同一文件恒同 id（幂等绑定）。 */
  def sessionIdFor(canonicalPath: String): String =
    Prefix + fileHash(canonicalPath)

  /** 展示键（样张 v2 徽标形态 `ask:7f3a`）。 */
  def displayKey(canonicalPath: String): String =
    "ask:" + fileHash(canonicalPath).take(4)

  // ── 分支管理问答（gitviz，2026-11-19 第二轮作者令）───────────────────────
  /**
   * gitviz 分支管理问答的会话前缀。**故意仍以 [[Prefix]]（`ask-`）起头**：既有
   * ask 判据（[[isAskSession]]）、WS ask 拦截面（web/js/ws.js 的 `startsWith('ask-')`）、
   * `askHandoffAck` 回执帧、会话持久化面全部照旧命中——这就是作者要的「复用同一条
   * 路」，不新造第二套会话族。`-gv-` 段是**项目域**判据（纯前缀，不查表）。
   */
  val GitVizPrefix = "ask-gv-"

  /** 判据单点：gitviz 分支管理问答会话（纯前缀，与前端 GV_SID_PREFIX 逐字对齐）。 */
  def isGitVizSession(sessionId: String): Boolean =
    sessionId != null && sessionId.startsWith(GitVizPrefix)

  /** Option 面（同 [[isAskSession]] 的双形态）。 */
  def isGitVizSession(sessionId: Option[String]): Boolean =
    sessionId.exists(isGitVizSession)

  /**
   * 会话 id 形状闸（gitviz）：`ask-gv-` + 8..16 hex。前端是 id 生成方（确定性键 =
   *  同项目同会话），后端只校验形状、拒绝夹带任意字符的 id（会话键会进
   *  `<dataRoot>/ask/index.json` 与日志，不做形状闸等于放行任意键）。
   */
  def isGitVizSessionIdShape(sessionId: String): Boolean =
    isGitVizSession(sessionId) &&
      sessionId.drop(GitVizPrefix.length).matches("[0-9a-f]{8,16}")

  /** 后端派生兜底（前端未给合法 id 时）：项目名 → 稳定键。 */
  def gitVizSessionIdFor(project: String): String =
    GitVizPrefix + fileHash("gitviz:" + project)

  // 历史硬约束「这个 agent 没有任何 git 写能力」被 2026-11-25 22:47
  // 作者裁定「放开」取代；工具授权改由普通会话授权链决定。

end AskSessions

/** 一个 ask 绑定：文件 ↔ 会话 ↔ 最近已知的控制台主会话（转交目标，§7）。 */
final case class AskBinding(
  id: String,
  file: String,
  fileName: String,
  createdAt: Long,
  /** 转交目标主会话（askOpen 时由前端随帧上报刷新；缺省空 = 转交不可用）。 */
  mainSessionId: String = "",
  /**
   * 绑定域（gitviz 分支管理问答批，2026-11-19 第二轮）：
   *   · `"file"` = 既有文件 ask（`file` 是文件绝对路径，`fileName` 是显示名）；
   *   · `"gitviz"` = 分支管理问答（`file` 是**项目仓库根**，`fileName` 是项目名）。
   * 同一张绑定表承载两域，是为了让「一域一会话 / 跨重启复现 / 转交目标刷新」这三件
   * 既有能力零复制地复用（作者令「复用同一条路，不新造第二套」）。缺省 `"file"`
   * ⇒ 存量 index.json 逐字兼容（无该字段即旧语义）。
   */
  kind: String = AskBinding.KindFile,
  device: Option[String] = None
):

  def displayKey: String = AskSessions.displayKey(file)

  /** 本绑定是不是分支管理问答域（判据单点；禁在别处比较字符串）。 */
  def isGitViz: Boolean = kind == AskBinding.KindGitViz
end AskBinding

object AskBinding:
  val KindFile: String = "file"
  val KindGitViz: String = "gitviz"

/** 上下文指纹（§3.3 触发源读数）：任一变化 ⇒ 置待重组。 */
final case class AskFingerprint(
  fileMtime: Long = 0L,
  fileSize: Long = 0L,
  recallDbMtime: Long = 0L,
  graphVersion: String = ""
):

  def sameAs(other: AskFingerprint): Boolean = this == other
end AskFingerprint

/**
 * 重组调度状态（每文件一份，内存态；重启后按「未组装」重新起步——首次
 *  askOpen 必组装，冷却语义不受影响）。
 */
final case class AskAssemblyState(
  lastAssembledAt: Long = 0L,
  fingerprint: AskFingerprint = AskFingerprint(),
  /** 触发源已置待重组（连续触发合并 = 布尔单标记，§3.3 防抖语义）。 */
  pending: Boolean = false
)

object AskCooldown:

  sealed trait Plan

  object Plan:
    /** 冷却期内 / 无触发：保持现状（重组不发生）。 */
    case object Hold extends Plan

    /** 首次组装（尚无上下文）。 */
    case object Assemble extends Plan

    /** 冷却期满 + 待重组：重组（对话历史不动，§3.3）。 */
    case object Reassemble extends Plan
  end Plan

  /**
   * 唯一判定面（纯函数）：
   *   - 从未组装 ⇒ Assemble（开窗前三路齐备，§8-4）；
   *   - 指纹变化 ⇒ pending 置位（触发合并语义由调用方持有单标记实现）；
   *   - pending ∧ 冷却期满 ⇒ Reassemble；
   *   - 其余 ⇒ Hold。
   */
  def plan(now: Long, state: AskAssemblyState, current: AskFingerprint): Plan =
    val seen = state.lastAssembledAt > 0L
    if !seen then Plan.Assemble
    else
      val dirty = state.pending || !state.fingerprint.sameAs(current)
      if !dirty then Plan.Hold
      else if now - state.lastAssembledAt >= AskSessions.ReassemblyCooldownMs then Plan.Reassemble
      else Plan.Hold
  end plan

  /** plan = Assemble/Reassemble 后的新状态（组装完成时刻落账）。 */
  def afterAssemble(now: Long, current: AskFingerprint): AskAssemblyState =
    AskAssemblyState(lastAssembledAt = now, fingerprint = current, pending = false)
end AskCooldown

/**
 * 文件绑定表（跨重启复现的最小持久态）。线程安全：单 Ref + 读写全在 IO。
 * 持久化 = `<dataRoot>/ask/index.json`（AtomicJson 原子写，先例 saveIndex）。
 */
final class AskSessionRegistry(dataRoot: os.Path):
  private val logger = NebflowLogger.forName("nebflow.ask")

  private val file: os.Path = dataRoot / "ask" / "index.json"

  /** 内存权威；启动时懒加载（首个 bind/find 前读盘一次）。 */
  private val bindingsRef =
    val initial = loadFromDisk()
    new java.util.concurrent.atomic.AtomicReference[Map[String, AskBinding]](initial)

  private def loadFromDisk(): Map[String, AskBinding] =
    try
      if !os.exists(file) then Map.empty
      else
        val text = os.read(file)
        io.circe.parser.parse(text) match
          case Right(json) =>
            json.hcursor.downField("bindings").as[List[Json]].toOption match
              case Some(items) =>
                items
                  .flatMap { item =>
                    for
                      id <- item.hcursor.downField("id").as[String].toOption
                      f <- item.hcursor.downField("file").as[String].toOption
                      n <- item.hcursor.downField("fileName").as[String].toOption
                      c <- item.hcursor.downField("createdAt").as[Long].toOption
                      m = item.hcursor.downField("mainSessionId").as[String].toOption.getOrElse("")
                      k = item.hcursor
                        .downField("kind")
                        .as[String]
                        .toOption
                        .filter(AskBinding.KindGitViz == _)
                        .getOrElse(AskBinding.KindFile)
                      device = item.hcursor.downField("device").as[Option[String]].toOption.flatten
                    yield AskBinding(id, f, n, c, m, k, device)
                  }
                  .map(b => b.id -> b)
                  .toMap
              case None => Map.empty
          case Left(_) => Map.empty
        end match
    catch
      case NonFatal(e) =>
        logger.warn(s"ask index load failed (non-fatal, starting empty): ${e.getMessage}")
        Map.empty

  private def persist(bindings: Map[String, AskBinding]): Unit =
    try
      val json = Json.obj(
        "bindings" -> bindings.values.toList
          .sortBy(b => (b.file, b.createdAt))
          .map(b =>
            Json.obj(
              "id" -> b.id.asJson,
              "file" -> b.file.asJson,
              "fileName" -> b.fileName.asJson,
              "createdAt" -> b.createdAt.asJson,
              "mainSessionId" -> b.mainSessionId.asJson,
              "kind" -> b.kind.asJson,
              "device" -> b.device.asJson
            )
          )
          .asJson
      )
      AtomicJson.writeSync(file, json.spaces2)
    catch case NonFatal(e) => logger.warn(s"ask index persist failed (non-fatal): ${e.getMessage}")

  /**
   * 幂等绑定：同文件恒同会话 id（askOpen/askQuestion 前置调用）。
   *
   *  gitviz 分支管理问答批（2026-11-19 第二轮）：`explicitId` 存在时用它当会话键
   *  （前端是 gitviz 域的 id 生成方 —— 确定性键让「同项目重开 = 同一会话」两侧一致），
   *  `kind` 标出绑定域；两者缺省 = 既有文件 ask 语义逐字不变。
   */
  def bind(
    canonicalFile: String,
    fileName: String,
    explicitId: String = "",
    kind: String = AskBinding.KindFile,
    device: Option[String] = None
  ): IO[AskBinding] =
    val id = if explicitId.trim.nonEmpty then explicitId
      else device match
        case Some(remote) => AskSessions.sessionIdFor(s"device:${remote.length}:$remote:$canonicalFile")
        case None => AskSessions.sessionIdFor(canonicalFile)
    IO {
      bindingsRef.get.get(id) match
        case Some(existing) =>
          if existing.file != canonicalFile || existing.device != device then
            throw new IllegalArgumentException(s"ask binding identity conflict for $id: device or path differs")
          // 文件名漂移时刷新显示名，不改绑定身份。
          if existing.fileName != fileName then
            val updated = existing.copy(fileName = fileName)
            bindingsRef.set(bindingsRef.get + (id -> updated))
            persist(bindingsRef.get)
            updated
          else existing
        case None =>
          val fresh = AskBinding(id, canonicalFile, fileName, System.currentTimeMillis(), "", kind, device)
          bindingsRef.set(bindingsRef.get + (id -> fresh))
          persist(bindingsRef.get)
          fresh
    }
  end bind

  def byId(id: String): IO[Option[AskBinding]] = IO(bindingsRef.get.get(id))

  def all: IO[List[AskBinding]] =
    IO(bindingsRef.get.values.toList.sorted(using Ordering.by[AskBinding, String](_.file)))

  /** 转交目标主会话刷新（askOpen 随帧上报；§7 转交与回问的投递根）。 */
  def refreshMainSession(id: String, mainSessionId: String): IO[Unit] =
    IO {
      bindingsRef.get.get(id).foreach { b =>
        if b.mainSessionId != mainSessionId then
          bindingsRef.set(bindingsRef.get + (id -> b.copy(mainSessionId = mainSessionId)))
          persist(bindingsRef.get)
      }
    }

  /** 重组调度状态（内存态，每会话一份）。 */
  private val assemblyRef =
    new java.util.concurrent.atomic.AtomicReference[Map[String, AskAssemblyState]](Map.empty)

  def assemblyState(id: String): IO[AskAssemblyState] =
    IO(assemblyRef.get.getOrElse(id, AskAssemblyState()))

  def setAssemblyState(id: String, st: AskAssemblyState): IO[Unit] =
    IO(assemblyRef.set(assemblyRef.get + (id -> st)))

  /** 触发源置待重组（§3.3：记忆/文件/图谱任一变化；连续触发合并）。 */
  def markDirty(id: String): IO[Unit] =
    IO {
      assemblyRef.get.get(id) match
        case Some(st) if !st.pending => assemblyRef.set(assemblyRef.get + (id -> st.copy(pending = true)))
        case None => assemblyRef.set(assemblyRef.get + (id -> AskAssemblyState(pending = true)))
        case _ => () // 已 pending：合并
    }
end AskSessionRegistry
