# Extract wsSend from SharedResources — Complete Actor/IO Boundary Cleanup

## Background

Issue `002-simplify-session-actor.md` extracted SessionService, AgentService, and ConfigService from SessionActor, deleted GuardianActor, and simplified SessionActor to agent lifecycle only. One item was intentionally deferred: removing `wsSend` from `SharedResources`.

**Current state:** `SharedResources.wsSend` is a per-connection callback stored in a global singleton case class. Each WebSocket connection creates a per-connection copy via `sharedResources.copy(wsSend = perConnWsSend)`. This is a layering violation — global resources should not carry per-connection state.

## Problem

`SharedResources` is documented as "global singletons only" but still contains:

```scala
case class SharedResources(
  // ... global singletons ...
  wsSend: Json => IO[Unit],  // <-- per-connection, does not belong here
  // ...
)
```

This causes two issues:

1. **Layering violation**: Global resource container carries per-connection state. Every actor in the tree receives a "mostly global but slightly per-connection" resource bag, making it unclear what is safe to share across sessions.
2. **Hidden coupling**: `AgentActor` accesses `wsSend` via `resources.wsSend` in 12 places. The dependency is implicit — you cannot tell from `AgentActor.apply` signature that it needs a WebSocket connection.

## Goal

Remove `wsSend` from `SharedResources` entirely. Pass `wsSend` as an explicit constructor parameter to every component that needs it.

## Scope

### In Scope

- Remove `wsSend` field from `SharedResources`
- Add `wsSend: Json => IO[Unit]` as explicit parameter to `AgentActor.apply`
- Add `wsSend: Json => IO[Unit]` as explicit parameter to `SessionActor.apply`
- Update all `AgentActor` internal call sites: `resources.wsSend(...)` → `state.wsSend(...)`
- Update all `SessionActor` internal call sites: `resources.wsSend(...)` → `wsSend(...)` (constructor param)
- Update `SessionActor.SpawnAgent` to pass `wsSend` when spawning `AgentActor`
- Update subagent spawn call sites in `AgentActor` to pass `state.wsSend`
- Update `WebSocketRoutes` to pass `perConnWsSend` to `SessionActor`
- Update `GatewayMain` to remove `wsSend` from `SharedResources` construction

### Out of Scope

- No behavior changes — all WebSocket messages sent should be identical
- No changes to service layer (SessionService, AgentService, ConfigService)
- No changes to protocol definitions beyond adding `wsSend` to `AgentState`
- No changes to `executeTool` signature in `handlers.scala` (already receives `wsSend` explicitly)

## Design Decision: How AgentActor Carries wsSend

`AgentActor` has 12 call sites spread across multiple behavior functions (`idle`, `processing`, `waitingForUser`, `waitingForPermission`, `delegating`, `streamEmitter`). These functions already receive `state: AgentState` as a parameter. The cleanest approach:

**Add `wsSend` to `AgentState`** (in `protocol.scala`). Behavior functions access it via `state.wsSend`. This avoids threading an extra parameter through every behavior function signature.

```scala
// protocol.scala — AgentState
case class AgentState(
  messages: List[Message],
  status: AgentStatus,
  depth: Int,
  subagents: Map[String, ActorRef[AgentCommand]],
  activeStreamFiber: Option[Fiber[IO, Throwable, Unit]],
  sessionId: Option[String] = None,
  pendingCompaction: Option[CompactionContext] = None,
  pendingManualCompaction: Option[String] = None,
  latestUsage: Option[TokenUsage] = None,
  pendingAskUser: Option[Deferred[IO, List[String]]] = None,
  pendingPermission: Option[Deferred[IO, Boolean]] = None,
  recentToolCalls: List[ToolCallRecord] = Nil,
  turnIdx: Int = 0,
  wsSend: Json => IO[Unit] = _ => IO.unit  // <-- ADD
)
```

`AgentActor.apply` sets it at construction:

```scala
idle(
  agentDef,
  resources,
  depth,
  parentRef,
  AgentState(
    messages = initialMessages,
    status = AgentStatus.Idle,
    // ... other fields ...
    wsSend = wsSend  // <-- set from constructor param
  ),
  stash,
  context
)
```

All internal `resources.wsSend(...)` become `state.wsSend(...)`.

**Why not behavior function parameter?** There are 5+ behavior functions and multiple internal helpers (`emitStream`, `emitStreamIO`, `streamEmitter`). Adding `wsSend` to every signature is noisy and error-prone. `AgentState` is already the canonical way to carry per-agent mutable context.

**Safety note:** `wsSend` defaults to `_ => IO.unit`. If `AgentActor.apply` forgets to set it, WS messages silently disappear. The migration order below ensures compile errors surface any missed call sites.

## Current Call Sites

### AgentActor.scala

`resources.wsSend` is used in the following locations (line numbers from current `feat/simplify-session-actor` branch):

