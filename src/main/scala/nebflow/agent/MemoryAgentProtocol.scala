package nebflow.agent

/**
 * Memory Agent protocol.
 *
 * Memory Agent runs as a Dream session for periodic consolidation
 * and pattern extraction across all memory files.
 *
 * MemoryAgentScope is used by the Dream scheduler to identify which files to process.
 */
object MemoryAgentProtocol:

  /** Identifies which scope a memory file belongs to. */
  enum MemoryAgentScope:
    case User
    case Agent(agentName: String)
    case Folder(topFolderId: String)

  /** Label for logging and identification. */
  extension (scope: MemoryAgentScope)

    def label: String = scope match
      case MemoryAgentScope.User => "user"
      case MemoryAgentScope.Agent(name) => s"agent:$name"
      case MemoryAgentScope.Folder(folderId) => s"folder:${folderId.take(8)}"

  /** Unique key for actor lookup and naming. */
  extension (scope: MemoryAgentScope)

    def key: String = scope match
      case MemoryAgentScope.User => "user"
      case MemoryAgentScope.Agent(name) => s"agent-$name"
      case MemoryAgentScope.Folder(folderId) => s"folder-${folderId.take(8)}"

end MemoryAgentProtocol
