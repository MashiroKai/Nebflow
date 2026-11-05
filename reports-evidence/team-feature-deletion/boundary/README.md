# Team feature deletion — boundary evidence

Worktree: `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed`
Branch: `delegate/delegate-nebflow-f97716ed`
Baseline sha: `2af513702c81ff0522c952fb7ea7a43b59b578b2` (= main tip, already contains #1553)
Captured: 2026-11-04 22:23–22:27 local
Nature: **read-only** recon. Zero code changes in this batch (`git status --porcelain` = 0 at capture time).

All commands below are verbatim (run from the worktree root unless a `cd` is shown).

---

## 1. Baseline / cleanliness

```
$ git rev-parse --abbrev-ref HEAD
delegate/delegate-nebflow-f97716ed
$ git rev-parse HEAD
2af513702c81ff0522c952fb7ea7a43b59b578b2
$ git status --porcelain | wc -l
0
```

---

## 2. `TeamSessionRegistry` reference base (src/main = 91, src/test = 181)

```
$ grep -rn "TeamSessionRegistry" src/main --include=*.scala | wc -l
91
$ grep -rn "TeamSessionRegistry" src/main --include=*.scala | cut -d: -f1 | sort | uniq -c | sort -rn
  24 src/main/scala/nebflow/core/tools/MailTool.scala
  18 src/main/scala/nebflow/core/flow/FlowTreeActor.scala
   9 src/main/scala/nebflow/gateway/WebSocketRoutes.scala
   8 src/main/scala/nebflow/agent/AgentSessionExecution.scala
   7 src/main/scala/nebflow/core/tools/AgentControlTool.scala
   4 src/main/scala/nebflow/gateway/PresenceRoutes.scala
   3 src/main/scala/nebflow/agent/AgentFinishTurn.scala
   2 src/main/scala/nebflow/core/tools/SubTaskTool.scala
   2 src/main/scala/nebflow/agent/ContextRefresher.scala
   1 src/main/scala/nebflow/gateway/WsTasksWorkspaceHandlers.scala
   1 src/main/scala/nebflow/gateway/WsSystemHandlers.scala
   1 src/main/scala/nebflow/gateway/WsSkillsAsksHandlers.scala
   1 src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala
   1 src/main/scala/nebflow/gateway/WsMemoryFoldersHandlers.scala
   1 src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala
   1 src/main/scala/nebflow/gateway/WsDropboxHandlers.scala
   1 src/main/scala/nebflow/gateway/WsDispatch.scala
   1 src/main/scala/nebflow/gateway/WsConfigHandlers.scala
   1 src/main/scala/nebflow/core/tools/types.scala
   1 src/main/scala/nebflow/core/reminders.scala
   1 src/main/scala/nebflow/core/ports.scala
   1 src/main/scala/nebflow/agent/SharedResources.scala
   1 src/main/scala/nebflow/agent/AgentActor.scala
```

=> **23 files** (task brief said 22; the extra one is `WsDispatch.scala:15`, a pure unused import).

```
$ grep -rn "TeamSessionRegistry" src/test --include=*.scala | wc -l
181
$ grep -rn "TeamSessionRegistry" src/test --include=*.scala | cut -d: -f1 | sort | uniq -c | sort -rn
  55 src/test/scala/nebflow/core/flow/TeamSessionRegistrySpec.scala
  24 src/test/scala/nebflow/core/tools/MailIdleGateWiringSpec.scala
  16 src/test/scala/nebflow/gateway/DeleteSessionCleanupSpec.scala
  16 src/test/scala/nebflow/core/tools/AgentControlToolSpec.scala
  12 src/test/scala/nebflow/core/tools/MailActivateLifecycleSpec.scala
  10 src/test/scala/nebflow/core/tools/MailToolRootSenderSpec.scala
   7 src/test/scala/nebflow/agent/AgentControlE2ESpec.scala
   6 src/test/scala/nebflow/core/tools/MailDedupWiringSpec.scala
   6 src/test/scala/nebflow/core/tools/ColdQueueActivationSpec.scala
   5 src/test/scala/nebflow/gateway/ActiveAgentsFilterSpec.scala
   5 src/test/scala/nebflow/core/tools/MailAttachSpec.scala
   4 src/test/scala/nebflow/core/tools/MailToolCheckTeamScopeSpec.scala
   4 src/test/scala/nebflow/core/project/NodeAcceptanceSpec.scala
   3 src/test/scala/nebflow/core/tools/MailDeliveryRetireSpec.scala
   3 src/test/scala/nebflow/agent/ReminderWiringSpec.scala
   2 src/test/scala/nebflow/core/tools/MailDedupReplayWindowSpec.scala
   2 src/test/scala/nebflow/agent/FreezeGateSpec.scala
   1 src/test/scala/nebflow/core/tools/ShellSessionRestartSpec.scala
```

**Important caveat (kept honest)**: `src/main` 91 splits into **60 real code references + 31 comment/import-only references**. 14 of the 23 files carry exactly ONE hit, and in 5 of them it is a prose comment (`types.scala:71`, `ports.scala:26`, `reminders.scala:67`, `SharedResources.scala:98`) or a plain import (`AgentActor.scala:12`); the other 9 single-hit files are gateway handler files each carrying the same unused grouped import at line 15.

Verification of "import only, unused" (each returned `1` = the import line itself, nothing else):

```
$ for f in WsDispatch WsSessionChatHandlers WsConfigHandlers WsMemoryFoldersHandlers \
           WsSkillsAsksHandlers WsDropboxHandlers WsTasksWorkspaceHandlers WsFileOpsHandlers; do
    echo "$f: $(grep -c TeamSessionRegistry src/main/scala/nebflow/gateway/$f.scala)"
  done
WsDispatch: 1
WsSessionChatHandlers: 1
WsConfigHandlers: 1
WsMemoryFoldersHandlers: 1
WsSkillsAsksHandlers: 1
WsDropboxHandlers: 1
WsTasksWorkspaceHandlers: 1
WsFileOpsHandlers: 1
```

Also `WsSystemHandlers.scala` has 1 hit = its line-15 import (its `cancelFlow` handler uses the FQN `nebflow.core.flow.FlowTreeRegistry` inline, not the import).

---

## 3. Frontend call points — the decisive reading for item 3

```
$ grep -rn "/api/teams" src/main/resources/web/
src/main/resources/web/js/flowViewers.js:223:    const resp = await fetch(`/api/teams/mail-queue/${encodeURIComponent(a.sessionId)}`, { headers: authHeaders() });
src/main/resources/web/js/flowViewers.js:256:        const resp = await fetch(`/api/teams/mail-queue/${encodeURIComponent(sid)}/${encodeURIComponent(itemId)}`, { method: 'DELETE', headers: authHeaders() });
src/main/resources/web/js/flowViewers.js:309:    const resp = await fetch(`/api/teams/mailbox/${encodeURIComponent(parentSid)}/${encodeURIComponent(flowName)}`, { headers: authHeaders() });
src/main/resources/web/js/flowViewers.js:345:    const resp = await fetch(`/api/teams/def/${encodeURIComponent(flowName)}`, { headers: authHeaders() });
src/main/resources/web/js/ws.js:1590:      // with the unprefixed sid from /api/teams/mounted), so strip the
```

```
$ grep -rn "/api/team/" src/main/resources/web/
src/main/resources/web/js/flowViewers.js:158:    const resp = await fetch(`/api/team/rules/${encodeURIComponent(teamName)}`, { headers: authHeaders() });
src/main/resources/web/js/flowViewers.js:174:        const r = await fetch(`/api/team/rules/${encodeURIComponent(teamName)}`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ content: text }) });
```

```
$ grep -rn "cancelFlow" src/main/resources/web/
(no output)
$ grep -rn "treeBranch" src/main/resources/web/
src/main/resources/web/js/ws.js:408:  // flowProgress/flowCompleted/teamList/treeBranch*) removed 2026-09-05
```

### 3.1 The six fetch sites live in orphan functions

```
$ grep -rn "flowViewers" src/main/resources/web/js/*.js | grep -v "^src/main/resources/web/js/flowViewers.js"
src/main/resources/web/js/agentFileViewer.js:5:import { openViewerShell } from './flowViewers.js';
```
(the other hits are prose comments only)

```
$ for fn in openMailbox openDefinition openRules openNodeResultViewer closeViewer refreshMailboxPending; do
    echo "--- $fn"; grep -rn "$fn" src/main/resources/web/ --include=*.js --include=*.html \
      | grep -v "js/flowViewers.js:" | head -5; done
--- openMailbox
           (no output)
--- openDefinition
           (no output)
--- openRules
           (only /Users/kaiyu/Claude code/Stand/src/main/resources/web/js/sidebar/pathRules.js openRulesEditor — a DIFFERENT function)
--- openNodeResultViewer
           (no output)
--- closeViewer
           (no output)
--- refreshMailboxPending
           (no output)
```

=> The only module importing `flowViewers.js` is `agentFileViewer.js`, and it imports **`openViewerShell` only**. Therefore `openMailbox` / `openDefinition` / `openRules` / `openNodeResultViewer` / `closeViewer` / `refreshMailboxPending` are **exported but never imported anywhere** — the 6 `/api/teams*` + `/api/team/rules` fetches sit inside functions with **no UI entry point**. Deleting the endpoints breaks no live UI. This is why item 3 is judged "随端点一并删" rather than "前端视觉面不动".

### 3.2 `/api/teams/mounted` has zero frontend caller

```
$ grep -rn "mounted" src/main/resources/web/js/*.js | grep -i "api\|teams"
(no output — the only /api/teams/mounted mention is the ws.js:1590 comment)
```

### 3.3 `getTeams` is still SENT on every WS connect

```
$ grep -rn "getTeams" src/main/resources/web/
src/main/resources/web/js/ws.js:1378:    sendWs({type: 'getTeams'});
```
Context: this line is inside the WS connection bootstrap (`connect()`), right beside `sendWs({type:'getSkills'})` / `gateway('memoryStatus')` / `getConfig` — i.e. it runs on every connect. Its response frame `teamList` has **zero frontend handler** (`ws.js:408` self-documents the 2026-09-05 removal). Live request, dead consumer.

### 3.4 WS frame consumers (all dead)

```
$ grep -rn "flowMail\|mailQueued\|mailDequeued\|treeBranch\|teamList" src/main/resources/web/
src/main/resources/web/js/flowViewers.js:186:// Context of the currently open mailbox — lets mailQueued/mailDequeued WS
src/main/resources/web/js/flowViewers.js:280:/** Called by flowCanvas on mailQueued/mailDequeued — live-refresh the
src/main/resources/web/js/ws.js:1613:        const teamList = handlers[msg.type];      (local variable name, not the frame)
src/main/resources/web/js/ws.js:1614:        if (teamList) for (const h of teamList.slice()) {
```
(plus the `ws.js:407-408` "removed 2026-09-05" comment)

Server side emitters (all still firing):
```
$ grep -rn "treeBranch\|\"flowMail\"\|mailQueued\|mailDequeued\|teamList" src/main/scala
src/main/scala/nebflow/core/tools/MailTool.scala:2066:              "type" -> "mailQueued".asJson,
src/main/scala/nebflow/core/tools/MailTool.scala:2679:            "type" -> "flowMail".asJson,
src/main/scala/nebflow/core/flow/FlowTreeActor.scala:312:          emit(config, "treeBranchMounted", "name" -> name.asJson, "type" -> "team".asJson)
src/main/scala/nebflow/core/flow/FlowTreeActor.scala:382:            _ <- emit(cfg, "treeBranchMounted", "name" -> baseName.asJson, "type" -> "team".asJson)
src/main/scala/nebflow/core/flow/FlowTreeActor.scala:421:      _ <- emit(cfg, "treeBranchUpdated", "name" -> instanceName.asJson)
src/main/scala/nebflow/agent/AgentFinishTurn.scala:917:            "type" -> "mailDequeued".asJson,
src/main/scala/nebflow/gateway/WsSkillsAsksHandlers.scala:114:          "type" -> "teamList".asJson,
```

---

## 4. Route literals (exact)

```
$ grep -n 'case req @ \(GET\|POST\|DELETE\|PUT\) -> Root / "team' src/main/scala/nebflow/gateway/PresenceRoutes.scala
593:      case req @ GET -> Root / "teams" / "mounted" =>
610:      case req @ GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
622:      case req @ DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
634:      case req @ GET -> Root / "teams" / "mail-queue" / sessionId =>
646:      case req @ DELETE -> Root / "teams" / "mail-queue" / sessionId / itemId =>
780:      case req @ GET -> Root / "teams" / "def" / flowName =>
2098:      case req @ GET -> Root / "team" / "rules" / teamName =>
2109:      case req @ POST -> Root / "team" / "rules" / teamName =>
```

**Anchor correction vs. the task brief**: the brief says "PUT teams/:name/rules 热重载 (:2110-2130)"; the live route is **`POST /api/team/rules/:name`** at `:2109` (singular `team`, method POST). The hot-reload trigger is `:2123-2127` (`FlowTreeRegistry.treesRef.get` → `TreeCommand.ReloadDefinition`). A read counterpart `GET /api/team/rules/:name` exists at `:2098`.

WS registration + handler:
```
$ grep -n "cancelFlow" src/main/scala/nebflow/gateway/WsSystemHandlers.scala
45:    "cancelFlow" -> handleCancelFlow,
321:          treeRef ! nebflow.core.flow.TreeCommand.CancelPipeline(flowName)
$ grep -n "getTeams" src/main/scala/nebflow/gateway/WsSkillsAsksHandlers.scala
42:    "getTeams" -> handleGetTeams,
```
(the `cancelFlow` handler spans `:303-330`; the DAG branch `RunningFlowRegistry.cancel(instanceId)` is at `:317-318` and is NOT team code)

---

## 5. On-disk data (archive scope)

```
$ ls -1 /Users/kaiyu/.stand/teams/ | grep -v DS_Store | wc -l
9
$ find /Users/kaiyu/.stand/teams -type f | wc -l
103
$ du -sh /Users/kaiyu/.stand/teams
512K
```

Per-team `team.json` keys (9 dirs; `nebflow-project` carries `team.json.archived`, the author's earlier manual archive):

| team | lead | members | flows |
|---|---|---|---|
| czt-project | Manager | czt-physicist, czt-writer, czt-researcher | research |
| html-deck-studio | Manager | content-planner, html-builder, visual-reviewer | — |
| nebflow-project (`.archived`) | Manager | Backend, Frontend, Docs, prompt-engineer, tool-engineer, qa-frontend, qa-backend, cache-engineer | — |
| nebflow-rust | Manager | Backend, Frontend, Docs, qa-backend, qa-frontend | code-review, git-merge |
| nebflow-website | Manager | Frontend, Designer | — |
| ReminderIsland | Manager | swift-dev, qa-swift | — |
| sipm-paper | Manager | sipm-physicist, sipm-writer, sipm-verifier | — |
| slideblocks | Manager | Frontend, Backend, Docs | — |
| voice-recognition-test | Manager | Backend, Frontend, vr-algorithm | — |

### 5.1 "Team declarations" in the agents dir = ZERO hits

The actual on-disk shape of an agent definition is `agents/<Name>/agent.json`. Only **2 live `agent.json`** exist, and **neither contains a `team` key**:

```
$ find /Users/kaiyu/.stand/agents -maxdepth 2 -name "*.json"
/Users/kaiyu/.stand/agents/Nebula/agent.json
/Users/kaiyu/.stand/agents/project-dispatcher/agent.json
$ grep -n "team" /Users/kaiyu/.stand/agents/Nebula/agent.json /Users/kaiyu/.stand/agents/project-dispatcher/agent.json
(zero)
$ grep -l '"team"' /Users/kaiyu/.stand/agents/*/*.json
(zero)
```

The earlier `grep -rln "team" /Users/kaiyu/.stand/agents/` hits are all **prose** in `memory.md` / `system.md` / `skill-proposals/*.md` — not declarations.

=> **Team↔member relation is declared nowhere in `agents/`; it lives only in `teams/<name>/team.json` (`lead` + `members`).** So the agents-side archive object is a proven zero.

### 5.2 Leftover / live per-session files

```
$ find /Users/kaiyu/.stand/sessions -maxdepth 2 -name "flow-tree.json"
/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json      (1 file, 862 B, mtime 2026-07-19)
$ find /Users/kaiyu/.stand/sessions -maxdepth 2 -name "flows.json" | wc -l
24                    # LIVE data (MountedFlowStore) — NOT in archive scope
$ find /Users/kaiyu/.stand/sessions -maxdepth 2 -type d -name flow-mailbox | wc -l
6
$ find /Users/kaiyu/.stand/sessions -maxdepth 3 -type f -path '*/flow-mailbox/*' | wc -l
18                    # adjacent team-mail history (not in this batch's archive scope)
$ find /Users/kaiyu/.stand/sessions -maxdepth 2 -name 'mail-queue.json' | wc -l
15                    # adjacent (existing "retired but not removed, do not touch" convention)
```

### 5.3 The auto-mount finding (why "nobody uses teams" is only half true)

`FlowTreeActor.restoreTeams` (`:516-575`) does not merely replay `flows.json` entries — it also auto-mounts **every** team found on disk:

```scala
// FlowTreeActor.scala:551-573
existing  <- successfulNames.get
allTeams  <- EntityLoader.listTeams()
unmounted  = allTeams.values.toList.filterNot(td => existing.contains(td.name))
_ <- unmounted.traverse_ { teamDef =>
       createTeamAgentSessions(teamDef.name, teamDef, cfg)   // creates one session record per lead+member
       ...
```

Measured consequence:

```
$ ls -1 /Users/kaiyu/.stand/sessions | wc -l          # 22458 dirs
$ python3 (count _index.json records whose flowName ∈ teams/)
session records total: 844
with team flowName:    32
  ReminderIsland 3 / czt-project 4 / html-deck-studio 4 / nebflow-rust 6 /
  nebflow-website 3 / sipm-paper 4 / slideblocks 4 / voice-recognition-test 4
```

32 = exactly the 8 live teams' (1 lead + N members). So **team session records are live data in the current session index**, re-created on every session activation. Deleting the feature removes them (an upside), but it IS a behaviour change.

### 5.4 `flows.json` content sample

```
$ head -c 200 /Users/kaiyu/.stand/sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/flows.json
{"flows":[{"name":"ReminderIsland","flowName":"ReminderIsland"},{"name":"sipm-paper",...},
 {"name":"nebflow-rust",...},{"name":"slideblocks",...},{"name":"html-deck-studio",...},
 {"name":"czt-project",...},{"name":"voice-recognition-test",...},{"name":"nebflow-website",...}]}
```
8 team entries per session.

---

## 6. Isolation checks for item 5 ("不动的面")

```
$ grep -c "TeamSessionRegistry" src/main/scala/nebflow/core/entity/FlowDagExecutor.scala
0
$ grep -rn "MountedFlowStore" src/main --include=*.scala | sed 's|src/main/scala/nebflow/||'
core/flow/FlowTreeActor.scala:24:(comment) / :513:(comment) / :523 / :527 / :573 / :574 / :582 / :583 / :683 / :685
core/flow/MountedFlowStore.scala:18(object def) / :77(end)
```
=> `FlowDagExecutor` has zero team coupling (flow-map is a separate mechanism). `MountedFlowStore`'s **only consumer is `FlowTreeActor`** — it becomes orphaned once the team tree is deleted (flagged as an open ruling item in the doc, not deleted in this batch).

```
$ grep -rn 'Mail("manager"' src/main/scala | wc -l
0            # but the guidance text exists in 3 error strings:
src/main/scala/nebflow/core/tools/MailTool.scala:2329  Use Mail("manager", ...) to report to your Team Lead.
src/main/scala/nebflow/core/tools/MailTool.scala:2651  Legacy gate: ... Use Mail("manager", ...) to report to your Team Lead.
src/main/scala/nebflow/core/tools/MailTool.scala:2177  Report to your Team Lead / Manager via Mail
```
(The `grep 'Mail("manager"'` literal count is 0 only because the source escapes the quotes as `Mail(\"manager\"`. The 3 strings above are the real matches and all become stale guidance after team deletion.)

---

## 7. What was NOT run

- **No `sbt` command** (this batch needs none; step 3 owns compile/test readings). The 91/181 dispositions are static judgements.
- No modifications under `src/`. `git status --porcelain` stays clean except the two artifact paths added by this batch (`reports-evidence/…` and the vault doc, which lives outside the repo).

---

## 8. Artifacts in this directory

| file | content |
|---|---|
| `README.md` | this file — verbatim commands + readings |
| `teams-manifest.json` | 103 entries, `{path, sha256, size, mtime}` for every file under `/Users/kaiyu/.stand/teams` (archive manifest seed, generated read-only) |