| Line | Context | What to Change |
|------|---------|----------------|
| ~395 | Subagent spawn (compaction) | `AgentActor(subDef, resources, depth + 1, Some(ctx.self))` → add `wsSend = state.wsSend` |
| ~468 | Subagent spawn (delegation) | `AgentActor(subDef, resources, subDepth, Some(ctx.self))` → add `wsSend = state.wsSend` |
| ~738 | AskUser notification | `resources.wsSend(askJson)` → `state.wsSend(askJson)` |
| ~759 | `executeTool` safe path | `wsSend = Some(resources.wsSend)` → `wsSend = Some(state.wsSend)` |
| ~776 | `executeTool` approved path | `wsSend = Some(resources.wsSend)` → `wsSend = Some(state.wsSend)` |
| ~804 | Permission notification | `resources.wsSend(permJson)` → `state.wsSend(permJson)` |
| ~820 | `executeTool` post-approval | `wsSend = Some(resources.wsSend)` → `wsSend = Some(state.wsSend)` |
| ~1012 | `emitStream` helper | `resources.wsSend(...)` → `state.wsSend(...)` |
| ~1022 | `emitStreamIO` helper | `resources.wsSend(...)` → `state.wsSend(...)` |
| ~1038 | `streamEmitter` textDelta | `resources.wsSend(json)` → `state.wsSend(json)` |
| ~1044 | `streamEmitter` thinking | `resources.wsSend(json)` → `state.wsSend(json)` |
| ~1050 | `streamEmitter` toolStart | `resources.wsSend(json)` → `state.wsSend(json)` |

**Verify exact line numbers** by searching `resources.wsSend` in `AgentActor.scala` before starting.

### SessionActor.scala

Current uses of `resources.wsSend`:

| Location | Purpose | What to Change |
|----------|---------|----------------|
| `UserMessage` — busy check | Send error + sessionBusy=true | `resources.wsSend(...)` → `wsSend(...)` (from constructor) |
| `AgentTurnCompleted` | Send sessionBusy=false | `resources.wsSend(...)` → `wsSend(...)` |
| `AgentTurnFailed` | Send error + sessionBusy=false | `resources.wsSend(...)` → `wsSend(...)` |
| `Interrupt` | Send interrupted + sessionBusy=false | `resources.wsSend(...)` → `wsSend(...)` |
| `AgentTerminated` | Send sessionBusy=false | `resources.wsSend(...)` → `wsSend(...)` |

### handlers.scala

`executeTool` already accepts `wsSend: Option[Json => IO[Unit]] = None` explicitly. No signature change needed. Only the call sites in `AgentActor.scala` need to pass `state.wsSend` instead of `resources.wsSend`.

## Proposed Changes

### 1. SharedResources

```scala
// BEFORE
case class SharedResources(
  llm: LlmHandle[IO],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore,
  projectRoot: os.Path,
  wsSend: Json => IO[Unit],  // REMOVE
  thinkingModeRef: Ref[IO, Option[Json]],
  // ...
)

// AFTER
case class SharedResources(
  llm: LlmHandle[IO],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore,
  projectRoot: os.Path,
  thinkingModeRef: Ref[IO, Option[Json]],
  // ...
)
```

Update class doc to remove the "Per-connection" section. Update `GatewayMain` — remove the `wsSend = _ => IO.unit` placeholder.

### 2. AgentState (protocol.scala)

```scala
// ADD to AgentState case class
case class AgentState(
  // ... existing fields ...
  turnIdx: Int = 0,
  wsSend: Json => IO[Unit] = _ => IO.unit  // <-- ADD
)
```

### 3. AgentActor.apply

```scala
// BEFORE
def apply(
  agentDef: AgentDef,
  resources: SharedResources,
  depth: Int,
  parentRef: Option[ActorRef[AgentCommand]] = None,
  sessionId: Option[String] = None,
  initialMessages: List[Message] = Nil
): Behavior[AgentCommand]

// AFTER
def apply(
  agentDef: AgentDef,
  resources: SharedResources,  // global singletons only
  wsSend: Json => IO[Unit],    // explicit per-connection callback
  depth: Int,
  parentRef: Option[ActorRef[AgentCommand]] = None,
  sessionId: Option[String] = None,
  initialMessages: List[Message] = Nil
): Behavior[AgentCommand]
```

Inside `apply`, set `wsSend` on the initial `AgentState`:

```scala
AgentState(
  // ... existing fields ...
  wsSend = wsSend
)
```

### 4. Subagent spawn sites (AgentActor.scala)

Two subagent spawn call sites must pass `state.wsSend`:

```scala
// ~395: compaction subagent
val subActor = ctx.spawn(
  AgentActor(subDef, resources, state.wsSend, depth + 1, Some(ctx.self)),
  subId
)

// ~468: delegation subagent
val subActor = ctx.spawn(
  AgentActor(subDef, resources, state.wsSend, subDepth, Some(ctx.self)),
  subId
)
```

### 5. SessionActor.apply

```scala
// BEFORE
def apply(wsConnId: String, resources: SharedResources): Behavior[SessionCommand]

// AFTER
def apply(
  wsConnId: String,
  resources: SharedResources,
  wsSend: Json => IO[Unit]
): Behavior[SessionCommand]
```

