# Simplify SessionActor — Extract CRUD, Keep Lifecycle Coordination

## Problem

`SessionActor` has accumulated too many responsibilities, making it a bloated coordinator that mixes unrelated concerns:

| Concern | Current Location | Should Be |
|---------|-----------------|-----------|
| Session CRUD (create/delete/switch/rename/list) | `SessionActor` | Cats-effect service |
| Agent CRUD (list/create/update agents) | `SessionActor` | Cats-effect service |
| Config management (get/update nebflow.json) | `SessionActor` | Cats-effect service |
| Agent lifecycle (spawn/kill/busy-check) | `SessionActor` | **Keep in simplified actor** |
| WS notification on agent events | `SessionActor` | **Keep in simplified actor** |
| Interrupt/AskUser/Permission forwarding | `SessionActor` | **Keep in simplified actor** |

Additionally, `GuardianActor` only maintains a `Map[String, ActorRef[SessionCommand]]` and adds a layer of indirection without providing meaningful value over direct actor spawning.

## Current Architecture

```
GuardianActor (global singleton)
  └── SessionActor (per WebSocket — bloated, ~475 lines)
        └── AgentActor (spawned per turn)
              └── AgentActor (subagent via delegate)
```

**SessionActor issues:**

1. **Mixed concerns**: 400+ lines handling session CRUD, agent CRUD, config management, and agent lifecycle coordination all in one actor.
2. **`unsafeRunAndForget` everywhere**: CRUD operations are sequential IO but forced through actor message handling, creating unnecessary `dispatcher.unsafeRunAndForget` boilerplate.
3. **GuardianActor is redundant**: It only forwards `CreateSession`/`DestroySession` to a Map. Connection lifecycle can be managed directly by the WebSocket handler.

## Design Principle

> **Actor boundaries should align with concurrency and lifecycle boundaries, not CRUD boundaries.**
>
> CRUD operations are sequential, side-effecting IO — they don't need actors. Agent lifecycle coordination involves concurrent state (busy or not, which agent is running) and benefits from actor semantics.

## Proposed Architecture

```
WebSocket connection (cats-effect fiber)
  ├── SessionService (cats-effect — session CRUD + notifications)
  ├── AgentService (cats-effect — agent CRUD)
  ├── ConfigService (cats-effect — config IO)
  └── SessionActor (Pekko Typed — slimmed down, ~120 lines)
        └── AgentActor (spawned per turn)
              └── AgentActor (subagent via delegate)
```

### Layer Responsibilities

| Layer | Technology | Responsibility |
|-------|-----------|----------------|
| WebSocket / HTTP | cats-effect (http4s) | Connection lifecycle, auth, message routing, spawn SessionActor |
| SessionService | cats-effect IO | Session CRUD: create, delete, switch, rename, list, getActiveId, setActiveMessages. **Also owns `sendSessionList` notification** |
| AgentService | cats-effect IO | Agent CRUD: list, create, update, getConfig (wraps AgentLibrary) |
| ConfigService | cats-effect IO | Read/merge/write `~/.nebflow/nebflow.json` |
| SessionActor | Pekko Typed | Agent lifecycle: spawn/kill, busy-check, forward Interrupt/AskUser/Permission. **Zero persistence, zero CRUD** |
| AgentActor | Pekko Typed | Agent state machine, LLM streaming, tool execution, subagent tree |

## Detailed Changes

### 1. Delete GuardianActor

**Current:** `GuardianActor` spawns `SessionActor` per connection and tracks them in a `Map`.

**New:** WebSocket handler spawns `SessionActor` directly using the global `ActorSystem` (kept for AgentActors), holds the `ActorRef[SessionCommand]` in connection scope, and stops it on disconnect.

```scala
// In WebSocketRoutes
val sessionActor = actorSystem.systemActorOf(
  SessionActor(wsConnId, perConnResources),
  s"session-$wsConnId"
)
// On disconnect: ctx.stop(sessionActor)
```

**Rationale:** GuardianActor adds a layer of indirection for a simple "spawn and track" pattern that the WebSocket handler can do directly. Connection cleanup on disconnect is handled by `ctx.stop` + `SessionActor.Terminate`.

### 2. Extract SessionService (cats-effect)

Move session CRUD **and** `sendSessionList` notification from `SessionActor` to a new `SessionService`:

