/* 从 NodeTools.scala 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.project.*

/**
 * notifyDispatcher 参数载体（dispatch-notify 批 2026-09-05）：call() 解析的
 * （flag, provided）经 implicit 从 call() 词法作用域自动填入 createNode/proceed/
 * editNode——三者的既有调用点零文本改动（在飞批 trigger-chain-fix 占用了这些
 * 调用点行，碰撞规避；implicit 参数解析为 Scala 标准机制，非 hack）。
 */
/**
 * @param flag            legacy `notifyDispatcher` 实参（true/false）
 * @param provided        legacy `notifyDispatcher` 是否显式传入
 * @param policy          **新权威字段** `notify` 的声明值（None = 显式清除/未传，
 *                        由 [[policyProvided]] 区分；b64 批 2026-09-13）
 * @param policyProvided  `notify` 是否显式传入（true 且 policy=None ⇒ 显式清除，
 *                        节点回落 legacy 解析）
 */
final case class NodeEditNotify(
  flag: Boolean,
  provided: Boolean,
  policy: Option[String] = None,
  policyProvided: Boolean = false,
  /**
   * `notify` 值域校验失败的可行动报错（NODE_NOTIFY_INVALID）：非空 ⇒ 创建/编辑
   * 路径**前置拒绝**（`call()` 词法作用域解析单点，与 retry/loop 门同款短路）。
   */
  invalid: Option[String] = None
)

/**
 * loop 参数载体（LoopNode 批 2026-09-06）：call() 解析的（config, provided）经
 * implicit 自动填入 createNode/proceed/editNode——调用点零文本改动（与
 * NodeEditNotify 同机制）。provided=false（未传）= 编辑不改动 / 创建 None；
 * provided=true 且 config=Some(LoopConfig) = 启用 loop；provided=true 且
 * config=None = 显式停用 loop（loop=false）。
 */
final case class NodeEditLoop(config: Option[LoopConfig], provided: Boolean)

/**
 * retry 参数载体（批E2 P2 failed 回跳，spec §2.3）：call() 解析的（policy,
 * provided）经 implicit 自动填入 createNode/proceed/editNode（与 notify/loop 同
 * 机制）。provided=false（未传）= 编辑不改动 / 创建 None；provided=true 且
 * policy=Some = 设置/替换（对象或字符串形态）；provided=true 且 policy=None =
 * 显式清除（retry=null，replace-on-provide 与 deps 同款）。
 */
final case class NodeEditRetry(policy: Option[RetryPolicy], provided: Boolean)

/**
 * role 参数载体（nrloop 一期 2026-09-12，设计 §3.2）：call() 解析的（role, provided）
 * 经 implicit 自动填入 createNode/proceed/editNode（与 notify/loop/retry 同机制）。
 * `provided=false`（未传）= 创建落缺省 `task` / 编辑零改动；`provided=true` =
 * 创建期声明本节点角色（create-only：编辑期出现即拒 `NODE_ROLE_CREATE_ONLY`——
 * 角色是拓扑身份，同 `merge` 先例，改动只能新建节点）。
 */
final case class NodeEditRole(role: Option[String], provided: Boolean)

/**
 * **链归属声明载体**（chainmodel 批一 ①「显式成员制」2026-09-19）：call() 解析的
 * （decl, provided, invalid）经 implicit 自动填入 createNode/proceed/editNode（与
 * notify/loop/retry/role 同机制——调用点零文本改动）。
 *
 * `provided=false`（未传）= 创建不声明 / 编辑零改动（现有归属轨：未声明 ⇒ 派生兜底，
 * 存量数据逐字不变）；`provided=true` 且 `decl=Some(cid)` = 声明链归属（**声明即归属**，
 * 与它等谁/被谁等/被谁汇聚无关）；`provided=true` 且 `decl=None` = **显式撤销声明**
 * （回落派生兜底，replace-on-provide 与 notify 同款）。
 *
 * `invalid` = 值域校验失败的可行动报错（`NODE_CHAIN_ID_INVALID`）：非空 ⇒ 创建/编辑
 * 路径**前置拒绝**（0 副作用，与 notify/retry 门同款短路）。
 */
final case class NodeEditChainDecl(decl: Option[String], provided: Boolean, invalid: Option[String] = None)

