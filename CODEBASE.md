# Nebflow CODEBASE

## Overview

Nebflow is a self-hosted AI agent platform (Scala 3 + Pekko) that runs locally and provides a web-based chat UI for interacting with LLM-powered coding assistants. It supports multi-agent orchestration, tool execution, persistent memory, and remote device control via NebLink.

## Directory Structure

```
src/
  main/
    scala/nebflow/
      agent/           — Agent actor system, prompt building, context refresh
        AgentCore.scala       — Core agent behavior: LLM calls, tool execution, system prompt assembly
        AgentDef.scala        — Agent definition (name, tools, systemPrompt)
        AgentLibrary.scala    — Loads agents from disk, seeds defaults
        AgentActor.scala      — Pekko actor implementing agent behavior
        ContextRefresher.scala — Per-turn context refresh (system prefix, rules, skills, git branch)
        PromptSections.scala  — Conditional system prompt section registry
        InjectionSource.scala — File-backed prompt injection with mtime caching
        protocol.scala        — Agent state, messages, session context types
      core/             — Core utilities, tools, compaction, reminders
      llm/              — LLM provider adapters (Anthropic, OpenAI, etc.)
      actor/            — Pekko actor system wrappers
      service/          — Session store, rules store, file management
      gateway/          — HTTP/WebSocket gateway
      cli/              — CLI entry point
    resources/
      system-prefix.md  — JAR-bundled system prompt prefix (skills, memory, session mgmt)
  test/
    scala/nebflow/      — Test suites
```

## Development Log

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