All internal `resources.wsSend(...)` calls become `wsSend(...)`.

### 6. SessionActor.SpawnAgent

`SessionActor` passes `wsSend` from its constructor when spawning `AgentActor`:

```scala
// In SessionActor.active — SpawnAgent handler
val agentRef = ctx.spawn(
  AgentActor(
    agentDef,
    resources,
    wsSend,  // from SessionActor constructor
    depth = 0,
    parentRef = None,
    sessionId = Some(sessionId),
    initialMessages = initialMessages
  ),
  s"agent-$sessionId-${System.currentTimeMillis()}"
)
```

### 7. WebSocketRoutes

```scala
// BEFORE
val perConnResources = sharedResources.copy(wsSend = perConnWsSend)
IO(actorSystem.systemActorOf(SessionActor(wsConnId, perConnResources), s"session-$wsConnId"))

// AFTER
IO(actorSystem.systemActorOf(
  SessionActor(wsConnId, sharedResources, perConnWsSend),
  s"session-$wsConnId"
))
```

No more `copy` on `SharedResources`.

## Files to Modify

| File | Action |
|------|--------|
| `SharedResources.scala` | Remove `wsSend` field; update class doc |
| `protocol.scala` | Add `wsSend` field to `AgentState` |
| `AgentActor.scala` | Add `wsSend` param to `apply`; set on `AgentState`; replace all `resources.wsSend` with `state.wsSend`; update 2 subagent spawn sites |
| `SessionActor.scala` | Add `wsSend` param to `apply`; replace all `resources.wsSend` with `wsSend` |
| `WebSocketRoutes.scala` | Pass `perConnWsSend` explicitly to `SessionActor`; remove `perConnResources` copy |
| `GatewayMain.scala` | Remove `wsSend = _ => IO.unit` from `SharedResources` construction |

## Migration Order

1. **protocol.scala** — Add `wsSend` to `AgentState` (safe, no compile errors yet)
2. **SharedResources.scala** + **GatewayMain.scala** — Remove `wsSend` field (will break compile, that's expected)
3. **AgentActor.scala** — Add `wsSend` param, set on `AgentState`, replace all call sites, update 2 subagent spawns
4. **SessionActor.scala** — Add `wsSend` param, replace all call sites
5. **WebSocketRoutes.scala** — Pass `perConnWsSend` explicitly, remove `copy`
6. **Compile & fix** any remaining call sites

## Verification

### Compile Check

```bash
sbt compile
```

### Static Checks

```bash
# Verify zero references to resources.wsSend in actor layer
grep -rn "resources\.wsSend" src/main/scala/nebflow/agent/
# Expected: no output

# Verify SharedResources has no wsSend field
grep -n "wsSend" src/main/scala/nebflow/agent/SharedResources.scala
# Expected: no output (except possibly in comments)

# Verify WebSocketRoutes does not copy wsSend
grep -n "copy(wsSend" src/main/scala/nebflow/gateway/WebSocketRoutes.scala
# Expected: no output

# Verify no remaining AgentActor spawns without wsSend param
grep -rn "AgentActor(" src/main/scala/ | grep -v "wsSend"
# Expected: no output (all spawns pass wsSend explicitly)
```

### Runtime Check

1. Connect via WebSocket
2. Send a message — verify streaming (textDelta, toolStart, toolEnd, done)
3. Trigger a tool call that spawns a subagent — verify subagent delegation streams correctly
4. Verify AskUser / Permission notifications appear
5. Verify interrupt sends `interrupted` + `sessionBusy=false`
6. Verify session CRUD still works (create, switch, rename, delete)

### Code Review Checklist

- [ ] `SharedResources` has no `wsSend` field
- [ ] `AgentActor.apply` takes `wsSend` as explicit parameter
- [ ] `SessionActor.apply` takes `wsSend` as explicit parameter
- [ ] `AgentState` has `wsSend` field set from constructor
- [ ] No `resources.wsSend` references remain in `AgentActor.scala`
- [ ] No `resources.wsSend` references remain in `SessionActor.scala`
- [ ] Subagent spawn sites pass `state.wsSend` to child `AgentActor`
- [ ] `WebSocketRoutes` does not call `sharedResources.copy(wsSend = ...)`
- [ ] `GatewayMain` does not set `wsSend` in `SharedResources`
- [ ] `executeTool` call sites in `AgentActor` pass `state.wsSend` (or `Some(state.wsSend)`)

## Success Criteria

- `SharedResources.wsSend` is removed.
- `AgentActor` and `SessionActor` receive `wsSend` as explicit constructor parameters.
- `AgentState` carries `wsSend` — behavior functions access it via `state.wsSend`.
- Subagent spawns inherit parent's `wsSend` via `state.wsSend`.
- Zero `resources.wsSend` references in the actor layer.
- `SharedResources` is truly global — no per-connection state.
- All existing functionality preserved (streaming, tools, subagents, AskUser, permissions, interrupt).

## Related

- Follow-up to `002-simplify-session-actor.md` — completes the actor/IO boundary cleanup.
