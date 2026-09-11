package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject

import nebflow.core.PathUtil
import nebflow.core.project.{ProjectMemory, ProjectStore}
import nebflow.service.MemoryStore

/**
 * MemoryEdit（**保名换语义**，记忆改造批 2026-09-12，spec §5 R2 O-A）——记忆变更的
 * **记账**工具：四 action 语义不变，落点从「记忆文件」改为 append-only 队列
 * `<dataRoot>/memory/queue.jsonl`（[[MemoryQueue]]）。写盘时机 = **下一次上下文压缩**
 * 的记忆整理轨（[[nebflow.agent.MemoryTrack]] 起的 `memory-consolidator` 会话），
 * 本工具**零落盘**。
 *
 * 为什么保名换语义（而不是改名 `MemoryQueue`）：改名牵动 ≥4 处符号 + 既有测试
 * （`AgentCore.NebulaExclusiveTools` / `DreamAdmittedTools` / `registry` 映射 /
 * 本工具内身份串）——「工具名即权限边界」的既有先例（TeamTaskTools）要求名字稳定；
 * 名字与语义的短期错位由 description + 返回值文本兜底（spec §5 R2 O-A 理由）。
 *
 * 接口语义（四 action 沿用 §C.2 + project-memory 批 2026-09-05）：
 *   target : "user" | "agent" | "project:<name>"
 *            → ~/.nebflow/User.md | ~/.nebflow/agents/Nebula/memory.md
 *            | <注册表解析的项目 workspace>/.nebflow/memory.md
 *   action : "append" | "update" | "remove" | "replace_section"
 *   section?  — ## 标题精确匹配；append 缺省 = 文件尾
 *   match?    — update/remove 定位：条目内精确子串（首个命中）
 *   content?  — append/update 的新文本（条目级，不是整文件）
 *
 * 记账期校验（本工具职责）：target 合法性（与 `resolveTarget` 同规同值域：user /
 * agent / `project:<name>`，未知项目 = 拒收，**禁回落 user、禁猜**）、动作名、
 * 必填参数、条目格式（append/update 的单条目纪律）。**不校验** section/match 在
 * 文件中的存在性——记的是意图，定位在**应用时**做（定位不到 ⇒ 消费者记
 * `obsolete`，spec §5 R8 表「队列与记忆文件双份真相」）。
 *
 * 路径安全（H-1 ①）：schema 无任何路径参数；`project:<name>` 经项目注册表解析，
 * 项目名校验同规（禁 / \ . .. 空名），路径**永不来自参数**。
 *
 * 预算 / 快照：这两道闸随写入权一起移交给应用侧——预算写侧强制由整理 agent 在
 * 改文件时依 `MemoryBudget` 判定（`replace_section` 仍是唯一收缩通道）；写前快照
 * 由整理 agent 按「动笔前手动快照」纪律执行（三处记忆文件）。本工具**不再**快照
 * （本工具不落盘，无可回滚对象）；机械可回滚锚 = `memory-backups/` + 变更史
 * （`<dataRoot>/memory/history.jsonl`，[[MemoryHistory]]）。
 *
 * dream 受限准入（2026-09-05 作者签准）在本工具**原样保留**：身份 `dream` 的
 * `append` 调用一律拒（DREAM_APPEND_DENIED）。**已知后果（如实登记）**：压缩前置
 * hook 侧的 Dream 抽取按 spec §5 R7(4) O-A 改由**引擎直接入队**
 * （`source.trigger="dream"`），不经本工具 ⇒ 动作面禁令在该链上不生效；执行者
 * （整理 agent）按三问准入自行裁量。这是 R7(4) 选定形态的直接推论，非本批新增口子。
 */
