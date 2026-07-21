<div align="center">

# Nebflow

Self-hosted AI coding assistant with multi-agent orchestration and cross-device collaboration.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/MashiroKai/Nebflow?label=stable)](https://github.com/MashiroKai/Nebflow/releases/latest)
[![Scala](https://img.shields.io/badge/Scala-3.5.2-red.svg)](https://www.scala-lang.org/)

</div>

---

Nebflow is a self-hosted AI coding assistant that runs entirely on your machine. It features a browser-based chat interface with streaming responses, native HTML card rendering, multi-agent orchestration, and cross-device collaboration — all in a single JAR with no external dependencies beyond Java.

## Features

- **Inline Card Rendering** — Agents render rich HTML cards (diagrams, charts, tables, animations) directly in the chat, not just text
- **Web UI & CLI** — Browser-based interface with streaming, syntax highlighting, and file editing; plus a terminal REPL mode
- **Multi-Provider LLM** — Zhipu GLM, DeepSeek, Qwen, Baichuan, and all OpenAI/Anthropic-compatible APIs, with automatic fallback chains
- **19 Built-in Tools** — Read, Write, Edit, Bash, Grep, Glob, WebSearch, WebFetch, Curl, Card, Delegate, ExecuteFlow, Mail, TransferFile, TaskCreate, TaskUpdate, TaskList, AskUserQuestion, RemoveUnnecessary
- **Multi-Agent System** — Named agents with per-agent system prompts, tool whitelists, isolated project workspaces, and inter-agent delegation
- **NebLink** — Connect multiple devices (macOS, Linux, Windows) over a synchronized mesh; execute commands and transfer files across devices
- **Skills** — Reusable prompt templates with YAML frontmatter; compatible with Claude Code, Codex, and OpenClaw ecosystems
- **Plan Mode** — Structured planning with canvas approval flow before execution
- **Flow Engine** — Multi-step workflow orchestration with DAG dependencies, verification, and retry loops
- **MCP Support** — Connect external tools and data sources via Model Context Protocol
- **Three-Tier Memory** — Persistent memory at user, agent, and project scope across conversations
- **Context Management** — Automatic and manual context compaction for long sessions
- **Permission System** — Ask-before-execute for destructive operations; auto-approve for read-only tools
- **Hooks** — Pre/post-execution callbacks triggered by tool patterns
- **Cross-Platform** — macOS, Linux, and Windows with automatic Java, Git for Windows, and ripgrep installation

## Quick Start

### Install (macOS / Linux)

```bash
curl -fsSL https://nebflow.space/install.sh | sh
```

### Install (Windows)

Open PowerShell and run:

```powershell
irm https://nebflow.space/install.ps1 | iex
```

The installer automatically detects and installs Java 17+, Git for Windows (on Windows), and ripgrep if they are not already available.

### Uninstall

```bash
# macOS / Linux
curl -fsSL https://nebflow.space/uninstall.sh | sh

# Windows PowerShell
irm https://nebflow.space/uninstall.ps1 | iex
```

## Usage

```bash
# Start web server (default port 8080)
nebflow start

# Start with custom port
nebflow start --port 3000

# Stop running server
nebflow stop

# CLI REPL mode
nebflow

# Show help
nebflow help
```

Open `http://localhost:8080` in your browser after starting the server.

## Configuration

Nebflow stores all data in `~/.nebflow/`. Configuration lives at `~/.nebflow/nebflow.json` and is created automatically on first run.

```json
{
  "llm": {
    "providers": {
      "zhipu": {
        "baseUrl": "https://open.bigmodel.cn/api/paas/v4",
        "apiKey": "${ZHIPU_API_KEY}"
      },
      "deepseek": {
        "baseUrl": "https://api.deepseek.com",
        "apiKey": "${DEEPSEEK_API_KEY}"
      },
      "qwen": {
        "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "apiKey": "${DASHSCOPE_API_KEY}"
      }
    },
    "model": {
      "default": "zhipu/glm-4.5"
    }
  },
  "mcpServers": {}
}
```

API keys can be set via environment variables (`${VAR_NAME}` syntax) or directly in the config file.

## Building from Source

### Prerequisites

- Java 17+
- sbt 1.x

```bash
sbt compile          # Compile
sbt assembly         # Build fat JAR
make install         # Install to ~/.local/bin
sbt test             # Run tests
make check           # All quality checks (compile + scalafmt + scalafix)
```

The assembled JAR is output to `target/scala-3.5.2/`.

## Architecture

```
src/main/scala/nebflow/
├── actor/        # Actor system (ActorRef, ActorSystem, Behavior)
├── agent/        # Agent lifecycle, session routing, multi-agent delegation
├── bridge/       // Device bridge plugins for NebLink
├── cli/          # CLI commands, REPL, TUI
├── core/         # Tools, permissions, LLM client, context compaction
│   ├── ask/      # Ask-user interaction service
│   ├── compact/  # Context compaction engine
│   ├── flow/     # Flow engine for multi-step workflows
│   ├── hooks/    # Pre/post tool execution hooks
│   ├── mcp/      # Model Context Protocol integration
│   ├── scheduler/ # Scheduled and recurring tasks
│   ├── skill/    # Skill loading and management
│   ├── task/     # Task list state management
│   ├── telemetry/ # Response time and usage tracking
│   └── tools/    # 19 built-in tools
├── dropbox/      # File dropbox for drag-and-drop uploads
├── gateway/      # HTTP server, WebSocket routes, static resource serving
├── llm/          # LLM client abstraction and provider implementations
├── neblink/      # Cross-device synchronization protocol
├── service/      # Session store, configuration, runtime preferences
└── shared/       # Shared types, defaults, HTTP utilities
```

Built with **Scala 3**, **Cats Effect 3**, and **Pekko Actors**. The web UI is served from embedded resources — no separate frontend build step required.

## Links

- **Website:** [nebflow.space](https://nebflow.space)
- **Releases:** [GitHub Releases](https://github.com/MashiroKai/Nebflow/releases)

## License

[Apache License 2.0](LICENSE)
