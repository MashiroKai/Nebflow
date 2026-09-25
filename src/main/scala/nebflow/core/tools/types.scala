package nebflow.core.tools

import cats.effect.std.Semaphore
import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.agent.*
import nebflow.core.hooks.*
import nebflow.core.task.TaskStore
import nebflow.core.{AskItem, AskOption, FileChangeTracker}
import nebflow.shared.*

case class ToolContext(
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  sessionStore: Option[nebflow.gateway.SessionStore] = None,
  agentActorRef: Option[ActorRef[AgentCommand]] = None,
  contextWindow: Int = Defaults.ContextWindow,
  sessionId: Option[String] = None,
  sessionName: Option[String] = None,
  /**
   * 顶层根会话 id（P0 接线修复，Explorer 取证 c759e8c）：AgentState 已持有
   * effectiveRootSessionId（spawn 时计算，非空用 rootSessionId 参数否则 sessionId），
   * thread 进 ToolContext——ProjectCreate mount 传真正顶层而非挂载者自身，
   * 使节点 out="Nebula" 投递到根会话而非执行者（防 qa-backend 自维持循环）。
   * None = 非 agent 会话上下文（如 REST 直调）。
   */
  rootSessionId: Option[String] = None,
  taskStore: Option[TaskStore] = None,
  wsSend: Option[Json => IO[Unit]] = None,
  readTracker: Option[ReadTracker] = None,
  fileHistory: Option[FileHistory] = None,
  parentRef: Option[ActorRef[AgentCommand]] = None,
  depth: Int = 0,
  agentDef: Option[AgentDef] = None,
  agentLibrary: Option[AgentLibrary] = None,
  fileLockManager: Option[FileLockManager] = None,
  fileChangeTracker: Option[FileChangeTracker] = None,
  hookEngine: HookEngine = HookEngine.noop,
  hookContext: HookContext = HookContext(None, "", ""),
  folderId: Option[String] = None,
  mailboxAddress: Option[String] = None,
  sharedResources: Option[SharedResources] = None,
  actorSystem: Option[ActorSystem] = None,
  messages: List[Message] = Nil,
  toolCallId: String = "",
  /**
   * 当轮 LLM 请求 id（审计 20260903 方案 B 关联增强）：pipeLlmCall 生成 →
   * ConsumeResult.requestId → pipeToolExecutions 注入本字段 → AgentCore 结构化
   * 埋点写入 ToolsLogWriter，tools 与 router 两类 JSONL 同 request_id 精确
   * 对齐。非 LLM 触发（REST 直调 / spec harness）为 None——key 必在、值可空。
   */
  requestId: Option[String] = None,
  /**
   * True when this call originates from another Nebflow instance via remote-exec.
   * Disables BashTool's auto-background mechanism — the caller manages lifecycle.
   */
  isRemoteExec: Boolean = false,
  /**
   * Team Manager task tools (2026-08-25): the team name of a team-agent
   * session, injected at spawn (AgentActor derives it from
   * TeamSessionRegistry). None for non-team contexts — TeamTaskCreate/Update
   * hard-reject (no cross-team write surface); TeamTaskList falls back to the
   * `team` parameter for Nebula's read-only oversight.
   */
  teamName: Option[String] = None,
  /**
   * Project 上下文（#28 阶段 0）：项目分发器会话注入——Node 工具 project
   * 参数缺省从本字段取（分发器无需每次传 project）。None = 非项目上下文。
   * TaskBoard 批 2：project 节点会话亦注入（SessionContext.projectName 链路
   * 接通——此前生产代码从未赋值，证据 §6-2），节点会话内 Node 系工具的
   * project 缺省解析随之生效。
   */
  projectName: Option[String] = None,
  /**
   * Project 任务板身份（TaskBoard 批 2，规格 §1d）：flowNodeId = project 节点
   * 会话的 NodeDef.id；isDispatcher = 分发器会话标记。由 AgentCore 从
   * SessionContext 透传（引擎侧身份，不信客户端参数）——TaskBoardTool 权限
   * 矩阵判定来源；两字段皆空 = 非项目会话（工具未挂载 + 工具内拒绝双保险）。
   */
  flowNodeId: Option[String] = None,
  isDispatcher: Boolean = false,
  /**
   * 节点角色（nrloop 一期 2026-09-12；设计 §3.2 + B1 透传链）：本节点会话所属
   * `NodeDef.role`（`task` | `verifier`，见 `NodeRoles`）。由 AgentCore 从
   * SessionContext 透传（引擎侧身份，不信客户端参数）——`node_report` 值域判据
   * （`NodeReportToolDef.enumFor(role)`，错误码 `NODE_REPORT_CATEGORY_ROLE`）与
   * 提示词面角色分支（`ProtocolFootnote`）的唯一来源。
   * None / 非项目会话 = 回落 `NodeRoles.Task`（与 `NodeDef` 解码缺省同口径）；
   * 挂载面过滤是另一道保险（`node_report` 仅 flowNodeSession 注入）。
   */
  flowNodeRole: Option[String] = None,
  /**
   * 链级抽象 P2（20260910 process-doc-chain-attribution spec §9.2 项 1）：本节点
   * 所属链 id = `chain-<分量最早 createdAt 节点 id>`（FlowMapStore.chainIdOf 判据
   * 单点，分量成员数 ≥2 才带值——孤立单节点链不带，与 payload chainId 条件键
   * 同口径）。NodeEngine 在节点 spawn 时一次性取值注入，经 SessionContext →
   * AgentCore 透传至此。分发器/非项目会话/单节点分量 = None。
   * 快照语义（§4.1 失败面③）：spawn 后接线并链不改本值 → 节点写文档时标
   * `chain-source: engine`（快照归属），权威归属留给索引。
   */
  flowChainId: Option[String] = None,
  /**
   * Bash 卡死防护阈值（#391）：默认 Defaults 值，测试可注入小阈值验证
   * 自动转后台/硬超时/停滞窗口；GatewayMain 从 nebflow.json 顶层键覆写。
   */
  bashConfig: nebflow.shared.BashResilienceConfig = nebflow.shared.BashResilienceConfig(),
  /**
   * 阶段 2a 沙箱策略（设计文档 §A.3）：从节点 projectRoot 构造、随 spawn 传递
   * （复用 projectRoot 传递链，AgentCore 在 ToolContext 构造点派生）。默认
   * SandboxPolicy.off = 全部闸门旁路（旧行为，§G.1 回滚语义；存量测试零改动）。
   * 相对路径一律以 sandbox.root 为基准解析（修掉 Glob/Grep 默认根=user.dir）。
   */
  sandbox: nebflow.core.sandbox.SandboxPolicy = nebflow.core.sandbox.SandboxPolicy.off,
  /**
   * **会话初始 cwd**（B5 缺口② · 作者 2026-09-17 M-1 裁定「会话启动即 `cd` 座椅」，
   * 选项①）：Some = 本会话 shell 的初 cwd（worktree 节点的**座椅路径**，NodeEngine
   * 两个 spawn 点经 SessionContext.sessionCwd → AgentState 透传到此处）。
   *
   * 与 [[sandbox]] **分道**（本字段不参与策略构造）：sandbox 仍是围栏面
   * （root = 工作区根，2026-09-05 21:05 裁定不推翻），本字段只决定「shell 从哪个目录
   * 起步」。消费单点 = `BashTool`（`initialDir` 优先取本字段，缺省回落 `sandbox.root`）。
   * 座椅缺失 ⇒ `ShellSession.resolveCwdOrFail` 显式失败（fail-closed），**不**回落
   * 工作区根。None = 旧行为（初 cwd = 沙箱根）逐字节不变。
   */
  sessionCwd: Option[String] = None
):
  /**
   * 「Nebula 本体根会话」身份判据的**运行期求值面**（工具面按角色分化批 B1，
   * 2026-09-13）——**纯委托**给单点 [[nebflow.agent.AgentCore.isRootAgent]]
   * （同一份实现的第二个求值面；第一个 = 定义期挑 schema 变体）。**禁**在此
   * 重写 `name=="Nebula" && depth==0`（判红：spec 的静态断言）。
   *
   * 派生 def（不是字段）⇒ 零构造点改动；`agentDef=None`（REST 直调 / harness）
   * fail-closed 为 false。
   */
  def isRootAgent: Boolean = AgentCore.isRootAgent(agentDef, depth)