object MemoryEditTool extends Tool:

  val name = "MemoryEdit"

  /** 合法动作（唯一值域；schema enum 同源）。 */
  private val Actions = List("append", "update", "remove", "replace_section")

  // `def` + s-interpolation（home 硬编码 → 运行时动态化批 2026-09-11）：目标文件
  // 路径走 PathUtil.dataRootRenderValue —— 默认 home ⇒ `~/.nebflow/...`，隔离实例
  // ⇒ 该实例 home 的绝对路径。`def` on purpose：dataRoot 可在对象初始化后被换根。
  def description =
    s"""Record a memory-change request into the append-only queue. NOTHING is written to memory files by this tool: entries are applied at the next context compaction by the memory-consolidation agent. The return value always states what was queued — never claim or assume the change is live.
## Targets (no path parameter exists — targets are names resolved to fixed files)
- target=user → ${PathUtil.dataRootRenderValue}/User.md — user facts: identity, preferences, working style, environment.
- target=agent → ${PathUtil.dataRootRenderValue}/agents/Nebula/memory.md — routing experience, technical lessons, domain knowledge.
- target=project:<name> → `<workspace>/.nebflow/memory.md` of the REGISTERED project `<name>` — project state, progress, conventions. Resolved via the project registry; unknown project → error (list projects first). Project memory is injected into that project's dispatcher and node contexts — NOT into your global system prompt.
- `target` is REQUIRED and is the only routing carrier. It is never defaulted: a missing/invalid target rejects the whole request (MEMORYEDIT_TARGET) rather than silently landing in the wrong layer.
## Actions (recorded now, executed at the next compaction)
- append: add an entry at the end of `section` (omit `section` = end of file). `content` required.
- update: locate the FIRST entry containing `match` (exact substring; scope to `section` when given) and replace it with `content`. Both required.
- remove: locate the same way and delete the entry. `match` required.
- replace_section: replace the ENTIRE body of `section` with `content` (bulk cleanup — use instead of many removes). Both required.
## Semantics
- Entries are markdown list lines ("- ..."); convention: `- <fact>（→<id> detail at ${PathUtil.dataRootRenderValue}/memory/<id>.md）`.
- `section` matches a "## Heading" line exactly (the "## " prefix is optional in the parameter).
- No whole-file rewrite exists by design — memory cannot be wiped in one call.
- Recording validates the target, the action, the required parameters and the entry format. It does NOT check that `section`/`match` exist in the file today: the queue carries intent, the executor locates at apply time, and a miss is reported back as `obsolete` (never silently dropped).
- Identity: Nebula may record all four actions. dream is admitted for revision actions only (remove/update/replace_section) — append is denied (DREAM_APPEND_DENIED): dream must not create new memories (2026-09-05 author-approved iron rule, enforced at this tool's dispatch layer).
- append/update `content` must be ONE entry: a single line starting with "- ". Multi-line content is rejected (MEMORYEDIT_ENTRY_FORMAT). Use replace_section for a multi-line section body.
## Queue
- Ledger: ${PathUtil.dataRootRenderValue}/memory/queue.jsonl (append-only JSONL; `note` = queued request, `outcome` = consumer's verdict: applied / modified / rejected / obsolete / deduped / timeout). Change history: ${PathUtil.dataRootRenderValue}/memory/history.jsonl.
- Idempotent: an identical request (same target+action+section+match+content) already pending returns the EXISTING q-id and appends nothing.
- Capacity: at most 500 pending notes; beyond that the oldest (by atMs) are dropped and recorded in a `drop` line with their ids — drops are never silent.
- Consumption is the memory-consolidation agent's job (it runs on compaction, reads the queue and the three memory files, and writes an `outcome` per note). Do not re-record a note you can see pending.
## Budget & snapshot (enforced at apply time, not here)
- Write-side budget enforcement moved with the write: the executor checks the POST-WRITE file size before saving (hard caps: User.md 50KB, agent memory.md 30KB, project memory.md 10KB) and must consolidate first when over. replace_section stays exempt — it is the shrinking channel.
- Snapshot: the executor snapshots the memory files before writing (manual snapshot discipline). This tool performs no snapshot because it writes nothing; rollback anchors are ${PathUtil.dataRootRenderValue}/memory-backups/<ts>/ plus the change history above.
- Injection is NEVER truncated (ruling 2026-09-05 §3.3): the write-side gate is the only enforcement, so an over-budget memory taxes every future session."""

  def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "target" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> s"Memory target (REQUIRED): \"user\" → ${PathUtil.dataRootRenderValue}/User.md; \"agent\" → ${PathUtil.dataRootRenderValue}/agents/Nebula/memory.md; \"project:<name>\" → <workspace>/.nebflow/memory.md of registered project <name>. Missing/invalid → the request is rejected (no default).".asJson
      ),
      "action" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("append".asJson, "update".asJson, "remove".asJson, "replace_section".asJson),
        "description" -> "append / update / remove / replace_section (see description). Recorded now, executed at the next compaction.".asJson
      ),
      "section" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "## Heading exact match ('## ' prefix optional). append: insert at this section's end (omit = file end). update/remove: scope the match search. replace_section: the section to replace (required).".asJson
      ),
      "match" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "update/remove locator: exact substring inside a list entry (first hit wins). Required for update/remove.".asJson
      ),
      "content" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "New text (entry-level). Required for append/update/replace_section.".asJson
      )
    ),
    "required" -> Json.arr("target".asJson, "action".asJson)
  )

  def summarize(input: JsonObject): String =
    val t = input("target").flatMap(_.asString).getOrElse("?")
    val a = input("action").flatMap(_.asString).getOrElse("?")
    s"MemoryEdit($t/$a)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 300 then result.take(297) + "..." else result

  // ------------------------------------------------------------------
  // Target 白名单（值域与 spec §3.5.1 三层映射同源；本工具只做**校验 + 展示路径**）
  // ------------------------------------------------------------------

  /** 校验通过的 target：`id` = 历史/结果文本里的目标标识（= 入队 note 的 `target`
    * 字段值域 "user" | "agent" | "project:<name>"）；`path` = 实际记忆文件（仅供
    * 结果文本与变更史展示——本工具不写它）。 */
  private case class Target(id: String, path: os.Path)

  private def validateTarget(target: String): Either[ToolError, Target] =
    target match
      case "user"  => Right(Target("user", MemoryStore.userMemoryPath))
      case "agent" => Right(Target("agent", MemoryStore.agentMemoryPath("Nebula")))
      case p if p.startsWith("project:") =>
        val projectName = p.stripPrefix("project:")
        // 项目名校验与注册表同规（ProjectStore：禁 / \ . .. 空名——路径穿越在
        // 名字层面即断，路径本身永远来自注册表而非参数）
        if projectName.isEmpty || projectName.contains("/") || projectName.contains("\\") ||
          projectName == "." || projectName == ".."
        then
          Left(ToolError(
            s"MemoryEdit: invalid project name '$projectName' in target '$target'. (MEMORYEDIT_TARGET)"))
        else
          ProjectStore.load(projectName).unsafeRunSync() match
            case None =>
              Left(ToolError(
                s"""MemoryEdit: unknown project '$projectName' — target=project:<name> resolves via the project registry (${PathUtil.dataRootRenderValue}/projects/<name>/project.json). Check the project name (list projects first). (MEMORYEDIT_TARGET)"""))
            case Some(pd) =>
              Right(Target(p, ProjectMemory.path(pd.workspace)))
      case "" =>
        Left(ToolError(
          s"""MemoryEdit: `target` is REQUIRED and is the only routing carrier — it is never defaulted to "user" (silently landing project state in the global layer is worse than losing a note). Legal forms: "user" (${PathUtil.dataRootRenderValue}/User.md), "agent" (${PathUtil.dataRootRenderValue}/agents/Nebula/memory.md), "project:<name>" (registered project's <workspace>/.nebflow/memory.md). (MEMORYEDIT_TARGET)"""))
      case other =>
        Left(ToolError(
          s"""MemoryEdit: unknown target '$other' — legal forms: "user" (${PathUtil.dataRootRenderValue}/User.md), "agent" (${PathUtil.dataRootRenderValue}/agents/Nebula/memory.md), "project:<name>" (registered project's <workspace>/.nebflow/memory.md). (MEMORYEDIT_TARGET)"""))

  // ------------------------------------------------------------------
  // 条目模型（行模型函数保留：条目纪律与消费者侧同一套判据）
  // ------------------------------------------------------------------

  private def normalizeContent(content: String): Vector[String] =
    content.trim.linesIterator.toVector

  /** `section` 归一：`## ` 前缀可选（与旧直写路径 `canonicalSection` 同规）——
    * 队列里只存规范化后的节名，消费侧按同名匹配。 */
  private def canonicalSection(section: String): String =
    section.trim.stripPrefix("## ").trim

  /** 条目模型防漂移（QC nit 6）：append/update 的 content 必须是「单条目」——恰一行
    * 且以 "- " 开头。多行 content 会被写进文件而 locate 永远扫不到（只认条目行）
    * ——在入口拒绝并给可行动出路。replace_section 按设计允许多行区段体，不走此校验。 */
  private def singleEntryGuard(action: String, content: String): Option[ToolError] =
    val ls = normalizeContent(content)
    if ls.lengthIs > 1 then Some(ToolError(
      s"""MemoryEdit: $action content must be ONE entry line, got ${ls.length} lines — multi-line content drifts out of the entry model (locate scans "- " lines only; extra lines would be invisible to update/remove). Use replace_section for a multi-line section body, or record each entry separately. (MEMORYEDIT_ENTRY_FORMAT)"""))
    else if !ls.headOption.exists(_.startsWith("- ")) then Some(ToolError(
      s"""MemoryEdit: $action content must start with "- " (markdown list entry); got "${content.trim.take(60)}" — non-entry text is invisible to update/remove. (MEMORYEDIT_ENTRY_FORMAT)"""))
    else None

  // ------------------------------------------------------------------
  // call：校验 → 入队（记账）
  // ------------------------------------------------------------------

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val target   = input("target").flatMap(_.asString).getOrElse("")
      val action   = input("action").flatMap(_.asString).getOrElse("")
      val section  = input("section").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
      val matchStr = input("match").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
      val content  = input("content").flatMap(_.asString)

      // 校验次序（全部先于任何文件系统动作，含 project 目标的注册表读）：
      // 动作名 → 必填参数 → 条目格式 → 身份（dream）→ target 值域。
      val actionError: Option[ToolError] =
        if !Actions.contains(action) then
          Some(ToolError(
            s"MemoryEdit: unknown action '$action' — one of append/update/remove/replace_section. (MEMORYEDIT_ACTION)"))
        else None

      val paramError: Option[ToolError] = actionError.orElse {
        (action, section, matchStr, content) match
          case ("append", _, _, None) =>
            Some(ToolError("MemoryEdit: append requires `content` (the entry to add). (MEMORYEDIT_PARAM)"))
          case ("update", _, None, _) | ("update", _, _, None) =>
            Some(ToolError("MemoryEdit: update requires `match` (locator) and `content` (replacement). (MEMORYEDIT_PARAM)"))
          case ("remove", _, None, _) =>
            Some(ToolError("MemoryEdit: remove requires `match` (locator). (MEMORYEDIT_PARAM)"))
          case ("replace_section", None, _, _) | ("replace_section", _, _, None) =>
            Some(ToolError("MemoryEdit: replace_section requires `section` and `content` (full new body). (MEMORYEDIT_PARAM)"))
          case _ => None
      }

      val entryFormatError: Option[ToolError] = paramError.orElse {
        (action, content) match
          case ("append", Some(c)) => singleEntryGuard("append", c)
          case ("update", Some(c)) => singleEntryGuard("update", c)
          case _                   => None
      }

      // dream 动作面白名单（2026-09-05 作者签准，修订 2026-08-31 裁定①）：
      // identity==dream 仅放行 remove/update/replace_section，append 一律拒绝。
      // 拦截点 = 最早处：被拒不触 target 校验（project 目标的注册表读）/入队。
      // 身份来源 = ctx.agentDef（AgentCore 工具执行链注入）；None（REST 直调 /
      // spec harness）非 dream，行为零变化。禁用全局状态猜身份。
      val dreamDenied: Option[ToolError] = entryFormatError.orElse {
        if ctx.agentDef.exists(_.name == "dream") && action == "append" then
          Some(ToolError(
            "MemoryEdit: dream is admitted for revision actions only (remove/update/replace_section) — " +
              "append is denied: dream 禁写新记忆（2026-09-05 作者签准铁律，工具面强制）。" +
              "Revise existing entries via update/remove/replace_section; report new-memory candidates to Nebula instead. " +
              "(DREAM_APPEND_DENIED)"))
        else None
      }

      val outcome: Either[ToolError, String] = dreamDenied match
        case Some(err) => Left(err)
        case None =>
          validateTarget(target).flatMap { t =>
            val sessionId = ctx.sessionId
            val trigger   = MemoryQueue.TriggerManual
            MemoryQueue.enqueue(
              target = t.id,
              action = action,
              section = section.map(canonicalSection).filter(_.nonEmpty),
              matchText = matchStr,
              content = content.map(_.trim).filter(_.nonEmpty),
              sessionId = sessionId,
              trigger = trigger,
              actor = MemoryHistory.actorOf(ctx)
            ) match
              case Left(reason) =>
                Left(ToolError(
                  s"""MemoryEdit: nothing was recorded — the queue append failed ($reason). The request is unchanged and was NOT applied; fix the queue file (${PathUtil.dataRootRenderValue}/memory/queue.jsonl) permissions/space and retry. (MEMORYEDIT_QUEUE)"""))
              case Right(res) => Right(queuedText(t, action, section, matchStr, content, res))
          }
      outcome
    }

  /** 结果文本：**只说记账**（`queued q-… (applied at next compaction)`），不得回显
    * 「已写入」（spec §5 R2 O-A / R8 表「禁静默丢弃」配套口径）。 */
  private def queuedText(
    t: Target,
    action: String,
    section: Option[String],
    matchStr: Option[String],
    content: Option[String],
    res: MemoryQueue.EnqueueResult
  ): String =
    val scope = section.map(s => s" section='$s'").getOrElse("")
    val loc   = matchStr.map(m => s""" match="$m"""").getOrElse("")
    val detail = action match
      case "append"          => s"+ ${content.getOrElse("").trim.take(200)}"
      case "update"          => s"replace first entry matching${loc}${scope} with ${content.getOrElse("").trim.take(200)}"
      case "remove"          => s"delete first entry matching${loc}${scope}"
      case "replace_section" => s"replace whole body of section '${section.getOrElse("")}'"
      case other             => other
    val head =
      if res.deduped then
        s"""MemoryEdit queued ${res.id} (applied at next compaction) — identical request already pending; nothing new was recorded (deduped)."""
      else s"""MemoryEdit queued ${res.id} (applied at next compaction)."""
    val lines = List(
      head,
      s"target=${t.id} action=$action → ${t.path} (NOT written yet)",
      detail,
      s"pending: ${res.pending} note(s) in ${PathUtil.dataRootRenderValue}/memory/queue.jsonl" +
        (if res.dropped > 0 then s" — capacity cap reached, oldest ${res.dropped} note(s) dropped (recorded in a drop line, never silent)" else ""),
      "The memory-consolidation agent applies the queue at the next context compaction; expect the change in memory only after that.",
      "Do not re-record the same entry — see `pending` above."
    )
    val histNote = if res.historyNote.nonEmpty then s"\nNOTE: ${res.historyNote}." else ""
    lines.mkString("\n") + histNote

end MemoryEditTool
