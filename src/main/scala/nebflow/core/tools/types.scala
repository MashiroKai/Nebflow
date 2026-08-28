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
  /** Bash 卡死防护阈值（#391）：默认 Defaults 值，测试可注入小阈值验证
    * 自动转后台/硬超时/停滞窗口；GatewayMain 从 nebflow.json 顶层键覆写。 */
  bashConfig: nebflow.shared.BashResilienceConfig = nebflow.shared.BashResilienceConfig()
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
