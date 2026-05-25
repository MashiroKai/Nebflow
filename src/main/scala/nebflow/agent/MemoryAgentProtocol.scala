package nebflow.agent

import org.apache.pekko.actor.typed.ActorRef

/**
 * Memory Agent message protocol.
 *
 * Memory Agents are lightweight actors that manage memory .md files at each scope level:
 *   - User:      global singleton, manages ~/.nebflow/NEBFLOW.md
 *   - Agent:     per agent type, manages ~/.nebflow/agents/{name}/memory.md
 *   - Folder:    per top-level folder, manages all folder memory in its subtree
 *
 * Session memory is NOT managed by Memory Agents — session actors handle it directly.
 */
object MemoryAgentProtocol:

  /** Identifies which scope a memory agent manages. */
  enum MemoryAgentScope:
    case User
    case Agent(agentName: String)
    case Folder(topFolderId: String)

  /** Messages accepted by MemoryAgentActor. */
  sealed trait MemoryAgentCommand

  /**
   * A raw memory observation from a session agent.
   * The Memory Agent will decide how to integrate this into its managed memory file.
   *
   * @param entry           The raw memory content (e.g., a tagged bullet point)
   * @param sourceSessionId Which session produced this observation
   * @param category        Tag category: decision, fact, gotcha, convention, todo
   */
  case class MemoryWriteMail(
    entry: String,
    sourceSessionId: String,
    category: String
  ) extends MemoryAgentCommand

  /**
   * Trigger a dream cycle — the agent reviews its entire memory,
   * cleaning up stale/duplicate entries.
   * Sent by the idle timer (Pekko scheduler) after N minutes of inactivity.
   */
  case object TriggerDream extends MemoryAgentCommand

  /** Graceful shutdown. */
  case class Shutdown(replyTo: Option[ActorRef[ShutdownAck.type]] = None) extends MemoryAgentCommand

  case object ShutdownAck

  /** Sent back when a per-mail Memory Agent finishes its work. */
  case object MemoryAgentDone

  /** Label for logging and identification. */
  extension (scope: MemoryAgentScope)
    def label: String = scope match
      case MemoryAgentScope.User             => "user"
      case MemoryAgentScope.Agent(name)      => s"agent:$name"
      case MemoryAgentScope.Folder(folderId) => s"folder:${folderId.take(8)}"

  /** Unique key for actor lookup and naming. */
  extension (scope: MemoryAgentScope)
    def key: String = scope match
      case MemoryAgentScope.User             => "user"
      case MemoryAgentScope.Agent(name)      => s"agent-$name"
      case MemoryAgentScope.Folder(folderId) => s"folder-${folderId.take(8)}"

end MemoryAgentProtocol
