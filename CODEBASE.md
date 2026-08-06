# Nebflow CODEBASE

## Overview

Nebflow is a self-hosted AI agent platform (Scala 3 + Pekko) that runs locally and provides a web-based chat UI for interacting with LLM-powered coding assistants. It supports multi-agent orchestration, tool execution, persistent memory, and remote device control via NebLink.

## Directory Structure

```
src/
  main/
    scala/nebflow/
      actor/      (6 files)   — Pekko actor system wrappers (ActorRef, ActorSystem, Behavior, etc.)
      agent/      (14 files)  — Agent actor system, prompt building, context refresh, plan agent
      bridge/     (2 files)   — Bridge manager and plugin for external connections
      cli/        (21 files)  — CLI entry point, commands, TUI rendering, process management
      core/       (85 files)  — Core utilities, tools, compaction, flow engine, hooks, MCP, telemetry
        tools/    (38 files)  — All built-in tools (Bash, Edit, Read, Grep, Glob, Card, Delegate, etc.)
        compact/  (7 files)   — Context compaction (fast micro, full, history archiver)
        flow/     (7 files)   — Flow tree engine (daemon/pipeline/reactor/source orchestration)
        hooks/    (5 files)   — Hook engine, matchers, config loader
        mcp/      (4 files)   — MCP client, manager, JSON-RPC, transports
        scheduler/(3 files)   — Scheduled task actor, model, store
        task/     (2 files)   — Task model and store
        telemetry/(4 files)   — Telemetry events, reporter, sender, task inferencer
        skill/    (1 file)    — Skill service (loading from ~/.nebflow/skills/)
        ask/      (1 file)    — AskUser service
      dropbox/    (3 files)   — Dropbox sync models, service, utilities
      gateway/    (12 files)  — HTTP/WebSocket gateway, auth, rate limiting, session store, TTS
      llm/        (8 files)   — LLM provider adapters (Anthropic, OpenAI), health monitor, fallback
        providers/(2 files)   — Provider-specific adapters
      neblink/    (4 files)   — NebLink device discovery, presence, mesh sync
      service/    (7 files)   — Session store, config, memory, rules, backup services
      shared/     (10 files)  — Shared utilities (protocol, session meta, HTTP, CJK, terminal, etc.)
      Main.scala  (1 file)    — Application entry point
      Version.scala (1 file)  — Version constant (single source of truth)
    resources/
      system-prefix.md  — JAR-bundled system prompt prefix (skills, memory, session mgmt)
  test/
    scala/nebflow/
      actor/      (1 file)    — ActorSpec
      agent/      (3 files)   — AgentActorCompactionSpec, AgentDefSpec, PromptSectionsSpec
      core/       (14 files)  — Compaction, flow tree, tools, reversibility tests
        compact/  (4 files)   — CompactionPolicy, FastMicroCompact, FullCompact, HistoryArchiver
        flow/     (4 files)   — FlowTreeRegistry, FlowTreeScheduling, FlowTreeStore, FlowTreeTypes
        tools/    (5 files)   — AcademicSearch, DelegateTool, FileHistory, StringMatcher, ToolLoader
      demo/       (1 file)    — CompactionDemo
      dropbox/    (1 file)    — DropboxUtilSpec
      gateway/    (3 files)   — CloudSync, SessionStoreFolder, SessionStoreHistory
      llm/        (2 files)   — HealthMonitorSpec, OpenAiAdapterSpec
      neblink/    (4 files)   — DeviceCapabilities, GracePeriod, Model, Security
docs/
  (see docs breakdown below)
```

## File Counts

| Category | Files | Lines |
|----------|-------|-------|
| Source (`src/main/scala/nebflow/`) | 174 | 33,883 |
| Tests (`src/test/scala/nebflow/`) | 29 | 5,361 |
| Docs (`docs/`, excl. .DS_Store) | 53 | — |

### Source files by package (174 total)

| Package | Files | Description |
|---------|-------|-------------|
| core/ | 85 | Tools, compaction, flow engine, hooks, MCP, telemetry, scheduler |
| cli/ | 21 | CLI commands, TUI, process management |
| agent/ | 14 | Agent actor, core logic, prompt sections, context refresh |
| gateway/ | 12 | HTTP/WebSocket server, auth, session store, TTS |
| shared/ | 10 | Protocol, session meta, HTTP utils, CJK, terminal |
| llm/ | 8 | Provider adapters, health monitor, fallback, config |
| service/ | 7 | Session, config, memory, rules, backup services |
| actor/ | 6 | Pekko actor system wrappers |
| neblink/ | 4 | Device discovery, presence, mesh sync |
| dropbox/ | 3 | Dropbox sync |
| bridge/ | 2 | Bridge manager and plugin |
| (root) | 2 | Main.scala, Version.scala |

