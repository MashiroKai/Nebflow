package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.*
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.node.NodeRunner
import nebflow.core.project.*
import nebflow.shared.*

/**
 * DelegateTool —— Nebula 唯一的派发入口（unified-delegate 批 2026-10-03）。
 *
 * 大改后的形态（作者令）：**只填 `task` 与 `project`（选填）**——
 *   - 执行器 = 主 agent 动态选择（external-executors 批 #1225，S14 运行时半）：
 *     可选 `executor` 参数为唯一显式选择输入，缺省内置 `nebflow`；可用集 =
 *     [[ExecutorRegistry]] 检测面（GET /api/executors 快照）；不可用的显式
 *     选择 = 显式报错——不静默回落。
 *   - 工作目录 = `project` 挂载项目的 workspace；缺省 = 默认工作目录
 *     `<dataRoot>/general/`（首次派发现建）。
 *   - 台账 = 每次派发在 TaskLedger 落一条（task #N 随回执返回，地址面与
 *     任务列表共用这个号码）。
 *   - 地址 = `delegate:<id>`（[[DelegateRegistry]] 持久记录）——Mail 续聊、
 *     AgentControl 地址列、任务列表的唯一地址形态。旧 `kernel:<id>` 面
 *     由 MailTool 侧 tombstone（同一批）。
 *
 * 语义保留：后台一次性执行、结果以 `source="delegate"` 系统消息回投父会话、
 * BackoffSupervisor 拥有崩溃重启与 3600s 预算（DelegateBudget）、无并发上限
 * （2026-09-26 作者裁定，退休注释保留在 spawnBackground）。
 */
object DelegateTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** 内置执行器 spawn 的 agent 定义名（builtin-merge 批：kernel+general ⇒ nebflow）。 */
  val KernelAgentName = nebflow.core.entity.BuiltinAgents.ExecutorName

  /** 默认工作目录名（`project` 缺省时的工作座位）。 */
  val DefaultWorkspaceName = "general"

  /** Sub-agent 深度上限（与 `AgentCore.MaxDepth` 同值；执行会话是叶子）。 */
  val MaxDepth: Int = 5

  /**
   * brief 申报纪律行（提示词来源拆分批 2026-10-08 上提为公开常量——上提只为
   * 判据门可钉：`PromptFaceSplitSpec` 断言本行 = delegate_report 纪律且模型可见面
   * 零 node_report；文本内容 = 原 spawnWithPlugins 局部 `reportLine`，#566 起两态：
   * blocked 类别退役——报不了的腿照常 finish，阻塞事实写进终稿）。
   * S7/S8「node 同款提醒（防忘记调用）」的最小形态——delegate 常为单 turn 会话，
   * 提醒落在 brief 而非中途 ladder；状态矩阵批 2026-11-01 起「未申报」不再是
   * 静默放行——引擎以 system reminder 提醒补报（standby 或 finish），finish 是
   * 引擎唯一可判完成的申报。
   */
  val BriefReportLine: String =
    """[report] when your work reaches its end state, call delegate_report(category="finish"|"merge"|"standby", detail=…) right before your final text — "merge" = your deliverable commits sit on your working branch and need the merge queue (you keep working until the merge reminder arrives); "finish" = the TASK is definitely complete (not merely "this reporting turn is over"): pure read / no file changes, the merge has landed, or the task genuinely cannot be completed — state the blocker plainly in your final text; there is no separate blocked state. finish is REFUSED while your own scope map still holds any non-terminal node (running / pending / wiring) — you get a system reminder listing them with two ways out: cancel them (NodeCancel / NodeEdit abandon) or go standby until they finish, then report finish; "standby" = you are waiting for orders/events and NOT done (the turn ends with no terminal verdict and no delivery to the dispatcher — the task row stays running, your session parks in the explicit standby state until the next Mail wakes you; refused while unmerged deliverable commits sit on the branch — merge obligations come first). ALWAYS declare before ending a turn: an undeclared turn end is not judged complete — the engine reminds you to declare instead. The report updates your status record; your final text remains the deliverable."""

  /**
   * D12-b reset 警示行（delegate reset 零闸治理，作者裁定 2026-10-23）：就地腿
   * delegate 与根会话/人/其他会话共享检出区座位，任意一方一条 reset 系命令即
   * 清掉**未持久化**的进行中工作（auto-all 顶档下危险命令清单是死信——闸面
   * 不拦，只审计 + 本警示行，消灭「无人知晓」）。brief 头部位形：工作座行之后、
   * [[BriefReportLine]] 之前——单 turn 会话的第一屏即见（[[assembleBrief]]
   * 布局钉由 spec 承重）。
   */
  val ResetHazardLine: String =
    "[reset hazard] This checkout is shared with other live sessions (root, other delegates) — any party's `git reset --hard` / `checkout .` / `clean -f` / `branch -D` instantly discards every session's unpersisted in-progress work here (each one is written to the audit log). Work already committed or otherwise persisted to disk is unaffected. Never run reset-family commands in this workspace."

  val name = "Delegate"

  // 模型可见文案（executor 选择反转——S14 运行时半，external-executors 批
  // #1225 2026-10-06）。⚠ §16 待过目：本描述段为最小改写（终稿归 N3 ⑭），
  // 语义 = 「executor 由主 agent 动态选择；缺省 nebflow」（增补任务书 3.1）。
  val description =
    """Delegate a task to a background executor — the built-in Nebflow agent (default) or an external CLI agent installed on this machine. It runs in the background and its result is delivered back to your session as a system message when it finishes.

**Parameters:**
- `task` (required): self-contained brief — goal, constraints, absolute paths, exact commands/limits, and what "done" looks like. The executor starts with a clean context; the brief is its entire world.
- `name` (optional): a short name for this batch — at most 12 characters, summarizing it in a glance (e.g. "液体玻璃批", "ask-mode修复"). It shows at the front of the batch's row in the background-agents panel, so the user can tell batches apart at a glance. Omit it and the panel falls back to the truncated task title. Longer values are clamped to 12 characters.
- `project` (optional): name of a mounted project — the task then runs in that project's workspace. Omit it to run in the default general workspace.
- `executor` (optional): which executor runs the task. You pick it dynamically per task; omit it for the built-in default (`nebflow`). The available set (with detected external CLIs) is listed in your "Available executors" prompt section. An unavailable pick fails explicitly — there is no silent fallback.
- `mode` (optional): the execution form, and YOU pick it from the task context — no allocator and no model call decides this any more. `kernel` (the default when you omit the parameter) = minimal single-session executor with no orchestration tools; `dispatcher` = chain-orchestrating dispatcher (composes a per-delegate flow map via ChainWrite/NodeEdit, gets zero plugins itself, and its chain nodes run inside the dispatcher's seat and never create their own worktrees). Pass `dispatcher` yourself whenever the task is a multi-node chain. `auto` is REJECTED explicitly (the allocator was removed) — an omitted `mode` deterministically means `kernel`. Mode is a spawn-time lifetime property: changing it means re-dispatch.
- `attachments` (optional): an array of ABSOLUTE paths of files on this machine to hand the delegate — the channel for images/documents the author gave you, so their content never has to be re-typed into the brief. Max **9 files** per dispatch, each up to **1024 MB = 1 GiB (1,073,741,824 bytes)**; the caps are the same authored numbers as Mail/FriendMessage (`AttachContract`) and exceeding them is an EXPLICIT error, never a silent truncation. Validation happens at dispatch time — a relative path, a missing path, a non-regular file (directory/device/pipe), an unreadable file or an over-limit file FAILS THE WHOLE DISPATCH and names the offending path; nothing is spawned. Every accepted file is copied into a stable per-dispatch directory (`<worktree>/.nebflow/attachments/<taskId>/` when the delegate sits in a worktree, otherwise `<dataRoot>/delegate-attachments/<taskId>/`) with its sha256 verified against the original, so the temporary upload area being cleaned up later cannot lose it — the originals are only READ, never moved or deleted. A `[附件清单 · attachments manifest]` section is then appended to the END of the brief (a fixed paragraph, distinct from your hand-written text; the paths you typed into `task` stay verbatim) listing per file: copy path + original path + MIME/type + image flag + byte count + sha256. Attachment bytes do NOT enter the delegate's context — it must `Read` the listed copy path. Images (PNG/JPG/JPEG/GIF/WEBP/BMP) come back from `Read` as vision-ready image content, so the delegate sees them directly; a single image above the 10 MiB Read vision cap is refused explicitly by `Read`, and the manifest says so — the delegate is told to report that instead of pretending. Cross-device (a project that executes on another device) is REFUSED: those paths live on this machine's disk and that delegate could never open them.
- `worktree` (optional): the NAME of the delegate worktree this task runs in. **A task that writes files MUST pass it** — the value is the worktree name: `<worktreeRoot>/<name>` is created on branch `delegate/<name>` when that name does not exist yet, and the SAME tree is reused (never a second directory) when it does. **Omit it ONLY for read-only tasks** (pure search / audit / read-only verification / a report that does not change the repo): with no name no worktree is allocated at all and the delegate runs IN PLACE in the project workspace — it then shares that checkout with you and every other live session, so a write-task that omits the name will collide (two agents, one tree; the engine flags an in-place task that writes when it finishes). Parallel work must give each delegate its own name. Legacy targets still resolve as a second chance (an owning delegate id or an absolute worktree path) and the dispatch receipt states which rule hit.
- `force` (optional): only meaningful together with `worktree` — override the refusal to reuse a tree that is currently held by a live session. Two agents editing one tree is unsafe — pass true ONLY when you are certain the live session is not touching those files.

**Result and continuation address:** the tool result is an ACK carrying the task number (`task #N`) and the continuation address `delegate:<id>`. To send the instance more input (supplements, corrections), use `Mail(address="delegate:<id>", message=...)` — a running instance gets it queued for its next continuation turn; a finished one is continued as a new turn. Do not start a second Delegate for the same work.

**Rules:**
- Do NOT duplicate the delegate's work — work on something else and let the result arrive as a system message.
- The ack is not the result.
- Landing discipline (KG audit ①, author ruling 2026-11-03): when the task produces knowledge documents (designs, audits, research, conclusions), state in the brief that they land in the project's REGISTRATION dir vault `<dataRoot>/projects/<project>/.stand/docs/` — next to `project.json`, NOT in the project workspace repo's `docs/` (Obsidian format: frontmatter + ≥1 wikilink); `reports/` is for pure process files (batch run reports) only. Validation command for the closing report: `python3 <dataRoot>/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --project <project>`.
- **Limits:** No hard concurrency limit — in-flight delegates run concurrently.
- **Safety**: NEVER send signals to or kill any sbt/java/nebflow process — you run inside a Nebflow instance; killing it kills you and the user's session. Process inspection with `ps` (read-only) is fine."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "task" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task brief: goal, constraints, absolute paths, exact commands/limits, what \"done\" looks like. The executor starts with a clean context and no conversation history.".asJson
        ),
        "name" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "maxLength" -> 12.asJson,
          "description" -> "Optional short batch name (≤12 chars) summarizing this dispatch in a glance — shown at the front of the batch's row in the background-agents panel so batches are distinguishable at a glance (e.g. \"液体玻璃批\"). Omit to fall back to the truncated task title; longer values are clamped to 12 characters.".asJson
        ),
        "project" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional mounted project name — the task runs in that project's workspace. Omit for the default general workspace.".asJson
        ),
        "executor" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional executor id — you pick dynamically per task (see the \"Available executors\" section). Omit for the built-in default \"nebflow\". An unavailable id fails explicitly; there is no silent fallback.".asJson
        ),
        "worktree" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional: the NAME of the delegate worktree this task runs in. A task that writes files MUST pass it — the value is the worktree name: <worktreeRoot>/<name> is created on branch delegate/<name> when that name does not exist yet, and the SAME tree is reused (never a second directory) when it does. Omit it ONLY for read-only tasks (pure search / audit / read-only verification / a repo-neutral report): with no name NO worktree is allocated and the delegate runs IN PLACE in the project workspace, sharing that checkout with you and every other live session — a write-task that omits the name collides (two agents, one tree), and the engine flags an in-place task that wrote anything when it finishes. Parallel work must give each delegate its own name. Legacy targets (an owning delegate id or an absolute worktree path) still resolve as a second chance; the receipt states which rule hit.".asJson
        ),
        "force" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Optional (default false): only meaningful with the worktree parameter — override the refusal to reuse a worktree that is currently held by a live session. Two agents editing one tree is unsafe — pass true ONLY when you are certain the live session is not touching those files (e.g. it is parked/idle on a different file set).".asJson
        ),
        "mode" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("kernel".asJson, "dispatcher".asJson),
          "description" -> "Optional execution form — YOU pick it from the task context; no allocator and no model call decides it. \"kernel\" (the default when omitted) = minimal single-session executor (no orchestration tools); \"dispatcher\" = chain-orchestrating dispatcher (composes a per-delegate flow map via ChainWrite/NodeEdit, gets zero plugins itself, and its chain nodes work inside the dispatcher's seat and never create their own worktrees). Omit it and the mode is deterministically \"kernel\". \"auto\" is rejected explicitly — the mode allocator has been removed; pass kernel or dispatcher directly. Mode is a spawn-time lifetime property — changing it means re-dispatch.".asJson
        ),
        "attachments" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "items" -> io.circe.Json.obj("type" -> "string".asJson),
          "maxItems" -> nebflow.shared.AttachContract.MaxAttachmentsPerMessage.asJson,
          "description" -> ("Optional array of ABSOLUTE paths of files on this machine to hand the delegate (e.g. the images the " +
            "author gave you) — the delegate reads them from disk; their bytes never enter YOUR context either. " +
            s"Limits: at most ${nebflow.shared.AttachContract.MaxAttachmentsPerMessage} files, each up to " +
            s"${nebflow.shared.AttachContract.MaxFileBytesLabel} — exceeding either is an explicit error, never a silent " +
            "truncation. Validated at dispatch time: a relative path / missing path / non-regular file / unreadable file / " +
            "over-limit file FAILS the whole dispatch and names the path (nothing is spawned). Each accepted file is copied to " +
            "a stable per-dispatch directory (<worktree>/.nebflow/attachments/<taskId>/ in a worktree, otherwise " +
            "<dataRoot>/delegate-attachments/<taskId>/) with sha256 verified against the original; the original is only read, " +
            "never moved or deleted. A \"[附件清单 · attachments manifest]\" paragraph is appended at the END of the brief " +
            "(per file: copy path + original path + MIME + image flag + bytes + sha256; hand-written paths in `task` stay " +
            "verbatim). Images are visible to the delegate through Read (vision-ready); an image above the 10 MiB Read cap is " +
            "refused explicitly and the manifest says so. Refused for projects that execute on another device (those paths " +
            "are not readable there).").asJson
        )
      ),
      "required" -> io.circe.Json.arr("task".asJson)
    )
  )

  /** UI 标签 = 任务首行截断（schema 已无 description 参数）。 */
  private[tools] def labelOf(task: String): String =
    task.linesIterator.nextOption().getOrElse("delegate").trim match
      case "" => "delegate"
      case s => if s.length > 60 then s.take(57) + "..." else s

  /**
   * 批次短名解析（后台任务面板层级/命名/版式批 2026-10-31 · 作者令②）：Delegate
   * 工具 `name` 参数——≤12 字的扫读名（面板 delegate 组头行首显示）。trim 后空 =
   * None（回落既有标题截断显示）；超 12 字钳位截断（schema maxLength 是模型面约束，
   * 引擎侧再钳一次——REST/旧调用面可能绕开 schema）。
   */
  private[tools] def shortNameOf(input: JsonObject): Option[String] =
    input("name")
      .flatMap(_.asString)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(n => if n.length > DelegateRegistry.ShortNameMaxChars then n.take(DelegateRegistry.ShortNameMaxChars) else n)

  /**
   * delegate brief 装配单点（sleep-poll 禁令批 C 段抽纯函数——spec 判红锚）。
   * 布局：工作座行（+ 绑定行）→ reset 警示行（D12-b）→ delegate_report 纪律行
   * （[[BriefReportLine]]）→ 等待纪律铁律（[[Defaults.NoSleepPollRule]]）→
   * 插件能力查询行（[[Defaults.PluginDiscoveryLine]]，stand-plugins 批）→
   * output 块纪律行（[[Defaults.OutputBlockRule]]，作者令 2026-10-31）→
   * 空行 → 任务全文（→ 插件注入块）。
   * 铁律置于任务正文之前：单 turn 会话的第一屏即见（747cd36a 形态的启动面
   * 防线——与其在长轮询后靠 LoopGuard 兜底，不如开局立规）。
   *
   * output 块总闸·提示词面（prompt-face 补批 2026-11-01，作者令「把 output 块
   * 相关的内容先关闭」）：`includeOutputRule=false` ⇒ OutputBlockRule 行整行
   * 不进 brief（含其行首换行，行距字节零残留——plugin 行与任务正文间保持恰一
   * 空行）。生产缺省 = **组装期热读** `nebflow.shared.OutputBlockGate.enabled()`
   * （每次派发求值，翻闸后新派发即翻面）；离线 spec 注入常值，与机器真实配置
   * 解耦（渠道面 outputBlockGate 构造缝同款形态）。
   */
  private[tools] def assembleBrief(
    workspaceLine: String,
    bindingLine: Option[String],
    task: String,
    injectedBlock: String,
    /** 双模式批：dispatcher 腿编排纪律段（None = brief 逐字节不变——spec 判红锚）。 */
    dispatcherDiscipline: Option[String] = None,
    /** output 块总闸（prompt-face 补批）：true = brief 携带 OutputBlockRule 行。 */
    includeOutputRule: Boolean = nebflow.shared.OutputBlockGate.enabled(),
    /**
     * 附件清单段（附件传输批 2026-11-05）：非空 ⇒ 追加在 **brief 末尾**（插件注入块
     * 之后）——固定段落，与作者手写的 `task` 正文**结构可分**（作者写在正文里的路径
     * 逐字保留，本段只追加不改写）。空串 = 本次未带附件 ⇒ brief 逐字节不变。
     */
    attachmentSection: String = ""
  ): String =
    val outputRuleBlock = if includeOutputRule then s"\n${Defaults.OutputBlockRule}" else ""
    val attachmentBlock = if attachmentSection.isEmpty then "" else s"\n\n$attachmentSection"
    s"""$workspaceLine${bindingLine.map(l => s"\n$l").getOrElse("")}