```scala
class SessionService(store: SessionStore, wsSend: Json => IO[Unit]) {
  def createSession(name: String): IO[SessionMeta] =
    store.createSession(name) <* sendSessionList

  def deleteSession(id: String): IO[Unit] =
    store.deleteSession(id) <* sendSessionList

  def switchSession(id: String): IO[Unit] =
    store.switchSession(id) <* sendSessionList

  def renameSession(id: String, name: String): IO[Unit] =
    store.renameSession(id, name) <* sendSessionList

  def listSessions: IO[List[SessionMeta]] =
    store.listSessions

  def getActiveId: IO[String] =
    store.getActiveId

  def setActiveMessages(messages: List[Message]): IO[Unit] =
    store.setActiveMessages(messages)

  def saveMessages(sessionId: String, messages: List[Message]): IO[Unit] =
    store.saveMessagesForSession(sessionId, messages) *> store.flushIndex

  // Owned here — called by SessionActor via callback, not inline
  def sendSessionList: IO[Unit] =
    for
      sessions <- store.listSessions
      activeId <- store.getActiveId
      _ <- wsSend(Json.obj(
        "type" -> "sessionList".asJson,
        "sessions" -> sessions.asJson,
        "activeId" -> activeId.asJson
      ))
    yield ()
}
```

**Key design decision:** `sendSessionList` lives in `SessionService`, not `SessionActor`. SessionActor receives a `() => IO[Unit]` callback (or `SessionService` reference) and calls it via `unsafeRunAndForget` at the boundary. This keeps notification logic testable outside the actor.

### 3. Extract AgentService (cats-effect)

Move agent CRUD from `SessionActor` to `AgentService`:

```scala
class AgentService(library: AgentLibrary) {
  def listAgents: IO[List[AgentInfo]] =
    library.loadAll().map(_.values.toList.map { d =>
      AgentInfo(d.name, d.description, d.tools, d.subagents.map(_.name))
    }.sortBy(_.name))

  def getAgentConfig(name: String): IO[Option[AgentConfig]] =
    library.get(name).map(_.map { defn =>
      val configJson =
        if defn.configPath.nonEmpty && os.exists(os.Path(defn.configPath) / "agent.json")
        then os.read(os.Path(defn.configPath) / "agent.json")
        else ""
      AgentConfig(defn.name, configJson, defn.systemPrompt)
    })

  def createAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    validateName(name) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          os.makeDir.all(dir)
          os.write.over(dir / "agent.json", configJson)
          os.write.over(dir / "system.md", systemMd)
        }.attempt.map(_.leftMap(_.getMessage))

  def updateAgent(name: String, configJson: String, systemMd: String): IO[Either[String, Unit]] =
    validateName(name) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        IO.blocking {
          val dir = AgentLibrary.defaultDir / name
          if !os.exists(dir) then throw new RuntimeException(s"Agent not found: $name")
          os.write.over(dir / "agent.json", configJson)
          os.write.over(dir / "system.md", systemMd)
        }.attempt.map(_.leftMap(_.getMessage))

  private val AgentNameRegex = "[a-zA-Z0-9_-]+".r
  private def validateName(name: String): Either[String, Unit] =
    if name.nonEmpty && AgentNameRegex.matches(name) then Right(())
    else Left(s"Invalid agent name: $name")
}

case class AgentConfig(name: String, configJson: String, systemMd: String)
```

**Note:** Not "pure" — does file IO. But it's sequential, side-effecting IO that doesn't need actor semantics. Errors returned as `Either[String, Unit]`; WebSocket handler decides whether to send error notification.

### 4. Extract ConfigService (cats-effect)

Move config read/merge/write from `SessionActor` to `ConfigService`:

```scala
object ConfigService {
  private val configPath = os.home / ".nebflow" / "nebflow.json"

  def getConfig: IO[String] = IO.blocking {
    val content = if os.exists(configPath) then os.read(configPath) else "{}"
    content.replaceAll("(?i)\"(api[_-]?key|secret|token|password)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"***\"")
  }

  def updateConfig(incoming: String): IO[Either[String, Unit]] = IO.blocking {
    val existing = if os.exists(configPath) then os.read(configPath) else "{}"
    val merged = mergeConfig(existing, incoming)
    os.write.over(configPath, merged, createFolders = true)
  }.attempt.map(_.leftMap(_.getMessage).void)

  private def mergeConfig(existing: String, incoming: String): String = ??? // same logic as current
}
```

### 5. Simplify SessionActor (target: ~120 lines)

