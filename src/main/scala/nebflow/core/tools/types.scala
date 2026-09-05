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
  /** 顶层根会话 id（P0 接线修复，Explorer 取证 c759e8c）：AgentState 已持有
    * effectiveRootSessionId（spawn 时计算，非空用 rootSessionId 参数否则 sessionId），
    * thread 进 ToolContext——ProjectCreate mount 传真正顶层而非挂载者自身，
    * 使节点 out="Nebula" 投递到根会话而非执行者（防 qa-backend 自维持循环）。
    * None = 非 agent 会话上下文（如 REST 直调）。 */
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
  /** 当轮 LLM 请求 id（审计 20260903 方案 B 关联增强）：pipeLlmCall 生成 →
    * ConsumeResult.requestId → pipeToolExecutions 注入本字段 → AgentCore 结构化
    * 埋点写入 ToolsLogWriter，tools 与 router 两类 JSONL 同 request_id 精确
    * 对齐。非 LLM 触发（REST 直调 / spec harness）为 None——key 必在、值可空。 */
  requestId: Option[String] = None,
  /**
   * True when this call originates from another Nebflow instance via remote-exec.
   * Disables BashTool's auto-background mechanism — the caller manages lifecycle.
   */
  isRemoteExec: Boolean = false,
  /** Team Manager task tools (2026-08-25): the team name of a team-agent
    * session, injected at spawn (AgentActor derives it from
    * TeamSessionRegistry). None for non-team contexts — TeamTaskCreate/Update
    * hard-reject (no cross-team write surface); TeamTaskList falls back to the
    * `team` parameter for Nebula's read-only oversight. */
  teamName: Option[String] = None,
  /** Project 上下文（#28 阶段 0）：项目分发器会话注入——Node 工具 project
    * 参数缺省从本字段取（分发器无需每次传 project）。None = 非项目上下文。 */
  projectName: Option[String] = None,
  /** Bash 卡死防护阈值（#391）：默认 Defaults 值，测试可注入小阈值验证
    * 自动转后台/硬超时/停滞窗口；GatewayMain 从 nebflow.json 顶层键覆写。 */
  bashConfig: nebflow.shared.BashResilienceConfig = nebflow.shared.BashResilienceConfig(),
  /** 阶段 2a 沙箱策略（设计文档 §A.3）：从节点 projectRoot 构造、随 spawn 传递
    * （复用 projectRoot 传递链，AgentCore 在 ToolContext 构造点派生）。默认
    * SandboxPolicy.off = 全部闸门旁路（旧行为，§G.1 回滚语义；存量测试零改动）。
    * 相对路径一律以 sandbox.root 为基准解析（修掉 Glob/Grep 默认根=user.dir）。 */
  sandbox: nebflow.core.sandbox.SandboxPolicy = nebflow.core.sandbox.SandboxPolicy.off
)

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
end Tool