${ResetHazardLine}

${BriefReportLine}

${Defaults.NoSleepPollRule}

${Defaults.PluginDiscoveryLine}${outputRuleBlock}${dispatcherDiscipline.map(l => s"\n\n$l").getOrElse("")}

$task${if injectedBlock.isEmpty then "" else s"\n\n$injectedBlock"}$attachmentBlock"""

  end assembleBrief

  /**
   * 双模式批（设计 §5.1/§5.7）：dispatcher 模式会话的座位纪律段（brief 装配单点
   * 注入；模型可见文案）。**9.19 纯编排口径重写批（2026-11-01 · 作者令）**：
   * 9.19 版 project dispatcher（tag `2026.9.19` 树 `seed/agents/project-dispatcher/
   * system.md`；设计 [[docs/design/20261028_213056_delegate-dual-mode-redesign
   * __delegate-nebflow-192d10a5.zh-CN.md]] §2 考古）的编排纪律 = 拆解/派发/收拢/
   * 推进度，分发器**零亲自动手**——对照发现旧段只讲「编排四件用法」，而两模式
   * 共享的 delegate 面系统提示词（`BuiltinAgents.NebflowDelegatePrompt`）通篇
   * executor 框架（「executor, dispatcher and manager in one」「Simple work →
   * you do it yourself」）在 dispatcher 腿无任何覆盖句 → 框架冲突时基线框架赢
   * （亲自动手怂恿面）。本段重写为五段纯编排纪律，**开场覆盖句显式废黜共享面
   * 的 executor 层**（kernel=自己干 / dispatcher=只编排 的提示词语义分界——
   * 两模式共享同一 system prompt，分界只能由本段单点承载）：
   *  ① 覆盖句 + 零亲自动手（任务性 Write/Edit/Bash = 模式违例；自己的调用只读
   *     图谱/文件/git 事实；「简单活自己干」在本模式读作「简单活=单节点链」）。
   *  ② 拆解（9.19 纪律原文）：一 node = 一个会话能完成的最小单元；task 三要素
   *     （goal+constraints+acceptance）自足；拓扑定量（只读=1 节点；微改=1 节点
   *     实现+自验+落位；多件=并行腿+verify）。
   *  ③ 派发（2026-10-29 作者补令三段逐字保留）：subagent-via-nodes 用法指导
   *     （作者逐字「分发器的Subagent虽然摘除了，但是agentflow是完全可以实现
   *     Subagent的能力的」）；整链一次编写（作者逐字「整链一次编写应该不是
   *     单node的编写吧」——ChainWrite 一次给全链，NodeEdit 只精修）；worktree
   *     精确化（作者裁定 2026-10-29 18:12——本体照走判定机，链节点共享座位）。
   *  ④ 收拢：结果沿 out 边完成通知回流（禁轮询）；到达即跨节点综合（矛盾点名、
   *     证据留路径）；终稿 = 结论先行的 dispatch summary，非节点转录。
   *  ⑤ 推进度：写域相交任务 `deps` 串行（§5.7 纪律层）；失败/补充令 = 先盘点
   *     在飞链（NodeList）再逐点显式处置（9.19 接令纪律）——修链不修活（修理
   *     = 链编辑，绝不亲自上手）。
   * F9/F10 旧钉短语（Subagent gone by design / SELF-CONTAINED / ONE ChainWrite
   * call / verifier+loop / 座位共享 / deps）逐字保留——spec 锚不破。
   */
  val DispatcherDisciplineLine: String =
    """── Dispatcher mode: pure orchestration (this section overrides the executor framing of your seat) ──
You orchestrate a chain; you never execute it yourself. In this mode the executor tier of your seat is OFF: the seat prompt's "simple work → you do it yourself" reads as "simple work → a one-node chain" here, and any Write/Edit/Bash call that performs task work is a mode violation, not a shortcut — your own calls only read the map, the files and git facts.

Decompose: one node = the smallest unit one agent can finish in one session; every node task is SELF-CONTAINED (goal + constraints + acceptance — a node sees only its own task text, none of your context). Size the topology to the work: read-only / analysis = one node; a micro change = one node that implements, self-verifies and lands; multi-part work = parallel legs feeding a verify step before anything lands.