**Remove from SessionActor:**
- All session CRUD handlers (`SwitchSession`, `CreateSessionCmd`, `DeleteSession`, `RenameSession`, `ListSessions`)
- All agent CRUD handlers (`ListAgents`, `GetAgentConfig`, `CreateAgent`, `UpdateAgent`, `CreateAgentSession`)
- Config handlers (`GetConfig`, `UpdateConfig`)
- `SetThinking`, `SetPolicy`, `ClearChat` — move to WebSocket handler direct calls
- `SendSessionList` — WebSocket handler calls `SessionService.sendSessionList` directly
- **Persistence** (`saveMessagesForSession`, `flushIndex`) — delegate to `SessionService` callback
- **WS notifications** (`sessionBusy`, `error`, `interrupted`) — delegate to `wsSend` callback

**Keep in SessionActor:**
- `UserMessage` → busy check → spawn `AgentActor`
- `Interrupt(sessionId)` → forward to active agent
- `AskUserResponse` / `PermissionResponse` → forward to active agent
- `AgentTurnCompleted` → **callback to SessionService.saveMessages + SessionService.sendSessionList**, send `sessionBusy=false`
- `AgentTurnFailed` → send error + `sessionBusy=false`
- `AgentTerminated` → send `sessionBusy=false`
- `Terminate()` → stop all running agents, stop self

**Retain `agentStates` as `Map[String, AgentSessionState]`:**

A WebSocket connection can switch between sessions, and each session may have an active agent. Simplifying to `Option` would block session switching while an agent runs. Keep the `Map` keyed by `sessionId`.

```scala
private case class SessionData(
  agentStates: Map[String, AgentSessionState] = Map.empty
)
```

**SessionActor constructor receives callbacks, not full resources:**

```scala
def apply(
  wsConnId: String,
  resources: SharedResources,
  onAgentComplete: (String, List[Message]) => IO[Unit],  // SessionService.saveMessages
  onAgentFailed: (String, AgentError) => IO[Unit]        // wsSend error + sessionBusy=false
): Behavior[SessionCommand] = ???
```

Or simpler — keep `wsSend` and `sessionService` as constructor params, but all persistence/notifications go through them.

**Supervision strategy remains:**

```scala
Behaviors
  .supervise(
    Behaviors.setup[SessionCommand] { context =>
      // ...
    }
  )
  .onFailure[Exception](
    SupervisorStrategy.restart.withLimit(3, java.time.Duration.ofMinutes(1))
  )
```

The restart limit is safe because SessionActor holds no persistent state — all state is either in the `Map` (rebuilt on message replay) or delegated to callbacks.

### 6. Update WebSocketRoutes

WebSocket handler becomes a thin router:

```scala
// Direct cats-effect calls (no actor)
case "switchSession"     => sessionService.switchSession(id)
case "createSession"    => sessionService.createSession(name)
case "deleteSession"    => sessionService.deleteSession(id)
case "renameSession"    => sessionService.renameSession(id, name)
case "listAgents"       => agentService.listAgents.flatMap(sendAgentList)
case "createAgent"      => agentService.createAgent(...).flatMap {
  case Left(err) => wsSend(errorJson(err))
  case Right(_) => wsSend(successJson) *> sessionService.sendSessionList
}
case "getConfig"        => configService.getConfig.flatMap(sendConfig)
case "updateConfig"     => configService.updateConfig(...).flatMap {
  case Left(err) => wsSend(errorJson(err))
  case Right(_) => wsSend(successJson)
}
case "setThinking"      => thinkingModeRef.set(...)
case "setPolicy"        => permState.setPolicy(...)
case "clearChat"        => sessionService.setActiveMessages(Nil)

// Forward to SessionActor (agent lifecycle only)
case "userMessage"      => sessionActor ! SessionCommand.UserMessage(...)
case "interrupt"        => sessionActor ! SessionCommand.Interrupt(sessionId)
case "askUserAnswer"    => sessionActor ! SessionCommand.AskUserResponse(...)
case "permissionAnswer" => sessionActor ! SessionCommand.PermissionResponse(...)
```

**No more `askActor` boilerplate** for CRUD operations. All CRUD is direct IO.

### 7. SharedResources Refactor

Current: `wsSend` is a per-connection callback stored in `SharedResources`, copied per connection in GuardianActor.

New: `SharedResources` keeps only **global singletons** (llm, dispatcher, sessionStore, etc.). `wsSend` is passed directly to `SessionActor` and `SessionService` constructors, not through `SharedResources`.