### Test files by package (29 total, 5,361 lines)

| Package | Files | Key tests |
|---------|-------|-----------|
| core/ | 14 | FlowTreeTypesSpec (795L), FullCompactSpec, DelegateToolSpec, FileHistorySpec, ToolLoaderSpec, StringMatcherSpec |
| neblink/ | 4 | NeblinkSecuritySpec (209L), GracePeriodSpec, ModelSpec, DeviceCapabilitiesSpec |
| gateway/ | 3 | CloudSyncSpec (360L), SessionStoreHistorySpec (357L), SessionStoreFolderSpec |
| agent/ | 3 | AgentActorCompactionSpec, AgentDefSpec, PromptSectionsSpec |
| llm/ | 2 | HealthMonitorSpec, OpenAiAdapterSpec |
| dropbox/ | 1 | DropboxUtilSpec |
| demo/ | 1 | CompactionDemo |
| actor/ | 1 | ActorSpec |

### Docs breakdown (53 files, excl. .DS_Store)

| Directory | Files | Contents |
|-----------|-------|----------|
| docs/design/ | 18 | 17 .md + 1 .html — flow engine, sync, memory, CLI, card rendering, telemetry, etc. |
| docs/issues/ | 18 | .md issue specs (#000–#026, not all numbers used) |
| docs/issues/templates/ | 11 | 9 .md + 1 .py + 1 .md README — issue templates (bug, feature, perf, refactor, etc.) |
| docs/architecture/ | 2 | JARVIS Phase 1 spec and summary |
| docs/ (root) | 4 | actor-mailbox-pattern-review.md, agent-icon-design-guide.md, design-memory-v2.md, hooks-and-callbacks.md |

## Development Log

### 2026-07-25: Project-level config via projects directory

**Feature:** Project-level rules, agents, and flows are loaded from the existing projects directory (`~/.nebflow/agents/<agent>/projects/<project>/`), NOT from the project folder on disk. This keeps all Nebflow config centralized in `~/.nebflow/`.

**Structure** (`~/.nebflow/projects/<project>/`):
- `NEBFLOW.md` — Project rules (injected into system prompt as "Project Rules", merged with personal folder rules)
- `agents/<name>/` — Project agent definitions (override global `~/.nebflow/agents/` on name conflict)
- `flows/<name>/` — Project flow definitions (merged with global flows, project takes priority)

**Loading priority:** Projects dir > Global `~/.nebflow/`. The projects dir is computed from `folderId`, independent of `projectRoot` (which points to the actual project on disk).

**Changes:**
- `ContextRefresher.scala` — `resolveProjectsDir()` computes the projects dir from folderId, loads `NEBFLOW.md` + agent overrides from there
- `AgentLibrary.scala` — Extracted `loadFromDir()` for reuse

### 2026-08-03: Architecture refactor — Team/Flow entity system

**Major changes:**
- **Removed YAML format**: All flows now use `~/.nebflow/flows/<name>.json` (DAG format). Deleted `FlowDefLoader`, `FlowDef`/`FlowAgentDef` types, all `flow.yaml` directories. `EntityLoader` is the single source of truth.
- **Team/Flow/Agent separation**: `~/.nebflow/teams/<name>.json` (TeamDef), `~/.nebflow/flows/<name>.json` (FlowDagDef), `~/.nebflow/agents/<name>/agent.json` (AgentDef). `EntityLoader` loads/saves all three.
- **Mail three-way dispatch**: `MailTool.deliverToShortName` resolves address as: (1) team name → load team.json → forward to lead, (2) agent name → FlowMembership.resolveSessionId, (3) flow name → spawn FlowDagRunner.
- **Removed Dispatch tool**: Agents cannot be called directly. Flow via Mail, Team via Mail.
- **Auto-mount teams on startup**: `FlowTreeActor.restoreFlows` scans `~/.nebflow/teams/*.json` and mounts all teams.
- **Frontend tab split**: Teams and Flows are separate Canvas tabs (not sub-tabs). Flows tab auto-opens when running flows are detected.
- **RunningFlowRegistry**: Tracks running DAG instances for frontend progress visualization. `cleanupStale` removes completed flows after 5 minutes.
- **FlowDagRunner**: One-shot actor spawned by MailTool for flow name dispatch. Executes DAG nodes synchronously, Mails result back to caller.

### 2026-07-19: Proactive learning instructions in system-prefix.md

**Problem:** Nebflow stores all user input in session files, but this data was never used to learn user preferences. The 4-level memory system existed but relied entirely on the agent's own initiative to write — without explicit guidance on what signals to capture.

**Change:** Added "Proactive Learning — Capture User Patterns" section to `system-prefix.md`, between "When to Write" and "Memory is a Living Document". Specifies 5 concrete signal types (user correction, user shortcut, repeated style, workflow preference, domain context) and a "what NOT to record" list to prevent memory pollution.

**Rationale:** Zero-code-cost approach — leverages existing memory infrastructure (MemoryStore, ContextRefresher, PromptSections) by guiding the LLM to actively identify and persist user patterns during normal conversation. No new analysis pipeline, no extra LLM calls.

### 2026-07-18: Merge batch — 5 feature branches into main

Merged (in order):
1. `fix/flow-debug-and-frontend` — FlowTreeActor error exposure + flowName in events + CardTool frontend design protocol
2. `feature/safety-mode-ui` — Safety mode toggle UI with text label (安全/编辑放行/全放行)
3. `feature/git-branch-persistence` — gitBranch persisted in SessionMeta across restarts
4. `feature/hot-reload-tools` — File watcher for external tool configs in ~/.nebflow/tools/ (500ms debounce)
5. `feature/memory-system` — Unified memory injection into system prompt (replaces MemoryAutoRead synthetic Read) + MaintenanceService (every 10 delegate calls)

**Key architecture change:** Memory injection moved from synthetic Read tool messages (MemoryAutoRead, deleted) to direct system prompt injection via `ContextRefresher.buildMemoryBlock` + `PromptSections` entry 810. Memory is now part of the system prompt, not the message list.

**Conflict resolution:** 3 files had auto-mergeable conflicts (AgentActor.scala, AgentCore.scala, protocol.scala) — both git-branch-persistence and memory-system modified the same files. Git auto-merge preserved both sets of changes correctly.

Version bumped to 1.4.1-beta.34.

### 2026-07-18: Conditional prompt injection mechanism

**Problem:** System prompt assembly was scattered across `buildSystemPrompt` (inline if/mapping for voice, askUser, envInfo, rules, skills) and `pipeLlmCall` (string concatenation for language, deviceInfo, agentSessions). Adding a new conditional section required touching multiple places.

**Solution:** Unified all conditional injection into `PromptSections` framework:
- Expanded `PromptContext` to carry all runtime conditions and pre-rendered dynamic content
- Registered all sections in `PromptSections.all` with self-describing `PromptSection` entries (order + condition + render)
- Simplified `buildSystemPrompt` to: prefix + agentPrompt + buildConditionalBlocks(ctx)
- `pipeLlmCall` now builds a full PromptContext and passes it to buildSystemPrompt — no more string appending

**Files changed:** `PromptSections.scala`, `AgentCore.scala`, new test: `PromptSectionsSpec.scala`

### Section order ranges

| Range | Purpose |
|-------|---------|
| 100-199 | Fixed foundational sections (env info) |
| 400-499 | Tool-dependent sections (AskUserQuestion) |
| 500-599 | Feature-flag sections (voice) |
| 600-699 | Runtime-state sections (devices, sessions, language) |
| 800-899 | Catalog sections (skills) |
| 900+    | Project rules and fallback |

## Key Decisions

- **Prompt cache:** systemStable is sent to Anthropic with `cache_control: ephemeral`. Conditional sections (including memory block) are part of systemStable. Per-turn dynamic content (reminders, maintenance triggers) goes into the message list.
- **Memory injection:** Memory files are injected into the system prompt every turn via `ContextRefresher.buildMemoryBlock` + `PromptSections` entry 810. Replaces the old `MemoryAutoRead` synthetic Read message approach (deleted 2026-07-18).
- **Per-turn refresh:** All prompt sources are re-read from disk every turn (mtime-cached so unchanged files cost only a stat() syscall).
- **Agent system.md:** User-editable. `stripAllMigrated` removes sections that have been moved to conditional injection (currently only "Voice Output").