end ToolContext

case class ToolError(message: String)
case class ProcessResult(stdout: String, stderr: String, exitCode: Int, cwd: String)

trait Tool:
  def name: String
  def description: String
  def inputSchema: JsonObject
  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]]
  def summarize(input: JsonObject): String
  def summarizeResult(input: JsonObject, result: String): String
  def maxResultSizeChars: Int = Defaults.DefaultMaxResultSizeChars

  /**
   * Extract image content blocks from a successful tool result.
   * Override in tools that produce image data (e.g. Read on image files).
   * Returns None for text-only results. When present, these blocks are
   * injected into the LLM message alongside the tool_result so vision
   * APIs can process the image.
   */
  def extractImages(input: JsonObject, result: String): Option[List[ContentBlock.Image]] = None

  /**
   * The **model-facing projection** of a successful tool result (imgticket batch
   * ii, author #687-D 2026-09-16).
   *
   * A tool returns ONE string from [[call]], and the engine hands that same
   * string to both consumers: the frontend (`ToolExecResult.frontendContent` →
   * the ToolEnd frame and the `.ui.json` history line) and the model
   * (`ToolExecResult.content` → `ContentBlock.ToolResult`). For a tool whose
   * payload exists for the browser rather than for a language model — Card's
   * HTML body with its base64-inlined images — the two consumers want different
   * strings: the model gains nothing from the markup, and the payload's size is
   * what pushes the result past `Defaults.DefaultMaxResultSizeChars`, at which
   * point `ToolResultGuard` replaces what the model sees with a truncated
   * preview plus a disk copy.
   *
   * This hook narrows **only** the model-facing string. The frontend face stays
   * the tool's verbatim result, so nothing about rendering, replay or the
   * stored history changes. Default = identity: every tool that does not
   * override it keeps today's behaviour byte-for-byte.
   */
  def modelFacingResult(result: String): String = result
end Tool
