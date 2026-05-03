# Task List Not Displaying Due to Session ID Mismatch

## Problem

The task list UI component is completely non-functional — tasks are created, updated, and stored correctly on the backend, but they never appear in the frontend.

## Root Cause

The `sessionId` passed to task tools via `ToolContext` is the **actor path name** (e.g., `agent-sess-abc123-1715234567890`), not the **real user session ID** (e.g., `sess-abc123`). This causes two failures:

1. **Tasks stored in wrong directory**: `FileTaskStore` uses the actor path as the session directory name, so tasks are saved under `~/.nebflow/tasks/agent-sess-xxx-.../` instead of `~/.nebflow/tasks/sess-xxx/`.
2. **WebSocket messages rejected by frontend**: The `taskListUpdate` event carries the actor path as `sessionId`, but the frontend's `isActive()` check compares it against `state.activeSessionId` (the real session ID). They never match, so `renderTaskList()` is never called.

### Code locations

**`AgentActor.scala:760-762`** — `executeTool` is called with the wrong `sessionId`:

```scala
case _ =>
  ToolRisk.classify(call.name) match
    case ToolRisk.Safe =>
      executeTool(
        call,
        resources.projectRoot.toString,
        Some(resources.llm),
        None, None, None, None,
        Some(ctx.self),
        agentDef.contextWindow,
        sessionId = Some(ctx.self.path.name),  // <-- WRONG: actor path, not session ID
        taskStore = Some(resources.taskStore),
        wsSend = Some(state.wsSend)
      )
```

The same bug exists in the `NeedsApproval` branch at lines 777 and 821.

**`TaskCreateTool.scala:86-87`** — `emitTaskListUpdate` uses `ctx.sessionId` (the actor path) for the WebSocket message:

```scala
for
  id <- store.create(sessionId, createInput)
  _ <- emitTaskListUpdate(store, sessionId, ctx)  // sessionId here is ctx.sessionId = actor path
yield Right(s"Task #$id created successfully: ${createInput.subject}")
```

**`main.js:105-107`** — Frontend rejects the message:

```javascript
function isActive(msg) {
  return !msg.sessionId || msg.sessionId === state.activeSessionId;
}
```

Since `msg.sessionId` = actor path and `state.activeSessionId` = real session ID, this always returns `false` for task updates.

## Impact

- Task system is completely invisible to users despite being fully implemented on backend
- Tasks are fragmented across multiple filesystem directories (one per actor spawn)
- Task list appears to be broken / not implemented

## Proposed Solution

Pass the **real user session ID** (`state.sessionId`, already available in `AgentState`) through the tool execution chain.

### Option A: Fix `AgentActor` call sites (minimal change)

Change `sessionId = Some(ctx.self.path.name)` to `sessionId = state.sessionId` in all three `executeTool` call sites in `AgentActor.scala`.

### Option B: Cleaner separation (recommended)

1. Rename `ToolContext.sessionId` to `ToolContext.actorId` to hold the actor path (used for logging/identification)
2. Add `ToolContext.userSessionId` for the real session ID
3. Update all task tools to use `userSessionId` for storage and WebSocket pushes
4. Update `executeTool` signature to accept both IDs separately

However, Option A is sufficient and much smaller in scope.

## Files to Modify

| File | Change |
|------|--------|
| `src/main/scala/nebflow/agent/AgentActor.scala` | Change `sessionId = Some(ctx.self.path.name)` to `sessionId = state.sessionId` at lines 760, 777, 821 |
| `src/main/scala/nebflow/core/handlers.scala` | Verify `executeTool` parameter `sessionId` is correctly forwarded to `ToolContext` |
| `src/main/scala/nebflow/core/tools/types.scala` | (Optional) Add comment clarifying that `sessionId` should be the user session ID, not actor path |

## Verification Steps

1. Open the web UI and create a new session
2. Ask the agent to create a task: "Create a task to review the code"
3. Verify the task appears in the UI below the header (`#task-list` element)
4. Ask the agent to mark it complete: "Mark task 1 as completed"
5. Verify the task status updates in the UI (icon changes from loader to checkmark)
6. Check `~/.nebflow/tasks/` — there should be exactly one directory per real session ID, not per actor spawn