Dispatch: compose the ENTIRE chain in ONE ChainWrite call — multi-node complex chains included; never assemble a chain node-by-node through repeated NodeEdit calls (NodeEdit is for refining an existing chain's edges and parameters, not for building it). Never name plugins and never pass a plugins key: node plugins are allocated automatically per-node from each node's task at chain registration, so the [plugin discovery] line above is query-only for you — you never declare plugins. Your Subagent tool is gone by design: recon/subtask capacity comes through the chain — a single lookup or subtask is a one-node chain (or a node in the chain you are composing). Judgment is verdict-driven: a step needing judgment gets a verifier node with a (fail)<worker>:loop back edge — never re-run blind, and never rule "done" yourself (no self-declared-goal loops).

Collect: end-node results are delivered back to this session as completion notifications — never poll. On arrival synthesize across nodes: name contradictions, keep the evidence (paths + facts), and make your final text the conclusion-first dispatch summary the parent session reads — not a node transcript.

Advance: the chain's nodes all run in THIS session's workspace (inside this session's worktree seat when one was created for it — that is sharing, not nesting); the nodes themselves never create branches or worktrees. Order tasks whose file writes may overlap through `deps` (serialize them); only write-disjoint tasks may run in parallel. On a node failure or a supplement order: inventory the in-flight chain first (NodeList), then dispose of every affected node explicitly — a correction rides Mail(node:<id>) / NodeEdit / NodeCancel, and state is read with NodeList — the repair is a chain edit, never your own hands."""

  def summarize(input: JsonObject): String =
    s"Delegate(${labelOf(input("task").flatMap(_.asString).getOrElse(""))})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  private def routeWsSend(
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String],
    subagentId: String,
    /**
     * 批次短名（面板命名批 2026-10-31）：随 agentStart 注入 delegateName——live 行
     *  即时拿到短名，不等快照回填；None = 无名旧形态逐字。
     */
    delegateName: Option[String] = None
  ): io.circe.Json => IO[Unit] =
    val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    parentSessionId match
      case Some(sid) =>
        json =>
          json.asObject match
            case Some(obj) =>
              // 面板树批（2026-10-29 ①）：agentStart（面板入口帧）注入
              // parentSessionId——前端按它把本子会话缩进挂到父 delegate 行下
              // （快照面 activeAgentEntryJson.parentSessionId 同契约）。仅
              // agentStart 注入：流事件族零载荷膨胀（routeSubagentWsSend 的
              // project/taskId 注入判据同律）。
              val isStart = obj("type").exists(_.asString.contains("agentStart"))
              val withParent =
                if !isStart || obj.contains("parentSessionId") then obj
                else obj.add("parentSessionId", sid.asJson)
              // 面板命名批：同判据注入 delegateName（短名 live 面）。
              val withName =
                if !isStart || delegateName.isEmpty || withParent.contains("delegateName") then withParent
                else withParent.add("delegateName", delegateName.get.asJson)
              // name/address 批（2026-11-05）：agentStart 同判据注入 mailAddress——live
              // 行即时拿到可复制地址，不等快照回填（形态由 MailAddress 单点推导，与快照
              // face / AgentControl list 逐字同源）。只在「有短名 = 本派发路径确为 Delegate
              // 批次」时注入：subtask-/legacy 腿不该被贴上 delegate: 地址。
              val withAddr =
                if !isStart || delegateName.isEmpty || withName.contains("mailAddress") then withName
                else
                  nebflow.core.delegate.MailAddress.of(AgentKind.Delegate, subagentId) match
                    case Some(addr) => withName.add("mailAddress", addr.asJson)
                    case None => withName
              val builder = withAddr.add("rootSessionId", sid.asJson).add("sessionId", sid.asJson)
              val finalObj =
                if obj.contains("nodeSessionId") then builder
                else builder.add("nodeSessionId", subagentId.asJson)
              base(Json.fromJsonObject(finalObj))
            case None => base(json)
      case None =>
        json =>
          json.asObject match
            case Some(obj) => base(Json.fromJsonObject(obj.add("nodeSessionId", subagentId.asJson)))
            case None => base(json)
    end match
  end routeWsSend

  /** 供 BlockingSubagent 复用的路由包装（nodeSessionId 戳 + 根会话索引）。 */
  /**
   * 监督链自动恢复批（2026-10-22）：boot 复活腿（DelegateRevival）同款路由——
   * 可见域从 tools 放宽到本工程（复活腿在 agent 层，路由单点不二写）。
   */
  private[nebflow] def routeWsSendFor(
    subagentId: String,
    parentSessionId: Option[String],
    wsSend: Option[io.circe.Json => IO[Unit]]
  ): io.circe.Json => IO[Unit] = routeWsSend(wsSend, parentSessionId, subagentId)

  /**
   * 工作座位解析（v2.1 §A.3 路由语义，2026-10-09 实施批）：
   *
   *   - `Local`：本机座位——project 缺省（默认 general 目录）或 origin 缺省/self
   *     的挂载项目。**存量语义逐字节**（同一路径、同一 makeDir、同一错误）。
   *   - `RemoteRoute`：project 的 `origin.device` 绑在对端设备 ⇒ 本地 workspace
   *     解析**替换为路由判定**——派活投递给 origin 设备（DeviceExecutor 语义，
   *     [[nebflow.core.delegate.DeviceDelegate.dispatch]]）。模型面零新参数零新
   *     概念（project 名即派生事实，§A.2 理由 1-3）。
   *
   * 挂载项目仍走 [[ProjectRuntimeRegistry.get]]（现行单点）；**remote-origin 项目
   * 不在 host 挂载**（挂载面拒绝——G.12 路径错觉防护），故远端路由的解析顺序 =
   * 运行时注册表 miss ⇒ 项目注册表现读（ProjectStore.load）命中 remote-origin
   * 定义 ⇒ 路由。**未挂载的本机项目仍 = DELEGATE_PROJECT_UNMOUNTED 显式错误不变**
   * （验收 #3）。origin 设备离线/未知 ⇒ `DELEGATE_DEVICE_OFFLINE` 显式失败并列
   * 在线设备，**绝不静默落 host**（G.12）。
   */
  private[tools] sealed trait WorkspaceResolution

  private[tools] object WorkspaceResolution:

    /**
     * 本机座位：workspace（**引擎侧本机座位**——设备项目 = 本机 brain 根）+ 挂载
     * 项目名（缺省派发 = None）+ 会话默认设备上下文（跨设备批 2026-10-25：设备
     * 项目 = (设备, 设备盘 workspace)，六件工具默认路由设备；本机项目 None）。
     */
    final case class Local(
      workspace: String,
      project: Option[String],
      sessionDevice: Option[String] = None,
      sessionDeviceWorkspace: Option[String] = None
    ) extends WorkspaceResolution

    /**
     * 远端路由：origin 设备 + 项目名 + origin 盘 workspace（对端本地直达零取件）。
     * 跨设备批后服务面收窄 = **未挂载**的 remote-origin 定义（DeviceDelegate 整会话
     * 派设备——hook 保留）；已挂载设备项目走 Local（挂载语义，大脑在引擎侧）。
     */
    final case class RemoteRoute(peer: PeerInfo, deviceName: String, project: String, workspace: String)
        extends WorkspaceResolution
  end WorkspaceResolution

  private[tools] def resolveWorkspace(
    project: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, WorkspaceResolution]] =
    project match
      case None =>
        IO.blocking {
          val ws = PathUtil.dataRoot / DefaultWorkspaceName
          os.makeDir.all(ws)
          Right(WorkspaceResolution.Local(ws.toString, None))
        }.handleErrorWith(e => IO.pure(Left(ToolError(s"Delegate: default workspace unavailable: ${e.getMessage}"))))
      case Some(name) =>
        def localOf(rt: ProjectRuntime): IO[Either[ToolError, WorkspaceResolution]] =
          val ws = os.Path(rt.project.workspace)
          IO.blocking(os.makeDir.all(ws)).map(_ => Right(WorkspaceResolution.Local(ws.toString, Some(name))))

        /** 设备显示名解析（deviceId → 名册现查；名册不可用 = 原样 id，防御一致）。 */
        def deviceNameOf(deviceId: String): IO[String] =
          ctx.sharedResources.flatMap(_.neblinkService) match
            case None => IO.pure(deviceId)
            case Some(neblink) =>
              neblink.peers.map(_.find(_.deviceId == deviceId).map(_.deviceName).getOrElse(deviceId))
        ProjectRuntimeRegistry.get(name).flatMap {
          case Some(rt) =>
            // 已挂载项目：
            //   - origin 缺省/self = 现状（本机座位 + makeDir）。
            //   - 绑对端（跨设备批 2026-10-25 语义收口）：**挂载语义** = 引擎侧大脑
            //     ——座位 = 本机 brain 根（[[ProjectStore.brainWorkspace]]，🔴 零
            //     makeDir：设备路径不是本机盘路径），会话默认设备上下文随座位（六件
            //     工具默认路由设备）。不再整会话派设备（那是未挂载 remote-origin 的
            //     DeviceDelegate 腿；挂载面已接管设备项目）。
            rt.project.origin match
              case None => localOf(rt)
              case Some(o) if ProjectOrigin.isSelfLiteral(o.device) => localOf(rt)
              case Some(o) =>
                deviceNameOf(o.device).map { devName =>
                  Right(
                    WorkspaceResolution.Local(
                      nebflow.core.project.ProjectStore.brainWorkspace(rt.project),
                      Some(name),
                      sessionDevice = Some(devName),
                      sessionDeviceWorkspace = Some(o.path)
                    )
                  )
                }
            end match
          case None =>
            // 运行时注册表 miss：查注册表定义（remote-origin 项目不挂载的权威落点）。
            ProjectStore.load(name).flatMap {
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Delegate: project '$name' is not mounted — check the exact name (mounted projects list: TaskInfo / project panel). (DELEGATE_PROJECT_UNMOUNTED)"
                    )
                  )
                )
              case Some(pd) =>
                pd.origin match
                  case Some(o) if !ProjectOrigin.isSelfLiteral(o.device) =>
                    // remote-origin 定义（未挂载是**预期形态**）→ 派生路由（非 UNMOUNTED）。
                    routeRemote(o.device, name, o.path, ctx)
                  case _ =>
                    // 本机定义但未挂载：现行显式错误**逐字不变**。
                    IO.pure(
                      Left(
                        ToolError(
                          s"Delegate: project '$name' is not mounted — check the exact name (mounted projects list: TaskInfo / project panel). (DELEGATE_PROJECT_UNMOUNTED)"
                        )
                      )
                    )
            }
        }
    end match
  end resolveWorkspace

  /**
   * 路由判定腿：origin 设备解析（精确 deviceId 优先 → 名册解析单点）。
   * 离线/未知 = `DELEGATE_DEVICE_OFFLINE` 显式失败（G.12：列在线设备，绝不静默落
   * host——静默回落会制造「以为在 D 的 workspace 跑、实际在 host 跑」的路径错觉）。
   * NebLink 未配置 = 同类显式失败（无设备网络 = 无路由标的）。
   */
  private def routeRemote(
    device: String,
    projectName: String,
    workspace: String,
    ctx: ToolContext
  ): IO[Either[ToolError, WorkspaceResolution]] =
    ctx.sharedResources.flatMap(_.neblinkService) match
      case None =>
        IO.pure(
          Left(
            ToolError(
              s"Delegate: project '$projectName' is bound to device '$device' but NebLink is not configured on this host — " +
                "no device network, no route. (DELEGATE_DEVICE_OFFLINE)"
            )
          )
        )
      case Some(neblink) =>
        neblink.peers.flatMap { peers =>
          val exact = peers.find(_.deviceId == device)
          (exact match
            case Some(p) => Right(p)
            case None => RemoteExecutor.resolvePeer(device, peers)
          ) match
            case Left(_) =>
              val online = peers.map(p => s"${p.deviceName}(${p.deviceId.take(8)})")
              IO.pure(
                Left(
                  ToolError(
                    s"project '$projectName' 的设备 '$device' 不在线（在线设备：${
                        if online.isEmpty then "（无）" else online.mkString(", ")
                      }）。" +
                      "任务不会落回本机执行——等该设备上线后重试。 (DELEGATE_DEVICE_OFFLINE)"
                  )
                )
              )
            case Right(peer) =>
              IO.pure(Right(WorkspaceResolution.RemoteRoute(peer, peer.deviceName, projectName, workspace)))
          end match
        }
    end match
  end routeRemote

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val task = input("task").flatMap(_.asString).getOrElse("")
    val project = input("project").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val description = labelOf(task)
    // 面板命名批（2026-10-31 · 作者令②）：批次短名随三条 spawn 腿落台账记录。
    val shortName = shortNameOf(input)
    // 续用腿（worktree-reuse-selection 批 2026-11-02）：`worktree` = 目标三形态
    // （delegate id / 目录名 / 绝对路径）；`force` = 越过「活会话持有」闸。缺省 =
    // 新建（默认形态，存量语义逐字节）。
    val reuseTarget = input("worktree").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val forceReuse = input("force").flatMap(_.asBoolean).getOrElse(false)

    if task.trim.isEmpty then
      IO.pure(
        Left(
          ToolError(
            "Missing required parameter: task. Give the executor a self-contained brief — goal, constraints, absolute paths, what \"done\" looks like."
          )
        )
      )
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum sub-agent depth ($MaxDepth) reached. Cannot delegate further.")))
    else
      // 附件面（附件传输批 2026-11-05）：**形态 + 全部投递前判据都在这里跑完**——
      // 台账条目、座位树、会话一个都还没建（零副作用）。任一不过 ⇒ 派发失败并指名
      // 路径（[[DelegateAttachments.validate]] 的判据序），绝不带病发出。
      DelegateAttachments.parse(input) match
        case Left(err) => IO.pure(Left(err))
        case Right(rawPaths) =>
          DelegateAttachments.validate(rawPaths).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(attachmentPaths) =>
              dispatchLeg(input, task, project, description, shortName, reuseTarget, forceReuse, attachmentPaths, ctx)
          }
    end if
  end call

  /** 执行器腿分叉（附件判据之后；`attachmentPaths` 已通过全部投递前判据）。 */
  private def dispatchLeg(
    input: JsonObject,
    task: String,
    project: Option[String],
    description: String,
    shortName: Option[String],
    reuseTarget: Option[String],
    forceReuse: Boolean,
    attachmentPaths: List[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // 执行器选择反转（S14 运行时半，#1225）：显式 `executor` 参数是唯一选择
    // 输入，缺省 nebflow；nebflow.json executor 块止读（⑫）。fail-closed：
    // 不可用的显式选择 = 显式错误，零静默回落（DELEGATE_EXECUTOR_PENDING
    // 墓碑由本批真通路替换——R2/R11）。
    ExecutorRegistry.resolveRequested(input("executor").flatMap(_.asString)) match
      case Left(err) => IO.pure(Left(ToolError(err)))
      case Right(ExecutorRegistry.ResolvedSelection.Internal) =>
        // 模式参数（delegate-param-decisions 批 2026-11-04）：解析单点 = 纯函数
        // [[nebflow.core.delegate.ModeParam.resolve]]——不填 ⇒ 确定性 kernel；
        // `auto` ⇒ 显式报错（分配器已删除，不静默映射）；其它词 ⇒ 显式报错。
        // 这里零 IO、零端口、零配置读取 ⇒ spawn 路径无模型调用。
        val modeParam = input("mode").flatMap(_.asString).map(_.trim.toLowerCase).filter(_.nonEmpty)
        nebflow.core.delegate.ModeParam.resolve(modeParam) match
          case Right(modeDecision) =>
            internalSpawn(
              project,
              task,
              description,
              ctx,
              input("executor").flatMap(_.asString),
              modeDecision,
              shortName,
              reuseTarget,
              forceReuse,
              attachmentPaths
            )
          case Left(err) => IO.pure(Left(ToolError(err)))
        end match
      case Right(ExecutorRegistry.ResolvedSelection.External(defn, binPath)) =>
        // 外部执行器腿不参与双模式（Q5 裁定：恒单会话执行形态 = kernel 语义）；
        // mode/worktree 参数在场 = 显式拒（语义不存在的参数不静默吞）。
        if input("mode").exists(_.asString.exists(_.trim.nonEmpty)) then
          IO.pure(
            Left(
              ToolError(
                "Delegate: the mode parameter applies to the built-in executor only — external executors are always single-session (kernel semantics). Drop the parameter or omit executor."
              )
            )
          )
        else if reuseTarget.isDefined then
          IO.pure(
            Left(
              ToolError(
                "Delegate: the worktree (reuse) parameter applies to the built-in executor only — external executors resolve their own workspace and never sit in this engine's delegate worktrees. Drop the parameter or omit executor. (DELEGATE_WORKTREE_EXTERNAL_UNSUPPORTED)"
              )
            )
          )
        else externalSpawn(project, task, description, defn, binPath, ctx, shortName, attachmentPaths)
  end dispatchLeg

  /** 派发台账一条（fail-soft：失败不阻断派发，回执无号码）。 */
  private def createLedgerEntry(
    resolvedProject: Option[String],
    description: String
  ): IO[Option[String]] =
    IO.blocking {
      TaskLedgerStore
        .open()
        .createSyncReturningId(
          title = s"${resolvedProject.getOrElse(DefaultWorkspaceName)} — $description",
          // 🔴 project 字段随派发面落库（#506 过滤字段错位修复，2026-10-18）：此前 tag
          // 只进标题前缀（上方遗留编码），字段恒 None ⇒ `Task list project=<名>` 精确
          // 过滤返空。标题前缀保留（面板/回执既有显示契约），字段成为权威 tag。
          project = resolvedProject,
          actor = TaskLedgerHistory.Actors.Nebula
        )
    }.attempt
      .map {
        case Right(Right(id)) => Some(id)
        case _ => None
      }

  /** 调用者根会话解析（回投链与面板归桶锚点）。 */
  private def callerRootOf(ctx: ToolContext, resources: AgentRuntimePort): IO[String] =
    ctx.sessionId match
      case Some(sid) =>
        resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
      case None => IO.pure("")

  /** 内置执行器腿（nebflow）：actor spawn ＋ BackoffSupervisor 监督（原路径）；远端路由 = 设备投递。 */
  private def internalSpawn(
    project: Option[String],
    task: String,
    description: String,
    ctx: ToolContext,
    executorParam: Option[String] = None,
    /** 模式判定（[[nebflow.core.delegate.ModeParam.resolve]] 的纯函数产物——确定性、零模型调用）。 */
    modeDecision: nebflow.core.delegate.ModeDecision = nebflow.core.delegate.ModeParam.default,
    /** 面板命名批（2026-10-31）：批次短名（≤12 字）随 Record 落盘 + agentStart 注入。 */
    shortName: Option[String] = None,
    /** `worktree` 参数原文（= worktree 的 name；None = 零分配的就地通道）。 */
    reuseTarget: Option[String] = None,
    /** 越过「活会话持有」闸（红线闸，仅显式 true 越过）。 */
    forceReuse: Boolean = false,
    /**
     * 附件原文（附件传输批 2026-11-05）：已过 [[DelegateAttachments.validate]] 的
     * 绝对路径清单。空 = 本次不带附件（全链路逐字节零改动）。稳定副本在台账条目
     * 之后落地（taskId 已知 ⇒ 目录名与回执同号）。
     */
    attachmentPaths: List[String] = Nil
  ): IO[Either[ToolError, String]] =
    resolveWorkspace(project, ctx).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(WorkspaceResolution.Local(workspace, resolvedProject, sessionDevice, sessionDeviceWorkspace)) =>
        (ctx.actorSystem, ctx.sharedResources) match
          case (Some(system), Some(resources)) =>
            // 座位计划（delegate-param-decisions 批 2026-11-04）：`worktree` 参数 =
            // worktree 的 name，解析全在 [[WorktreeCandidates.resolveNamed]]（纯读 +
            // 纯函数，零模型调用）：
            //   ① 精确 name 命中 `<worktreeRoot>/<name>` ⇒ 复用同一棵树；
            //   ② 旧口径兜底（delegate id / 目录名 / 绝对路径）⇒ 复用；
            //   ③ 都没命中 ⇒ 按 name 新建（同名目录被非 worktree 占用 = 显式拒）。
            // 缺省 None = **零分配**（就地跑道，只读任务通道）。
            // 设备项目腿（座位在设备盘）没有本机树可建/可复用 ⇒ 显式拒。
            //
            // 附件 × 设备座位 = 显式拒（附件传输批 2026-11-05）：本机路径在设备盘上
            // 不存在，带过去只会变成「清单里有、谁也打不开」的静默丢失面。闸放在
            // seatPlanIo 这一格 = 与既有早退同点（台账未落、零 spawn 副作用）。
            val seatPlanIo: IO[Either[ToolError, WorktreeCandidates.SeatPlan]] =
              if attachmentPaths.nonEmpty && sessionDevice.isDefined then
                IO.pure(
                  Left(
                    ToolError(
                      "Delegate: attachments cannot be delivered to a device-resident seat — these files live on THIS " +
                        s"machine's disk and this project executes on device '${sessionDevice.getOrElse("?")}', which cannot read " +
                        "those paths. Nothing was dispatched. Cross-device attachment transfer is not wired yet: run the task " +
                        "host-locally (drop the device origin) or hand the files to that device by another channel. " +
                        "(DELEGATE_ATTACHMENTS_REMOTE)"
                    )
                  )
                )
              else
                reuseTarget match
                  case None => IO.pure(Right(WorktreeCandidates.SeatPlan.InPlace))
                  case Some(target) if sessionDevice.isDefined =>
                    IO.pure(
                      Left(
                        ToolError(
                          s"Delegate: the worktree parameter applies to host-local projects only — this project executes against " +
                            s"device '${sessionDevice.getOrElse("?")}' and its worktrees live on the device disk, not under this engine's " +
                            ".nebflow/worktrees. Drop the parameter to run in place on the device. (DELEGATE_WORKTREE_REMOTE)"
                        )
                      )
                    )
                  case Some(target) =>
                    WorktreeCandidates.inventory(workspace).map { inv =>
                      WorktreeCandidates.resolveNamed(inv, workspace, target, forceReuse) match
                        case Right(plan) => Right(plan)
                        case Left((code, msg)) =>
                          Left(ToolError(s"Delegate: worktree parameter refused — $msg ($code)"))
                    }
            seatPlanIo.flatMap {
              case Left(err) => IO.pure(Left(err))
              case Right(seatPlan) =>
                for
                  rootSid <- callerRootOf(ctx, resources)
                  taskId <- createLedgerEntry(resolvedProject, description)
                  // 附件稳定副本落地（附件传输批 2026-11-05）：**先于一切 spawn**——
                  // 复制/校验失败即成显式失败（会话没建、座位树没建，只留一条台账行
                  // 说明发生过什么）。目录名用 taskId（与回执同号，人/agent 都能对上）；
                  // 台账缺席时退回落一个 UUID 片段（仍稳定、仍可追溯）。
                  staged <- DelegateAttachments.stage(
                    attachmentPaths,
                    taskId.getOrElse("dispatch-" + java.util.UUID.randomUUID().toString.take(8)),
                    PathUtil.dataRoot.toNIO
                  )
                  result <- staged match
                    case Left(err) => IO.pure(Left(err))
                    case Right(attachmentPlan) =>
                      for
                        safetyMode <- resources.effectiveSafetyMode.map(nebflow.core.SafetyMode.toString)
                        r <- spawnKernel(
                          task = task,
                          description = description,
                          workspace = workspace,
                          project = resolvedProject,
                          taskId = taskId,
                          system = system,
                          resources = resources,
                          parentDepth = ctx.depth,
                          parentRef = ctx.agentActorRef,
                          wsSend = ctx.wsSend,
                          parentSessionId = ctx.sessionId,
                          safetyMode = safetyMode,
                          rootSessionId = rootSid,
                          // 新鲜派发腿跑判定机（R6 worktree + S13 分支纪律）；Mail
                          // continue 复活腿（不经此处）保持旧形态。
                          // 座位分配腿开启（新鲜派发才建/复用树；Mail 复活腿不经此处）。
                          allocateSeat = true,
                          seatPlan = seatPlan,
                          // 模式判定（纯函数产物——零模型调用）。
                          modeDecision = modeDecision,
                          // 跨设备批（2026-10-25）：设备项目的会话默认设备上下文透传——
                          // 六件工具默认路由设备 + brief 设备 workspace 行。
                          sessionDevice = sessionDevice,
                          sessionDeviceWorkspace = sessionDeviceWorkspace,
                          // 面板命名批（2026-10-31）：批次短名透传（Record 落盘 + agentStart 注入）。
                          shortName = shortName,
                          // 附件传输批：稳定副本计划（清单在 brief 末尾渲染）。
                          attachmentPlan = attachmentPlan
                        )
                      yield r
                yield result
            }
          case _ =>
            IO.pure(Left(ToolError("Delegate requires ActorSystem and SharedResources")))
      case Right(remote @ WorkspaceResolution.RemoteRoute(peer, deviceName, projectName, workspace)) =>
        // 显式 `executor` 选择与远端路由冲突 = fail-closed（执行器选择是 host 本地
        // 语义；对端用自己的执行器集原生执行——§A.3）。缺省（无 executor 参数）
        // 不进此分支（resolveRequested 已折叠为 Internal）。
        //
        // 附件 × 设备执行 = 显式拒（附件传输批 2026-11-05）：本机路径在设备侧不存在，
        // 静默带过去等于「清单里有、谁也打不开」——跨设备搬运不在本批范围，如实说。
        if attachmentPaths.nonEmpty then
          IO.pure(
            Left(
              ToolError(
                "Delegate: attachments cannot be delivered to a device-resident project — these files live on THIS " +
                  s"machine's disk and project '$projectName' executes on device '$deviceName', which cannot read those paths. " +
                  "Nothing was dispatched. Cross-device attachment transfer is not wired yet: run the task host-locally or " +
                  "hand the files to that device by another channel. (DELEGATE_ATTACHMENTS_REMOTE)"
              )
            )
          )
        else
          executorParam.map(_.trim).filter(_.nonEmpty) match
            case Some(_) =>
              IO.pure(
                Left(
                  ToolError(
                    s"Delegate: explicit executor selection applies to host-local execution only — " +
                      s"project '$projectName' executes on device '$deviceName' with THAT device's own executors. " +
                      "Drop the executor parameter to dispatch to the device. (DELEGATE_EXECUTOR_REMOTE_CONFLICT)"
                  )
                )
              )
            case None =>
              // 续用腿 × 整会话派设备 = 显式拒（对端设备的 worktree 清单不经本机账）。
              if reuseTarget.isDefined then
                IO.pure(
                  Left(
                    ToolError(
                      s"Delegate: worktree reuse applies to host-local execution only — project '$projectName' executes on device '$deviceName' and its worktrees live on the device disk. (DELEGATE_WORKTREE_REMOTE)"
                    )
                  )
                )
              else dispatchRemote(remote, task, description, ctx, shortName)
    }
  end internalSpawn

  /**
   * 远端投递腿（v2.1 §A.3 变化二）：origin 设备 = 执行地。台账/任务面/回执与本地
   * 腿同构（task #N + delegate 地址面）；完成结果经 delegate-result 回投父会话
   * （`source="delegate(project:<名> @ <设备名>)"`）。spawnId 幂等 + HMAC 设备签名
   * （[[nebflow.core.delegate.DeviceDelegate]]）。
   */
  private def dispatchRemote(
    route: WorkspaceResolution.RemoteRoute,
    task: String,
    description: String,
    ctx: ToolContext,
    /** 面板命名批（2026-10-31）：批次短名随 host 侧 dispatch 记录落盘。 */
    shortName: Option[String] = None
  ): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case Some(resources) =>
        for
          rootSid <- callerRootOf(ctx, resources)
          taskId <- createLedgerEntry(Some(route.project), description)
          dispatchAck <- nebflow.core.delegate.DeviceDelegate.dispatch(
            resources = resources,
            peer = route.peer,
            projectName = route.project,
            workspace = route.workspace,
            task = task,
            title = description,
            parentSessionId = ctx.sessionId.getOrElse(""),
            rootSessionId = rootSid,
            taskId = taskId
          )
          result <- dispatchAck match
            case Left(err) =>
              taskId.foreach { tid =>
                nebflow.core.project.TaskLedgerStore
                  .open()
                  .appendNoteSync(
                    tid,
                    s"Device dispatch FAILED: $err",
                    from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                    actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
                  )
              }
              IO.pure(Left(ToolError(s"Delegate: device dispatch failed — $err")))
            case Right(payload) =>
              val spawnId = payload.hcursor.downField("spawnId").as[String].getOrElse("")
              // Host-side dispatch record (result-injection anchor): DeviceDelegate
              // .handleResult resolves the delegate-result's live injection (parent
              // -session ExternalEvent, task-ledger note, report slot) through
              // DelegateRegistry.find(spawnId). Without this row the device's
              // result arrives and is dropped with a log line only.
              if spawnId.nonEmpty then
                nebflow.core.delegate.DelegateRegistry.put(
                  nebflow.core.delegate.DelegateRegistry.Record(
                    id = spawnId,
                    executor = s"device:${route.deviceName}",
                    cwd = route.workspace,
                    project = Some(route.project),
                    taskId = taskId,
                    parentSessionId = ctx.sessionId.getOrElse(""),
                    title = description,
                    createdAt = System.currentTimeMillis(),
                    name = shortName
                  )
                )
              val ack =
                s"""Delegate task dispatched to device '${route.deviceName}' (project '${route.project}', task ${taskId
                    .map(n => s"#$n")
                    .getOrElse("n/a")}, spawnId $spawnId).
The session runs on that device with its own executors (workspace there: ${route.workspace}); its result will be delivered back to this session when it completes, and artifacts stay on the device (registered as pointers).
No queueing: if the device goes offline mid-run the result will NOT arrive — re-dispatch after it returns."""
              IO.pure(Right(ack))
        yield result
      case None => IO.pure(Left(ToolError("Delegate requires SharedResources")))
    end match
  end dispatchRemote

  /**
   * 外部执行器腿（#1225 真适配器）：ExternalSessionRunner 一条通路——子进程、
   * 流帧、终态回投、续聊、取消全部在 runner；此处只做选座、台账与回执。
   */
  private def externalSpawn(
    project: Option[String],
    task: String,
    description: String,
    defn: nebflow.core.executor.ExecutorRegistry.ExecutorDef,
    binPath: String,
    ctx: ToolContext,
    /** 面板命名批（2026-10-31）：批次短名随外部腿 SpawnSpec 落 Record。 */
    shortName: Option[String] = None,
    /** 附件原文（附件传输批 2026-11-05）：外部腿不建座位树 ⇒ 副本只落 dataRoot 分支。 */
    attachmentPaths: List[String] = Nil
  ): IO[Either[ToolError, String]] =
    resolveWorkspace(project, ctx).flatMap {
      case Left(err) => IO.pure(Left(err))
      // 外部执行器 + 远端路由 = 同 internalSpawn 的 fail-closed 判（执行器选择是
      // host 本地语义；对端用自己的执行器集）。
      case Right(_: WorkspaceResolution.RemoteRoute) =>
        IO.pure(
          Left(
            ToolError(
              "Delegate: explicit executor selection applies to host-local execution only — " +
                "the selected project executes on its origin device with THAT device's own executors. " +
                "Drop the executor parameter to dispatch to the device. (DELEGATE_EXECUTOR_REMOTE_CONFLICT)"
            )
          )
        )
      case Right(WorkspaceResolution.Local(workspace, resolvedProject, sessionDevice, sessionDeviceWorkspace)) =>
        ctx.sharedResources match
          case Some(resources) =>
            if attachmentPaths.nonEmpty && sessionDevice.isDefined then
              IO.pure(
                Left(
                  ToolError(
                    "Delegate: attachments cannot be delivered to a device-resident seat — these files live on THIS " +
                      s"machine's disk and this project executes on device '${sessionDevice.getOrElse("?")}', which cannot read " +
                      "those paths. Nothing was dispatched. (DELEGATE_ATTACHMENTS_REMOTE)"
                  )
                )
              )
            else
              for
                rootSid <- callerRootOf(ctx, resources)
                taskId <- createLedgerEntry(resolvedProject, description)
                // 附件稳定副本（外部腿同样落地 + 校验；清单拼进 task 文本末尾——
                // 外部 runner 只吃一个 prompt 串，没有 brief 装配点）。
                staged <- DelegateAttachments.stage(
                  attachmentPaths,
                  taskId.getOrElse("dispatch-" + java.util.UUID.randomUUID().toString.take(8)),
                  PathUtil.dataRoot.toNIO
                )
                spawned <- staged match
                  case Left(err) => IO.pure(Left(err.message))
                  case Right(attachmentPlan) =>
                    nebflow.core.executor.ExternalSessionRunner.spawn(
                      nebflow.core.executor.ExternalSessionRunner.SpawnSpec(
                        executorId = defn.id,
                        binPath = binPath,
                        task = withAttachmentManifest(task, attachmentPlan),
                        title = description,
                        workspace = workspace,
                        project = resolvedProject,
                        taskId = taskId,
                        parentSessionId = ctx.sessionId.getOrElse(""),
                        rootSessionId = rootSid,
                        parentRef = ctx.agentActorRef,
                        wsSend = ctx.wsSend,
                        resources = resources,
                        // 面板命名批（2026-10-31）：批次短名随外部腿落 Record。
                        name = shortName,
                        // 归属修复批（2026-10-30）：设备项目 + 外部执行器 = host 本地进程——
                        // 跳过本机 git 判定腿（设备仓 worktree 归属设备，外部腿不建）。
                        deviceProject = sessionDevice.isDefined
                      )
                    )
              yield spawned
                .map { case (sessionId, ack) =>
                  ack + attachmentReceiptLine(staged) + DelegateRegistry.continuationLine(sessionId)
                }
                .left
                .map(ToolError(_))
          case None =>
            IO.pure(Left(ToolError("Delegate requires SharedResources")))
    }

  /** 外部腿的回执附件行（`staged` 可能是 Left——那一路不会走到回执，取空即可）。 */
  private def attachmentReceiptLine(
    staged: Either[ToolError, DelegateAttachments.Plan]
  ): String =
    staged.toOption.filterNot(_.isEmpty).map(p => "\n" + DelegateAttachments.receiptLine(p)).getOrElse("")

  /** 附件清单拼进单串 prompt（外部执行器腿：没有 brief 装配点）。 */
  private def withAttachmentManifest(task: String, plan: DelegateAttachments.Plan): String =
    if plan.isEmpty then task else task + "\n\n" + DelegateAttachments.render(plan)

  private def spawnKernel(
    task: String,
    description: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    system: ActorSystem,
    resources: AgentRuntimePort,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String],
    safetyMode: String,
    rootSessionId: String,
    /** 座位分配腿开关（新鲜派发 = true；Mail 复活腿 = false——沿既有座位不重分配）。 */
    allocateSeat: Boolean = false,
    /** 座位计划（`worktree` 参数的解析产物；缺省 = 零分配）。 */
    seatPlan: WorktreeCandidates.SeatPlan = WorktreeCandidates.SeatPlan.InPlace,
    /** 模式判定（纯函数产物——确定性、零模型调用）。 */
    modeDecision: nebflow.core.delegate.ModeDecision = nebflow.core.delegate.ModeParam.default,
    /** 跨设备批：会话默认设备上下文（设备项目 delegate）。None = 本机（零变化）。 */
    sessionDevice: Option[String] = None,
    sessionDeviceWorkspace: Option[String] = None,
    /** 面板命名批（2026-10-31）：批次短名透传（Record 落盘 + agentStart 注入）。 */
    shortName: Option[String] = None,
    /** 附件传输批（2026-11-05）：已落地的稳定副本计划（空 = 本次不带附件）。 */
    attachmentPlan: DelegateAttachments.Plan = DelegateAttachments.empty
  ): IO[Either[ToolError, String]] =
    // 提示词来源拆分批（2026-10-08）：delegate spawn 走 **delegate 面解析路径**
    // `delegateEntry`（= delegate_report 纪律的系统提示词；节点面恒走
    // `EntityLoader.loadAgent` → `entry`，两来源机械可分）。判据门 =
    // `nebflow.core.entity.PromptFaceSplitSpec`。
    IO.blocking(nebflow.core.entity.BuiltinAgents.delegateEntry(KernelAgentName).map(_.toAgentDef).get).flatMap {
      agentDef =>
        spawnBackground(
          agentDef = agentDef,
          task = task,
          description = description,
          workspace = workspace,
          project = project,
          taskId = taskId,
          system = system,
          resources = resources,
          parentDepth = parentDepth,
          parentRef = parentRef,
          wsSend = wsSend,
          parentSessionId = parentSessionId,
          safetyMode = safetyMode,
          rootSessionId = rootSessionId,
          allocateSeat = allocateSeat,
          seatPlan = seatPlan,
          modeDecision = modeDecision,
          sessionDevice = sessionDevice,
          sessionDeviceWorkspace = sessionDeviceWorkspace,
          shortName = shortName,
          attachmentPlan = attachmentPlan
        ).map(_.map { case (subagentId, ack) => ack + DelegateRegistry.continuationLine(subagentId) })
    }

  /**
   * Spawn one background executor instance. Returns `(subagentId, ack)` —
   * the id is the `delegate:<id>` continuation-address payload.
   * `private[nebflow]`: the Mail delegate leg's continue path reuses this
   * single spawn point (no parallel spawn path); 引擎加固批 2026-10-09（P2-5）
   * 起 boot sweep 的内部腿恢复通道（MergeQueueBootSweep.internalRevival）同点
   * 复用——仍无第二 spawn 路径，仅可见域从 tools 放宽到本工程。
   *
   * worktree-judge-fifo 批（#1224）：`judgeWorkspace = true` 仅新鲜派发腿
   * （`call`）传入——判定机跑 R6 worktree 判定 + S13 分支纪律（无论判否一律建
   * 分支 `delegate/<id>`，建权在判定机）。Mail continue 复活腿缺省 false =
   * 旧形态逐字（cwd 沿 record.cwd，不重判、不再建分支）。
   */
  private[nebflow] def spawnBackground(
    agentDef: AgentDef,
    task: String,
    description: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    system: ActorSystem,
    resources: AgentRuntimePort,
    parentDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Option[io.circe.Json => IO[Unit]],
    parentSessionId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    rootSessionId: String = "",
    /** 座位分配腿开关（新鲜派发 = true；Mail 复活腿 / 设备受委托腿 = false）。 */
    allocateSeat: Boolean = false,
    /** 座位计划（`worktree` 参数的解析产物；缺省 = 零分配）。 */
    seatPlan: WorktreeCandidates.SeatPlan = WorktreeCandidates.SeatPlan.InPlace,
    /**
     * origin 标记（v2.1 阶段 1，2026-10-09）：**执行设备侧** delegate（受委托形态）
     * 随记录落 `delegated-by:<hostDeviceId>`（DelegateRegistry.Record.delegatedBy）。
     * 本地/受控 spawn 腿 = None（缺省，零变化）。唯一非缺省调用方 =
     * [[nebflow.core.delegate.DeviceDelegate.spawnLocalDelegate]]。
     */
    delegatedBy: Option[String] = None,
    /** 跨设备批（2026-10-25）：会话默认设备上下文（设备项目 delegate）。None = 本机。 */
    sessionDevice: Option[String] = None,
    sessionDeviceWorkspace: Option[String] = None,
    /** 模式判定（[[nebflow.core.delegate.ModeParam]] 的纯函数产物；缺省 = 确定性 kernel）。 */
    modeDecision: nebflow.core.delegate.ModeDecision = nebflow.core.delegate.ModeParam.default,
    /** 面板命名批（2026-10-31）：批次短名（Record 落盘 + agentStart 帧注入）。 */
    shortName: Option[String] = None,
    /** 附件传输批（2026-11-05）：稳定副本计划（空 = 本次不带附件，全链路零改动）。 */
    attachmentPlan: DelegateAttachments.Plan = DelegateAttachments.empty,
    /**
     * 续命血缘（会话续命名与位修正批 2026-11-03 · 作者 16:51 裁定②）：本派生是
     * 续命兜底腿（原位续不可恢复）时 = 被继承的原 delegate 会话 id——Record 落盘
     * `resumedFrom`（审计 + AgentControl/面板显示面）。None = 新鲜派发/原位续
     * （原位续同一身份，无血缘）。
     */
    resumedFrom: Option[String] = None
  ): IO[Either[ToolError, (String, String)]] =
    val childDepth = parentDepth + 1
    val agentName = agentDef.name
    val subagentId = s"delegate-$agentName-${java.util.UUID.randomUUID().toString.take(8)}"
    val childWsSend = routeWsSend(wsSend, parentSessionId, subagentId, delegateName = shortName)
    val resolvedRoot = if rootSessionId.nonEmpty then rootSessionId else parentSessionId.getOrElse(subagentId)

    // 模式判定（delegate-param-decisions 批 2026-11-04）：**参数即权威，零模型调用**。
    // 判定值由调用方经 [[nebflow.core.delegate.ModeParam.resolve]] 求好后传入
    // （不填 ⇒ 确定性 kernel；`auto` 在参数解析点已显式报错，到不了这里）；本处
    // 只做记账与分叉，没有任何端口调用——这是「spawn 路径零模型调用」的落点。
    val decision: Option[nebflow.core.delegate.ModeDecision] = Some(modeDecision)

    IO.pure(()).flatMap { _ =>
      val modeWord = decision.map(_.mode)
      val isDispatcher = modeWord.contains(DualModeConfig.ModeDispatcher)
      // mode-decision 审计行（设计 §3.2）：总闸开才写（Inert 零审计行）。
      val modeAudit: IO[Unit] =
        decision match
          case None => IO.unit
          case Some(d) =>
            IO
              .blocking(
                ChainStore.appendSync(
                  ChainStore.keyFor(taskId, subagentId),
                  "mode-decision",
                  io.circe.Json.obj(
                    "delegateId" -> subagentId.asJson,
                    "mode" -> d.mode.asJson,
                    "leg" -> d.leg.asJson,
                    "reason" -> d.reason.asJson
                  )
                )
              )
              .handleErrorWith(e => logger.warn(s"mode-decision chain line failed: ${e.getMessage}").void)

      // ── 插件腿（双模式分叉 · 设计 §4.3/§5.1）────────────────────────────
      // kernel/未判定：spawn 时对整 brief 一次分配（现状逐字）；
      // dispatcher：本体零插件——跳过 allocatePreparation，审计行记
      // allocation=dispatcher-off（Inert 族新词：模式性豁免，非故障）。
      val pluginLeg: IO[Either[String, (NodeEngine.PluginPreparation, PluginInjection.AllocatedSet)]] =
        if isDispatcher then
          modeAudit.as(
            Right(NodeEngine.PluginPreparation.empty -> PluginInjection.AllocatedSet(Nil, "dispatcher-off"))
          )
        else modeAudit *> PluginInjection.allocatePreparation(subject = subagentId, taskText = task)

      pluginLeg.flatMap {
        case Left(err) =>
          IO.pure(Left(ToolError(s"Delegate: plugin allocation refused — $err (DELEGATE_PLUGINS_REFUSED)")))
        case Right((preparation, allocated)) =>
          val grantIo: IO[Either[String, nebflow.core.plugin.PluginMcpManager.Grant]] =
            if preparation.mcpPlugins.isEmpty then IO.pure(Right(nebflow.core.plugin.PluginMcpManager.Grant(Nil)))
            else resources.pluginMcp.acquire(subagentId, preparation.mcpPlugins)
          grantIo.flatMap {
            // pluginMcp.acquire 失败 → 本腿已启动者已被 manager 回滚停用；spawn 显式
            // 拒（零 running 残留——NodeStarter 同款口径）。
            case Left(err) => IO.pure(Left(ToolError(s"Delegate: plugin MCP startup failed — $err")))
            case Right(grant) =>
              val effectiveDef =
                if grant.serverIds.isEmpty && preparation.builtinTools.isEmpty then agentDef
                else agentDef.copy(pluginMcpServers = grant.serverIds, pluginTools = preparation.builtinTools)
              spawnWithPlugins(
                effectiveDef,
                task,
                description,
                workspace,
                project,
                taskId,
                system,
                resources,
                childDepth,
                parentRef,
                childWsSend,
                parentSessionId,
                safetyMode,
                resolvedRoot,
                // 座位分配腿（参数决定；dispatcher 与 kernel 同一条腿——节点层 seal
                // 不动：节点永不建 worktree，而是落分发器的座位里干活，不是嵌套）。
                // 终态面零新增代码：HoldForMerge/MergeQueue 全程由绑定驱动
                // （DelegateTerminal.holdForMerge 读 rec.branch/baseBranch/worktree）。
                allocateSeat,
                seatPlan,
                subagentId,
                preparation,
                allocated,
                delegatedBy,
                sessionDevice,
                sessionDeviceWorkspace,
                modeDecision = modeDecision,
                shortName = shortName,
                resumedFrom = resumedFrom,
                attachmentPlan = attachmentPlan
              )
          }
      }
    }

  end spawnBackground

  /**
   * 分配后的 spawn 本体（原 spawnBackground for-推导逐字，两处注入：brief 尾部
   * `<injected-plugins>` 块 + agentDef 运行时授予；记账两行：DelegateRegistry
   * plugins 集 + ChainStore delegate-spawn 行 plugins/allocation 字段）。
   */
  private def spawnWithPlugins(
    agentDef: AgentDef,
    task: String,
    description: String,
    workspace: String,
    project: Option[String],
    taskId: Option[String],
    system: ActorSystem,
    resources: AgentRuntimePort,
    childDepth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    childWsSend: io.circe.Json => IO[Unit],
    parentSessionId: Option[String],
    safetyMode: String,
    resolvedRoot: String,
    allocateSeat: Boolean,
    seatPlan: WorktreeCandidates.SeatPlan,
    subagentId: String,
    preparation: nebflow.core.project.NodeEngine.PluginPreparation,
    allocated: nebflow.core.project.PluginInjection.AllocatedSet,
    delegatedBy: Option[String] = None,
    /** 跨设备批（2026-10-25）：会话默认设备上下文（设备项目 delegate）。None = 本机。 */
    sessionDevice: Option[String] = None,
    sessionDeviceWorkspace: Option[String] = None,
    /** 模式判定（参数决定的确定性产物；恒在场——模式是每个 delegate 都有的属性）。 */
    modeDecision: nebflow.core.delegate.ModeDecision = nebflow.core.delegate.ModeParam.default,
    /**
     * 面板命名批（2026-10-31）：批次短名——Record 落盘；None = 旧形态（且复活腿
     *  不丢名：落盘时回读存量记录补保）。
     */
    shortName: Option[String] = None,
    /**
     * 续命血缘（会话续命名与位修正批 2026-11-03）：派生腿 = 原 delegate id；
     *  新鲜派发 = None（Record 落盘 `resumedFrom`，审计与显示面数据源）。
     */
    resumedFrom: Option[String] = None,
    /**
     * 附件传输批（2026-11-05）：已落地的稳定副本计划（空 = 本次不带附件，
     *  brief 与回执逐字节不变）。座位树副本在本方法内镜像（fail-soft）。
     */
    attachmentPlan: DelegateAttachments.Plan = DelegateAttachments.empty
  ): IO[Either[ToolError, (String, String)]] =
    val agentName = agentDef.name
    val isDispatcher = modeDecision.mode == DualModeConfig.ModeDispatcher
    for
      // 座位分配腿（delegate-param-decisions 批 2026-11-04）：**参数决定一切，零模型
      // 调用**。三态计划来自 [[WorktreeCandidates.resolveNamed]]：
      //   - InPlace（`worktree` 缺省）⇒ 零分配，就地跑道（只读任务通道）；
      //   - Reuse ⇒ 落既有树（精确 name 命中 / 旧口径命中）；
      //   - Create ⇒ 按 name 建 `<基工作区>/.nebflow/worktrees/<name>`（分支
      //     `delegate/<name>`）；建败 ⇒ WARN + 就地回落（回执标 [[SeatSource.Fallback]]）。
      // 设备项目（sessionDevice 在场）：座位在设备盘，本机不建树 ⇒ 无绑定（
      // 参数在场时已在 internalSpawn 显式拒——DELEGATE_WORKTREE_REMOTE）。
      binding <-
        if !allocateSeat then IO.pure(None)
        else if sessionDevice.isDefined then IO.pure(None)
        else
          seatPlan match
            case WorktreeCandidates.SeatPlan.InPlace =>
              DelegateWorkspace
                .prepareInPlace(workspace, subagentId)
                .handleErrorWith(e =>
                  logger
                    .warn(s"DelegateWorkspace.prepareInPlace failed (degraded to legacy spawn): ${e.getMessage}")
                    .as(None)
                )
            case WorktreeCandidates.SeatPlan.Reuse(cand, source) =>
              DelegateWorkspace
                .reuse(workspace, subagentId, cand, source)
                .map(b => Some(b): Option[nebflow.core.delegate.DelegateWorkspace.Binding])
                .handleErrorWith { e =>
                  // fail-open 合同：绑定失败绝不拒绝派发——回落就地座位（回执标 Fallback）。
                  logger
                    .warn(s"DelegateWorkspace.reuse failed (falling back to an in-place seat): ${e.getMessage}") *>
                    DelegateWorkspace
                      .prepareInPlace(workspace, subagentId)
                      .map(_.map(_.copy(seatSource = nebflow.core.delegate.DelegateWorkspace.SeatSource.Fallback)))
                      .handleErrorWith(e2 =>
                        logger
                          .warn(s"DelegateWorkspace.prepareInPlace failed (degraded to legacy spawn): ${e2.getMessage}")
                          .as(None)
                      )
                }
            case WorktreeCandidates.SeatPlan.Create(name) =>
              DelegateWorkspace
                .createNamed(workspace, name, subagentId)
                .flatMap {
                  case Right(b) => IO.pure(Some(b): Option[nebflow.core.delegate.DelegateWorkspace.Binding])
                  case Left(err) =>
                    logger.warn(s"DelegateWorkspace.createNamed('$name') failed ($err) — falling back IN PLACE") *>
                      DelegateWorkspace
                        .prepareInPlace(workspace, subagentId)
                        .map(
                          _.map(
                            _.copy(
                              seatSource = nebflow.core.delegate.DelegateWorkspace.SeatSource.Fallback,
                              seatName = Some(name)
                            )
                          )
                        )
                        .handleErrorWith(e2 =>
                          logger
                            .warn(
                              s"DelegateWorkspace.prepareInPlace failed (degraded to legacy spawn): ${e2.getMessage}"
                            )
                            .as(None)
                        )
                }
      // 附件座位树镜像（附件传输批 2026-11-05）：座位树存在 ⇒ 稳定副本再复制一份
      // 进 `<worktree>/.nebflow/attachments/<id>/`（delegate 的 cwd 就在那棵树里）。
      // fail-soft：镜像失败只 WARN，清单退回 dataRoot 副本——稳定副本已是权威。
      seatedAttachments <- DelegateAttachments.mirrorToSeat(attachmentPlan, binding.flatMap(_.worktree))
      // 判是腿的 delegate 工作座 = worktree；判否/无腿 = 原 workspace。设备项目（归属
      // 修复批）：本机引擎座位恒 = 基 workspace（brain 根）——设备 worktree 是设备
      // 本地路径（如对端盘符），绝不能当本机 projectRoot。
      effectiveWs =
        if sessionDevice.isDefined then workspace
        else binding.map(_.cwd).getOrElse(workspace)
      // 设备项目的默认设备 cwd（会话默认设备 workspace 的生效值）：设备 git 腿 =
      // 设备 worktree 座位（brief 申报与 Bash cwd 注入同源——模型开局即在座位内）；
      // 无腿 = origin 盘 workspace（直用工作区形态）。本机项目恒原值（零变化）。
      effectiveDevWs =
        (sessionDevice, binding.flatMap(_.worktree)) match
          case (Some(_), Some(wt)) => Some(wt)
          case _ => sessionDeviceWorkspace
      params = NodeRunner.SpawnParams(
        agentDef = agentDef,
        resources = resources,
        sessionId = subagentId,
        sessionName = description,
        depth = childDepth,
        parentRef = parentRef,
        wsSend = childWsSend,
        // 工作座位 = 项目 workspace 或默认 general 目录（unified-delegate 批）；
        // 判是腿 = worktree（worktree-judge-fifo 批）。任务产物留在任务的工作目录里。
        projectRoot = Some(effectiveWs),
        safetyMode = safetyMode,
        rootSessionId = resolvedRoot,
        // 跨设备批（2026-10-25）：设备项目 delegate 的会话默认设备上下文——六件
        // 工具未显式带 device 默认路由设备（cwd 注入 = 设备盘 workspace；归属修复批
        // 2026-10-30：设备 git 腿改为注入 worktree 座位——见 effectiveDevWs）。
        sessionDevice = sessionDevice,
        sessionDeviceWorkspace = effectiveDevWs
      )
      // brief：工作座行 + 判定机的 working-branch 申报行（🔴 均模型可见——
      // §16 待过目，最小成稿；无绑定 = 无行，旧 brief 逐字——无绑定 = 非 git
      // 或纯读腿（合法形态，非仅失败降级））+ delegate_report
      // 申报纪律行（[[BriefReportLine]]，来源拆分批上提为公开常量以便判据门钉住）
      // + sleep-poll 禁令批 C 段：等待纪律铁律（Defaults.NoSleepPollRule 单点
      // 文本——节点脚注/subagent 步同源；747cd36a 实撞：5h16m sleep 570 心跳
      // 轮询的启动面防线）。装配单点 = [[assembleBrief]]（spec 判红锚）。
      reportLine = BriefReportLine
      // 设备项目 workspace 行（跨设备批）：真实工作区在设备本盘——行里给设备盘
      // 路径 + 默认路由语义（本机座位 = brain 根仅是引擎侧锚点，非项目文件）。
      // 台账 #913 ③（2026-10-26）：host 开了强制显式设备 ⇒ 行尾追加纪律句
      // （缺省 device 的调用会被引擎直接拒绝——brief 与引擎行为同源，防模型
      // 依赖「by default」措辞撞墙）。
      fleetEnforceNote =
        if nebflow.core.device.FleetConfig.enforceExplicitDevice then
          " fleet.enforceExplicitDevice is ON on this host: pass device=\"" +
            sessionDevice.getOrElse(
              "local"
            ) + "\" on EVERY Bash/Read/Write/Edit/Glob/Grep call — calls without it are refused."
        else ""
      deviceWorkspaceLine =
        (sessionDevice, effectiveDevWs) match
          case (Some(dev), Some(ws)) =>
            s"[workspace] $ws (on device $dev — Bash/Read/Write/Edit/Glob/Grep run on that device by " +
              "default; paths are device-local absolute paths. Pass device=\"local\" explicitly for a " +
              s"local-engine command. This session's local engine seat is $effectiveWs — it holds only " +
              "the flow map and ledgers, not the project files)" + fleetEnforceNote
          case _ =>
            s"[workspace] $effectiveWs (file tools need absolute paths; Bash cwd is not guaranteed)" +
              (if nebflow.core.device.FleetConfig.enforceExplicitDevice then
                 " fleet.enforceExplicitDevice is ON: pass device=\"<name>\" on every Bash/Read/Write/Edit/Glob/Grep call (device=\"local\" for this machine) — calls without it are refused."
               else "")
      // jev-plugins 批 #1226：分配集注入 = prepareNodePlugins 等价路径（注入块
      // 追加在 brief 尾部——NodeStarter 首条消息同款位形；空分配 = 旧 brief 逐字）。
      // 双模式批：dispatcher 腿追加编排纪律段（写域相交任务用 deps 串行化等——
      // 设计 §5.7 三层防线的纪律层；kernel/未判定 = None，brief 逐字节不变）。
      brief = assembleBrief(
        workspaceLine = deviceWorkspaceLine,
        bindingLine = binding.flatMap(_.briefLine),
        task = task,
        injectedBlock = preparation.injectedBlock,
        dispatcherDiscipline = if isDispatcher then Some(DispatcherDisciplineLine) else None,
        // 附件清单段（附件传输批）：**brief 末尾**的固定段落——与作者手写正文结构可分，
        // 手写路径逐字保留；空计划 ⇒ 空串（旧 brief 逐字节不变）。
        attachmentSection = DelegateAttachments.render(seatedAttachments)
      )
      // 回执 = ACK 三行 + **座位来源行**（作者令 2026-11-04：「命中哪条要写进派发
      // 回执，不许静默」）+ 模式行（参数即权威的读面）+ 附件行（附件传输批：
      // 「带了几个、叫什么、多大」，空计划 ⇒ 无此段）。
      attachmentLine = DelegateAttachments.receiptLine(seatedAttachments)
      ack =
        s"""Delegate task started in the background${taskId.map(n => s" (task #$n)").getOrElse("")}.
Executor: ${agentName}; workspace: $effectiveWs${project.map(p => s" (project '$p')").getOrElse("")}${binding
            .map(b => s"; branch: ${b.branch}")
            .getOrElse("")}.
Mode: ${modeDecision.mode} (${modeDecision.leg} — ${modeDecision.reason}).
${DelegateWorkspace.receiptLine(binding, workspace)}${
            if attachmentLine.isEmpty then "" else "\n" + attachmentLine
          }
It runs in session $subagentId with no project context and no memory; the brief is its entire context.
You will be notified when it completes via a system message. Do NOT duplicate this task — wait for the result or work on something unrelated."""
      // 消息身份牌消解（msgtokens 批 2026-10-31）：brief 里的 {{msg:sid8-seq6}} 在
      // 进入子会话前展开为消息全文（映射缺失 = 显式报错行注入）。装配侧单点——
      // assembleBrief 本体（spec 判红锚）逐字节不动；initialPrompt 与首条 UserInput
      // 同吃消解后文本。机制关 = 原样透传。
      resolvedBrief <- resources.msgTokens.resolve(brief)
      subagentRef <- NodeRunner.spawnAgentActor(system, params)
      adapterRef <- NodeRunner.spawnSupervisedAdapter(
        system = system,
        params = params,
        childRef = subagentRef,
        childName = subagentId,
        description = description,
        agentName = agentName,
        subagentId = subagentId,
        parentSessionId = parentSessionId.getOrElse(""),
        initialPrompt = resolvedBrief,
        source = "delegate",
        wsSend = Some(childWsSend)
      )
      _ = logger.info(
        s"Spawned supervised delegate session: $subagentId (depth=$childDepth, workspace=$effectiveWs, project=${project.getOrElse("-")}${binding
            .map(b => s", branch=${b.branch}${b.worktree.map(w => s", worktree=$w").getOrElse("")}")
            .getOrElse("")}${if allocated.names.nonEmpty then s", plugins=${allocated.names.mkString(",")}" else ""})"
      )
      _ <- NodeRunner.registerAgent(
        resources,
        id = subagentId,
        ref = subagentRef,
        kind = AgentKind.Delegate,
        rootSessionId = resolvedRoot,
        parentRef = parentRef,
        startedAt = System.currentTimeMillis(),
        lastActivityMs = System.currentTimeMillis(),
        supervisorRef = Some(adapterRef),
        parentSessionId = parentSessionId.getOrElse("")
      )
      _ <- IO
        .blocking(
          DelegateRegistry.put(
            DelegateRegistry.Record(
              id = subagentId,
              executor = ExecutorRegistry.DefaultId,
              cwd = effectiveWs,
              project = project,
              taskId = taskId,
              parentSessionId = parentSessionId.getOrElse(""),
              title = description,
              createdAt = System.currentTimeMillis(),
              // 面板命名批（2026-10-31）：批次短名落盘；None（Mail 复活腿等不经
              // call 的腿）回读存量记录补保——复活不丢名。
              name = shortName.orElse(DelegateRegistry.find(subagentId).flatMap(_.name)),
              branch = binding.map(_.branch),
              worktree = binding.flatMap(_.worktree),
              baseBranch = binding.flatMap(_.baseBranch),
              projectWorkspace = binding.map(_.baseWs),
              // 分配集记账（#1226 可查面）：分配动作发生即记（空集也是显式空）；
              // disabled/off（未发生）= None。
              plugins = if allocated.actionRan then Some(allocated.names) else None,
              // 判定机已删（delegate-param-decisions 批）：worktreeJudged 不再由模型
              // 产出 ⇒ 恒 None（旧盘面记录照常解码）。
              worktreeJudged = None,
              // 就地腿的派发时快照（补充令第 2 条护栏的对比基线）：收尾时
              // DelegateTerminal.inPlaceWriteAlarm 用它判「没分配树却动了工作区」。
              seatSnapshotSha = binding.flatMap(_.snapshotSha),
              seatSnapshotDirty = binding.flatMap(_.snapshotDirty),
              // origin 标记（v2.1 阶段 1）：受委托腿随记录落 delegated-by。
              delegatedBy = delegatedBy,
              // G1 编排模式初值（delegate-ui 批 2026-10-08）：内置腿 spawn 恒 single
              // （delegate 内首调 Subagent/Subtask/AgentFlow 后由记账点惰性升级——
              // 写点见 DelegateRegistry.upgradeOrchestrationMode）。Mail 续聊复活腿
              // 同点复用（本函数是唯一 spawn 记录点）⇒ 新会话同样拿到初值。
              orchestrationMode = Some(DelegateRegistry.ModeSingle),
              // 执行形态落记录（spawn 时终身属性；本批起参数决定 ⇒ 恒有值）。
              mode = Some(modeDecision.mode),
              // 续命血缘（会话续命名与位修正批 2026-11-03）：派生腿落原 delegate id
              // （审计 + 显示面）；新鲜派发/原位续 = None（零字段，旧行字节不变）。
              resumedFrom = resumedFrom
            )
          )
        )
        .handleErrorWith(e => logger.warn(s"DelegateRegistry.put failed: ${e.getMessage}").void)
      // 派发期基线快照（`[base-moved]` 降噪重写批 2026-11-05，作者令第三条「派发时
      // 就该考虑并行不冲突」）：spawn 当场记下基分支 tip，后续判「这次 base 前进有
      // 没有动到我改过的文件」用这个快照比对——不靠逐条弹通知在事后打扰人。
      // 幂等 + fail-soft（非 git / 读不到 = 零动作，扫描拍首次见自动补基线）。
      _ <- nebflow.core.delegate.BaseDriftWatch
        .noteDispatch(subagentId, binding.map(_.baseWs), binding.flatMap(_.baseBranch))
      // ⑤A 链存储：spawn 行（branch/worktree/cwd/基分支——N1 与 #1228 的数据源）；
      // #1226 增分配摘要字段（plugins 集 + 三态模式词——结构帧可查，零新建存储）。
      _ <- IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(taskId, subagentId),
            "delegate-spawn",
            DelegateWorkspace
              .spawnPayload(
                subagentId,
                ExecutorRegistry.DefaultId,
                project,
                binding,
                plugins = if allocated.actionRan then Some(allocated.names) else None,
                // 双模式批：dispatcher 本体零插件 = 显式 dispatcher-off 审计词
                // （Inert 族——「模式性豁免」非故障；kernel 未判定腿保持旧规则）。
                allocation =
                  if allocated.actionRan then Some(allocated.mode)
                  else if isDispatcher then Some("dispatcher-off")
                  else None,
                // 归属修复批（2026-10-30）：设备项目无 worktree 腿（无 git/不可达/建败）
                // ⇒ 直用 workspace 座位落行（additive 键 workspaceSeat——执行形态面板
                // 「直接使用工作区」标注的数据源；本机项目恒 None，旧行字节不变）。
                workspaceSeat =
                  if sessionDevice.isDefined && binding.isEmpty then sessionDeviceWorkspace
                  else None
              )
              .deepMerge(
                // 续命血缘（会话续命名与位修正批 2026-11-03）：派生腿落 resumedFrom 键
                // （链账本可查「这一代派生自谁」）；新鲜派发零键（旧行字节不变）。
                resumedFrom.map(r => io.circe.Json.obj("resumedFrom" -> r.asJson)).getOrElse(io.circe.Json.obj())
              )
              .deepMerge(
                // 附件传输批（2026-11-05）：台账/面板可观测面——本 delegate 带了哪些附件
                // （名字 + 字节数 + sha256 + 类型 + 图片旗 + 副本落点）。空计划零键
                // （未带附件的旧行字节不变）。
                if seatedAttachments.isEmpty then io.circe.Json.obj()
                else io.circe.Json.obj("attachments" -> DelegateAttachments.summaryJson(seatedAttachments))
              )
          )
        )
        .handleErrorWith(e => logger.warn(s"ChainStore delegate-spawn line failed: ${e.getMessage}").void)
      // N1 刀1（v3 §6/A3 宿主挂载腿）：spawn 成功即挂 delegate 作用域宿主——FlowMapStore
      // openScope（`<基工作区>/.nebflow/flow-maps/<会话id>/`）＋ NodeEngine 实例（自根：
      // rootSessionId = 会话 id，节点 out=Nebula 投 delegate 本人）。存储锚 = 基工作区
      // （`workspace` 参数——判是腿的 worktree 只作座位不作存储根，台账寿命长于 worktree）。
      // fail-soft：挂载失败只 WARN，绝不拖垮 delegate 本体（刀1 面 additive——工具面在刀2
      // 接入作用域；「查无作用域图」在刀2 有显式报错形态）。
      _ <-
        nebflow.core.project.DelegateFlowHosts
          .mount(
            delegateId = subagentId,
            project = project,
            baseWorkspace = workspace,
            engineCwd = effectiveWs,
            system = system,
            resources = resources,
            wsSend = Some(childWsSend)
          )
          .handleErrorWith(e =>
            logger
              .warn(s"delegate flow-host mount failed (delegate continues without a scope map): ${e.getMessage}")
              .as(None)
          )
      _ <- resources.subAgentTaskStore
        .recordTask(
          SubAgentTask(
            taskId = subagentId,
            parentSessionId = parentSessionId.getOrElse(""),
            agentName = agentName,
            prompt = task,
            description = description,
            status = "running",
            retryCount = 0,
            spawnedAt = System.currentTimeMillis(),
            completedAt = None,
            lastError = None,
            source = "delegate",
            // 执行形态随行落任务行（参数决定，恒有值）。
            mode = Some(modeDecision.mode)
          )
        )
        .handleErrorWith(e => logger.warn(s"subAgentTaskStore.recordTask failed: ${e.getMessage}"))
      _ <- taskId match
        case Some(tid) =>
          IO.blocking(
            TaskLedgerStore
              .open()
              .appendNoteSync(
                tid,
                s"Delegate dispatched → $subagentId (executor=${ExecutorRegistry.DefaultId}, workspace=$effectiveWs${binding.map(b => s", branch=${b.branch}").getOrElse("")})",
                from = TaskLedgerHistory.Origins.Nebula,
                actor = TaskLedgerHistory.Actors.Nebula
              )
          ).attempt
            .void
        case None => IO.unit
      _ <- subagentRef ! AgentCommand.UserInput(resolvedBrief, Some(adapterRef))
    yield Right((subagentId, ack))
    end for
  end spawnWithPlugins

end DelegateTool