```scala
// SharedResources — global only
case class SharedResources(
  llm: LlmHandle[IO],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore,
  projectRoot: os.Path,
  thinkingModeRef: Ref[IO, Option[Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: Ref[IO, ReminderState],
  contextWindow: Int,
  skillDiscovery: Option[SkillDiscovery],
  agentLibrary: AgentLibrary,
  askSemaphore: Semaphore[IO],
  taskStore: TaskStore
)
```

## Files to Modify

| File | Action | Notes |
|------|--------|-------|
| `GuardianActor.scala` | **Delete** | WebSocket handler spawns SessionActor directly |
| `SessionActor.scala` | **Rewrite** | ~120 lines, agent lifecycle only, callbacks for persistence/notifications |
| `protocol.scala` | **Trim** | Remove `GuardianCommand`, `SessionRef`, `Ack`; remove CRUD messages from `SessionCommand`; remove response types for CRUD (`SwitchResult`, `DeleteResult`, `SessionList`, `AgentListResp`, `AgentConfigResp`, `AgentCreatedResp`, `AgentUpdatedResp`, `ConfigDataResp`, `ConfigUpdatedResp`) |
| `WebSocketRoutes.scala` | **Rewrite** | Direct cats-effect calls for CRUD; no `askActor` helper; keep actor forwarding for agent messages |
| `GatewayMain.scala` | **Modify** | Remove `GuardianActor` creation; create `SessionService`/`AgentService`/`ConfigService`; keep `ActorSystem` for AgentActors |
| `SharedResources.scala` | **Modify** | Remove `wsSend` field |
| New: `SessionService.scala` | **Create** | Session CRUD + sendSessionList notification |
| New: `AgentService.scala` | **Create** | Agent CRUD, wraps AgentLibrary |
| New: `ConfigService.scala` | **Create** | Config read/merge/write |

## Migration Steps

### Phase 1: Extract Services (safe, no behavior change)

1. **Create `ConfigService`** — extract config management from `SessionActor`. No callers updated yet.
2. **Create `AgentService`** — extract agent CRUD from `SessionActor`. No callers updated yet.
3. **Create `SessionService`** — extract session CRUD + `sendSessionList` from `SessionActor`. No callers updated yet.

### Phase 2: Thread wsSend (medium risk)

4. **Modify `SharedResources`** — remove `wsSend`, keep only global resources.
5. **Update `AgentActor`** — add `wsSend` as explicit constructor param; replace all `resources.wsSend` with `wsSend`.
   - Audit all call sites: `AgentActor` internal streaming events, AskUser/Permission notifications, subagent spawn
   - Update `SessionActor.SpawnAgent` to pass `wsSend`
   - Update test spawns

### Phase 3: Simplify Actor Layer (medium risk)

6. **Rewrite `SessionActor`** — remove all CRUD handlers, delegate persistence/notifications to callbacks.
7. **Delete `GuardianActor`** — WebSocket handler spawns SessionActor directly via `ActorSystem`.
8. **Rewrite `WebSocketRoutes`** — route CRUD directly to services; remove `askActor`; keep actor forwarding for agent lifecycle.
9. **Update `GatewayMain`** — wire up new services; remove GuardianActor; pass `wsSend` directly to SessionActor/SessionService.

### Phase 4: Cleanup

10. **Clean up `protocol.scala`** — remove obsolete message and response types.
11. **Update tests** — replace GuardianActor tests; add SessionService/AgentService/ConfigService tests.

## Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|-----------|
| SessionActor still needs `unsafeRunAndForget` for callbacks | Low | Boundary is now a single callback invocation, not scattered persistence logic. Actor ~120 lines. Acceptable. |
| WebSocket handler grows with direct service calls | Low | Handler is routing only — `service.op *> wsSend(result)`. No business logic. |
| Actor system still needed | None | Keep `ActorSystem[Nothing]` as global singleton for spawning AgentActors. |
| `SharedResources.wsSend` removal affects AgentActor | **Medium** | **AgentActor uses `resources.wsSend` in ~10 places for streaming. Two options:** (1) Add `wsSend` as explicit param to `AgentActor.apply`, or (2) Keep `wsSend` in `SharedResources` but make it a per-connection copy. **Recommended: Option 1** — explicit is clearer. |
| SessionActor restart loses `agentStates` Map | Low | Map is rebuilt on message replay. On restart, in-flight agents are orphaned (already true today). Acceptable. |

