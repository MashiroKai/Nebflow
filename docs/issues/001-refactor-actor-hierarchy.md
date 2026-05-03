# Refactor: Flatten Actor Hierarchy — Agent Self-Managed Lifecycle

## Problem

The current actor hierarchy has a mismatch between **state management layers** and **actor tree layers**:

```
GuardianActor (global singleton)
  └── SessionActor (per WebSocket connection)
        └── AgentActor (spawned per user turn)
              └── AgentActor (subagent via delegate)
```

**Issues:**

1. **Centralized bottleneck**: `SessionActor` maintains an `agentStates: Map[String, AgentSessionState]` to track running agents. This is unnecessary — the Actor model is already distributed; a central map adds coordination overhead without benefit.
2. **Redundant lifecycle management**: `SessionActor` decides when to spawn/kill agents, but `AgentActor` already has its own state machine (`idle` → `processing` → `idle`/`stopped`) and parent-child supervision via `ctx.watchWith`.
3. **Awkward IO/Actor bridging**: `SessionActor` is full of `resources.dispatcher.unsafeRunAndForget(...)` calls because it sits between the cats-effect WebSocket layer and the Pekko actor layer.
4. **Interrupt propagation is indirect**: User interrupt goes `WebSocket → SessionActor → agentStates lookup → AgentActor`, instead of directly to the root agent which can recursively propagate to its subtree.
5. **GuardianActor is pure overhead**: It only maintains a `Map[String, ActorRef[SessionCommand]]` of sessions. This is connection management, not agent logic — it should be handled by cats-effect `Resource`.

## Design Principle

> **Agent trees self-manage their lifecycle. Upper layers only hold a reference to the root agent.**

The actor boundary should align with the **agent boundary**:
- Each `AgentActor` = one agent instance with its own state machine.
- Parent agent manages child agents via `ctx.spawn` / `ctx.watchWith` / `ctx.stop`.
- WebSocket/session layer holds only the **root agent ref** and sends messages directly.

## Proposed Architecture

```
WebSocket connection (cats-effect fiber)
  ├── SessionContext (Ref[IO, SessionMeta] — pure data, no actor)
  └── Root AgentActor (Pekko Typed)
        ├── AgentActor (subagent, delegate)
        │     └── AgentActor (nested subagent)
        └── AgentActor (another subagent)
```

### Layer Responsibilities

| Layer | Technology | Responsibility |
|-------|-----------|----------------|
| WebSocket / HTTP | cats-effect (http4s) | Connection lifecycle, auth, message routing |
| SessionContext | `Ref[IO, SessionData]` | Active session ID, root agent ref, user preferences |
| AgentActor | Pekko Typed | Agent state machine, LLM streaming, tool execution, subagent tree |
| SessionStore | cats-effect IO | Persistence (messages, session metadata) |

## Detailed Changes

### 1. Remove GuardianActor

**Current:** `GuardianActor` spawns `SessionActor` per connection and tracks them in a `Map`.

**New:** WebSocket connection handler uses cats-effect `Resource` to manage the root `AgentActor` lifecycle.

```scala
// Pseudo-code for WebSocketRoutes
Resource.make(
  IO.spawnActor(AgentActor(rootAgentDef, resources, depth = 0))
)(ref => IO(ref ! AgentCommand.Stop("connection closed")))
  .use { rootAgentRef =>
    // Hold rootAgentRef in SessionContext
    // Route user messages directly: rootAgentRef ! UserInput(...)
  }
```

**Rationale:** Connection management is not agent logic. cats-effect `Resource` handles cleanup on connection drop more naturally than Actor death watch.

### 2. Simplify SessionActor → SessionManager (pure cats-effect)

**Current:** `SessionActor` is a Pekko actor that:
- Receives `UserMessage` → checks `agentStates` map → spawns `AgentActor`
- Receives `AgentTurnCompleted` → persists messages → removes from map
- Handles session CRUD (create/delete/switch/rename/list)
- Forwards `AskUserResponse` / `PermissionResponse` to active agent

**New:** Split into two parts:

#### A. SessionManager (cats-effect, no actor)

```scala
class SessionManager(store: SessionStore) {
  def createSession(name: String): IO[SessionMeta] = ...
  def deleteSession(id: String): IO[Unit] = ...
  def switchSession(id: String): IO[Unit] = ...
  def renameSession(id: String, name: String): IO[Unit] = ...
  def listSessions: IO[List[SessionMeta]] = ...
  def getActiveId: IO[String] = ...
}
```

All session CRUD commands go directly to `SessionManager` from the WebSocket handler. No actor involved.

#### B. Root Agent receives `UserInput` directly

```scala
// WebSocket handler
val rootAgentRef: ActorRef[AgentCommand] = ...

// On user message
rootAgentRef ! AgentCommand.UserInput(text, replyTo = None)
```

The `AgentActor` already has a `StashBuffer`. If it's `processing`, new `UserInput` is stashed automatically. No need for a busy check in `SessionActor`.

**Note:** If we want to reject messages while busy (instead of stashing), add a simple `Ref[IO, Boolean]` in `SessionContext` or let the root agent reply with a "busy" event.

### 3. AgentActor handles its own completion and persistence

**Current:** `AgentActor.finishTurn` sends `AgentEvent.Completed` to `SessionActor`, which then calls `sessionStore.saveMessagesForSession`.

**New:** Root agent persists directly on turn completion.

```scala
// AgentActor.finishTurn (root agent, parentRef == None)
case None =>
  val newMessages = state.messages :+ Message(Assistant, ...)
  // Root agent persists its own state
  resources.dispatcher.unsafeRunAndForget(
    resources.sessionStore.saveMessagesForSession(sessionId, newMessages)
  )
  emitStream(Done)
  stash.unstashAll(idle(state.copy(messages = newMessages, status = Idle)))
```

