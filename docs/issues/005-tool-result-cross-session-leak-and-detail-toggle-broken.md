# Tool Result Cross-Session Leak and Detail Toggle Broken

## Problem

Two related frontend bugs in the chat UI:

### Bug 1: Tool results leak into wrong session

When a tool is used in **Session A** while the user is viewing **Session B**, the tool result is incorrectly rendered into Session B's chat area.

**Root cause**: `renderTool()` in `chat.js` unconditionally appends to `state.dom.chat` without checking whether the incoming `msg.sessionId` matches `state.activeSessionId`. The `isActive()` guard is missing for the `toolEnd` handler branch that renders the card.

Looking at `main.js:176-184`:

```javascript
onMessage('toolEnd', (msg) => {
  if (msg.label && msg.label.startsWith('AskUser')) return;
  if (isActive(msg)) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', ...}, msg.sessionId);  // <-- correct: only saves to storage
  }
});
```

The `isActive()` check is present here, but the issue occurs because `renderTool()` itself operates on the global `state.dom.chat`. If a race condition or rapid session switching happens while a tool is in-flight, the pending `currentToolCard` can be rendered into the wrong session's DOM.

Additionally, `renderTool()` removes `state.currentToolCard` at the start (line 186-189), which is a global singleton. If Session A has a pending tool card and Session B receives a `toolEnd`, Session B's call to `renderTool()` will remove Session A's pending card reference from the global state, leaving a dangling DOM element in Session A.

### Bug 2: Tool card detail cannot be expanded

Clicking (or long-pressing) a tool card no longer expands its `.body` to show the full tool output.

**Root cause**: `attachToolClick()` in `utils.js` uses a "long-press" detection mechanism that is fragile:

```javascript
export function attachToolClick(card) {
  let pressTimer = null;
  let pressStart = 0;
  const LONG_PRESS_MS = 400;

  function onMouseDown(e) {
    pressStart = Date.now();
    pressTimer = setTimeout(() => { pressTimer = null; }, LONG_PRESS_MS);
  }
  function onMouseUp(e) {
    const duration = Date.now() - pressStart;
    if (pressTimer && duration < LONG_PRESS_MS) {
      const body = card.querySelector('.body');
      if (body) body.classList.toggle('open');
    }
    clearTimeout(pressTimer);
    pressTimer = null;
  }
  // ...
}
```

The logic is inverted: a `setTimeout` sets `pressTimer = null` after 400ms, meaning if the user holds longer than 400ms, `pressTimer` is null and the toggle **doesn't** fire on mouseup. But if the user clicks quickly (< 400ms), `pressTimer` is still truthy and the toggle fires.

However, in practice this is unreliable because:
1. Any click that happens to take > 400ms (e.g., on a slower machine, or if the user hesitates) silently does nothing.
2. The `pressTimer` is never reset on `mouseleave`, so dragging out of the card and releasing can leave the timer running.
3. The interaction is non-discoverable — users expect a simple click to expand, not a timed press.

## Proposed Solution

### Fix 1: Session-scoped tool card state

Replace the global `state.currentToolCard` with a per-session map:

```javascript
// In state.js
sessionToolCards: {},  // sessionId -> DOM element
```

Update `renderToolPending()` and `renderTool()` to use `state.sessionToolCards[msg.sessionId || state.activeSessionId]` instead of the global `state.currentToolCard`.

In `main.js`, ensure all tool-related handlers (`toolStart`, `toolEnd`, `agentToolStart`, `agentToolEnd`) operate on the correct session's tool card.

### Fix 2: Replace long-press with simple click

Change `attachToolClick()` to use a straightforward click handler:

```javascript
export function attachToolClick(card) {
  card.addEventListener('click', (e) => {
    const body = card.querySelector('.body');
    if (body) body.classList.toggle('open');
  });
}
```

This is simpler, more reliable, and matches user expectations.

### Fix 3: Harden `renderTool()` against cross-session rendering

Add an explicit guard at the top of `renderTool()`:

```javascript
export function renderTool(label, summary, content, isError, inputJson, sessionId) {
  if (sessionId && sessionId !== state.activeSessionId) return null;
  // ... rest of function
}
```

Pass `msg.sessionId` from all call sites in `main.js`.

## Files to Modify

| File | Change |
|------|--------|
| `src/main/resources/web/js/state.js` | Add `sessionToolCards: {}` to state |
| `src/main/resources/web/js/chat.js` | Update `renderTool()` and `renderToolPending()` to use per-session tool cards; add sessionId guard |
| `src/main/resources/web/js/utils.js` | Simplify `attachToolClick()` to simple click handler |
| `src/main/resources/web/js/main.js` | Pass `msg.sessionId` to `renderTool()`; update all tool card references |
| `src/main/resources/web/js/persistence.js` | Update `restoreFromStorage()` tool card rendering to attach click handler correctly |

## Verification Steps

1. Open Session A, trigger a tool (e.g., `Read` a file).
2. While the tool is pending, switch to Session B.
3. Confirm the tool result appears only in Session A, not Session B.
4. Switch back to Session A and click the tool card — detail should expand.
5. Switch to Session B and click any tool card there — detail should also expand.
6. Test on both desktop (mouse) and mobile (touch) if possible.