## AgentActor `wsSend` Migration Detail

Current `AgentActor` accesses `wsSend` via `SharedResources` in ~10 places:
- `AgentActor.scala:738, 759, 776, 804, 820` — AskUser / Permission WS notifications
- `AgentActor.scala:1012, 1022, 1038, 1044, 1050` — Streaming events (textDelta, toolStart, etc.)

**Recommended approach (Option 1):**

```scala
// AgentActor.apply signature change
def apply(
  agentDef: AgentDef,
  resources: SharedResources,  // global singletons only, no wsSend
  wsSend: Json => IO[Unit],    // explicit per-connection callback
  depth: Int,
  parentRef: Option[ActorRef[AgentCommand]] = None,
  sessionId: Option[String] = None,
  initialMessages: List[Message] = Nil
): Behavior[AgentCommand]
```

All internal `resources.wsSend(...)` calls become `wsSend(...)`. `SharedResources` stays global-only.

**Call sites to update:**
- `SessionActor.SpawnAgent` — passes `wsSend` when spawning AgentActor
- Any direct AgentActor spawn in tests

## Testing Strategy

### Unit Tests (new)

| Service | Test Focus |
|---------|-----------|
| `SessionService` | CRUD operations call store methods; `sendSessionList` emits correct JSON; operations are atomic (store op then notification) |
| `AgentService` | `createAgent`/`updateAgent` write correct files; `validateName` rejects invalid names; `getAgentConfig` returns correct data |
| `ConfigService` | `getConfig` redacts secrets; `updateConfig` merges correctly (preserves `***` values); handles missing file |

### Integration Tests

- **WebSocket end-to-end**: Connect, create session, send message, verify streaming, interrupt, switch session, delete session — all via actual WS connection
- **Actor lifecycle**: Spawn SessionActor, send UserMessage, verify AgentActor spawned, send Interrupt, verify cleanup
- **Concurrent safety**: Two rapid UserMessages for same session — second should receive "already busy" error

### Regression Checklist

- [ ] Streaming (textDelta, toolStart, toolEnd, done)
- [ ] Subagent delegation and streaming
- [ ] AskUser / Permission flow
- [ ] Context compaction (full + micro)
- [ ] Session CRUD (create, switch, rename, delete, list)
- [ ] Agent CRUD (create, update, list, getConfig)
- [ ] Config read/update with secret redaction
- [ ] Interrupt during LLM streaming
- [ ] Interrupt during tool execution
- [ ] Rate limiting

## Success Criteria

- [ ] `GuardianActor.scala` deleted.
- [ ] `SessionActor.scala` under 150 lines (currently ~475).
- [ ] `SessionService`, `AgentService`, `ConfigService` created as cats-effect services.
- [ ] `SharedResources.wsSend` removed — only global singletons remain.
- [ ] WebSocket handler routes CRUD directly to services, agent messages to SessionActor.
- [ ] No `askActor` / `askPattern` usage in WebSocket handler.
- [ ] All existing functionality preserved (streaming, tools, subagents, compaction, AskUser, permissions).
- [ ] `unsafeRunAndForget` only in SessionActor for callback invocation at actor boundary.
- [ ] Unit tests for all three services.

## Related

- Supersedes `001-refactor-actor-hierarchy.md` — that proposal went too far by eliminating SessionActor entirely, losing actor supervision and centralized WS notification.
- `AgentActor` design remains unchanged — it already correctly self-manages subagent trees.

## Trade-off Summary

| Aspect | Current | Proposed | Verdict |
|--------|---------|----------|---------|
| **SessionActor lines** | ~475 | ~120 | Better — single responsibility |
| **Actor message types** | ~25 (SessionCommand + responses) | ~8 (lifecycle only) | Better — less protocol boilerplate |
| **askPattern usage** | ~10 places in WebSocketRoutes | 0 | Better — no sync-over-async |
| **unsafeRunAndForget** | ~15 scattered | ~3 in SessionActor only | Better — boundary is explicit |
| **Testability** | Need ActorTestKit for CRUD | Pure cats-effect tests for services | Better |
| **Files touched** | 6 | 9 (3 new services) | More files, but simpler each |
| **Migration risk** | — | Medium | AgentActor wsSend migration is the trickiest part |

**Overall: Correct refactor.** The separation of "sequential IO" vs "concurrent lifecycle" is architecturally sound. The only non-trivial work is threading `wsSend` through to AgentActor explicitly.