For subagents, they report to parent via `DelegateResult` and then `Behaviors.stopped`, same as today.

### 4. Interrupt goes directly to root agent

**Current:**
```
WebSocket → SessionCommand.Interrupt(sessionId) → SessionActor lookup agentStates → AgentCommand.Interrupt()
```

**New:**
```
WebSocket → rootAgentRef ! AgentCommand.Interrupt()
```

`AgentActor` already handles `Interrupt()` by canceling its fiber and forwarding to all subagents:

```scala
case AgentCommand.Interrupt() =>
  state.activeStreamFiber.foreach(_.cancel)
  state.subagents.values.foreach(_ ! AgentCommand.Interrupt())
  // ... cleanup and return to idle
```

This is exactly right — remove the indirection.

### 5. AskUser / Permission responses go directly to root agent

**Current:** `SessionCommand.AskUserResponse` → `SessionActor` → finds active agent → `AgentCommand.UserAnswered`.

**New:** WebSocket handler holds the root agent ref and sends `AgentCommand.UserAnswered` directly.

```scala
// In WebSocket message handler
case "askUserAnswer" =>
  rootAgentRef ! AgentCommand.UserAnswered(answers)
```

The root agent either handles it itself (if `pendingAskUser` is set) or ignores it.

### 6. Agent management commands (list/create/update agents)

These are currently handled by `SessionActor` but have nothing to do with session state or agent runtime.

**New:** Move to `AgentLibrary` (already exists) exposed as a plain cats-effect service:

```scala
class AgentManager(library: AgentLibrary) {
  def listAgents: IO[List[AgentInfo]] = ...
  def createAgent(name: String, config: String, prompt: String): IO[Unit] = ...
  def updateAgent(name: String, config: String, prompt: String): IO[Unit] = ...
  def getAgentConfig(name: String): IO[Option[AgentConfig]] = ...
}
```

WebSocket handler calls these directly, no actor round-trip.

### 7. Config management commands

Same pattern — plain file IO, no actor needed.

## Files to Modify

| File | Action | Notes |
|------|--------|-------|
| `GuardianActor.scala` | **Delete** | Replaced by cats-effect `Resource` in WebSocket handler |
| `SessionActor.scala` | **Delete** | Split into `SessionManager` (cats-effect) and direct root agent messaging |
| `protocol.scala` | **Trim** | Remove `GuardianCommand`, `SessionCommand` (or keep only session CRUD as plain case classes), `SessionRef`, `Ack` |
| `WebSocketRoutes.scala` | **Rewrite** | Spawn root `AgentActor` directly; hold ref in connection scope; route messages directly to agent |
| `AgentActor.scala` | **Modify** | Add root-agent persistence in `finishTurn`; ensure `Interrupt` propagates correctly; remove `replyTo` dependency for completion events (or make it optional) |
| `SharedResources.scala` | **Modify** | Remove `wsSend` from `SharedResources` — pass it explicitly to root agent; `wsSend` is per-connection, not global |
| `GatewayMain.scala` | **Modify** | Remove `ActorSystem[GuardianCommand]` creation; no global actor system needed (or keep one for AgentActors only) |
| New: `SessionManager.scala` | **Create** | Pure cats-effect session CRUD service |
| New: `AgentManager.scala` | **Create** | Pure cats-effect agent CRUD service (wraps `AgentLibrary`) |

## Migration Steps

1. **Create `SessionManager`** — extract session CRUD from `SessionActor`, pure cats-effect.
2. **Create `AgentManager`** — extract agent CRUD from `SessionActor`, pure cats-effect.
3. **Modify `WebSocketRoutes`** — spawn root `AgentActor` directly via `ActorSystem`; hold ref in connection scope; route user messages directly.
4. **Modify `AgentActor`** — add direct persistence in `finishTurn` for root agents; test interrupt propagation.
5. **Delete `GuardianActor`** — remove from `GatewayMain`.
6. **Delete `SessionActor`** — remove all references.
7. **Clean up `protocol.scala`** — remove obsolete message types.
8. **Update tests** — actor tests for Guardian/Session need replacement.

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Root agent ref becomes stale if actor crashes | Pekko supervisor strategy restarts the actor; WebSocket handler should watch and recreate if needed |
| Multiple concurrent user messages to same root agent | `AgentActor.StashBuffer` handles this; or add explicit busy check in `SessionContext` |
| Session persistence failure during agent completion | Already async (`unsafeRunAndForget`); failure is logged but doesn't crash agent. Consider retry or user notification. |
| Actor system still needed for AgentActors | Yes, keep `ActorSystem[Nothing]` or `ActorSystem[AgentCommand]` as a global singleton for spawning agents. |

## Success Criteria

- [ ] `GuardianActor.scala` deleted.
- [ ] `SessionActor.scala` deleted.
- [ ] `WebSocketRoutes` spawns root `AgentActor` directly and holds its ref.
- [ ] User messages go directly to root agent, no intermediate actor.
- [ ] Interrupt goes directly to root agent, propagates to subagent tree.
- [ ] Session CRUD is pure cats-effect, no actor.
- [ ] Agent CRUD is pure cats-effect, no actor.
- [ ] All existing functionality preserved (streaming, tools, subagents, compaction, AskUser, permissions).

## Related

- Current `AgentActor` design already supports self-managed subagent trees via `ctx.spawn` + `ctx.watchWith`.
- `StashBuffer` in `AgentActor` already handles message queuing during processing.