object NodeEditTool extends Tool:
  val name = "NodeEdit"

  /**
   * 观测面上下文经济学批（20260907 裁定⑤b）：描述 7,438 → 3,742 字符（-50%，
   * 压措辞不改语义——参数面/动作语义/错误码/校验/重激活规则全保留）；合并
   * 观测面P0P1引擎批时统一进基线后落的 main 语义（failed 重激活条款 / abandon
   * 无 TTL 裁定 / notifyDispatcher completion-only）→ 4,308 字符（仍 -42% vs
   * 7,438；NodeSchemaSlimSpec 预算断言随之 3800→4400，语义不可删故放宽预算）。
   * 批D2 描述重写（20260908 语义门控 spec §5 批D2，行为基线=E1+E2 已实施代码）：
   * out 门控语法逐段展开（单值/扇出/失败信号边/门缺省 on={pass} 零漂移/Nebula
   * 双门例外/mode=signal deps 同款）+ retry 自动回跳链（下游单侧持有/gen 代次/
   * cap 耗尽 RetryCap 升级/邻居与环两校验）+ NODE_MERGE_PASS_ONLY 硬拒与
   * 无 on-failed 边 WARNING 两条新校验条款 → ~5,3xx 字符（预算断言随之
   * 4700→5400，同窗合并使前缀缓存一次性失效——spec §4.2 cache 纪律）。
   * 中断恢复语义批 R1/R2（20260908_interrupt-recovery-semantics §2.4 批 R2，
   * 2026-09-13）：interrupted 重激活条款（语义明示 = fresh 重跑非续跑，近 200 字符）
   * → 5,633 字符（预算断言随之 5450→5700）。
   * notify 默认值回归修复批 B-3 + A-2（2026-09-14，b64 批 502 字符零压缩的补账）：
   * ① 语义订正——`notify` 行由「create default "dispatcher"」改为**未声明 = 缺键 =
   *   legacy**（与实现一致：显式门集边照投根，只有**显式** dispatcher/silent 才抑制）；
   *   并把 `notifyDispatcher` 行**并入** `notify` 行尾（LEGACY 别名 + 「一个版本」期限
   *   仍可读）；② 压缩——`description`/`descriptionLong` 两行并一行、`out` 行 Nebula
   *   条款与 `notify` 行去重、`## Semantics` 措辞收紧 → **6,042 字符**（**未抬预算**，
   *   仍守 6050；b64 批新增两条参数行后描述曾涨到 6,532 = 超预算 482）。
   *   13 条语义锚（NodeSchemaSlimSpec）逐条在位，见批报告。
   * 该描述随 tools 数组进分发器每次请求。长度上限由 NodeSchemaSlimSpec 断言钉住）。
   */
  val description =
    """Create/edit a Flow Map node — the dispatcher's single topology tool (flow-map.json is store-owned).
## Parameters
- project (optional; current project by default).
- nodename: unique display name — missing = create, existing = edit.
- description (required on create, ≤60 chars): one-line purpose (card/payload metadata). descriptionLong (optional, ≤200 chars): longer summary — detail channel only. Both replace on edit.
- task (optional): node task; an entry node (task, no in) runs on create.
- in (optional): upstream id(s) added as barrier inputs (multi-in = barrier); each gains a default pass edge here.
- deps (optional, replace-on-provide): upstream ids awaited for COMPLETION SIGNAL only (need the result? use in); []/null clears; failed/cancelled/blocked never trigger; deps edits on RUNNING nodes rejected. A ref may be "chain:<id>" = wait for that WHOLE chain (all members completed) — a pure scheduling gate, NEVER a membership edge, deps-only (not 'in'); unknown id ⇒ NODE_CHAIN_REF_UNKNOWN.
- retry (optional, downstream-held like deps): failed auto-retry {upstream:"<in/deps-neighbor>", max:N} or "<id>:<N>"; null clears. FAIL + gen<N ⇒ that upstream re-runs (fresh result over the pass edge); gen≥N ⇒ failed + RetryCap escalation. max 1-10; neighbor-only (NODE_RETRY_NEIGHBOR); acyclic (NODE_RETRY_CYCLE).
- out (optional; edit rewrites the edge set; empty/null = dangling (state the intent with dangling=true): result retained, auto-delivered once wired): "B" = pass edge with payload (legacy); "Nebula" = EXIT MARKER (bare = pass/signal, zero root notify; a gate set "(pass)Nebula" / "(pass,failed)Nebula" declares root notify — see notify); fan-out "(pass)B, (failed)C"; failure edge "(failed)C:signal". Gates ⊆ pass,failed,fail (default pass); mode :result (default) | :signal (deps parity) | :loop. 'failed' = NODE-STATUS gate (that node failed); 'fail' = VERDICT gate (verifier reject), verifier-only, always "(fail)<worker>:loop" (NODE_VERDICT_GATE_ON_TASK_NODE / NODE_LOOP_EDGE_ROLE). ':loop' = CONTROL edge: not in the DAG, no in mirror, never settles a barrier; loop nodes must cover pass AND failed. On-failed into a merge node rejected (NODE_MERGE_PASS_ONLY).
- plugins (optional, replace-on-provide): plugin name(s) — THE capability mechanism (no per-node agent): skills → first message, mcp.json → MCP servers + tool grants. Must be Catalog-listed (ready to use); a blocked (deny-listed) package is refused. Omitting the key on create is refused (NODE_PLUGINS_UNDECLARED) — use plugins=[] for 'no capability face'.
- worktree (optional, create-time only): true = isolated git worktree at .nebflow/worktrees/<from-name> (same-name branch off main); fail-fast; refused on edits.
- Retired (rejected): agent/skill/mcp (NODE_AGENT_RETIRED) ⇒ plugins; preset (NODE_PRESET_RETIRED) — no per-node scheme; dispatcher's applies.
- abandon (optional, default false): terminal / wiring / pending / STALE running node → cancelled + edges detached, no TTL. LIVE running refused (use NodeCancel).
- role (optional, CREATE-ONLY): "task" (default; node_report: finish | blocked) | "verifier" (judges another node's output; node_report: pass | fail | blocked). A verifier's out MUST declare one "(fail)<worker>:loop" route when it declares any out edge (NODE_VERIFIER_NEEDS_ROUTE); an empty-out verifier create needs verifierRoutePending=true; on edit ⇒ NODE_ROLE_CREATE_ONLY.
- chainId (optional, declare-on-write): EXPLICIT chain membership — the node belongs to this id verbatim (declaration beats the derived fallback; undeclared keeps the derived in/out component). Metadata only: deps NEVER decides membership, so declaring changes no start/merge/barrier behaviour. Value domain in the schema property (else NODE_CHAIN_ID_INVALID); null = withdraw. Audited as chain-membership-changed.
- reactivateCompleted (optional, edit only): explicit authorization NODE_COMPLETED_REACTIVATION: re-run a COMPLETED node (status → wiring/pending, result cleared, upstreams re-delivered; logged). Omitted ⇒ edit only rewires + auto-delivers the retained result.
- restoreChain (optional, default false): when in/deps reference an ARCHIVED node (or this nodename is archived), true pulls that whole chain back onto the active map FIRST, then proceeds normally.
- notify (optional; unset = legacy: a "(pass)Nebula" :result edge DOES notify the root): "silent" | "dispatcher" (dispatcher session, NOT root) | "root" = who sees the COMPLETED event. Explicit dispatcher/silent suppress its root delivery (edge kept, no rewiring); failed never suppressed; null clears. Settable while wiring/pending/running; else NODE_NOTIFY_INVALID. Legacy one-version alias notifyDispatcher (≈ notify=dispatcher; ignored with a warning once declared); completion-only (failed always notifies; blocked reserved).
## Semantics
- Create requires an input side (task or in) → else EMPTY_NODE_CONNECTION; out may be empty; entry (task) runs at create, async.
- Verdict routing (role=verifier): fail is a VERDICT — THE VERIFIER STILL COMPLETES; "(fail)<worker>:loop" re-runs the target. Its out: distinct pass/fail targets (NODE_VERDICT_ROUTE_COLLISION), one fail target (NODE_VERIFY_MULTI_FAIL_TARGET), never "Nebula" (NODE_LOOP_TARGET_NEBULA), no retry (NODE_RETRY_LOOP_CONFLICT); the engine owns the round/wall-clock budget and fails it on exhaustion (loop-budget).
- out delivery: completed ⇒ pass edges fire (:result payload / :signal bare start; ≤1 per (target,mode)); failed ⇒ on-failed :signal edges fire, the rest waits (D5); wiring into a FAILED upstream with no on-failed edge ⇒ warning.
- merge=true (create-only): batch landing sink — fires when ALL upstreams completed; upstream failure ⇒ blocked (upstream-incomplete). REQUIRES in ≥1 (NODE_MERGE_REQUIRES_UPSTREAM); with no out yet it REQUIRES dangling=true (NODE_MERGE_SINK_NEEDS_OUT).
- Edit: in appends; deps replaces; out rewrites the edge set; description(s) replace. Removing a consumed target (running/terminal) rejected — NodeCancel first; other terminal rewires auto-deliver the retained result to new targets.
- Blocked node edit (task/description/in/out/deps/loop changed) reactivates: status → wiring/pending, deliveredTo cleared, blockCount kept, completed upstreams re-delivered.
- Failed node edit: a real change reactivates like blocked (first-choice recovery; blockCount→0). INTERRUPTED edit (SIGINT/SIGTERM left it non-terminal): a real change reactivates as a FRESH RERUN (task re-read from the top — NOT a checkpoint resume; boot recovery owns resume). COMPLETED re-runs only with reactivateCompleted=true; cancelled not reactivatable (create successor).
- Archived nodes (TTL-expired, result retained): only 'out' rewiring is accepted; other edits refused (restoreChain=true overrides).
- Validation (0 spawn except worktree): description rules; referenced nodes exist; DAG cycle check; running target ⇒ input frozen. Result = the agent's final output, auto-saved + delivered along out. Full result: NodeList(detail=<nodeId>)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project name (optional; defaults to the dispatcher's current project)".asJson
        ),
        "nodename" -> Json
          .obj("type" -> "string".asJson, "description" -> "Display name; unique within the Flow Map".asJson),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "REQUIRED on create: one-line summary of the node's purpose, 1-60 chars (always-loaded card/payload metadata; replace on edit)".asJson
        ),
        "descriptionLong" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional longer summary, ≤200 chars — detail channel only (NodeList detail= / REST), never in default payloads".asJson
        ),
        "task" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Node task context; entry nodes (task, no in) run immediately".asJson
        ),
        "in" -> Json.obj(
          "oneOf" -> Json
            .arr(
              Json.obj("type" -> "string".asJson),
              Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
            )
            .asJson,
          "description" -> "Upstream node id(s) to add as barrier inputs".asJson
        ),
        "deps" -> Json.obj(
          "oneOf" -> Json
            .arr(
              Json.obj("type" -> "string".asJson),
              Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
            )
            .asJson,
          "description" -> "Upstream node id(s) this node waits on for completion signal only (no result injected). Replace-on-provide: [] / null clears. Needs the result? Use in. A ref may also be \"chain:<chainId>\" = wait for that entire chain (all members completed) — a pure scheduling gate, never a membership edge; NODE_CHAIN_REF_UNKNOWN when the chain id does not exist, and chain refs are deps-only (rejected in 'in').".asJson
        ),
        "chainId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> ("EXPLICIT chain membership declaration (chainmodel batch 1 ①): the node belongs to this chain id verbatim — independent of what it waits on, who waits on it, or who merges it (\"declared ⇒ belongs\"). Undeclared nodes keep the derived fallback (the weak in/out component; ≥2 members to be reported); `deps` is a pure scheduling gate and NEVER a membership edge, so declaring/withdrawing changes NO start/merge/barrier behaviour — only which chain the node is reported under (payload chainId / NodeList chains[]). " +
            "Value = chain id token: non-empty, first char alphanumeric, rest letters/digits/'.'/'-'/'_' , ≤120 chars (it becomes a payload key AND an archive batch file name ⇒ path separators, whitespace, ':' or '..' are refused with NODE_CHAIN_ID_INVALID, never silently truncated). " +
            "null = withdraw the declaration (falls back to the derived component). " +
            "Every membership change is audited as `chain-membership-changed` (summary `from=<old|-> to=<new|-> reason=declaration|re-id|fallback`, ts on the event line): the declared write, a re-id, and the derived re-grouping that used to happen silently all have a write point now (NodeEdit create + edit tails).").asJson
        ),
        "out" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Out-edge spec (OPTIONAL on create — empty/null leaves the node dangling: no delivery, no root notify, the result is retained and auto-delivered once wired; on edit replaces the whole edge set): edge = target + gates + mode. Target = node id OR node name (the engine resolves names to nodes for validation, in-edge mirrors and delivery). \"B\" = pass edge with payload (legacy); \"Nebula\" = EXIT MARKER (pass gate, mode=signal ⇒ zero delivery); to notify the root write an EXPLICIT gate set — \"(pass)Nebula\" / \"(pass,failed)Nebula\"; fan-out \"(pass)B, (failed)C\"; node failure edge = \"(failed)C:signal\" (failure starts C on its own task — error text never injected). Gates (parens, comma-sep) ⊆ pass,failed — default pass; explicit gates narrow. mode :result (payload; default) | :signal (barrier settle only — deps parity). Loop nodes must cover BOTH pass and failed (NODE_LOOP_GATE_INCOMPLETE). On-failed edge into a merge node rejected (NODE_MERGE_PASS_ONLY); JSON arrays rejected — use segment syntax. Same (target,mode) edges merge gates".asJson
        ),
        "dangling" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> ("CREATE-ONLY declaration token (default false, ignored on edit — the dangling form on edit is the out=null disconnect): " +
            "declares this node's empty out edge as INTENTIONAL. Required instead of 'out' on merge=true creates (NODE_MERGE_SINK_NEEDS_OUT: a landing sink must hand its landed result onward — with no edge the result is retained with zero delivery; " +
            "pass dangling=true when the sink intentionally waits for its report/notify wiring). On any empty-out create it upgrades the wiring-gap notice from 'suspicious' to 'declared (dangling=true)' and logs a dangling-declared audit event; " +
            "the derived wiringGap payload key (pending/retained) is unchanged.").asJson
        ),
        "plugins" -> Json.obj(
          "oneOf" -> Json
            .arr(
              Json.obj("type" -> "string".asJson),
              Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson))
            )
            .asJson,
          "description" -> "Plugin package name(s) allocated to this node (§B.4) — THE capability mechanism (no per-node agent): skills injected into the first message + plugin MCP servers + builtin tool grants. Replace-on-provide (like deps). Names must exist in the Plugin Catalog (a listed package is ready to use); a blocked (deny-listed) package is refused, and a package switched off for dispatch by the author is refused for NEW dispatches only".asJson
        ),
        "worktree" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Create-time only: true = isolated git worktree auto-created at .nebflow/worktrees/<derived-from-node-name> (same-name branch, baseline = main HEAD; failure rejects the NodeEdit). false/omitted = workspace direct-run. Refused on edits".asJson
        ),
        "merge" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Merge/collection node (batch landing sink, create-only): triggers only when ALL upstreams completed (in-barrier); an upstream failure converts this node to blocked (category=upstream-incomplete) instead of the collect placeholder-start. Must NOT carry 'worktree' — a merge node lands on the workspace root repo (sandbox root = workspace, .git writable); task should embed the upstream branch/worktree list + landing command set. REQUIRES 'in' (≥1 existing upstream id) on create — zero-upstream merge is rejected (NODE_MERGE_REQUIRES_UPSTREAM): create the upstreams first, then this node with in=<ids>".asJson
        ),
        "notify" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> ("Notification policy for this node's COMPLETED event (unset = legacy resolution; no create-time default). " +
            "\"silent\" = nobody is notified (Flow Map + persisted result only); \"dispatcher\" = the project dispatcher is notified, NOT the root; \"root\" = the root sees it. " +
            "The policy also decides whether an existing 'Nebula' out-edge actually posts: only with an explicit dispatcher/silent that edge is kept as a DECLARATION while its runtime delivery is suppressed (markNebulaDelivered bookkeeping, so no redelivery revival) — you never need to rewire an existing topology to silence it; undeclared never suppresses. " +
            "A ':signal' Nebula edge is an inert exit marker either way (ledger only) — the policy cannot promote it to a root notify. " +
            "FAILED events are never suppressed: a failed node always notifies the dispatcher, and an explicit '(failed)Nebula' edge still reports to the root. " +
            "Chains with 2+ members also emit ONE aggregated 'source=chain' summary to the root when the chain is archived — that is independent of this field. " +
            "Legal values: silent | dispatcher | root; null clears the declaration (legacy resolution applies again). " +
            "Settable/withdrawable while wiring/pending/running (NODE_NOTIFY_INVALID on any other value).").asJson
        ),
        "notifyDispatcher" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "LEGACY alias of 'notify' (kept for one version): completion backflow toggle. Ignored (warned) when the node already declares 'notify'; prefer 'notify' in new calls (notifyDispatcher=true ≈ notify=dispatcher). Settable/withdrawable while wiring/pending/running".asJson
        ),
        "abandon" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Abandon a TERMINAL (blocked/completed/failed/cancelled), wiring/pending, or dead-session running node → cancelled + edges detached; no node-level TTL/eviction — the chain sweep archives it; audit-logged".asJson
        ),
        "role" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> ("Node role — CREATE-ONLY (NODE_ROLE_CREATE_ONLY; rejected on edit: a node's role decides its node_report value domain and whether it may route a verdict, so it is a topology identity, not a runtime switch). " +
            "\"task\" (default) = execution node: reports finish/blocked; \"verifier\" = verification node: it judges another node's output and reports pass/fail/blocked, routing the reject verdict along its '(fail)<worker>:loop' edge. " +
            "A verifier MUST declare exactly one fail route when it declares any out edge (NODE_VERIFIER_NEEDS_ROUTE), may not use the 'failed' gate, and may not also be a loop=true node. " +
            "Creating a verifier with NO out edge requires verifierRoutePending=true (see that property). " +
            "Use verifier only when the verification outcome must DRIVE ROUTING (a rejected artifact must trigger a re-run); a report that does not route stays a task node with the conclusion in its result text.").asJson
        ),
        "verifierRoutePending" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> ("CREATE-ONLY license for role=verifier with no out edge yet (default false, ignored on edit; the C-i two-phase token): " +
            "lets a verifier be created BEFORE its '(fail)<worker>:loop' route target exists — the route is wired later with NodeEdit out=\"(fail)<worker>:loop\" (wiring it releases the pending state; no persisted flag). " +
            "The receipt carries a '(verifierRoutePending)' warning and a verifier-route-deferred audit event. " +
            "The mirror face is NOT exempt: a downstream in= append onto a route-less verifier is still refused (NODE_VERIFIER_NEEDS_ROUTE), and edits that leave the verifier route-less are refused with the same code.").asJson
        ),
        "reactivateCompleted" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> ("Explicit authorization NODE_COMPLETED_REACTIVATION (default false): re-run a COMPLETED node on this edit — status → wiring/pending, result and delivery ledger cleared, upstreams re-delivered, the node runs again (its old result is discarded). " +
            "Only valid on a completed node (else rejected). Without this flag an edit of a completed node keeps today's behaviour: rewiring only, the retained result is auto-delivered to newly wired targets, no re-run. " +
            "Audit: every reactivation is logged (preStatus / source / gen / blockCount / loopRound). The routed-loop re-run leg (fail edge) lands in a later batch; this flag is the phase-1 authorization entry.").asJson
        ),
        "restoreChain" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> ("Chain restore flag (default false = today's behaviour: a pure reference). " +
            "When 'in'/'deps' names a node that has been ARCHIVED (or the edited node itself is archived), passing true pulls " +
            "that node's WHOLE archived chain back onto the active map FIRST, then runs this create/edit as usual — the " +
            "returned node(s) land on the map and the new node joins the same chain (chain membership is derived from the " +
            "topology, so nothing else is needed). Restore is always whole-chain (never a single node): a chain is one task " +
            "unit. Use it to continue an archived line of work (a follow-up node wired onto an archived upstream, or rewiring " +
            "an archived node's out); without the flag the archived node stays archived and still delivers its retained result " +
            "along the new edge. The tool result starts with one '[chain-restored] <chainId> ...' line per restored chain.").asJson
        ),
        "loop" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "LoopNode flag (create/edit): true = this node iterates — a WORKER session produces a result, a VERIFY session checks it; PASS → delivered downstream; FAIL → worker re-runs (same session, constant input) up to maxRounds(K). false/omitted = normal single-pass node. A loop node still declares its normal in/out/deps (and may merge) — loop only adds the inner iterate-verify loop. Its out MUST cover both pass and failed (NODE_LOOP_GATE_INCOMPLETE; empty out is legal).".asJson
        ),
        "retry" -> Json.obj(
          "oneOf" -> Json
            .arr(
              Json.obj(
                "type" -> "object".asJson,
                "properties" -> Json.obj(
                  "upstream" -> Json.obj("type" -> "string".asJson),
                  "max" -> Json.obj("type" -> "integer".asJson)
                ),
                "required" -> Json.arr("upstream".asJson, "max".asJson)
              ),
              Json.obj("type" -> "string".asJson)
            )
            .asJson,
          "description" -> "Failed auto-retry policy (P2, downstream-held like deps): {upstream:\"<in/deps-neighbor id>\", max:N} or \"<id>:<N>\". On this node's FAILURE with gen<N the engine auto-reactivates it (gen+1, deliveredTo cleared) AND re-runs the upstream (terminal upstreams only — in-flight reruns untouched); the fresh result re-delivers along the pass edge as a new attempt. The auto-retry replaces that round's failed dispatcher notification (each retry is logged via nodeUpdated + retry event); gen≥N → terminal failed: dispatcher notification AND RetryCap escalation to Nebula both fire. max 1-10 (NODE_RETRY_MAX_RANGE; max=1 = one retry, two runs). upstream must be an in/deps neighbor (NODE_RETRY_NEIGHBOR — wire first); retry chains must stay acyclic (NODE_RETRY_CYCLE). Settable in any status — arms on the NEXT failure; a retry edit never reactivates by itself (edit task to re-arm an already-failed node). null clears (replace-on-provide)".asJson
        ),
        "maxRounds" -> Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Loop round cap K (1-50, default 5): re-run the worker up to K times before the verify PASSes; reaching K without PASS terminalizes the node as failed (result states 'loop reached maxRounds'). Only meaningful with loop=true".asJson
        ),
        "verify" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Verify-agent name (default \"general\"): the session that checks each worker output (general agent + plugins — shares the node's plugins). Must exist in the Agent Catalog. Only with loop=true".asJson
        ),
        "verifyTask" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Verify checklist template (against the original task / acceptance baseline). Empty → engine default checklist. Only with loop=true".asJson
        )
      ),
      "required" -> Json.arr("nodename".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val nn = input("nodename").flatMap(_.asString).getOrElse("")
    s"NodeEdit($nn)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val project = input("project").flatMap(_.asString)
    val nodename = input("nodename").flatMap(_.asString).getOrElse("")
    val task = input("task").flatMap(_.asString)
    val description = input("description").flatMap(_.asString)
    // 裁定⑤c（20260907）：双层化长描述——可选，≤200，仅 detail 通道消费
    val descriptionLong = input("descriptionLong").flatMap(_.asString)
    val worktree = input("worktree")
    val abandon = input("abandon").flatMap(_.asBoolean).getOrElse(false)
    // 显式授权入口 `NODE_COMPLETED_REACTIVATION`（nrloop 一期 2026-09-12，附 C5①）：
    // 布尔参数，**仅 completed 节点有意义**（适用范围闸在 editNode 首段；创建路径
    // 显式拒——新节点无「重激活」语义）。默认 false ⇒ 今日行为逐字不变。
    val reactivateCompleted = input("reactivateCompleted").flatMap(_.asBoolean).getOrElse(false)
    // restoreChain（链级抽象 P2 · spec §5.3-②③）：**拉回显式旗标**——缺省 false ⇒
    // 纯引用语义（今日行为逐字不变）：in/deps 引用归档节点只走既有 D1 补投递通道
    // （findNode 双区兜底），节点留在归档区；传 true 才把目标所属归档链**整体**搬回
    // 活动区（恒整批，不做单节点拉回——链是整体单位）。
    val restoreChain = input("restoreChain").flatMap(_.asBoolean).getOrElse(false)
    // merge（merge-node 批 20260905）：create-only 标记——edit 路径不接收（对既有
    // 节点传 merge 会被静默忽略；归档节点传 merge 走 forbidden 拒绝）。
    val merge = input("merge").flatMap(_.asBoolean).getOrElse(false)
    val mergeProvided = input("merge").flatMap(_.asBoolean).isDefined
    // 建位期声明闸令牌（nodegate 方案件 §1(ii) C-ii / §1(i) C-i，本批四项①③）：
    // 两个 create-only 布尔——`dangling` = 显式登记空 out 为有意（merge sink 的唯一
    // 放行通道 + ⚠ 升级 + 审计）；`verifierRoutePending` = 空 out verifier 的两段式
    // 许可。编辑路径**容忍忽略**（同 merge 先例：令牌是建位时刻的许可，非节点字段）。
    val dangling = input("dangling").flatMap(_.asBoolean).getOrElse(false)
    val verifierRoutePending = input("verifierRoutePending").flatMap(_.asBoolean).getOrElse(false)
    // notifyDispatcher（dispatch-notify 批 2026-09-05）：Option 保留「显式传入」信号
    // （缺省 = 不改动——同 plugins 缺省不改动形态）。载体经 implicit 传入 createNode/
    // proceed/editNode——三者的既有调用点零改动（碰撞规避：在飞批占用了这些调用点行）。
    val notifyProvided = input("notifyDispatcher").flatMap(_.asBoolean)
    // notify 三值（b64 批 2026-09-13，R1/R2/R3/R5）：**新权威字段**。
    // 三形态（replace-on-provide，与 deps/retry 同款）：
    //   未传          → policyProvided=false（创建落缺省 `dispatcher`；编辑零改动）
    //   传 null       → 显式**清除**（回落 legacy 解析）
    //   传 "silent"…  → 校验（NODE_NOTIFY_INVALID）后落盘
    val notifyPolicyJson = input("notify")
    // **provided = 键存在**（含 `null`）：`null` 是「显式清除」这一动作（回落 legacy 解析），
    // 必须与「未传 = 不改动」区分开——否则 edit 路径的 replace-on-provide 闸门会把清除
    // 当成 no-op（`policyProvided=false` ⇒ 写回腿整条跳过）。
    val notifyPolicyProvided = notifyPolicyJson.isDefined
    val notifyPolicyParsed: Either[String, Option[String]] =
      notifyPolicyJson match
        case None => Right(None)
        case Some(j) if j.isNull => Right(None)
        case Some(j) =>
          j.asString match
            case Some(s) => NotifyPolicy.validate(s).map(Some.apply)
            case None =>
              Left(
                s"'notify' must be a string (silent | dispatcher | root) or null to clear it, got ${j.noSpaces}. (${NotifyPolicy.InvalidCode})"
              )
    implicit val notifyFlag: NodeEditNotify = NodeEditNotify(
      notifyProvided.getOrElse(false),
      notifyProvided.isDefined,
      policy = notifyPolicyParsed.getOrElse(None),
      policyProvided = notifyPolicyProvided,
      invalid = notifyPolicyParsed.left.toOption
    )
    // loop 参数（LoopNode 批 2026-09-06）：Option 区分「未传」（编辑不改动 / 创建 None，
    // provided=false）与「传了」（loop true 启用 / false 停用）。载体经 implicit 传入
    // createNode/proceed/editNode——调用点零文本改动（与 notifyFlag 同机制）。maxRounds
    // 缺省 5（与主设计 §2.5 K=5 同参）；verify 缺省 "general"（通用 agent + plugins）。
    val loopJson = input("loop")
    val loopProvided = loopJson.exists(j => !j.isNull)
    val loopEnabled = loopJson.flatMap(_.asBoolean).getOrElse(false)
    val maxRounds = input("maxRounds").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(5)
    val verify = input("verify").flatMap(_.asString).getOrElse("general")
    val verifyTask = input("verifyTask").flatMap(_.asString).getOrElse("")
    implicit val loopFlag: NodeEditLoop =
      if !loopProvided then NodeEditLoop(None, provided = false)
      else NodeEditLoop(if loopEnabled then Some(LoopConfig(maxRounds, verify, verifyTask)) else None, provided = true)
    // retry 参数（批E2 P2 failed 回跳，spec §2.3）：两形态——JSON 对象
    // {"upstream":"<id>","max":N} 或字符串 "<id>:<N>"；null=清除。replace-on-provide
    // 与 deps 同款：传了（任何形态含 null）= 整体替换/清除，未传 = 不改动。
    // max 范围 1-10 在解析单点校验（NODE_RETRY_MAX_RANGE）——创建/编辑共用。
    val retryJson = input("retry")
    val retryProvided = retryJson.isDefined
    val retryParsed: Either[String, Option[RetryPolicy]] =
      retryJson match
        case None => Right(None)
        case Some(j) if j.isNull => Right(None)
        case Some(j) if j.isObject =>
          (j.hcursor.get[String]("upstream"), j.hcursor.get[Int]("max")) match
            case (Right(up), Right(mx)) =>
              if mx < 1 || mx > 10 then
                Left(
                  s"'retry.max' must be between 1 and 10 (got $mx) — the auto-retry budget per node. (NODE_RETRY_MAX_RANGE)"
                )
              else if up.trim.isEmpty then Left("'retry.upstream' must be a non-empty node id")
              else Right(Some(RetryPolicy(up.trim, mx)))
            case _ => Left("'retry' object form requires string 'upstream' and integer 'max'")
        case Some(j) if j.isString =>
          j.asString.getOrElse("").trim.split(':').toList match
            case up :: mx :: Nil if up.trim.nonEmpty =>
              mx.trim.toIntOption match
                case Some(n) if n >= 1 && n <= 10 => Right(Some(RetryPolicy(up.trim, n)))
                case Some(n) =>
                  Left(
                    s"'retry.max' must be between 1 and 10 (got $n) — the auto-retry budget per node. (NODE_RETRY_MAX_RANGE)"
                  )
                case None =>
                  Left(s"'retry' string form must be '<upstream-id>:<max-int>', got '${j.asString.getOrElse("")}'")
            case _ => Left(s"'retry' string form must be '<upstream-id>:<max-int>', got '${j.asString.getOrElse("")}'")
        case Some(_) => Left("'retry' must be an object {upstream, max} or a string '<upstream-id>:<max>'")
    implicit val retryFlag: NodeEditRetry = NodeEditRetry(retryParsed.getOrElse(None), retryProvided)
    // role 参数（nrloop 一期 2026-09-12，设计 §3.2）：值域 `task|verifier`（NodeRoles
    // 单点，大小写宽容）；未传 = 创建落缺省 task / 编辑零改动。**create-only**——
    // 编辑路径出现即拒（错误码 NODE_ROLE_CREATE_ONLY，见 editNode 首个分支）。
    val roleJson = input("role")
    val roleProvided = roleJson.exists(j => !j.isNull)
    val roleParsed: Either[String, Option[String]] =
      roleJson match
        case None => Right(None)
        case Some(j) if j.isNull => Right(None)
        case Some(j) =>
          j.asString.map(_.trim) match
            case Some(s) if NodeRoles.isValid(s) => Right(Some(NodeRoles.normalize(s)))
            case Some(s) =>
              Left(
                s"'role' must be one of ${NodeRoles.All.toList.sorted.mkString(" | ")} (got '$s') — " +
                  "role=task: execution node (reports finish/blocked); role=verifier: verification node that judges another " +
                  "node's output and routes its verdict along '(fail)<worker>:loop' (reports pass/fail/blocked). " +
                  "(NODE_ROLE_INVALID)"
              )
            case None =>
              Left("'role' must be a string: \"task\" or \"verifier\" (NODE_ROLE_INVALID)")
    implicit val roleFlag: NodeEditRole = NodeEditRole(roleParsed.getOrElse(None), roleProvided)
    // chainId 参数（**chainmodel 批一 ①「显式成员制」** 2026-09-19）：三形态
    // （replace-on-provide，与 notify 同款）——
    //   未传        → provided=false（创建不声明 / 编辑零改动：现有归属轨逐字不变）
    //   传 null     → 显式**撤销**声明（回落派生兜底）
    //   传 "<chain>"→ 声明链归属（值域校验失败 ⇒ 前置拒绝 NODE_CHAIN_ID_INVALID）
    // 载体经 implicit 传入 createNode/proceed/editNode（与 notify/loop/retry/role 同机制，
    // 调用点零文本改动）。🔴 声明**不改调度**（deps 才是闸）：只改「归属被报在哪条链上」。
    val chainDeclJson = input("chainId")
    val chainDeclProvided = chainDeclJson.isDefined
    val chainDeclParsed: Either[String, Option[String]] =
      chainDeclJson match
        case None => Right(None)
        case Some(j) if j.isNull => Right(None)
        case Some(j) =>
          j.asString match
            case Some(s) if FlowMapStore.isDeclarableChainId(s) => Right(Some(s.trim))
            case Some(s) =>
              Left(
                s"'chainId' must be a chain id token — non-empty, ≤${FlowMapStore.ChainIdMaxLength} chars, first char " +
                  "alphanumeric, then letters/digits/'.'/'-'/'_' only (no path separators, whitespace or '..'): the value is " +
                  s"used verbatim as the chain id in payloads (chainId / chains[].id) and as the archive batch file name. Got '$s'. (NODE_CHAIN_ID_INVALID)"
              )
            case None =>
              Left(
                "'chainId' must be a string (the chain id to declare) or null to withdraw the declaration. (NODE_CHAIN_ID_INVALID)"
              )
    implicit val chainDeclFlag: NodeEditChainDecl =
      NodeEditChainDecl(chainDeclParsed.getOrElse(None), chainDeclProvided, chainDeclParsed.left.toOption)
    val inJson = input("in")
    val depsJson = input("deps")
    val outJson = input("out")
    // 阶段 2b（§B.4 第 3 步）：plugins 参数。Option 区分「未传」（编辑不改动 /
    // 创建 Nil）与「已传」（整列表替换，replace-on-provide 与 deps 同款）；
    // 解析复用 parseIn 三形态宽容（string / array / 逗号串）。
    val pluginsJson = input("plugins")
    val pluginsProvided = pluginsJson.exists(j => !j.isNull)
    val pluginsParsed: Either[String, List[String]] =
      if !pluginsProvided then Right(Nil)
      else NodeTools.parseIn(pluginsJson).left.map(err => err.replace("'in'", "'plugins'"))
    if nodename.isEmpty then IO.pure(Left(ToolError("Missing 'nodename'")))
    // 退役参数硬闸（2026-09-05 插件架构对齐）：agent/skill/mcp 一律拒绝——schema 层
    // 已删属性（严格调用方过不了 schema），此处运行时兜底（宽容调用方拿到可行动错误，
    // 指向 plugins）。形态：NODE_AGENT_RETIRED 单一错误码三参数共用。
    else if input.contains("agent") || input.contains("skill") || input.contains("mcp") then
      IO.pure(
        Left(
          ToolError(
            "'agent'/'skill'/'mcp' node params are RETIRED and no longer accepted (2026-09-05 plugin-architecture alignment) — " +
              "every node executes the general agent; capability differentiation goes through 'plugins' (a plugin = skills + mcp.json, either " +
              "alone is valid; see the Plugin Catalog in your prompt). Existing flow-map nodes keep their old values for display only. " +
              "(NODE_AGENT_RETIRED)"
          )
        )
      )
    // preset 退役硬闸（panelscheme 批 2026-09-21，作者令：节点无自有模型方案设置）——
    // 节点模型 = 项目分发器当前方案（派发时解析，SchemePolicy 单点）；存量节点保留
    // 其存储 preset 值仅作显示/审计（引擎已不再读取）。schema 层已删属性，此处运行时
    // 兜底给可行动错误。
    else if input.contains("preset") then
      IO.pure(
        Left(
          ToolError(
            "'preset' node param is RETIRED and no longer accepted (2026-09-21 panel model-scheme convergence) — nodes have no " +
              "model-scheme setting of their own: a node runs on the project dispatcher's current model scheme (set it on the " +
              "project-dispatcher agent in Settings; nodes pick it up at dispatch time). Existing nodes keep their stored preset " +
              "value for display only. (NODE_PRESET_RETIRED)"
          )
        )
      )
    // worktree 类型闸：布尔显式化（String→Boolean 改造）——传字符串（旧形态）不再
    // 宽容收编，直接拒绝（旧文案教「先建 worktree 再传裸名」的工作流已被
    // 「worktree=true 即时创建」取代）。
    else if worktree.exists(w => !w.isBoolean && !w.isNull) then
      IO.pure(
        Left(
          ToolError(
            "'worktree' must be a boolean (2026-09-05 explicit-boolean rework): true = an isolated worktree is auto-created for this node " +
              "(derived from the node name, baseline = main HEAD); false/omitted = run directly in the workspace. " +
              "The old string form (pre-existing bare name) is no longer accepted. (WORKTREE_NOT_BOOLEAN)"
          )
        )
      )
    else if pluginsProvided && pluginsParsed.isLeft then
      IO.pure(Left(ToolError(pluginsParsed.swap.toOption.getOrElse("invalid plugins"))))
    // loop maxRounds 范围校验（LoopNode 批 2026-09-06）：1-50 合理区间（主设计 K=5
    // 口径，上限防御异常配置）；loop=true 时校验，非 loop 忽略。
    else if loopEnabled && (maxRounds < 1 || maxRounds > 50) then
      IO.pure(
        Left(
          ToolError(
            s"'maxRounds' must be between 1 and 50 (got $maxRounds) — the loop round cap K (loop node iteration budget). (NODE_LOOP_MAXROUNDS_RANGE)"
          )
        )
      )
    // retry 解析/范围错误前置拦截（批E2）：对象/字符串形态与 max 范围在解析单点
    // 已判，这里统一拦在进 create/edit 之前（0 spawn）。
    else if retryProvided && retryParsed.isLeft then
      IO.pure(Left(ToolError(retryParsed.swap.toOption.getOrElse("invalid retry"))))
    // role 值域错误前置拦截（nrloop 一期，0 spawn）：非法角色值在进 create/edit 之前拒。
    else if roleProvided && roleParsed.isLeft then
      IO.pure(Left(ToolError(roleParsed.swap.toOption.getOrElse("invalid role"))))
    // chainId 值域错误前置拦截（chainmodel 批一 ①，0 spawn）：非法链号在进 create/edit 之前拒
    // （fail-closed——链号进载荷键与归档批文件名，禁路径分隔符/空白/`..`）。
    else if chainDeclProvided && chainDeclParsed.isLeft then
      IO.pure(Left(ToolError(chainDeclParsed.swap.toOption.getOrElse("invalid chainId"))))
    // description 校验（创建必写 + 编辑可 update 共用）：trim 非空 + ≤60 字符
    // （裁定⑤c 双层化：短文进默认载荷；长文走 descriptionLong ≤200）。
    else if description.exists(d => d.trim.isEmpty) then
      IO.pure(
        Left(
          ToolError(
            "'description' must be a non-empty one-line summary of the node's purpose (NODE_DESCRIPTION_REQUIRED)"
          )
        )
      )
    else if description.exists(_.trim.length > 60) then
      IO.pure(
        Left(
          ToolError(
            s"'description' must be ≤60 characters (got ${description.map(_.trim.length).getOrElse(0)}) — keep it to one line; longer context goes in the task or descriptionLong (NODE_DESCRIPTION_TOO_LONG)"
          )
        )
      )
    else if descriptionLong.exists(d => d.trim.isEmpty) then
      IO.pure(Left(ToolError("'descriptionLong' must be non-empty when provided (NODE_DESCRIPTION_LONG_REQUIRED)")))
    else if descriptionLong.exists(_.trim.length > 200) then
      IO.pure(
        Left(
          ToolError(
            s"'descriptionLong' must be ≤200 characters (got ${descriptionLong.map(_.trim.length).getOrElse(0)}) (NODE_DESCRIPTION_LONG_TOO_LONG)"
          )
        )
      )
    else
      val plugins = pluginsParsed.getOrElse(Nil)
      // flag off（§G.2 回滚语义）：NodeEdit 忽略 plugins 参数——不校验不存储
      val pluginsEffective: IO[Option[List[String]]] =
        if !pluginsProvided then IO.pure(None)
        else nebflow.core.plugin.PluginsConfig.enabled.map(enabled => if enabled then Some(plugins) else None)

      pluginsEffective.flatMap { pluginsOpt =>
        val pluginsForCall = pluginsOpt.getOrElse(Nil)
        // 插件存在性 + 信任门白名单校验（0 spawn 快速失败，§B.4 第 3 步）；
        // flag off → pluginsOpt=None → 跳过校验（参数被忽略）。
        val pluginValidation: IO[Either[String, Unit]] =
          pluginsOpt match
            case None => IO.pure(Right(()))
            case Some(names) =>
              if names.isEmpty then IO.pure(Right(()))
              else
                names.traverse(nebflow.core.plugin.PluginRegistry.resolve).map { results =>
                  results.find(_.isLeft) match
                    case Some(Left(err)) => Left(err)
                    case _ => Right(())
                }
        pluginValidation.flatMap {
          case Left(err) => IO.pure(Left(ToolError(err)))
          case Right(_) =>
            NodeTools.resolveProject(project, ctx).flatMap {
              case Left(err) => IO.pure(Left(ToolError(err)))
              case Right(rt) =>
                // 链拉回（链级抽象 P2 · spec §5.3-④）：restoreChain=true 时**先拉回**
                // 再执行原创建/编辑流——拉回把目标节点搬回活动区，于是后续的按名查找、
                // in/deps 接线、归档节点「只放行 out 改接」闸全部按活动区语义走（拉回
                // 即打开了 spec §5.1 列出的全部拒绝点）；新节点落库后与拉回成员同属一个
                // 合并分量（§5.3-⑤ 派生式链身份，零代码并链）。
                NodeTools.withChainRestore(rt, restoreChain, inJson, depsJson, nodename) {
                  rt.store.snapshot.flatMap { s =>
                    s.nodes.values.find(_.name == nodename) match
                      case Some(existing) =>
                        // 令 1 闸 A（派发面，唯一判定点 = NodeEdit 落库时刻）：只判**新增**
                        // 分配；节点上已有的分配 = 已做出的派发承诺（S2/S3），不重判 ⇒
                        // 作者此后关闭该插件不会让既有节点的编辑被拒。
                        val newlyAssigned = pluginsForCall.filterNot(existing.plugins.toSet.contains)
                        dispatchFaceCheck(newlyAssigned).flatMap {
                          case Left(err) => IO.pure(Left(ToolError(err)))
                          case Right(_) =>
                            editNode(
                              rt,
                              existing,
                              task,
                              description,
                              descriptionLong,
                              abandon,
                              worktree.flatMap(_.asBoolean),
                              pluginsOpt,
                              inJson,
                              depsJson,
                              outJson,
                              ctx,
                              reactivateCompleted
                            )
                        }
                      case None =>
                        // 归档节点编辑兜底（fix b「已存在边+归档上游不补投递」修复 20260903）：
                        // 活动区按名未命中 → 归档区按名兜底。归档节点只支持 out 改接（悬空
                        // 完成结果的补投递——「悬空节点后来被接线」的归档变体：editNode 的
                        // completed+newOut 投递分支沿 deliverOutTo 补投，barrier 随之结算）；
                        // 其余编辑域（task/description/in/deps/配置/abandon）拒绝——归档是显示
                        // 过期（TTL 满、结果保留可投递），不是重激活通道（重激活只属于
                        // Blocked 态）。修复前：归档名落 createNode → 同名重复节点（拓扑
                        // 污染）或静默失败。
                        rt.store.archiveSnapshot.flatMap { arch =>
                          arch.nodes.values.find(_.name == nodename) match
                            case Some(archived) =>
                              val forbidden =
                                task.isDefined || description.isDefined || descriptionLong.isDefined || worktree.isDefined ||
                                  abandon || inJson.isDefined || depsJson.isDefined ||
                                  pluginsProvided ||
                                  mergeProvided || notifyProvided.isDefined || retryProvided ||
                                  // chainmodel 批一 ①：链归属声明同 config 族——归档节点只放行
                                  // out 改接（归档是显示过期 + 结果可补投，不是改写归属的通道）。
                                  chainDeclFlag.provided ||
                                  // 显式授权入口对归档节点无意义（归档是显示过期 + 结果可补投，
                                  // 不是重激活通道——重激活只属于活动区节点）。
                                  reactivateCompleted
                              if abandon then
                                IO.pure(
                                  Left(
                                    ToolError(
                                      s"Node '$nodename' is archived (display TTL expired) — abandon is not applicable; it already ages out of views on its own."
                                    )
                                  )
                                )
                              else if mergeProvided then
                                IO.pure(
                                  Left(
                                    ToolError(
                                      s"Node '$nodename' is archived — 'merge' is a create-only flag and cannot be set on an archived node."
                                    )
                                  )
                                )
                              else if !outJson.isDefined then
                                IO.pure(
                                  Left(
                                    ToolError(
                                      s"Node '$nodename' is archived (display TTL expired, result retained). Only 'out' rewiring is supported for archived nodes (result re-delivery)."
                                    )
                                  )
                                )
                              else if forbidden then
                                IO.pure(
                                  Left(
                                    ToolError(
                                      s"Node '$nodename' is archived — only 'out' rewiring is supported (result re-delivery); task/description/in/deps/config edits are not."
                                    )
                                  )
                                )
                              else
                                editNode(
                                  rt,
                                  archived,
                                  task,
                                  description,
                                  descriptionLong,
                                  abandon,
                                  worktree.flatMap(_.asBoolean),
                                  pluginsOpt,
                                  inJson,
                                  depsJson,
                                  outJson,
                                  ctx
                                )
                              end if
                            case None =>
                              if abandon then
                                IO.pure(
                                  Left(ToolError(s"Node '$nodename' not found — abandon requires an existing node"))
                                )
                              else if reactivateCompleted then
                                IO.pure(
                                  Left(
                                    ToolError(
                                      s"'reactivateCompleted' is an edit-only parameter (it re-runs an existing COMPLETED node) — no node named '$nodename' exists, " +
                                        "so create it normally first. (NODE_COMPLETED_REACTIVATION)"
                                    )
                                  )
                                )
                              // P1 声明存在性（nodegate 方案件 §1(iii)，本批四项④，0 spawn）：
                              // 新建节点必须**显式声明**能力面——漏传键 = 静默漏挂能力面（现场
                              // 活证 n-89c91443：建位 10 分钟后靠节点自陈才发现）。`plugins=[]`
                              // = 显式「本节点无需能力面」，与「漏传」区分。只对「新建」生效
                              // （编辑路径 replace-on-provide 语义不动）。
                              else if !pluginsProvided then
                                IO.pure(
                                  Left(
                                    ToolError(
                                      s"New node '$nodename' must DECLARE its plugin capability face — pass plugins=[\"<catalog-name>\"] for the capabilities this node needs, " +
                                        "or plugins=[] to explicitly declare 'no capability face needed'. The omitted key is refused so a silently missing capability can't happen. (NODE_PLUGINS_UNDECLARED)"
                                    )
                                  )
                                )
                              else
                                dispatchFaceCheck(pluginsForCall).flatMap {
                                  case Left(err) => IO.pure(Left(ToolError(err)))
                                  case Right(_) =>
                                    createNode(
                                      rt,
                                      nodename,
                                      task,
                                      description,
                                      descriptionLong,
                                      worktree.flatMap(_.asBoolean),
                                      pluginsForCall,
                                      inJson,
                                      depsJson,
                                      outJson,
                                      merge,
                                      dangling,
                                      verifierRoutePending,
                                      pluginsProvided
                                    )
                                }
                        }
                  }
                }
            }
        }
      }

    end if

  end call

  /**
   * 令 1（插件开关语义，2026-09-12）**闸 A** = 派发许可的**唯一**判定点。
   * 2026-09-13 无审批批口径更新：内容面判定 = 「在位即信任 ∧ 未被封禁」。
   *
   * `dispatchFaceCheck` 只对**新派发**（新建节点的 plugins / 既有节点上新增的插件名）
   * 生效：内容面仍由 `PluginRegistry.resolve` 在更早处把门（不存在 / **被封禁** 一律拒），
   * 本函数只追加**派发面**判定 ⇒ 关闭（`plugins.dispatch.<n>.authorEnabled = false`）
   * 挡住未来派发，但**不影响**：
   *  - 已派发/在飞节点（闸 B/C/E 只判内容面，见 `NodeEngine.prepareNodePlugins`）；
   *  - 运行期工具面（闸 D 只判封禁，见 `PluginMcpManager.revalidate`）；
   *  - 已启用提示词的审计面（节点首条消息里已注入的 `<injected-plugins>` 全文与
   *    审计留存不受任何开关影响）。
   * 名单为空 ⇒ 零开销 Right（既有调用点绝大多数不带 plugins 参数）。
   */
  private def dispatchFaceCheck(names: List[String]): IO[Either[String, Unit]] =
    if names.isEmpty then IO.pure(Right(()))
    else
      names.distinct
        .traverse { n =>
          nebflow.core.plugin.PluginRegistry.contentTrusted(n).map { trusted =>
            if nebflow.core.plugin.PluginDispatchPolicy.effective(n, trusted) then Right(())
            else if !trusted then
              Left(
                s"Plugin '$n' is BLOCKED (deny-list) — a blocked package is refused on every path " +
                  s"(no dispatch, no load, in-flight MCP stopped). Unblock it if intended: Plugin panel, " +
                  s"REST POST /api/plugins/$n/unblock, or CLI 'nebflow plugin unblock $n'. (PLUGIN_BLOCKED)"
              )
            else
              Left(
                s"Plugin '$n' is disabled for new dispatches by the author (dispatch switch is OFF). " +
                  "It affects FUTURE dispatches only — nodes already dispatched keep their plugin grant. " +
                  s"To use it again either re-enable it (panel switch, POST /api/plugins/$n/enable, or CLI 'nebflow plugin enable $n'), " +
                  s"or grant a temporary dispatch transition (POST /api/plugins/$n/dispatch/grant). (PLUGIN_DISPATCH_DISABLED)"
              )
          }
        }
        .map(_.collectFirst { case Left(e) => e }.toLeft(()))

  // ── 新建 ─────────────────────────────────────────────

  /**
   * description 校验单点（create 必写；edit 传了才校验）。裁定⑤c（20260907
   * 双层化）：短文 ≤60 进默认载荷；长文走 validateDescriptionLong（≤200，
   * detail 通道）。存量 ≤200 长描述不回溯。
   */
  private def validateDescription(description: Option[String], creating: Boolean): Option[ToolError] =
    description match
      case None if creating =>
        Some(
          ToolError(
            "New node requires 'description' — a one-line summary (≤60 chars) of what this node does. " +
              "It is the always-loaded metadata shown on the Flow Map card (the node's result is read on demand). (NODE_DESCRIPTION_REQUIRED)"
          )
        )
      case Some(d) if d.trim.isEmpty =>
        Some(ToolError("'description' must be non-empty (trim) — one line, ≤60 chars (NODE_DESCRIPTION_REQUIRED)"))
      case Some(d) if d.trim.length > 60 =>
        Some(
          ToolError(
            s"'description' must be ≤60 characters (got ${d.trim.length}) — one line; longer context goes in the task or descriptionLong (NODE_DESCRIPTION_TOO_LONG)"
          )
        )
      case _ => None

  /** descriptionLong 校验单点（裁定⑤c，可选参数：传了才校验；≤200 字符）。 */
  private def validateDescriptionLong(descriptionLong: Option[String]): Option[ToolError] =
    descriptionLong match
      case Some(d) if d.trim.isEmpty =>
        Some(ToolError("'descriptionLong' must be non-empty (trim) — ≤200 chars (NODE_DESCRIPTION_LONG_REQUIRED)"))
      case Some(d) if d.trim.length > 200 =>
        Some(
          ToolError(
            s"'descriptionLong' must be ≤200 characters (got ${d.trim.length}) (NODE_DESCRIPTION_LONG_TOO_LONG)"
          )
        )
      case _ => None

  /**
   * worktree 派生规则（2026-09-05 显式布尔改造，规则写死）：
   * 目录名 = 节点名 sanitize——空白与 git-ref 非法字符（~^:?*[\\ 及控制符）→ '-'、
   * 连续 '-' 折叠、首尾 '-.' 剥除、截 40 字符、空则 "node"（**保留 CJK/Unicode
   * 字母数字**——生产节点名以中文为主，全部归一成 "node" 会让派生名失去区分度；
   * git 分支名与 macOS/Linux 文件名均合法接受 Unicode）。冲突追加 -2..-99；
   * 分支同名；基线 = main HEAD（无 main → 当前 HEAD）。
   * 创建时机选型（裁决 a「NodeEdit 即时创建」）：fail-fast——分发器组图当下拿到
   * 可行动错误，零 spawn 零 token 浪费；spawn 时懒创建（方案 b）会把失败推迟到
   * 执行链中段，形成 failed 节点 + 重入轮次。git 不可用/非 git 工作区 → 拒建节点。
   */
  private def createWorktreeFor(ws: os.Path, nodename: String): Either[String, String] =
    def git(args: String*): Either[String, String] =
      val res = os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = false, mergeErrIntoOut = true)
      if res.exitCode != 0 then Left(res.out.trim().take(300)) else Right(res.out.trim())
    val sanitized =
      nodename.trim
        .replaceAll("[\\s~^:?*\\[\\\\]+", "-")
        .replaceAll("-{2,}", "-")
        .stripPrefix("-")
        .stripSuffix("-")
        .stripPrefix(".")
        .take(40)
        .stripSuffix("-")
        .stripSuffix(".lock")
        .stripSuffix(".")
    val base = if sanitized.isEmpty then "node" else sanitized
    val wtRoot = ws / ".nebflow" / "worktrees"
    val candidates = (1 to 99).map(i => if i == 1 then base else s"$base-$i")
    candidates.find { n => !os.exists(wtRoot / n) && !os.exists(ws / ".nebflow" / n) } match
      case None =>
        Left(
          s"Cannot derive a free worktree name for node '$nodename' (tried '$base', '$base-2'…'$base-99') — pass a shorter/unique nodename"
        )
      case Some(name) =>
        val path = wtRoot / name
        os.makeDir.all(wtRoot)
        val baseRef = if git("rev-parse", "--verify", "--quiet", "main").isRight then "main" else "HEAD"
        git("worktree", "add", "-b", name, path.toString, baseRef).map(_ => name)

  end createWorktreeFor

  private def createNode(
    rt: ProjectRuntime,
    nodename: String,
    task: Option[String],
    description: Option[String],
    descriptionLong: Option[String],
    worktree: Option[Boolean],
    plugins: List[String],
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json],
    merge: Boolean = false,
    /**
     * C-ii 令牌（nodegate 方案件 §1(ii)）：显式登记空 out 为有意——merge sink 空
     * out 的唯一放行通道；⚠ 行升级 + `dangling-declared` 审计（create-only）。
     */
    dangling: Boolean = false,
    /**
     * C-i 令牌（nodegate 方案件 §1(i)）：空 out verifier 的两段式许可
     * （create-only；非空 out 不豁免；编辑/镜像面不豁免）。
     */
    verifierRoutePending: Boolean = false,
    /** P1 已过闸的旁证（plugins 键在本次调用出现）——驱动 P2a flag-off 警告面。 */
    pluginsDeclared: Boolean = false
  )(implicit
    notify: NodeEditNotify,
    loopFlag: NodeEditLoop,
    retryFlag: NodeEditRetry,
    roleFlag: NodeEditRole,
    chainDecl: NodeEditChainDecl
  ): IO[Either[ToolError, String]] =
    // 执行统一 general（2026-09-05 插件架构对齐）：新建节点不再接受 agent 参数，
    // 专业能力由 plugins 差异化；NodeDef.agent 字段保留（存量兼容读 + spawn 读取）。
    val agentName = "general"
    val inIds = NodeTools.parseIn(inJson)
    // deps 复用 parseIn 三形态宽容解析（deps 设计 §1.2：不新写解析器）；报错文案
    // 把参数名替换成 deps，避免误导（解析器文案写死 'in'）。
    val depsIds = NodeTools.parseIn(depsJson).left.map(err => err.replace("'in'", "'deps'"))
    val outEither = NodeTools.parseOut(outJson)
    (validateDescription(description, creating = true), validateDescriptionLong(descriptionLong)) match
      case (Some(err), _) => IO.pure(Left(err))
      case (_, Some(err)) => IO.pure(Left(err))
      case (None, None) =>
        (inIds, depsIds, outEither) match
          case (Left(err), _, _) => IO.pure(Left(ToolError(err)))
          case (_, Left(err), _) => IO.pure(Left(ToolError(err)))
          case (_, _, Left(err)) => IO.pure(Left(ToolError(err)))
          case (Right(ins), Right(deps), Right(out)) =>
            // 校验五（**2026-09-12 作者裁定重写**：out 可空置）：创建必须 (task ∨ in)——
            // **out 不再是创建必备边**（dangling 创建恢复合法：空 out = 零投递、零升根，
            // 结果保留在 result，接线后自动投递给新接下游）。给 out 则目标必须存在（存在性/
            // 环检在 proceed）且不得成环。in 侧 task 即连接（入口节点 task 创建即运行语义
            // 保持），in 边与 task 可并存；纯空挂载（无 task 无 in）仍由此拒（deps 是完成
            // 信号不是输入内容，不计入 in 侧）。错误码沿用 EMPTY_NODE_CONNECTION（供分发器自纠）。
            if !task.exists(_.trim.nonEmpty) && ins.isEmpty then
              IO.pure(
                Left(
                  ToolError(
                    s"Node '$nodename' must declare an input side — 'task' (entry semantics: starts running on create) or 'in' (barrier upstream). " +
                      "Out-only relay nodes are no longer supported. (EMPTY_NODE_CONNECTION)"
                  )
                )
              )
            // merge 校验①（merge-node 批 20260905 §权限选型③；worktree 布尔化后
            // contains(true) 语义）：合并节点不配 worktree——落地收口在 workspace 根仓
            // 执行，沙箱根必须 = workspace（.git 在根内可写）；配 worktree=true 则
            // 沙箱根 = worktree 目录，主仓 .git 在根外 → git 变更 EPERM。
            else if merge && worktree.contains(true) then
              IO.pure(
                Left(
                  ToolError(
                    "merge=true (batch landing sink) must NOT carry 'worktree' — a merge node lands on the workspace " +
                      "root repo; its sandbox root must be the workspace itself so .git is writable. Drop 'worktree'."
                  )
                )
              )
            // merge 校验②（mount-enforce 批 20260905，作者裁定「节点不允许空挂载」）：
            // merge=true 必须 ≥1 上游（in 非空）——合并节点靠上游 completed 投递清
            // in-barrier 触发（deliverOut→startNode），零上游=可触发点永不到达=空挂
            // pending 永不生效（实证 n-371cf932：merge=true、in=[] 悬挂 10+min，靠
            // 人工 abandon+重建救援）。合法创建顺序：先建上游节点，再建合并节点并
            // in=<上游 id>（in 声明自动改接各上游 out → 本节点）。
            else if merge && ins.isEmpty then
              IO.pure(
                Left(
                  ToolError(
                    "merge=true (batch landing sink) requires at least one upstream: declare 'in' with existing upstream node id(s). " +
                      "A merge node triggers only when its in-barrier clears via upstream completed delivery — with zero upstreams " +
                      "it can never fire (empty mount = pending forever). Create the upstream node(s) first, then create this merge " +
                      "node with in=<upstream-id(s)> (the in declaration rewires each upstream's out to this node). " +
                      "(NODE_MERGE_REQUIRES_UPSTREAM)"
                  )
                )
              )
            // ⑥ in 上限闸（U2/P1 · 2026-09-11 作者拍板）：合并节点 in ≤4，超限拆多个合并
            // 节点。此前纯纪律、引擎查无校验 ⇒ 一条超限 merge 会把 >4 条轨道压成单点
            // （落地冲突面与失败面同步放大）。边界 =4 合法 / ≥5 拒，码 NODE_MERGE_IN_CAP。
            else if merge && ins.distinct.size > NodeTools.MergeInCap then
              IO.pure(
                Left(
                  ToolError(
                    s"merge=true (batch landing sink) accepts at most ${NodeTools.MergeInCap} upstreams in one ledger — " +
                      s"this create declares ${ins.distinct.size} distinct upstream(s). Split the batch: create one merge " +
                      s"node per ≤${NodeTools.MergeInCap} upstream group, then wire the groups' merge nodes into a " +
                      "follow-up merge/report node. (NODE_MERGE_IN_CAP)"
                  )
                )
              )
            // (ii) W1 merge 口径（nodegate 方案件 §1(ii)，本批四项①，0 spawn）：merge
            // sink 建位必须带 out——落地收口的结果要经 out 交出去，空 out 落地结果滞留
            // （零投递零升根）。「先建 sink 后补 out」的 8–16 秒窗口（方案件 §2 形态③
            // 4/4 活证）由 dangling=true 显式登记（C-ii 令牌），零声明即拒。
            else if merge && out.isEmpty && !dangling then
              IO.pure(
                Left(
                  ToolError(
                    s"merge=true (batch landing sink) must declare 'out' — the landed result is delivered along out; with no edge it is retained with zero delivery. " +
                      "Declare out (e.g. a report/notify node), or pass dangling=true if this sink intentionally waits for its wiring. (NODE_MERGE_SINK_NEEDS_OUT)"
                  )
                )
              )
            else
              // 1. agent 存在性（新建恒 "general"——库缺 general = 环境残缺，fail-fast）
              EntityLoader.loadAgent(agentName).flatMap {
                case None =>
                  IO.pure(
                    Left(
                      ToolError(
                        s"Agent 'general' not found in global library — every node executes the general agent (plugin-architecture alignment); install/restore it first"
                      )
                    )
                  )
                case Some(_) =>
                  // 2. worktree 布尔派生（2026-09-05 显式化）：true → 即时创建（裁决 a，
                  //    fail-fast；失败拒绝建节点）；false/缺省 → workspace 直跑（现状）。
                  val viaWorktree: IO[Either[ToolError, String]] =
                    worktree match
                      case Some(true) =>
                        val ws = os.Path(rt.project.workspace)
                        // 必须是 git 仓**根**：--show-toplevel 输出与 workspace 一致。
                        // 只查 rev-parse 退出码会把「嵌在别的 git 仓子目录里的 workspace」
                        // 误判为 git 仓（worktree add 会挂到外层仓上）。
                        val repoRoot = os
                          .proc("git", "-C", ws.toString, "rev-parse", "--show-toplevel")
                          .call(cwd = ws, check = false, mergeErrIntoOut = true)
                        val isGitRoot = repoRoot.exitCode == 0 && repoRoot.out.trim() == ws.toString
                        if !isGitRoot then
                          IO.pure(
                            Left(
                              ToolError(
                                s"worktree=true requires the project workspace to be a git repository root (${ws} is not) — " +
                                  "run in the workspace instead (omit worktree), or git init the workspace first."
                              )
                            )
                          )
                        else
                          IO.blocking(createWorktreeFor(ws, nodename)).flatMap {
                            case Left(err) =>
                              IO.pure(
                                Left(
                                  ToolError(
                                    s"worktree=true auto-creation failed for node '$nodename' — node NOT created (fail-fast). git said: $err"
                                  )
                                )
                              )
                            case Right(bare) =>
                              proceed(
                                rt,
                                nodename,
                                agentName,
                                task,
                                description,
                                descriptionLong,
                                Some(bare),
                                plugins,
                                ins,
                                deps,
                                out,
                                merge,
                                dangling,
                                verifierRoutePending,
                                pluginsDeclared
                              )
                          }
                        end if
                      case _ =>
                        proceed(
                          rt,
                          nodename,
                          agentName,
                          task,
                          description,
                          descriptionLong,
                          None,
                          plugins,
                          ins,
                          deps,
                          out,
                          merge,
                          dangling,
                          verifierRoutePending,
                          pluginsDeclared
                        )
                  // loop verify agent 存在性（§2.6 校验②，0 spawn 拦截）：loop=true 时校验
                  // verify agent 可装载——缺失即拒（与 worker agent 同纪律，fail-fast）。
                  loopFlag.config match
                    case Some(lc) =>
                      EntityLoader.loadAgent(lc.verify).flatMap {
                        case None =>
                          IO.pure(
                            Left(
                              ToolError(
                                s"Verify agent '${lc.verify}' not found in global library — loop node requires a valid verify agent (worker '${agentName}', verify '${lc.verify}'). (NODE_LOOP_VERIFY_AGENT)"
                              )
                            )
                          )
                        case Some(_) => viaWorktree
                      }
                    case None => viaWorktree
              }

    end match

  end createNode

  private def proceed(
    rt: ProjectRuntime,
    nodename: String,
    agentName: String,
    task: Option[String],
    description: Option[String],
    descriptionLong: Option[String],
    worktree: Option[String],
    plugins: List[String],
    ins: List[String],
    deps: List[String],
    out: List[OutEdge],
    merge: Boolean = false,
    dangling: Boolean = false,
    verifierRoutePending: Boolean = false,
    pluginsDeclared: Boolean = false
  )(implicit
    notify: NodeEditNotify,
    loopFlag: NodeEditLoop,
    retryFlag: NodeEditRetry,
    roleFlag: NodeEditRole,
    chainDecl: NodeEditChainDecl
  ): IO[Either[ToolError, String]] =
    val nodeId = s"n-${java.util.UUID.randomUUID().toString.take(8)}"
    val outTargets = out.map(_.to).filterNot(_ == OutEdge.RootTarget).distinct
    for
      // 引用存在性 + 环检测（in 上游 → 本节点；deps 上游 → 本节点；本节点 → out 目标）。
      // wouldCreateCycle 已含 deps 反向边（deps 设计 §1.2 校验二：混合图单点覆盖）——
      // create 场景新节点无出边，环检天然为 false，保留调用与 in 对称（防御未来变化）。
      // chainmodel 批一 ③：deps 里的 `chain:<id>` 引用走**链可达性闸**（不是节点存在性
      // ——按字面查节点必然「查无此点」，报错文案指不到真正原因）；`in` 里的链引用 = 语法
      // 误用，静态可行动报错（链引用是 deps 专属语法）。
      inOk <- ins.traverse { id =>
        if FlowMapStore.isChainRef(id) then IO.pure(Left(NodeTools.chainRefInInputError(id)): Either[String, Unit])
        else NodeTools.ensureNodeExists(rt, id)
      }
      depsOk <- deps.traverse { id =>
        if FlowMapStore.isChainRef(id) then NodeTools.chainRefExists(rt, id)
        else NodeTools.ensureNodeExists(rt, id)
      }
      cycleIn <- ins.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
      cycleDeps <- deps.traverse(id => NodeTools.wouldCreateCycle(rt, fromId = id, to = nodeId))
      // out 目标解析（20260909 in 丢失事故修复面）：接受节点 id 或节点名，校验/环检/
      // 镜像记账全部在解析后的 id 上做——名字形态不再被 id-only 校验误拒，也不再绕过
      // 环检测（原实现按原始串查 Map，名字串查不到 → 环检测静默放行）。
      outResolved <- outTargets.traverse(t => NodeTools.resolveOutTarget(rt, t).map(opt => t -> opt))
      outOk = outResolved.map { case (t, opt) =>
        opt.toRight(
          s"Referenced node '$t' not found in project '${rt.project.name}' (not a node id, nor any node's name)"
        )
      }
      cycleOut <- outResolved.traverse { case (_, opt) =>
        opt match
          case Some(tid) => NodeTools.wouldCreateCycle(rt, fromId = nodeId, to = tid)
          case None => IO.pure(false) // 悬空已被 outOk 拒，环检无意义
      }
      // P1 校验层①（spec §2.2）：指向 merge 的 on-failed 边 → 硬拒（NODE_MERGE_PASS_ONLY）
      mergeGate <- NodeTools.ensureMergePassOnly(rt, out)
      // P1 校验层②（spec §2.2）：下游持 in 边而上游已 failed 无 on-failed 边且非 merge →
      // WARNING（不阻断，人工兜底合法）——补投链/死锁可见性由既有 mount-stalled 承载
      stallWarn <- NodeTools.stalledInWarning(rt, nodeId, nodename, ins, merge)
      // 通知策略自检（b64 批；M3/R14/spec §4.2 三条，仅 WARNING 不阻断）
      // B-3 修（2026-09-14）：此处传**未声明即缺键**（不再用 `NotifyPolicy.Default` 填
      // `Some`）。本函数的 `policy` 形参语义即「生效策略（缺键 ⇒ legacy 三态推断）」
      // ——旧写法把「用户未声明」合成 `Some("dispatcher")`，等于让默认值顶掉 legacy
      // 推断（与落盘点同一处回归），警告面会与实际生效策略不一致。
      notifyWarn <- NodeTools.notifyPolicyWarnings(rt, nodename, notify.policy, notify.flag, out)
      // pending verifier ⚠（C-i ②可见性，本批四项③）：令牌放行的空 out verifier，
      // 回执必须把「暂无 fail 路由 = 永远无法重跑被拒对象」说在决策当下。纯判据
      // （能走到回执 ⇒ 闸已放行 ⇒ 只剩「空 out + pending」形态）。
      pendingRouteWarn =
        if verifierRoutePending &&
          NodeRoles.normalize(roleFlag.role.getOrElse(NodeRoles.Task)) == NodeRoles.Verifier &&
          !OutEdge.canonical(out).exists(_.on.contains(OutEdge.Fail))
        then
          Some(
            s"⚠ verifier '$nodename' has no fail route yet (declared pending) — it can never re-run a rejected target until '(fail)<worker>:loop' is wired. (verifierRoutePending)"
          )
        else None
      // P2a flag-off 警告（nodegate 方案件 §1(iii)，本批四项④）：声明了 plugins 但
      // 全局开关关闭 ⇒ 声明被静默丢弃是「声明了却没真挂」的可机械判面——必须警告，
      // 禁静默（flag off 是运维回滚态，故不硬拒）。
      pluginsFlagWarn <-
        if pluginsDeclared then
          nebflow.core.plugin.PluginsConfig.enabled.map {
            case false =>
              Some(
                "⚠ plugins were declared on this node but plugins.enabled=false — the declaration was NOT applied (flag-off rollback state): the node runs with an EMPTY capability face. Re-enable plugins or drop the declaration. (plugins flag off)"
              )
            case _ => None
          }
        else IO.pure(None)
      // P3 文本扫描警告（nodegate 方案件 §1(iii) D7 采纳为警告，本批四项④）：task
      // 文本命中 Catalog 插件名而 plugins 空 ⇒ 提示（不硬拒——正文引用/提及会误报）。
      // 判据纯读（Catalog 枚举一次 + contains 匹配）；仅建位面（编辑面 task 语义
      // 另有重激活链，不在本批范围）。
      pluginsTextWarn <-
        if plugins.isEmpty && task.exists(_.trim.nonEmpty) then
          nebflow.core.plugin.PluginRegistry.listWithRejected().map { case (defs, _) =>
            defs
              .map(_.name)
              .filter(n => task.exists(_.contains(n)))
              .take(3)
              .map(n =>
                s"⚠ task text mentions plugin '$n' (Catalog) but plugins=[] — if this node needs it, pass plugins=[\"$n\"]; otherwise ignore this notice. (warning only)"
              )
          }
        else IO.pure(Nil)
      // loop 门集预检（2026-09-12 裁定 2/3，0 spawn）：本次创建会写入两条路径的最终边集
      // ——① 本节点 out（`(pass)Nebula` 单腿等形态）；② 每个 in 上游的 out 镜像追加
      // （`appendEdgeTo`，下游 in: 声明路）。任一违规 ⇒ 整调用拒绝（节点不落库、上游零改边）。
      loopGate <- rt.store.snapshot.map { s =>
        NodeTools
          .loopGateViolation(loopFlag.config.exists(_.enabled), nodename, out)
          .orElse(NodeTools.loopGateForAppends(s.nodes, ins, nodeId))
          // verdict 选通镜像腿（nodegate 方案件 §1(i)，本批四项③）：in 上游里的
          // verifier 被追加 pass 边后仍无 fail 路由 ⇒ 拒（**镜像腿不豁免** pending——
          // 否则立刻变成「有边无 fail 路由」= 缺陷形态本身）。
          .orElse(NodeTools.verdictRouteGateForAppends(s.nodes, ins, nodeId))
      }
      // 批E2 retry 风暴防护（spec §2.3，0 spawn）：邻居限定 + retry 环。值域 =
      // 本次声明的 in ∪ deps（节点尚未落库，in 邻接关系沿声明）。
      retryGate <- retryFlag.policy match
        case None => IO.pure(None)
        case Some(p) => NodeTools.retryGuard(rt, nodeId, nodename, p, ins ++ deps)
      // verdict 选通校验族（nrloop 一期 2026-09-12，设计 §3.3 #13，0 spawn）：
      // 角色 × fail 门 × :loop 控制边 —— 见 NodeTools.verdictRouteGate 判据表。
      // 与批 A 的 loop 门集不变量并列（后者由下方 loopGate 判），互不覆盖。
      verdictGate <- NodeTools.verdictRouteGate(
        rt,
        nodeId,
        nodename,
        roleFlag.role.getOrElse(NodeRoles.Task),
        loopFlag.config.exists(_.enabled),
        out,
        verifierRoutePending
      )
      // loop detect（§2.6）：入口节点（有 task）同 agent+task 归一化重复 → 拒
      dup <- task match
        case Some(t) if t.trim.nonEmpty => NodeTools.findDuplicateDispatch(rt, agentName, t)
        case _ => IO.pure(None)
      // 校验短路（0 spawn）
      validated <-
        if inOk.exists(_.isLeft) then
          IO.pure(Left(ToolError(inOk.collectFirst { case Left(e) => e }.getOrElse("invalid in"))))
        else if depsOk.exists(_.isLeft) then
          IO.pure(Left(ToolError(depsOk.collectFirst { case Left(e) => e }.getOrElse("invalid deps"))))
        else if cycleIn.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: adding in would create a loop (A→B→A) — DAG must stay acyclic")))
        else if cycleDeps.exists(identity) then
          IO.pure(Left(ToolError(s"Cycle detected: adding deps would create a loop — DAG must stay acyclic")))
        else if outOk.exists(_.isLeft) then
          IO.pure(Left(ToolError(outOk.collectFirst { case Left(e) => e }.getOrElse("invalid out"))))
        else if cycleOut.exists(identity) then
          IO.pure(
            Left(
              ToolError(
                s"Cycle detected: out → ${outTargets.mkString(", ")} would create a loop — DAG must stay acyclic"
              )
            )
          )
        else if notify.invalid.isDefined then
          // notify 值域（b64 批 R1/R2；错误码 NODE_NOTIFY_INVALID，文案含合法值域 + 实收值）
          IO.pure(Left(ToolError(notify.invalid.get)))
        else if mergeGate.isDefined then IO.pure(Left(ToolError(mergeGate.get)))
        else if retryGate.isDefined then IO.pure(Left(ToolError(retryGate.get)))
        else if verdictGate.isDefined then IO.pure(Left(ToolError(verdictGate.get)))
        else if loopGate.isDefined then IO.pure(Left(ToolError(loopGate.get)))
        else if dup.isDefined then
          IO.pure(
            Left(
              ToolError(
                s"疑似重复派发: agent '$agentName' already has a ${dup.get.status} node with the same task (node '${dup.get.name}'). Check NodeList before re-dispatching."
              )
            )
          )
        else IO.pure(Right(()))
      result <- validated match
        case Left(err) => IO.pure(Left(err))
        case Right(_) =>
          val now = System.currentTimeMillis()
          val node = NodeDef(
            id = nodeId,
            name = nodename,
            agent = agentName,
            worktree = worktree,
            // panelscheme 批（2026-09-21）：preset 参数退役——新建节点不再携带节点级
            // 方案（NodeDef.preset 字段保留，存量数据显示/审计用）。
            task = task,
            description = description.map(_.trim),
            descriptionLong = descriptionLong.map(_.trim),
            in = Nil,
            deps = deps,
            merge = merge,
            // LoopNode 配置（LoopNode 批 2026-09-06）：loop=true 时 NodeDef.loop=Some(LoopConfig)，
            // 否则 None（普通节点）。loop 不构成「可立即运行」的输入语义——仍须 task/in 承载
            // 输入（与 deps 同款：完成信号/迭代语义不是输入），入口/barrier 判定不变。
            loop = loopFlag.config,
            // P2 failed 回跳 retry 策略（spec §2.3）：未传 None（旧行为）；传了 = 校验
            // 通过的 RetryPolicy（下游单侧持有）。
            retry = retryFlag.policy,
            // dispatch-notify 回流标志（创建期按需开启；缺省 false=分发器新建节点
            // 不继承——收敛保证见 DispatchNotify）
            notifyDispatcher = notify.flag,
            // **通知策略（b64 批 R2；B-3 修 2026-09-14）**：创建期按**用户是否显式
            // 声明**落盘——传了就是传的值；**未传 = 缺键 `None`**（与显式 `null` 同落
            // 缺键 ⇒ 回落 legacy 解析）。
            // B-3 裁定：**默认值不得覆盖显式门集**。旧写法 `Some(getOrElse(Default))`
            // 让每个新建节点恒落 `Some("dispatcher")`，于是「显式写 `(pass,failed)Nebula`
            // = 上根声明」这条 08:04 前契约被一个**用户从未声明的默认值**静默覆盖
            // （引擎自证：`has Nebula out-edge(s) but notify=dispatcher ... SUPPRESSED`）。
            // 缺键节点的效力 = legacy 解析（`NotifyPolicy.completedRootVisible` 的
            // `None` 分支：`:result` 且门含 `pass` 的 Nebula 边 ⇒ 投根），与
            // `NodeEditTool.description` 中「ROOT NOTIFY needs a gate set」的承诺一致。
            notifyPolicy = notify.policy,
            out = OutEdge.canonical(out),
            status = if task.isDefined && ins.isEmpty then NodeLifecycle.Pending else NodeLifecycle.Wiring,
            createdAt = now,
            plugins = plugins,
            // 节点角色（nrloop 一期 2026-09-12，设计 §3.2）：create-only，未传 = task
            // （旧行为零变化——存量/既有调用方全落在 task 面）。
            role = roleFlag.role.getOrElse(NodeRoles.Task),
            // 链归属声明（chainmodel 批一 ①，2026-09-19）：建位参数面 = NodeEdit 的
            // `chainId`，持久面 = 本字段。**声明即归属**（恒为该值，与它等谁/被谁等/
            // 被谁汇聚无关）；未传 = None = 未声明 ⇒ 归属走派生兜底轨（存量数据全走
            // 此路 ⇒ 零迁移）。值域已在 call() 前置闸拒非法值（NODE_CHAIN_ID_INVALID）。
            // 🔴 纯元数据：不参与任何调度判据（deps 才是闸），无「创建即运行」影响。
            chainId = chainDecl.decl
          )
          // 单事务：加节点（deps 单侧持有，无上游侧镜像边要写）+ in 边（上游 out 追加 → 本节点）
          // + out 边（每个非 Nebula 目标 in 追加本节点）。P1 多边：in 声明为上游 out **追加**
          // 指向本节点的缺省 pass 边（不覆盖既有门控，扇出拓扑零扰动）。
          val mutateIO = rt.store.mutate { s =>
            val withNode = s.copy(nodes = s.nodes + (nodeId -> node))
            val insNodes = ins.foldLeft(withNode.nodes)((acc, upId) => NodeTools.appendEdgeTo(acc, upId, nodeId))
            val withIns = withNode.copy(nodes = insNodes)
            val withInList = ins.foldLeft(withIns) { (acc, upId) =>
              acc.nodes.get(nodeId) match
                case Some(n) => acc.copy(nodes = acc.nodes.updated(nodeId, n.copy(in = (n.in :+ upId).distinct)))
                case None => acc
            }
            val outNodes = out.foldLeft(withInList.nodes)((acc, e) =>
              // **红线①（nrloop 一期 2026-09-12，设计 §3.5 R5(a)）**：`:loop` 控制边
              // **不写 in 镜像**——与 `setOut` 同款过滤（此处是第三个镜像写点；漏掉它
              // 会让 `(fail)<worker>:loop` 在创建期把 verifier 记进 worker 的 in ⇒
              // worker 等 verifier、verifier 等 worker 的 round-1 barrier 死锁）。
              if e.to != OutEdge.RootTarget && !OutEdge.isLoopEdge(e) then
                // in 镜像按解析后 id 记账（同 setOut——20260909 事故修复）：目标串可能是名字
                OutEdge.resolveTargetId(acc, e.to) match
                  case Some(tid) =>
                    acc.get(tid) match
                      case Some(tn) => acc.updated(tid, tn.copy(in = (tn.in :+ nodeId).distinct))
                      case None => acc
                  case None => acc
              else acc
            )
            withInList.copy(nodes = outNodes.updated(nodeId, outNodes(nodeId).copy(out = OutEdge.canonical(out))))
          }
          // create 回执一致性断言（20260909 in 丢失事故护栏①）：回执返回前写后读，
          // 断言 in 已随节点同事务落定。现实现里 in 追加与节点插入在同一 Ref 事务
          // （不可分离）；本断言是防回归哨兵——in 追加若被挪出 mutate（或未来重构
          // 引入后置写）即在此显式红，杜绝「回执成功但 in 空 → barrier 空真」的静默形态。
          val createIO: IO[Either[ToolError, String]] =
            // 链归属变更留痕（chainmodel 批一 ⑤）需要**写前**归属视图：先取快照再执行
            // mutate（IO 顺序保证快照早于任何写动作）。`chainIdView` 是纯派生（分量 +
            // 声明），读快照即得写前归属；写后视图由 emit 内部现读 ⇒ 两次读数各有其时点。
            rt.store.combinedNodes.flatMap { chainBefore =>
              mutateIO.flatMap { s =>
                val created = s.nodes(nodeId)
                val missingIn = ins.filterNot(created.in.contains)
                if missingIn.nonEmpty then
                  val msg =
                    s"engine consistency tripwire: node '$nodename' ($nodeId) was created but in edge(s) [${missingIn.mkString(", ")}] are missing from the persisted state " +
                      s"(in=[${created.in.mkString(", ")}]) — its in-barrier would silently never fire. (ENGINE_IN_RECEIPT_MISMATCH)"
                  IO(logger.errorSync(s"[node.tools] $msg")).as(Left(ToolError(msg)))
                else
                  (
                    // 建位声明审计（nodegate 方案件 D8 采纳，本批四项②③）：两类显式许可
                    // 必须可事后对齐——被拒面查无事件、被放行面有迹可循（校验失败现况零
                    // 审计的补偿面；FlowMapEventLog.append 追加式先例 = abandoned /
                    // reactivated，零载荷漂移）。
                    (if dangling && out.isEmpty then
                       FlowMapEventLog.append(
                         rt.project.workspace,
                         rt.project.name,
                         nodeId,
                         "dangling-declared",
                         "node created with dangling=true (empty out declared intentional; result retained until wired)"
                       )
                     else IO.unit) *>
                      (if pendingRouteWarn.isDefined then
                         FlowMapEventLog.append(
                           rt.project.workspace,
                           rt.project.name,
                           nodeId,
                           "verifier-route-deferred",
                           "verifier created without fail route (verifierRoutePending=true) — wire '(fail)<worker>:loop' to enable re-run routing"
                         )
                       else IO.unit) *>
                      // 新节点事件（NodeList 同构 payload，in/out 以 store 最终态为准）
                      rt.engine.emitCreated(s.nodes(nodeId)) *>
                      // wiring 变更事件（barrier 合并接线 §2.3）：上游 out 追加指向本节点 +
                      // out 目标 in 追加——此前只有 nodeCreated，改写的节点无事件（缺失补齐）
                      NodeTools.emitWiringUpdates(rt, ins ++ out.map(_.to).filterNot(_ == OutEdge.RootTarget)) *>
                      // D1 修复（验收④b，@a4b5d184 spec 实证）：in 引用已完成上游（活动区
                      // 或归档区——findNode 兜底）→ 立即投递其结果，等同 §2.3「悬空节点
                      // out 接入下游 = 已完成节点改接」路径。归档节点活动区已消失，
                      // completeNode 的 deliverOut 不会再触发，唯一投递入口就是这里。
                      // 运行中上游不投递（barrier 等待语义——completeNode 时 deliverOut 自然投）。
                      // 后台化（收口③）：deliverOutTo 内 startNode 同步等下游节点终态，
                      // 直接调用会阻塞 NodeEdit 工具 fiber（见 runDetached 注释）。
                      // merge 分流（mount-enforce 批）：合并节点创建时 in 引用已 failed 的
                      // 上游（创建顺序翻转后的合法形态）——failed 错误文本≠产物，不作输入
                      // 投递，走 MergeNodePolicy 转 blocked 可见终态不悬挂（与 deliverFailed
                      // 运行期路径同语义单点 mergeBlockedByUpstreamFailure）；非 merge 下游
                      // 的 failed 错误投递语义保持不变。
                      NodeTools.runDetached(rt, s"deliver retained upstream results -> $nodeId")(
                        ins.traverse_ { upId =>
                          rt.store.findNode(upId).flatMap {
                            // R1（blocked 反馈重入设计 §6）：blocked 是终态且 result=反馈渲染串，
                            // 但反馈串不是可投结果——D1 显式排除 blocked（failed 错误投递语义保持不变）
                            case Some(up)
                                if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal
                                  .contains(up.status) =>
                              if s.nodes(nodeId).merge && up.status == NodeLifecycle.Failed then
                                rt.engine.mergeBlockedByUpstreamFailure(s.nodes(nodeId), up, up.result.get)
                              else rt.engine.deliverOutTo(up, nodeId, up.result.get)
                            case _ => IO.unit
                          }
                        }
                      ) *>
                      // D1-deps 补触发（deps 设计 §1.2，裁定③内明确要求）：接线后 deps 全满足
                      // 且 in barrier 已归零且自身 wiring/pending → 立即启动（上游已 completed
                      // 时接线即时触达，含归档上游——findNode 兜底）。与入口启动互斥（入口条件
                      // task && ins.isEmpty 独占启动，防双 startNode 并发 spawn）；ins 非空时
                      // D1 in-delivery 链的 barrier 结算同样经 startNode 闸门，双路径幂等无害。
                      (if !(task.isDefined && ins.isEmpty) then
                         NodeTools.runDetached(rt, s"D1-deps settle -> $nodeId")(
                           rt.store.getNode(nodeId).flatMap {
                             case Some(n)
                                 if (n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending)
                                   && n.in.forall(n.deliveredTo.contains) =>
                               rt.engine
                                 .depsSatisfied(n)
                                 .flatMap(ok => if ok then rt.engine.startNode(n.id) else IO.unit)
                             case _ => IO.unit
                           }
                         )
                       else IO.unit) *>
                      // 入口节点（task 且无 in）→ 创建即运行。后台化（收口③根因修复）：
                      // startNode 同步等节点整个任务跑完，直接调用会把分发器 turn 卡在
                      // NodeEdit 上直到节点完成（实测单 turn 56min），违背工具契约
                      // 「async, non-blocking」。deps 不构成「可立即运行」——即使误判也被
                      // startNode 内 deps 闸门拦下（deps 设计 §1.2）。
                      // 合并节点例外（merge-node 批 20260905）：merge=true 时 task 只是
                      // 落地指令集，**不构成可立即运行的入口语义**——落地收口必须等齐
                      // 全部上游（in barrier 归零后由上游投递链经 startNode 启动）。
                      // 否则合并节点会在零上游时开跑（S1 E2E 实测捕获）。
                      (if task.isDefined && ins.isEmpty && !merge then
                         NodeTools.runDetached(rt, s"start entry node $nodeId")(rt.engine.startNode(nodeId))
                       else IO.unit) *>
                      // 链归属变更留痕（chainmodel 批一 ⑤）：create 也是归属变更的写点——
                      // ①显式声明（reason=declaration，单成员声明链照样成链）；②新节点把两个
                      // 既有分量桥接成一条链时，**原成员**的链号归并（reason=fallback）此前
                      // 完全静默（判据见 emitChainMembershipChanges：全节点前后视图比对）。
                      NodeTools.emitChainMembershipChanges(rt, chainBefore)
                  ).as(
                    Right(
                      s"Node '$nodename' ($nodeId) created in project '${rt.project.name}'" +
                        (if task.isDefined && ins.isEmpty && !merge then " — entry node started running." else "") +
                        (if deps.nonEmpty then s" deps ← ${deps.mkString(",")}" else "") +
                        (if out.nonEmpty then s" out → ${out.map(_.to).mkString(", ")}" else "") +
                        (if chainDecl.decl.isDefined then s" chainId ← ${chainDecl.decl.get}" else "") +
                        (retryFlag.policy match
                          case Some(p) => s" retry ← ${p.upstream}:max=${p.max}"
                          case None => "") +
                        (if stallWarn.nonEmpty then "\n" + stallWarn.mkString("\n") else "") +
                        // 通知策略自检（b64 批；WARNING 族，含 M3「silent ∧ 链末端只警告」）
                        (if notifyWarn.nonEmpty then "\n" + notifyWarn.mkString("\n") else "") +
                        // 悬空提示（O-B 必做 4 + C-ii 升级，本批四项②）：声明了 dangling ⇒
                        // 带意图文案（declared 标记）；未声明保持原文案（可疑口径）。
                        (if out.isEmpty then
                           "\n" +
                             (if dangling then NodeTools.wiringGapHintDeclared(nodename, nodeId)
                              else NodeTools.wiringGapHint(nodename, nodeId)).mkString("\n")
                         else "") +
                        // pending verifier ⚠（C-i ②，本批四项③）+ plugins 声明面警告
                        // （P2a flag-off / P3 文本扫描，本批四项④）——WARNING 族同款形态
                        (if pendingRouteWarn.isDefined then "\n" + pendingRouteWarn.get else "") +
                        (if pluginsFlagWarn.isDefined then "\n" + pluginsFlagWarn.get else "") +
                        (if pluginsTextWarn.nonEmpty then "\n" + pluginsTextWarn.mkString("\n") else "")
                    )
                  )
                end if
              }
            }
          createIO
    yield result
    end for
  end proceed

  // ── 编辑 ─────────────────────────────────────────────

  /**
   * abandon 动作（blocked 反馈重入设计 §7.7）：终态节点 → status=cancelled + **摘边**
   * （案 A，2026-09-14 作者 17:24 拍板）+ 审计事件。
   *
   * **归档语义订正（2026-09-14）**：旧措辞「failed/cancelled 永不自动归档」**过强**
   * 且易被读成「cancelled 永不出图」——准确表述 = **无节点级出图路径（无节点级 TTL），
   * 但可随其全终态分量被 30s 链级 sweep 自动归档**（`FlowMapStore.chainArchivable`
   * 自 2026-09-08 P1 起对 cancelled **显式放行**）。本批摘边正是把「分量永不全终态」
   * 这一堵点摘掉：退役节点自成全终态分量后由 sweep 正常出库（**归档留底，非删除**）。
   * 场景存档（provisional）：措辞订正的权威 = 作者 2026-09-14 17:24 裁定。
   *
   * 分发器处置 blocked 节点的「放弃」载体；NodeCancel 语义不动（仅 running——
   * `cancelNode` 路径判据 / `detach` 默认 / `notify` 抑制口径**逐字不变**，回归钉见
   * `AbandonDetachSpec`）。
   * R2 纪律：mutate 内现读 fresh，fresh 已非终态（并发重激活）→ 拒写（拒写时**不摘边、
   * 不清理**——写点没发生就不申报已发生的事实）。
   *
   * 接受域扩展（裁定①待实施语义，deps 设计 §1.6）：终态 ∪ wiring/pending——拓扑
   * 清场时退役节点常是活的 wiring/pending（「悬空活节点」无处置出口），abandon 是
   * 其唯一出口（NodeCancel 仅 running）。wiring/pending 无在飞会话，无中断副作用；
   * 被退役节点的上游其后完成时 deliverOut → startNode 幂等跳过（cancelled ∈ Terminal）。
   *
   * 死会话 running 收殓（清场 c-②，20260903 03:04 清场误杀事故复盘）：running 且
   * 无在飞执行 fiber（会话死于传输中断/实例重启泄漏）→ 可收殓（cancelled，无 TTL，
   * 摘边后由链级 sweep 归档——2026-09-14 口径）。
   * 误杀防护（硬约束）：活 running（isRunning=true = 有在飞 fiber，取消信号可达）
   * 绝对拒绝，只能走 NodeCancel。无复活竞态：running 节点不会被 startNode 二次
   * spawn（入口状态幂等跳过），死会话不可能复活 → 预检后无需事务内复查 IO 信号。
   */
  private def abandonNode(rt: ProjectRuntime, node: NodeDef): IO[Either[ToolError, String]] =
    def doAbandon(allowDeadRunning: Boolean): IO[Either[ToolError, String]] =
      for
        now <- IO(System.currentTimeMillis())
        s <- rt.store.mutate { st =>
          st.nodes.get(node.id) match
            // R2：fresh 仍可收殓（终态/wiring/pending；或预检过死会话的 running）
            // 才写——并发重激活成 running 且未预检死会话时拒写（该窗口内节点已有
            // 在飞会话，abandon 不得中断）
            case Some(fresh) if fresh.status != NodeLifecycle.Running || allowDeadRunning =>
              st.copy(nodes =
                st.nodes.updated(
                  node.id,
                  rt.engine.withoutReportPending(
                    fresh.copy(
                      status = NodeLifecycle.Cancelled,
                      completedAt = Some(now),
                      // 2026-09-07 作者裁定：cancelled 无 TTL 强制清——abandon 是上层
                      // 裁决动作，节点留主图（不再 24h 后静默消失），由后续拓扑清理处置。
                      ttlExpireAt = None
                    )
                  )
                )
              )
              // 未申报计时同事务清表（noderpt 批 F3，2026-09-11 复核 D3 修复）：abandon
              // 是第 7 个**不经 run fiber** 的终态写点（`allowDeadRunning=true` 分支专门
              // 收殓「Running + 无活 fiber」的死会话节点，那类节点永远到不了
              // `cleanupRunTables`）⇒ 不清表则持久层/归档残留「终态节点带待申报计时」的
              // 误导态（复核探针 P2 实测残留）。复用引擎侧公共纯函数单点（同判据，防
              // 第 8 个写点再漏）。
            case _ => st // 状态已变（并发重激活/移除）→ 拒写
        }
        out <- s.nodes.get(node.id) match
          case Some(c) if c.status == NodeLifecycle.Cancelled =>
            // ── 案 A 摘边（cancelled 滞留主图修复批 2026-09-14，作者 17:24 拍板）──
            // 语义全文与自决项 = `NodeEngine.detachAbandonedNode` 头注；本处只负责
            // 「先落终态、再摘边」（顺序与 cancelNode 的 detach-first 相反，理由：
            // 状态写点有 R2 并发拒写分支，先摘边会在被拒时留下「没退役却已摘边」
            // 的不一致；`priorStatus` 传**写前**现值，供 deps 轨「已满足否」判定）。
            rt.engine.detachAbandonedNode(c.id, node.status).flatMap { d =>
              rt.engine.emitUpdated(c) *>
                // 顺带小件②（零提交 worktree 回收腿）：先跑完再落审计，好把结果写进
                // 同一行 `abandoned` 事件（不新增事件类型）。
                reclaimAbandonedWorktree(rt, c).flatMap { wtNote =>
                  FlowMapEventLog.append(
                    rt.project.workspace,
                    rt.project.name,
                    node.id,
                    "abandoned",
                    s"node abandoned via NodeEdit (${node.status}${
                        if allowDeadRunning && node.status == NodeLifecycle.Running then "/dead-session" else ""
                      } → cancelled, retained on map)" +
                      (if d.referrers.nonEmpty then
                         s"; incident edges detached — repaired referrers: ${d.referrers.mkString(",")}" +
                           s" (in-mirrors=${d.inMirrors.size} out-refs=${d.outRefs.size} deps-refs=${d.depsRefs.size})"
                       else "; no incident edges (already a lone node)") +
                      (if wtNote.nonEmpty then s"; $wtNote" else "")
                  ) *>
                    // P2 G11（spec §3.4）：cancelled 级联清理该会话 pending asks（与
                    // cancelNode/reap 同款单点——问出问题的节点被放弃，卡片必须关闭）。
                    rt.engine.cleanupPendingAsks(c.sessionRef).as(abandonReceipt(c))
                }
            }
          case _ =>
            // 状态未落（并发重激活/移除 → R2 拒写）：文案与本批前逐字一致（未摘边、
            // 未清理——写点没发生就不该申报已发生的事实）。
            IO.pure(s"Node '${node.name}' abandoned — cancelled (retained on map, no TTL; result retained)")
      yield Right(out)

    if node.status == NodeLifecycle.Running then
      rt.engine.isRunning(node.id).flatMap {
        case true =>
          IO.pure(
            Left(
              ToolError(
                s"Node '${node.name}' is running with a live session — abandon refused (mis-kill protection: abandon accepts terminal/wiring/pending and dead-session running only). Use NodeCancel for running nodes."
              )
            )
          )
        case false =>
          doAbandon(allowDeadRunning = true)
      }
    else doAbandon(allowDeadRunning = false)

  end abandonNode

  /**
   * abandon 回执（顺带小件①，2026-09-14）：「result retained」对**从未开工**的目标
   * （`startedAt=null ∧ result=null`，考古批实测 4/4 如此）是**空承诺**——abandon 分支
   * 不写 result（与 `cancelNode` 写 `cancelled[source=…]: reason=…` 不同），那类节点没有
   * 「已保留的结果」可言。故按「有没有产出」分叉：无产出如实写「已取消并摘边；无产出」，
   * **有产出件文案逐字照旧**（零回归）。这是本次「以为是 bug」的助燃剂之一（案 F）。
   */
  private def abandonReceipt(c: NodeDef): String =
    val noOutput = c.startedAt.isEmpty && c.result.isEmpty
    if noOutput then
      s"Node '${c.name}' abandoned — cancelled + incident edges detached; no output was produced " +
        "(nothing to retain, no TTL — the chain sweep archives it once its component is terminal)"
    else s"Node '${c.name}' abandoned — cancelled (retained on map, no TTL; result retained)"

  /**
   * abandon 的**零提交 worktree 回收腿**（顺带小件②，2026-09-14）：退役节点不留空
   * 工作目录 + 空分支（考古批实测存量遗留 = 1 个零提交 worktree + 同名空分支，
   * `git log main..<branch>` 零提交，且**无人工清理入口**）。
   *
   * 两条判据**同时**成立才动手（任一不成立 ⇒ 一行不动，返回「不动的原因」）：
   *   ① 工作目录 `git status --porcelain` **零行**（干净）；
   *   ② 分支相对基线（main；无 main 回落 HEAD）**零提交**（`rev-list --count <base>..<branch>` = 0）。
   * 动作 = `git worktree remove <dir>` 然后 `git branch -d <name>`。
   * 🔴 **禁 `-D` / 禁 `--force`**（2026-09-10 J7 纪律：强删丢弃审计血缘；`branch -d`
   * 自带的「未合并即拒」正是本腿的第二道保险）。**非零提交 ⇒ 分支与目录原样保留**。
   *
   * best-effort：任何异常/非零退出 ⇒ 只回落成「不动 + 原因」（写进 `abandoned` 事件），
   * **绝不影响 abandon 本身的成功**（状态与审计已落盘）。幂等：目录/分支已不在 ⇒
   * 前置查询直接给出 no-op。
   */
  private def reclaimAbandonedWorktree(rt: ProjectRuntime, n: NodeDef): IO[String] =
    n.worktree match
      case None => IO.pure("")
      case Some(raw) =>
        IO.blocking {
          try
            val ws = os.Path(rt.project.workspace)
            def git(cwd: os.Path, args: String*): Either[String, String] =
              val res = os.proc(Seq("git") ++ args).call(cwd = cwd, check = false, mergeErrIntoOut = true)
              if res.exitCode != 0 then Left(res.out.trim().take(300)) else Right(res.out.trim())
            PathUtil.normalizeWorktree(raw) match
              case Left(_) =>
                s"worktree value '${raw.take(40)}' is not a normalizable name — left untouched"
              case Right(bare) =>
                PathUtil.resolveWorktreeDir(ws, bare) match
                  case None =>
                    s"worktree '$bare' is not present in either location — nothing to reclaim"
                  case Some(dir) =>
                    val base = if git(ws, "rev-parse", "--verify", "--quiet", "main").isRight then "main" else "HEAD"
                    git(dir, "status", "--porcelain") match
                      case Left(e) =>
                        s"worktree '$bare' left untouched — 'git status' failed: $e"
                      case Right(st) if st.nonEmpty =>
                        s"worktree '$bare' left untouched — dirty (${st.linesIterator.size} line(s) in status --porcelain)"
                      case Right(_) =>
                        git(ws, "rev-list", "--count", s"$base..$bare") match
                          case Left(e) =>
                            s"worktree '$bare' left untouched — commit count unreadable: $e"
                          case Right(c) if c != "0" =>
                            s"worktree '$bare' kept — $c commit(s) ahead of $base (non-zero-commit is never reclaimed)"
                          case Right(_) =>
                            git(ws, "worktree", "remove", dir.toString) match
                              case Left(e) =>
                                s"zero-commit worktree '$bare' left untouched — 'git worktree remove' refused: $e"
                              case Right(_) =>
                                git(ws, "branch", "-d", bare) match
                                  case Left(e) =>
                                    s"zero-commit worktree '$bare' removed; branch kept — 'git branch -d' refused: $e"
                                  case Right(_) =>
                                    s"zero-commit worktree '$bare' removed + branch deleted (-d)"
                    end match
            end match
          catch
            case e: Throwable =>
              s"worktree reclaim skipped — ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
        }

  private def editNode(
    rt: ProjectRuntime,
    node: NodeDef,
    task: Option[String],
    description: Option[String],
    descriptionLong: Option[String],
    abandon: Boolean,
    worktree: Option[Boolean],
    pluginsOpt: Option[List[String]],
    inJson: Option[Json],
    depsJson: Option[Json],
    outJson: Option[Json],
    ctx: ToolContext,
    /**
     * 显式授权入口 `NODE_COMPLETED_REACTIVATION`（nrloop 一期 2026-09-12，附 C5①）：
     * true = 有权限把 **completed** 节点重激活回 wiring/pending 重跑一轮（默认 false
     * ⇒ completed 节点的编辑行为逐字不变：改接只补投递既有结果，不重跑）。
     */
    reactivateCompleted: Boolean = false
  )(implicit
    notify: NodeEditNotify,
    loopFlag: NodeEditLoop,
    retryFlag: NodeEditRetry,
    roleFlag: NodeEditRole,
    chainDecl: NodeEditChainDecl
  ): IO[Either[ToolError, String]] =
    // ── 动作分支 ──
    // role create-only（nrloop 一期 2026-09-12，设计 §3.2）：角色 = 拓扑身份（决定
    // node_report 值域与 verdict 选通合法性），中途改会让已落盘的值域/路由语义与
    // 声明面脱节 ⇒ 与 merge/worktree 同口径硬拒（可行动：新建节点替代）。放在最前
    // ——任何副作用（含 abandon）之前即拒。
    if roleFlag.provided then
      IO.pure(
        Left(
          ToolError(
            s"'role' is a create-time parameter — node '${node.name}' already exists (role=${node.role}). " +
              "A node's role decides its node_report value domain and whether it may route a verdict, so it cannot be changed " +
              "after creation: create a new node with the intended role and rewire. (NODE_ROLE_CREATE_ONLY)"
          )
        )
      )
    // 显式授权入口的适用范围闸（C5①）：只对 **completed** 节点有意义——blocked/failed
    // 本就可重激活（传它 = 无意义/误用），其余态不可重激活。给可行动错误而非静默忽略。
    else if reactivateCompleted && node.status != NodeLifecycle.Completed then
      IO.pure(
        Left(
          ToolError(
            s"'reactivateCompleted' is the explicit authorization to re-run a COMPLETED node — node '${node.name}' is ${node.status}, " +
              "so the flag is not applicable. blocked/failed/interrupted nodes reactivate on any actual edit (no flag needed); " +
              "wiring/pending/running nodes have never run to completion. (NODE_COMPLETED_REACTIVATION)"
          )
        )
      )
    else if abandon then abandonNode(rt, node)
    // worktree 创建期绑定闸（2026-09-05 显式布尔改造）：worktree 是 create-time
    // 参数——编辑路径出现即拒（旧实现对编辑路径静默忽略，布尔语义下静默忽略
    // = 「我说要建 worktree 你却没建」的陷阱；显式拒绝 + 指引重建）。
    else if worktree.isDefined then
      IO.pure(
        Left(
          ToolError(
            s"'worktree' is a create-time parameter — node '${node.name}' already exists ${
                if node.worktree.isDefined then s"with worktree '${node.worktree.get}'" else "in the workspace"
              }. " +
              "It cannot be added/changed on an existing node; recreate the node if the binding is wrong. (WORKTREE_CREATE_ONLY)"
          )
        )
      )
    // 校验三（deps 设计 §1.2）：编辑 running 下游传 deps → 同款冻结拒绝（「输入已冻结」
    // 对齐 in 语义）；给下游加 deps 的上游是 running 则合法（等它完成正是 deps 语义）。
    else if depsJson.isDefined && node.status == NodeLifecycle.Running then
      IO.pure(
        Left(ToolError(s"Node '${node.name}' is running — its input is frozen. NodeCancel it first, then rewire."))
      )
    else
      // out 处理（整体替换 / 显式断开）——同步校验先 match，再进 IO 链
      NodeTools.parseOut(outJson) match
        case Left(err) => IO.pure(Left(ToolError(err)))
        // 校验六-a 已按 2026-09-12 作者裁定**删除**（out 可空置）：显式 null/"null" 断开
        // 恢复合法（节点回到 dangling 形态——零投递、零升根，结果保留在 result，接线后
        // 自动投递）。「先断开再改接」工作流随裁定复活；重复投递面由 consumedGuard
        // （被移除旧目标已消费 ⇒ 拒）与投递侧逐目标去重共同兜住。
        case Right(newOut) =>
          // deps 处理（deps 设计 §1.2，replace-on-provide 语义裁定）：未传不改；
          // 传了（任何形态，含 [] / null）整体替换。
          NodeTools.parseIn(depsJson).left.map(err => err.replace("'in'", "'deps'")) match
            case Left(err) => IO.pure(Left(ToolError(err)))
            case Right(newDeps) =>
              val depsProvided = depsJson.isDefined
              // P1: out 变更判定与目标集（canonical 比较消歧「(pass)B」与既有单边等价）
              val outProvided = outJson.isDefined
              val newTargets = newOut.map(_.to).filterNot(_ == OutEdge.RootTarget).distinct
              val oldTargets = node.out.map(_.to).filterNot(_ == OutEdge.RootTarget).distinct
              val outChanged = outProvided && OutEdge.canonical(newOut) != OutEdge.canonical(node.out)
              // out 目标解析 + 存在性闸（20260909 in 丢失事故修复面）：新目标接受节点 id
              // 或节点名，统一解析为 id 后再过状态守卫/环检测/merge 门控——原实现按原始
              // 串查 Map，名字形态「查无此点」被状态守卫与环检测静默放行，坏边由此进入
              // 拓扑。悬空目标现在显式拒绝（可行动错误，不再静默半接线）。
              val resolvedIds: IO[Either[String, List[String]]] =
                if outProvided && newTargets.nonEmpty then
                  newTargets.traverse(t => NodeTools.resolveOutTarget(rt, t).map(opt => t -> opt)).map { pairs =>
                    val missing = pairs.collect { case (t, None) => t }
                    if missing.nonEmpty then
                      Left(
                        s"out target '${missing.head}' not found in project '${rt.project.name}' — not a node id, nor any node's name. Wire an existing node or \"Nebula\"."
                      )
                    else Right(pairs.collect { case (_, Some(id)) => id }.distinct)
                  }
                else IO.pure(Right(Nil))
              // D2 修复（验收②c，@a4b5d184 spec 实证）：旧目标已消费 → 拒绝改接。
              // 完成节点改投新目标时，若被移除的旧目标已启动/已完成（结果已投递、输入已消费），
              // 改投会造成语义漂移 + 重复投递风险。保留「旧目标未启动（Wiring/Pending）→
              // 原子改投」分支（§2.3 场景表第 3 行，②b PASS 依赖）；断开（out=null）已由
              // 上方 EMPTY_NODE_CONNECTION 拒绝、不投新目标无重复投递。**P1 多边改接只对
              // 被移除目标检查**（扇出改接可保留部分目标，保留者不拦）。
              val removedTargets = if outProvided then oldTargets.diff(newTargets) else Nil
              val consumedGuard: IO[Either[String, Unit]] =
                if node.status != NodeLifecycle.Completed then IO.pure(Right(()))
                else
                  removedTargets
                    .traverse(oldT =>
                      NodeTools.resolveOutTarget(rt, oldT).flatMap {
                        case Some(tid) =>
                          rt.store.findNode(tid).map {
                            case Some(x) if x.status != NodeLifecycle.Wiring && x.status != NodeLifecycle.Pending =>
                              Left(
                                s"Node '${node.name}' result already delivered to '${x.name}' (status=${x.status}) — input consumed. " +
                                  s"NodeCancel it first, then rewire, then recreate the old target node."
                              )
                            case _ => Right(())
                          }
                        case None => IO.pure(Right(())) // 悬空旧目标（手改数据防御）：保守不拦
                      }
                    )
                    .map(_.collectFirst { case Left(e) => e }.toLeft(()))
              // B5 缺口③（作者 2026-09-17 M-3 裁定，选项①）：本次声明的**新增控制边**若
              // 指向 running 目标 ⇒ 入待接线队列（不进 out），**不再整单拒绝**；新增
              // **非控制边**仍拒（frozen）；**未变边**两边皆不入（放行）。判据单点 =
              // NodeTools.partitionDeferredWiring。求值一次、以 guard 载荷下传
              // （写路径与回执共用同一读数，防两次快照读数漂移）。
              val deferredWiring: IO[(List[(OutEdge, String)], List[String])] =
                if outProvided && newOut.nonEmpty then NodeTools.partitionDeferredWiring(rt, node.out, newOut)
                else IO.pure((Nil, Nil))
              // 守卫：新增非控制边的 running 目标 → 拒绝（§2.2 状态守卫）+ P1 校验层①
              // （NODE_MERGE_PASS_ONLY：指向 merge 的 on-failed 边硬拒）+ 旧目标已消费 →
              // 拒绝（§2.3）。全部按解析后 id 判定（待接线队列接管的控制边已排除在外）。
              val guard: IO[Either[String, List[(OutEdge, String)]]] =
                resolvedIds.flatMap {
                  case Left(err) => IO.pure(Left(err))
                  case Right(ids) =>
                    deferredWiring.flatMap { case (deferred, frozenIds) =>
                      val frozenLive = frozenIds.filter(ids.contains)
                      val statusOk: IO[Either[String, Unit]] =
                        // 外层触发面**逐字保留**（outProvided ∧ 声明目标非空）——B5 只收窄
                        // 「running 目标清单」（新增边分类），不得连带收窄 merge/consumed 闸
                        // 的触发面（NodeEdgeGatingSpec ⑤a 即此面回归的机械把守点）。
                        if outProvided && ids.nonEmpty then
                          frozenLive.traverse(t => NodeTools.ensureTargetNotRunning(rt, t)).flatMap { rs =>
                            rs.collectFirst { case Left(e) => e } match
                              case Some(e) => IO.pure(Left(e))
                              case None =>
                                NodeTools.ensureMergePassOnly(rt, newOut).flatMap {
                                  case Some(gate) => IO.pure(Left(gate): Either[String, Unit])
                                  case None => consumedGuard
                                }
                          }
                        else consumedGuard
                      statusOk.map(_.map(_ => deferred))
                    }
                }
              guard.flatMap {
                case Left(err) => IO.pure(Left(ToolError(err)))
                case Right(deferred) =>
                  // 环检测（新 out：每个非 Nebula、**非 `:loop`** 目标，按解析后 id）
                  //
                  // **控制边豁免（nrloop 一期 2026-09-12，设计 §3.3 #14 / 红线①的第二处
                  // 落地）**：`:loop` 控制边走「图上不连」语义（`NodeDef.out` 只是**声明面**，
                  // 邻接/环检/谱系一概不认——见 `FlowMapStore.wouldCreateCycle.successors`
                  // 与 `topologicalChains`）。本调用点的判据是「加这条边是否成环」，对控制边
                  // 无意义：canonical 形态 `W --pass--> V` + `V --(fail)W:loop--> W` 在 DAG 上
                  // 是 W→V 单向，但若把回边喂进环检，`wouldCreateCycle(V, W)` 会经 W→V 判成环
                  // ⇒ **合法 verifier 的任何后续 out 编辑全被误拒**（功能死锁）。故只对
                  // 非控制边做环检；回边的存在性/自指/多目标/悬空由 `verdictRouteGate`
                  // 专项把守（判据表第 2 条，错误码更具体）。
                  val cycleTargets = newOut
                    .filterNot(OutEdge.isLoopEdge)
                    .map(_.to)
                    .filterNot(_ == OutEdge.RootTarget)
                    .distinct
                  val cycle: IO[List[Boolean]] = resolvedIds.flatMap {
                    case Left(_) => IO.pure(Nil) // guard 已拒，不可达
                    case Right(_) =>
                      cycleTargets.traverse(t =>
                        NodeTools.resolveOutTarget(rt, t).flatMap {
                          case Some(tid) => NodeTools.wouldCreateCycle(rt, node.id, tid)
                          case None => IO.pure(false) // 悬空非控制边已由 resolvedIds 拒（不可达）
                        }
                      )
                  }
                  cycle.flatMap { isCycles =>
                    val cycleTarget = cycleTargets.zip(isCycles).collectFirst { case (t, true) => t }
                    if cycleTarget.isDefined then
                      IO.pure(
                        Left(
                          ToolError(
                            s"Cycle detected: out → ${cycleTarget.get} would create a loop — DAG must stay acyclic"
                          )
                        )
                      )
                    else
                      // out 变更（原子：被移除目标 in 移除 + 新目标 in 追加）。outJson 未出现
                      // = 不改动（parseOut 注释「not passed = no change」的落地）。
                      //
                      // B5 缺口③：`deferred`（guard 载荷）里的控制边**不进 out**——它们
                      // 只落 `pendingOut` 待接线队列（目标此刻 running；离开 running 后由
                      // NodeEngine.applyDeferredWiring 自动接线）。队列 = 「本次仍声明 ∧ 尚未
                      // 接线者」（旧 pending 中已不在本次声明里的边随之出队，语义 = 声明面
                      // 才是权威）。
                      val deferredEdges = deferred.map(_._1)
                      val effectiveOut = newOut.filterNot(deferredEdges.contains)
                      val queuedOut =
                        (node.pendingOut.filter(e => OutEdge.canonical(newOut).contains(e)) ++ deferredEdges).distinct
                      val setOutIO =
                        if outProvided && outChanged then NodeTools.setOut(rt, node.id, effectiveOut, Some(queuedOut))
                        else IO.unit
                      val inAdds = NodeTools.parseIn(inJson)
                      inAdds match
                        case Left(err) => IO.pure(Left(ToolError(err)))
                        case Right(adds) =>
                          // deps 校验一 + 二（deps 设计 §1.2）：目标存在（findNode 归档兜底）
                          // + 环检测（wouldCreateCycle 单点已含 deps 反向边 = in+deps 混合图
                          // 覆盖）。replace 语义：对替换后全集逐条检查（旧 deps 边将消失，
                          // 不在检查路径——环路径经 successors=out ∪ deps 反向，旧 deps 是
                          // 本节点入边不参与）。
                          val depsChecks: IO[List[Either[String, Unit]]] =
                            if depsProvided then
                              newDeps.traverse { upId =>
                                // chainmodel 批一 ③：`chain:<id>` 引用走**链可达性闸**——
                                // 它不是节点 id，走节点存在性必然「查无此点」（报错指不到真因），
                                // 环检对它也无意义（引用边在环检图上经 resolveDepTargets 展开为
                                // 目标链成员，见 FlowMapStore.wouldCreateCycle）。
                                if FlowMapStore.isChainRef(upId) then NodeTools.chainRefExists(rt, upId)
                                else
                                  NodeTools.ensureNodeExists(rt, upId).flatMap {
                                    case Left(e) => IO.pure(Left(e): Either[String, Unit])
                                    case Right(_) =>
                                      NodeTools.wouldCreateCycle(rt, upId, node.id).map {
                                        case true =>
                                          Left(
                                            s"Cycle detected: adding deps from '$upId' would create a loop — DAG must stay acyclic"
                                          )
                                        case false => Right(())
                                      }
                                  }
                              }
                            else IO.pure(Nil)
                          depsChecks.flatMap { dChecks =>
                            // 校验六（**2026-09-12 作者裁定删除零连接下限**）：断开/清空 out
                            // 合法 ⇒ 「编辑后 in ∪ deps ∪ out 全空」不再拒（该判据已被裁定①②
                            // 推翻）。输入侧下限仍在创建侧（task ∨ in）把守纯空挂载。
                            // 注意 out 的「未传」与「显式 null 断开」经 parseOut 后同为 None——
                            // 仍须按 outJson 是否出现区分：传了才视为断开后的 None，未传保持原 out。
                            val finalIn = node.in ++ adds
                            // ⑥⑧ 合并节点闸门的触发面（本批 U2/P1）：只有**真的新加上游**
                            // 才算「加 in」——重复回显既有 in（幂等重发）不是加，不触发任何
                            // 闸门（防「同参数重编辑」被误拒的回归）。
                            val genuinelyNewIn = adds.distinct.filterNot(node.in.contains)
                            val finalDeps = if depsProvided then newDeps else node.deps
                            val finalOut = if outJson.isDefined then newOut else node.out
                            // notifyDispatcher 校验（dispatch-notify 批）：设置/撤销域为
                            // wiring/pending/running（完成时行为开关，不属输入冻结域）；
                            // 终态拒（blocked 出口=重激活）。
                            val notifyStatusOk = !notify.provided ||
                              (node.status == NodeLifecycle.Wiring || node.status == NodeLifecycle.Pending || node.status == NodeLifecycle.Running)
                            // notify 策略（b64 批 R1）可设置时机与 notifyDispatcher **同域**
                            // （spec §4.2「可设置时机」行：仅 wiring/pending/running——终态节点
                            // 改策略无意义）。b64：两个参数共用上一条闸门。
                            val notifyPolicyStatusOk = !notify.policyProvided || notifyStatusOk
                            // notify 值域错误优先短路（NODE_NOTIFY_INVALID）
                            val notifyPolicyInvalid = notify.invalid
                            // loop 校验（LoopNode 批 2026-09-06）：设置/撤销域同 hold——enabled
                            // 开关是行为开关（loop 迭代仅在 running 期驱动、PASS 完成时经
                            // completeNode 走 hold/merge/投递），wiring/pending/running 可设；
                            // 终态/held 拒（held 出口=release，blocked 出口=重激活）。blocked
                            // 节点 loop 变更走 actualChange → 重激活（下方 reactivate 分支应用）。
                            val loopStatusOk = !loopFlag.provided ||
                              (node.status == NodeLifecycle.Wiring || node.status == NodeLifecycle.Pending || node.status == NodeLifecycle.Running)
                            // loop 门集预检（2026-09-12 裁定 2/3，改接侧 self 面）：本次编辑的
                            // 最终 out（未传 out = 保持原边集）也要过同一不变量。loop 开关本身
                            // 可能在本调用内变化（loopFlag.provided）⇒ 用**生效后**的开关判定
                            // （未传 = 保持 node.loop；传了 = 本调用的声明值，与 loopStatusOk
                            // / reactivate 的 appliedLoop 同源口径）。
                            val effectiveLoopEnabled =
                              if loopFlag.provided then loopFlag.config.exists(_.enabled)
                              else node.loop.exists(_.enabled)
                            val loopSelfGate =
                              NodeTools.loopGateViolation(effectiveLoopEnabled, node.name, finalOut)
                            val earlyReject: Option[ToolError] =
                              validateDescription(description, creating = false)
                                .orElse(validateDescriptionLong(descriptionLong))
                                .orElse(
                                  if loopSelfGate.isDefined then Some(ToolError(loopSelfGate.get))
                                  else if dChecks.exists(_.isLeft) then
                                    Some(
                                      ToolError(dChecks.collectFirst { case Left(e) => e }.getOrElse("invalid deps"))
                                    )
                                  else if notifyPolicyInvalid.isDefined then Some(ToolError(notifyPolicyInvalid.get))
                                  else if !notifyStatusOk then
                                    Some(
                                      ToolError(
                                        s"notifyDispatcher can only be set or withdrawn before completion (wiring/pending/running) — node '${node.name}' is ${node.status} (result already delivered). re-activation is the exit for blocked/failed."
                                      )
                                    )
                                  else if !notifyPolicyStatusOk then
                                    Some(
                                      ToolError(
                                        s"notify can only be set or cleared before completion (wiring/pending/running) — node '${node.name}' is ${node.status} (result already delivered). re-activation is the exit for blocked/failed. (${NotifyPolicy.InvalidCode})"
                                      )
                                    )
                                  else if !loopStatusOk then
                                    Some(
                                      ToolError(
                                        s"loop can only be set or withdrawn before completion (wiring/pending/running) — node '${node.name}' is ${node.status} (result already delivered). re-activation is the exit for blocked/failed."
                                      )
                                    )
                                  // ⑥ in 上限（edit 路径，U2/P1）：合并节点 in 只增（append-only），
                                  // 追加后 distinct 计数 >4 → 拒（=4 合法）。码 NODE_MERGE_IN_CAP。
                                  else if node.merge && genuinelyNewIn.nonEmpty &&
                                    finalIn.distinct.size > NodeTools.MergeInCap
                                  then
                                    Some(
                                      ToolError(
                                        s"merge node '${node.name}' accepts at most ${NodeTools.MergeInCap} upstreams in one ledger — " +
                                          s"this edit would leave ${finalIn.distinct.size} distinct upstream(s). Split the batch: create " +
                                          s"a second merge node for the extra upstream(s), then wire both merge nodes into a " +
                                          "follow-up merge/report node. (NODE_MERGE_IN_CAP)"
                                      )
                                    )
                                  // ⑧ 已触发禁加 in（edit 路径，U2/P1）：running/blocked/completed
                                  // 的合并节点 in 账本冻结——它已按旧账本开火/终态化，追加的上游
                                  // 永远不会被这一轮 barrier 消费（静默的半接边）。新 worktree 配新
                                  // 合并节点。码 NODE_MERGE_FIRED_NO_IN。
                                  else if node.merge && genuinelyNewIn.nonEmpty &&
                                    NodeTools.MergeFiredStatuses.contains(node.status)
                                  then
                                    Some(
                                      ToolError(
                                        s"merge node '${node.name}' has already fired (status=${node.status}) — its in ledger is " +
                                          "frozen: an upstream appended now would never be consumed by this round's barrier " +
                                          "(silent half-wired edge). Create a NEW merge node for the new upstream(s) and wire it " +
                                          "downstream; un-triggered (wiring/pending) merges still accept appended upstreams. " +
                                          "(NODE_MERGE_FIRED_NO_IN)"
                                      )
                                    )
                                  else None
                                )
                            // 前置拒绝集统一闸（description 校验 + loop 门集 + deps 校验）
                            // + verdict 选通校验族（nrloop 一期 2026-09-12，0 spawn）：
                            // 本节点的**最终边集**（未传 out = 保持原边集）过同一判据——
                            // 角色 create-only ⇒ 生效角色恒为既有 node.role；旧式 loop 开关
                            // 可能在本调用内变化，取**生效后**的口径（与 loopSelfGate 同源）。
                            val selfLoopEnabled =
                              if loopFlag.provided then loopFlag.config.exists(_.enabled)
                              else node.loop.exists(_.enabled)
                            NodeTools
                              .verdictRouteGate(rt, node.id, node.name, node.role, selfLoopEnabled, finalOut)
                              .flatMap { verdictErr =>
                                if earlyReject.isDefined then IO.pure(Left(earlyReject.get))
                                else if verdictErr.isDefined then IO.pure(Left(ToolError(verdictErr.get)))
                                // 批E2 retry 风暴防护（spec §2.3，0 spawn）：邻居限定（值域 =
                                // 应用本次改动后的最终 in ∪ deps）+ retry 环。校验失败卡在
                                // 全部写路径（in 追加/重激活/写回）之前——本次编辑零副作用。
                                else
                                  (retryFlag.policy match
                                    case None => IO.pure(None)
                                    case Some(p) =>
                                      NodeTools.retryGuard(rt, node.id, node.name, p, finalIn ++ finalDeps)
                                  ).flatMap {
                                    case Some(err) => IO.pure(Left(ToolError(err)))
                                    case None =>
                                      // blocked/failed 重激活（blocked 反馈重入设计 §6 #5；failed
                                      // 放开=2026-09-07 批作者裁定③）：编辑 blocked/failed 节点且
                                      // task/description/in/out/deps 实际变更 → status 回
                                      // wiring/pending、deliveredTo 清空、completedAt/ttlExpireAt/
                                      // startedAt 复位，随后 D1 补投递链重投全部已完成上游。
                                      // blocked 版 blockCount 保留（§3.1 轮次历史）；failed 版轮次
                                      // 历史复位（重跑非语义阻塞轮次，见下方事务内注释）。
                                      // （agent 已退役不可编辑——重激活沿用节点自身 agent。）
                                      val taskChanged = task.exists(t =>
                                        NodeTools
                                          .normalizeTask(t) != node.task.map(NodeTools.normalizeTask).getOrElse("")
                                      )
                                      val descriptionChanged =
                                        description.exists(d => d.trim != node.description.getOrElse(""))
                                      val depsChanged = depsProvided && newDeps != node.deps
                                      val loopChanged = loopFlag.provided && loopFlag.config != node.loop
                                      // out 未传 ≠ 变更（actualChange quirk 修，2026-09-07）：
                                      // parseOut 未传=Nil 与 node.out 非空恒不等——裸比较会让
                                      // 终态节点「未传 out 的编辑」恒判 actualChange=true → 意外重
                                      // 激活，违背描述「No-op if nothing actually changed」。与
                                      // 上方 setOutIO / finalOut 同款 outProvided 守卫（canonical
                                      // 比较消歧段语法等价形态）。
                                      // chainmodel 批一 ①：**chainId 声明不进 actualChange**——归属是纯元数据
                                      // （声明不改任何调度判据），若纳入则「给 blocked/failed 节点声明链号」
                                      // 会被判成实际改动而**意外重激活**（清 result / 回 wiring 重跑）——
                                      // 与「声明不构成调度变更」直接冲突。故声明只写字段 + 留痕，零重激活。
                                      // 同理不进 `depsChanged`（deps 才是闸）。
                                      val actualChange =
                                        taskChanged || descriptionChanged || outChanged || adds.nonEmpty || depsChanged || loopChanged
                                      // 重激活判据（**2026-09-12 nrloop 一期**）：
                                      //   · blocked / failed：**逐字不变**（既有两态的语义、FeedbackRouter
                                      //     重入、blockCount 口径一律不动——C5③ 独立复核项）；
                                      //   · completed：**仅经显式授权入口** `NODE_COMPLETED_REACTIVATION`
                                      //     （本调用参数 `reactivateCompleted=true`）才可重跑 —— 作者裁定
                                      //     C1-2「completed 不可重激活 = 一律放开」，但 C5① 明文要求
                                      //     「**不得**作为实现细节隐性放宽 `reactivate` 判据」。
                                      //
                                      // 为什么不做「completed + actualChange ⇒ 一律重激活」的隐性放宽
                                      // （现场判定，与批 A 判据共存核对）：「已完成的悬空节点被接线 ⇒ 结果
                                      // 自动补投」（批 A O-B 必做 1/5 + W1/W2「out 可空置 + 接线即投递」）
                                      // 走的是**同一条** `out changed` 分支；隐性放宽会把它变成「接线即重跑」
                                      // （result 清空、节点回 wiring），与批 A 已落地的裁定直接互斥。故本批
                                      // 取「显式授权才重激活」：默认行为逐字不变（批 A 判据零放宽、零覆盖），
                                      // 需要重跑时分发器显式传 `reactivateCompleted=true`。
                                      // 执行腿（`reloopTo` 回边驱动）仍落二期 —— 其触发源经引擎侧同点进来
                                      // （事件留痕 `source=loop`），判据本体已在此就位。
                                      //   · interrupted（中断恢复语义批 2026-09-13 spec §2.4 批 R2）：
                                      //     与 blocked/failed 同判据（实际改动才重激活）——语义 =
                                      //     **fresh 重跑，非续跑**（自动续跑走 boot sweep：同 session
                                      //     + transcript 断点；人工 reactivate 是「换任务书从头跑」的
                                      //     兜底入口，`crashRecovery=false` 降级时节点停留 interrupted
                                      //     的可见可处置出口）。
                                      //   · cancelled：**不放开**（create successor）。
                                      val completedAuthorized =
                                        node.status == NodeLifecycle.Completed && reactivateCompleted
                                      val reactivate =
                                        ((node.status == NodeLifecycle.Blocked || node.status == NodeLifecycle.Failed
                                          || node.status == NodeLifecycle.Interrupted) && actualChange)
                                          || completedAuthorized
                                      val appliedTask = task.orElse(node.task)
                                      val appliedDeps = if depsProvided then newDeps else node.deps
                                      // wiring 变更涉及节点集（最终态事件）：本节点（in/out 变更，in 追加
                                      // 也改自身 in）、in 上游（out 追加指向本节点）、被移除/新增 out
                                      // 目标（in 增删）。deps 目标不入列——下游单侧持有，上游
                                      // payload 无「被谁依赖」字段，其卡片无需事件。
                                      val affected: List[String] =
                                        val fromOut =
                                          if outChanged then oldTargets ++ newTargets
                                          else Nil
                                        (node.id +: (adds ++ fromOut)).distinct
                                      for
                                        // 链归属变更留痕（chainmodel 批一 ⑤）：**写前**归属视图快照
                                        // （第一个绑定 ⇒ 早于本 for 内全部写动作；`chainIdView` 是纯
                                        // 派生，快照即写前归属）。写后视图由 emit 内部现读。
                                        chainBefore <- rt.store.combinedNodes
                                        // in 追加校验先行（存在性 + 环检），全部通过才动 store——旧实现
                                        // traverse 内 raiseError 后被 handleErrorWith 吞成 Left 值丢弃，
                                        // 后续 setOutIO/mutate 照跑且工具误报成功（一并修正）
                                        inChecks <- adds.traverse { upId =>
                                          // chainmodel 批一 ③：链引用是 deps 专属语法，`in` 里一律拒
                                          // （静态可行动报错——按字面当节点查会得到「查无此点」的误导面）。
                                          if FlowMapStore.isChainRef(upId) then
                                            IO.pure(Left(NodeTools.chainRefInInputError(upId)): Either[String, Unit])
                                          else
                                            NodeTools.ensureNodeExists(rt, upId).flatMap {
                                              case Left(e) => IO.pure(Left(e): Either[String, Unit])
                                              case Right(_) =>
                                                // loop 门集预检（2026-09-12 裁定 2/3，**镜像追加路径**——
                                                // 该路径不经 outJson 校验点，故必须在写路径上判）：本调用会
                                                // 给每个上游 out 追加一条 pass 边（活动区经 setOut / 归档区
                                                // 经其归档分支）⇒ 上游若是 loop 节点，最终边集可能违反两腿
                                                // 覆盖判据（尤其空 out / 单腿形态被追加后）。findNode 双区
                                                // 兜底；复算用 **appendEdgeTo 本身**（幂等条件单一真相源）。
                                                rt.store.findNode(upId).flatMap { upOpt =>
                                                  val gate = upOpt.flatMap { up =>
                                                    val after = NodeTools.appendEdgeTo(Map(up.id -> up), upId, node.id)
                                                    val prospective = after.get(upId).map(_.out).getOrElse(up.out)
                                                    // verdict 选通镜像腿（nodegate 方案件 §1(i)，本批四项③）：
                                                    // 上游若是 verifier 且追加后 fail 路由仍为空 ⇒ 拒
                                                    // （**镜像腿不豁免** pending——C-i ①）。与 loop 门集同点
                                                    // 同款（appendEdgeTo 复算，幂等分支零复制）。
                                                    NodeTools
                                                      .loopGateViolation(up, prospective)
                                                      .orElse(
                                                        NodeTools.verdictRouteGateForAppends(
                                                          Map(up.id -> up),
                                                          List(upId),
                                                          node.id
                                                        )
                                                      )
                                                  }
                                                  gate match
                                                    case Some(e) => IO.pure(Left(e): Either[String, Unit])
                                                    case None =>
                                                      NodeTools.wouldCreateCycle(rt, upId, node.id).map {
                                                        case true =>
                                                          Left(
                                                            s"Cycle detected: adding in from '$upId' would create a loop (A→B→A) — DAG must stay acyclic"
                                                          )
                                                        case false => Right(())
                                                      }
                                                }
                                            }
                                        }
                                        inResult <-
                                          if inChecks.exists(_.isLeft) then
                                            IO.pure(
                                              Left(
                                                ToolError(
                                                  inChecks.collectFirst { case Left(e) => e }.getOrElse("invalid in")
                                                )
                                              )
                                            )
                                          else
                                            for
                                              // in 追加（barrier）：每个上游 out 追加指向本节点的缺省
                                              // pass 边（P1 多边镜像——不覆盖上游既有边/门控，原子改投
                                              // §2.3 的单值语义在多边下自然兼容）。归档上游（活动区无
                                              // 副本）走 setOut 归档分支：活动侧目标 in 追加 + 归档 out
                                              // 镜像补写（旧 setOut(rt, upId, Some(node.id)) 同域语义）。
                                              _ <- adds.traverse_(upId =>
                                                rt.store.getNode(upId).flatMap {
                                                  case Some(up) if !up.out.exists(_.to == node.id) =>
                                                    NodeTools.setOut(rt, upId, up.out :+ OutEdge(node.id))
                                                  case Some(_) => IO.unit // 边已存在（幂等）
                                                  case None =>
                                                    rt.store.findNode(upId).flatMap {
                                                      case Some(archUp) if !archUp.out.exists(_.to == node.id) =>
                                                        NodeTools.setOut(rt, upId, archUp.out :+ OutEdge(node.id))
                                                      case _ => IO.unit
                                                    }
                                                }
                                              )
                                              _ <- setOutIO
                                              // B5 缺口③ 落痕（待接线登记，FlowMapEventLog.WiringDeferredType）：
                                              // 「接线时刻不确定」必须对分发器可见——入队即写审计行。
                                              _ <-
                                                if deferred.nonEmpty then
                                                  FlowMapEventLog.append(
                                                    rt.project.workspace,
                                                    rt.project.name,
                                                    node.id,
                                                    FlowMapEventLog.WiringDeferredType,
                                                    s"control edge(s) queued (target running, input frozen): " +
                                                      deferred
                                                        .map((e, tid) => s"${e.to}:${e.mode}->$tid")
                                                        .mkString(",") +
                                                      " — pendingOut; auto-wired by applyDeferredWiring once the target leaves running"
                                                  )
                                                else IO.unit
                                              // deps 替换写回（非重激活路径；重激活在下方事务内一并写）。
                                              // deps 单侧持有：只更新本节点字段，无上游侧镜像边。
                                              _ <-
                                                if depsProvided && depsChanged && !reactivate then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(node.id, fresh.copy(deps = newDeps))
                                                        )
                                                      case None => s
                                                  }.void
                                                else IO.unit
                                              // plugins 替换写回（阶段 2b §B.4 第 3 步，replace-on-provide
                                              // 与 deps 同款）：passed（any form, including []）= 整列表
                                              // 替换；未传 = 不改动。存在性+信任门校验已在 call() 前置
                                              // 拦截；这里纯写。对运行中节点仅改元数据——已运行会话的
                                              // 注入/MCP 不回溯（能力集 spawn 时冻结），下次运行生效。
                                              _ <-
                                                if pluginsOpt.isDefined then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(node.id, fresh.copy(plugins = pluginsOpt.get))
                                                        )
                                                      case None => s
                                                  }.void
                                                else IO.unit
                                              // description 写回（2026-09-05 创建必写批，编辑可 update）：
                                              // 校验（非空/≤200）已在 earlyReject 前置拦截；这里纯写
                                              // （trim 归一，与 create 同源）。blocked 节点的 description
                                              // 变更参与重激活判定（descriptionChanged）；非 blocked 节点
                                              // 纯元数据更新（不影响执行——description 是展示层）。
                                              _ <-
                                                if description.isDefined && !reactivate then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(
                                                            node.id,
                                                            fresh.copy(description = description.map(_.trim))
                                                          )
                                                        )
                                                      case None => s
                                                  }.void
                                                else IO.unit
                                              // descriptionLong 写回（裁定⑤c 双层化 20260907）：纯展示
                                              // 元数据（detail 通道/前端详情消费，不进默认载荷）——
                                              // 不参与重激活判定（任务语义信号由 task/description 承载）。
                                              _ <-
                                                if descriptionLong.isDefined && !reactivate then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(
                                                            node.id,
                                                            fresh.copy(descriptionLong = descriptionLong.map(_.trim))
                                                          )
                                                        )
                                                      case None => s
                                                  }.void
                                                else IO.unit
                                              // pendingSuccession 清除（取消静默死锁修复批 R4）：
                                              // 本节点带「待承接」标（其 in 上曾有被引擎取消并
                                              // 自动摘除的上游）而本次编辑有**实际变更** =
                                              // 分发器已介入处置（承接节点 append 回 in / 改接 /
                                              // 其它拓扑调整）→ 解除闸门，barrier 恢复可触发。
                                              // 清除只在 actualChange 时发生（纯 no-op 编辑不
                                              // 解锁，防误放行）；标记本身由 R4 摘除写入。
                                              _ <-
                                                if actualChange then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) if fresh.pendingSuccession.nonEmpty =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(node.id, fresh.copy(pendingSuccession = Nil))
                                                        )
                                                      case _ => s
                                                  }.void
                                                else IO.unit
                                              // notifyDispatcher 设置/撤销写回（dispatch-notify 批）：
                                              // 校验已在 earlyReject 拦截（终态 → 拒）；running 合法
                                              // （完成时行为开关）。事务内现读 fresh（R2 纪律）+ 状态双重保险。
                                              _ <-
                                                if notify.provided then
                                                  // R1 并存过渡（b64 批）：`notify` 已是权威值时
                                                  // 本键不再参与裁决 ⇒ **写侧拒绝静默改动**，只 WARN
                                                  // 告知（B1-b「二者冲突时 notify 优先并 WARN」）。
                                                  rt.store.findNode(node.id).flatMap {
                                                    case Some(fresh) if fresh.notifyPolicy.isDefined =>
                                                      logger.warn(
                                                        s"NodeEdit notifyDispatcher=${notify.flag} ignored on node '${node.name}' (${node.id}): the node declares notify=${fresh.notifyPolicy.get} (authoritative since the b64 batch) — use 'notify' to change its notification policy"
                                                      )
                                                    case _ =>
                                                      rt.store.mutate { s =>
                                                        s.nodes.get(node.id) match
                                                          case Some(fresh)
                                                              if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Running =>
                                                            s.copy(nodes =
                                                              s.nodes.updated(
                                                                node.id,
                                                                fresh.copy(notifyDispatcher = notify.flag)
                                                              )
                                                            )
                                                          case _ => s
                                                      }.void
                                                  }
                                                else IO.unit
                                              // notify 策略写回（b64 批 R1）：replace-on-provide（传 null
                                              // = 显式清除 ⇒ 回落 legacy 解析）。校验已在 earlyReject
                                              // 拦截；状态域与 notifyDispatcher 同（wiring/pending/running）。
                                              _ <-
                                                if notify.policyProvided then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh)
                                                          if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Running =>
                                                        s.copy(nodes =
                                                          s.nodes
                                                            .updated(node.id, fresh.copy(notifyPolicy = notify.policy))
                                                        )
                                                      case _ => s
                                                  }.void
                                                else IO.unit
                                              // loop 设置/撤销写回（LoopNode 批 2026-09-06）：校验已在
                                              // earlyReject 拦截（终态/held → 拒）；wiring/pending/running 合法
                                              // （loop 是 running 期行为开关）。事务内现读 fresh（R2 纪律）+
                                              // 状态双重保险；blocked 重激活在下方 reactivate 分支应用（不再走此）。
                                              _ <-
                                                if loopFlag.provided && !reactivate then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh)
                                                          if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Running =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(node.id, fresh.copy(loop = loopFlag.config))
                                                        )
                                                      case _ => s
                                                  }.void
                                                else IO.unit
                                              // retry 设置/清除写回（批E2 P2，spec §2.3）：replace-on-provide
                                              // （传了即整体替换，null=清除）；不参与重激活判定（retry 是
                                              // 「下一次失败」的行为开关，语义同 notifyDispatcher——已有
                                              // failed 节点要立即生效须另行重激活，如改 task）。
                                              // 语义校验（邻居/环）已在写路径前 retryGuard 拦截；此处纯写
                                              // （任意状态可设——failed 节点配置 retry 后，人工重激活触发的
                                              // 重跑再失败时自动回跳）。
                                              _ <-
                                                if retryFlag.provided then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(node.id, fresh.copy(retry = retryFlag.policy))
                                                        )
                                                      case None => s
                                                  }.void
                                                else IO.unit
                                              // chainId 声明写回（chainmodel 批一 ①，replace-on-provide
                                              // 与 notify 同款）：传了即整体替换（`null` = **撤销**声明 ⇒
                                              // 回落派生兜底）；未传 = 零改动。**无状态域闸**——归属是
                                              // 纯元数据，不构成行为开关（声明不改任何调度判据，见
                                              // NodeDef.chainId 头注），故中途可改（与 notify/loop 的
                                              // 「行为开关域闸」是有意的口径差）；归档节点仍由上游
                                              // forbidden 集拒（归档只放行 out 改接）。🔴 本项**不进
                                              // `actualChange`**（见该判据处注释）：声明不得把 blocked/
                                              // failed/interrupted 节点意外重激活（声明 ≠ 实际改动）。
                                              // 事务内现读 fresh（R2 纪律），仅改本字段；不 gate 在
                                              // `!reactivate` 上——重激活写回的是另一批字段，两者
                                              // 各自现读、互补覆盖。
                                              _ <-
                                                if chainDecl.provided then
                                                  rt.store.mutate { s =>
                                                    s.nodes.get(node.id) match
                                                      case Some(fresh) =>
                                                        s.copy(nodes =
                                                          s.nodes.updated(node.id, fresh.copy(chainId = chainDecl.decl))
                                                        )
                                                      case None => s
                                                  }.void
                                                else IO.unit
                                              // blocked/failed 重激活写回（R2 纪律）：事务内现读
                                              // fresh，fresh 仍 Blocked/Failed 才写；状态已变（并发
                                              // abandon/重激活）→ 拒写不重激活。failed 版差异
                                              // （2026-09-07 批，作者裁定①②语义点）：**轮次历史复位**
                                              // 而非保留——重跑不是语义阻塞轮次：blockCount→0、
                                              // blockedFeedback 清除（blockCount 复位后旧反馈成孤
                                              // 儿数据）；notifySentAt 清除（重激活后再次真失败 →
                                              // 分发器再获通知——重试回路非循环口径，DispatchNotify
                                              // §2.2；inFlight 占位在通知尝试后已释放，不会挡第二次）。
                                              // blocked 版三者保留（blockCount 记语义阻塞轮次，
                                              // FeedbackRouter LoopCap 依赖）。
                                              didReactivate <-
                                                if reactivate then
                                                  rt.store
                                                    .mutate { s =>
                                                      s.nodes.get(node.id) match
                                                        case Some(fresh)
                                                            if fresh.status == NodeLifecycle.Blocked || fresh.status == NodeLifecycle.Failed || fresh.status == NodeLifecycle.Completed || fresh.status == NodeLifecycle.Interrupted =>
                                                          val fromFailed = fresh.status == NodeLifecycle.Failed
                                                          // interrupted 面（批 R2）：与 failed 面**同清**
                                                          // notifySentAt——重激活 = 新一轮身份重跑，之后再
                                                          // 真失败必须能重新通知分发器（成功验收 §2.8-6）；
                                                          // blockCount/blockedFeedback 按 blocked 口径保留
                                                          // （中断不是语义阻塞轮次，也不是失败轮次）。
                                                          val fromInterrupted =
                                                            fresh.status == NodeLifecycle.Interrupted
                                                          val nextStatus =
                                                            if appliedTask.exists(_.trim.nonEmpty) && fresh.in.isEmpty
                                                            then NodeLifecycle.Pending
                                                            else NodeLifecycle.Wiring
                                                          s.copy(nodes =
                                                            s.nodes.updated(
                                                              node.id,
                                                              fresh.copy(
                                                                status = nextStatus,
                                                                task = appliedTask,
                                                                description =
                                                                  description.map(_.trim).orElse(fresh.description),
                                                                descriptionLong = descriptionLong
                                                                  .map(_.trim)
                                                                  .orElse(fresh.descriptionLong),
                                                                deps = appliedDeps,
                                                                loop =
                                                                  loopFlag.config.orElse(fresh.loop), // loop 变更随重激活应用
                                                                result = None, // blocked 反馈渲染串 / failed 错误文本均不复存在
                                                                deliveredTo = Nil,
                                                                // V8: 重激活 = 该节点身份重跑一轮，out=Nebula 投递
                                                                // 记账同样清零——重跑完成后的结果重新投递+记账。
                                                                nebulaDeliveredAt = None,
                                                                startedAt = None,
                                                                completedAt = None,
                                                                ttlExpireAt = None,
                                                                blockCount =
                                                                  if fromFailed then 0
                                                                  else fresh.blockCount, // blocked 保留轮次 / failed 复位
                                                                blockedFeedback =
                                                                  if fromFailed then None else fresh.blockedFeedback,
                                                                notifySentAt =
                                                                  if fromFailed || fromInterrupted then None
                                                                  else fresh.notifySentAt,
                                                                // ── engine-defects 批 #245（2026-09-15）：**陈旧判词必须随重激活作废** ──
                                                                // 缺陷：重激活是「同一身份重跑一轮」（本字段族的既有口径，见上方
                                                                // result/deliveredTo/nebulaDeliveredAt 三清），但 `lastVerdict` 不在其列
                                                                // ⇒ 重跑中的复核位仍挂着上一轮判词；下游判词闸
                                                                // （`mergeVerdictHolders`：`!lastVerdict.exists(pass)`）**每轮现读**，
                                                                // 于是被陈旧 `pass` 放开 ⇒ 下游按陈旧结论推进。
                                                                // 修法：仅对 `role=verifier` 清空（重跑中的复核位**当下没有判词** ⇒
                                                                // 闸必须等新判词）。判据方向为**收紧**（`None` 与 `fail` 同被闸挡住，
                                                                // 绝不放宽任何闸）；`role=task` 节点的重激活字段集逐字不动。
                                                                // 🔴 三条腿**不碰**：`resetForLoop`（回边驱动方 `lastVerdict` 刻意保留
                                                                // ——fail 判词是返工期间继续挡合并的依据，NodeEngine.scala:3735-3736）、
                                                                // `retryReactivate`、`reactivateForRetry`。
                                                                lastVerdict =
                                                                  if NodeRoles
                                                                      .normalize(fresh.role) == NodeRoles.Verifier
                                                                  then None
                                                                  else fresh.lastVerdict
                                                              )
                                                            )
                                                          )
                                                        case _ => s
                                                    }
                                                    .map(s2 =>
                                                      s2.nodes
                                                        .get(node.id)
                                                        .exists(n =>
                                                          n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending
                                                        )
                                                    )
                                                else IO.pure(false)
                                              _ <-
                                                if didReactivate then
                                                  // 重激活留痕（C5② 事件留痕，2026-09-12 nrloop 一期）：
                                                  // 单条 `reactivated` 事件，**结构化 k=v 尾段**（可解析，
                                                  // 与 `NodeEngine.reportMissingSummary` 同风格）至少含：
                                                  //   · preStatus = 放开前的终态（blocked/failed/completed，completed 为 nrloop 一期新增面）
                                                  //   · source    = 触发源（human = NodeEdit 人工/分发器；
                                                  //                 loop = 回边驱动，二期的 reloopTo 用）
                                                  //   · gen / blockCount / loopRound = 轮次或代次
                                                  //   · auth      = 授权入口名（仅 completed 面）
                                                  // completed 面另附 NODE_COMPLETED_REACTIVATION 授权名——
                                                  // 「改动既有纪律」的动作必须可事后对齐（C5 要求）。
                                                  val preStatus = node.status
                                                  val reactivateNote =
                                                    (if preStatus == NodeLifecycle.Failed then
                                                       s"failed node edited (round history reset: blockCount→0, notifySentAt cleared) → rerun: ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}"
                                                     else if preStatus == NodeLifecycle.Completed then
                                                       s"completed node reactivated (NODE_COMPLETED_REACTIVATION) → rerun: ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}"
                                                     else if preStatus == NodeLifecycle.Interrupted then
                                                       s"interrupted node edited (host graceful-shutdown interrupt; fresh rerun — NOT a checkpoint resume, boot recovery owns that) → rerun: ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}"
                                                     else
                                                       s"blocked node edited (round ${node.blockCount} preserved) → ${appliedTask.map(t => s"task=${t.take(80)}").getOrElse("")}"
                                                    ) +
                                                      s" [preStatus=$preStatus source=human gen=${node.gen} blockCount=${node.blockCount} loopRound=${node.loopRound}" +
                                                      (if preStatus == NodeLifecycle.Completed then
                                                         " auth=NODE_COMPLETED_REACTIVATION"
                                                       else "") + "]"
                                                  FlowMapEventLog.append(
                                                    rt.project.workspace,
                                                    rt.project.name,
                                                    node.id,
                                                    "reactivated",
                                                    reactivateNote
                                                  ) *>
                                                    // 重激活补投递（design §6 #5「随后走现有 D1 补投递链」）：
                                                    // deliveredTo 已清空 → 全部 in 上游（终态有结果、非 blocked）
                                                    // 重投（deliverOutTo dedup 幂等）→ barrier 结算 → 启动；
                                                    // 入口节点（task 且无 in）→ 直接启动。后台化（runDetached：
                                                    // 直接调用会把工具 fiber 卡到节点终态）。
                                                    NodeTools.runDetached(
                                                      rt,
                                                      s"reactivated redelivery + barrier settle -> ${node.id}"
                                                    )(
                                                      rt.store.getNode(node.id).flatMap {
                                                        case None => IO.unit
                                                        case Some(n) =>
                                                          n.in.traverse_ { upId =>
                                                            rt.store.findNode(upId).flatMap {
                                                              case Some(up)
                                                                  if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal
                                                                    .contains(up.status) =>
                                                                rt.engine.deliverOutTo(up, n.id, up.result.get)
                                                              case _ => IO.unit
                                                            }
                                                          } *> rt.store.getNode(node.id).flatMap {
                                                            case Some(n2)
                                                                if n2.in.nonEmpty && n2.in
                                                                  .forall(n2.deliveredTo.contains) =>
                                                              rt.engine.startNode(n2.id)
                                                            case Some(n2)
                                                                if n2.in.isEmpty && n2.status == NodeLifecycle.Pending =>
                                                              rt.engine.startNode(n2.id)
                                                            case _ => IO.unit
                                                          }
                                                      }
                                                    )
                                                else IO.unit
                                              // 终态节点改接 → 立即投递（§2.3：结果缓冲/归档 → 向新目标
                                              // 投递）。P1 多边 + 本批两处收敛：
                                              // ① **判据与路径 B 同口径**（O-B 必做 1 / N1 收敛，2026-09-12
                                              //    批）：`result.isDefined ∧ status != Blocked ∧
                                              //    Terminal.contains(status)`——**逐字同句**见下方 in 追加
                                              //    补投递支与 `proceed` 的创建侧谓词（三处同款，禁止第二种
                                              //    写法）；旧口径只认 `Completed`，使 failed/cancelled 源的
                                              //    滞留结果永远投不出去。
                                              // ② **投递对象 = 新接线目标**（O-B 必做 5 / N4 收敛）：不得对
                                              //    「新声明的全部目标」逐条投——那样会在只新增一条边时把已投过
                                              //    的 Nebula 边（无目标侧去重账本）再投一次。Nebula 腿只在
                                              //    「此前未曾声明」时进入本次投递（首次接线）；与 A9 的持久
                                              //    守卫（nebulaDeliveredAt）互为纵深防御。
                                              // 后台化（收口③）：deliverOutTo 内 startNode 同步等下游终态，
                                              // 直接调用会阻塞 NodeEdit 工具 fiber（见 runDetached 注释）。
                                              _ <- (
                                                outProvided && newOut.nonEmpty && node.result.isDefined
                                                  && node.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal
                                                    .contains(node.status)
                                              ) match
                                                case true =>
                                                  val res = node.result.get
                                                  val hadRoot = node.out.exists(_.to == OutEdge.RootTarget)
                                                  val newlyWired = newOut
                                                    .filterNot(OutEdge.isLoopEdge)
                                                    .filter(e =>
                                                      if e.to == OutEdge.RootTarget then !hadRoot
                                                      else !oldTargets.contains(e.to)
                                                    )
                                                  if newlyWired.isEmpty then IO.unit
                                                  else
                                                    NodeTools.runDetached(
                                                      rt,
                                                      s"deliver retained result ${node.id} -> ${newlyWired.map(_.to).mkString(",")}"
                                                    )(
                                                      newlyWired.traverse_(e => rt.engine.deliverOutTo(node, e.to, res))
                                                    )
                                                case false => IO.unit
                                              // 修复次因 A（edit 路径补 D1 等价投递 + barrier 结算复查，
                                              // create 路径见 proceed 内 D1 注释）：新追加上游中已终态且
                                              // 有结果的 → 立即投递（findNode 活动/归档兜底；含悬空完成的
                                              // 上游——其 completeNode 时 out 悬空未投，此处是唯一投递入口）；
                                              // 随后复查 barrier——deliveredTo 可能已含全部 in 上游（分批
                                              // 送达/重复接线）→ startNode。投递与结算串在同一 detached
                                              // fiber（deliverOutTo 自带结算 → startNode；复查随后命中
                                              // running/terminal 由 startNode 幂等跳过）；后台化遵循
                                              // runDetached 的阻塞教训（直接调用会把工具 fiber 卡到节点终态）。
                                              _ <-
                                                if adds.nonEmpty then
                                                  NodeTools.runDetached(
                                                    rt,
                                                    s"deliver appended upstream results + settle barrier -> ${node.id}"
                                                  )(
                                                    for
                                                      // 补投递完整性（fix b「已存在边+归档上游不补投递」修复
                                                      // 20260903）：不只投递本次追加上游——全部 in 上游中
                                                      // 「终态有结果而 deliveredTo 未记」的都补投（findNode
                                                      // 活动/归档兜底）。覆盖两类缺口：①边已存在但投递曾丢失
                                                      // （陈旧 out 覆盖时代的历史悬空，实证 n-219106db：两个
                                                      // 归档 completed 上游在 in 里、deliveredTo 恒空、下游
                                                      // 永久等待）；②新建边指向归档上游（fix a 路径，setOut
                                                      // 归档感知后不再崩溃）。运行中上游天然跳过（无 result，
                                                      // 等 completeNode → deliverOut 正常投）。
                                                      // R1：blocked 反馈串不是可投结果，显式排除（同 create 路径）。
                                                      _ <- rt.store.getNode(node.id).flatMap {
                                                        case Some(nFresh) =>
                                                          nFresh.in.traverse_ { upId =>
                                                            rt.store.findNode(upId).flatMap {
                                                              case Some(up)
                                                                  if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal
                                                                    .contains(up.status) =>
                                                                rt.engine.deliverOutTo(up, node.id, up.result.get)
                                                              case _ => IO.unit
                                                            }
                                                          }
                                                        case None => IO.unit
                                                      }
                                                      _ <- rt.store.getNode(node.id).flatMap {
                                                        case Some(n)
                                                            if n.in.nonEmpty && n.in.forall(n.deliveredTo.contains) =>
                                                          rt.engine.startNode(n.id)
                                                        case _ => IO.unit
                                                      }
                                                    yield ()
                                                  )
                                                else IO.unit
                                              // D1-deps 补触发（edit 路径，deps 设计 §1.2）：接线后若 deps 全
                                              // 满足 && in barrier 已归零 && 自身 wiring/pending → 立即启动。
                                              // 上游已 completed 时接 deps 边即时触达（含归档上游 findNode 兜底
                                              // ——「归档 completed 上游是唯一投递入口」先例同款）。startNode 内
                                              // 闸门二次把关，重复调用幂等。
                                              _ <-
                                                if depsProvided then
                                                  NodeTools.runDetached(rt, s"D1-deps settle -> ${node.id}")(
                                                    rt.store.getNode(node.id).flatMap {
                                                      case Some(n)
                                                          if (n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending)
                                                            && n.in.forall(n.deliveredTo.contains) =>
                                                        rt.engine
                                                          .depsSatisfied(n)
                                                          .flatMap(ok =>
                                                            if ok then rt.engine.startNode(n.id) else IO.unit
                                                          )
                                                      case _ => IO.unit
                                                    }
                                                  )
                                                else IO.unit
                                              // wiring 变更事件（NodeList 同构 payload，store 最终态）——此前只发
                                              // 本节点且 payload 含陈旧 in、改写的上游/新旧目标无事件（缺失补齐）
                                              _ <- NodeTools.emitWiringUpdates(rt, affected)
                                              // 链归属变更留痕（chainmodel 批一 ⑤，写点②）：声明写入 / 撤销 /
                                              // 本节点被并入别的分量 / 本节点把两个分量桥接成一条链 ⇒ 逐节点
                                              // 一条 `chain-membership-changed`。判据 = 写前快照 (`chainBefore`)
                                              // 与写后现读的**有效链归属视图**比对（判据单点 =
                                              // FlowMapStore.chainIdView，与载荷 chainId 同源）。
                                              _ <- NodeTools.emitChainMembershipChanges(rt, chainBefore)
                                            yield Right(
                                              s"Node '${node.name}' updated" +
                                                (if didReactivate then
                                                   if node.status == NodeLifecycle.Failed then
                                                     " — reactivated from failed (round history reset; next real failure re-notifies dispatcher)"
                                                   else if node.status == NodeLifecycle.Completed then
                                                     s" — reactivated from completed (NODE_COMPLETED_REACTIVATION; round ${node.blockCount} preserved)"
                                                   else
                                                     s" — reactivated from blocked (round ${node.blockCount} preserved)"
                                                 else "") +
                                                (if chainDecl.provided then
                                                   s" — chainId → ${chainDecl.decl.getOrElse("(withdrawn; back to the derived chain)")}"
                                                 else "") +
                                                (if notify.provided then s" — notifyDispatcher → ${notify.flag}"
                                                 else "") +
                                                (if retryFlag.provided then
                                                   retryFlag.policy match
                                                     case Some(p) => s" — retry ← ${p.upstream}:max=${p.max}"
                                                     case None => " — retry cleared"
                                                 else "") +
                                                (if outProvided && newOut.nonEmpty then
                                                   s" — out → ${newOut.map(_.to).mkString(",")}"
                                                 else "") +
                                                (if deferred.nonEmpty then
                                                   s" — wiring deferred (target running): ${deferred.map((e, _) => s"${e.to}:${e.mode}").mkString(",")} queued in pendingOut, auto-wired once the target leaves running (${FlowMapEventLog.WiringDeferredType})"
                                                 else "") +
                                                (if adds.nonEmpty then s" — in += ${adds.mkString(",")}" else "") +
                                                (if depsProvided && depsChanged then
                                                   s" — deps → [${newDeps.mkString(",")}]"
                                                 else "") +
                                                // W1 悬空提示（O-B 必做 4）：本次编辑把该节点留在无出边
                                                // 形态 ⇒ 尾部 ⚠ 行（与 stalledInWarning 同款形态）。
                                                (if outProvided && finalOut.isEmpty then
                                                   "\n" + NodeTools.wiringGapHint(node.name, node.id).mkString("\n")
                                                 else "")
                                            )
                                        // P1 校验层②（spec §2.2）：下游持 in 边而上游已 failed 无 on-failed
                                        // 边且非 merge → WARNING 级提示（不阻断，附成功结果尾部；死锁持续
                                        // 可见性由既有 mount-stalled 事件承载）
                                        stallWarn <- NodeTools.stalledInWarning(
                                          rt,
                                          node.id,
                                          node.name,
                                          finalIn,
                                          node.merge
                                        )
                                        // 通知策略自检（b64 批）：按**本次编辑后的生效声明**判
                                        // （传了 notify 用新值，否则沿用节点现值；legacy flag 同源）。
                                        notifyWarn <- NodeTools.notifyPolicyWarnings(
                                          rt,
                                          node.name,
                                          if notify.policyProvided then notify.policy else node.notifyPolicy,
                                          if notify.provided then notify.flag else node.notifyDispatcher,
                                          finalOut
                                        )
                                      yield inResult.map { r =>
                                        val warn = stallWarn ++ notifyWarn
                                        if warn.isEmpty then r else r + "\n" + warn.mkString("\n")
                                      }
                                      end for
                                  }
                              }
                          }
                      end match
                    end if
                  }
              }

end NodeEditTool
