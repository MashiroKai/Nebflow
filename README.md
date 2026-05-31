<div align="center">

# Nebflow

Self-hosted AI coding assistant with inline HTML card rendering.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/MashiroKai/Nebflow?label=stable)](https://github.com/MashiroKai/Nebflow/releases/latest)

</div>

---

Nebflow is a self-hosted AI coding assistant that runs entirely on your machine. It features a browser-based chat interface with streaming responses, native HTML card rendering, multi-provider LLM support, and a built-in agent system — all in a single JAR with no external dependencies beyond Java.

## Features

- **Inline Card Rendering** — Agents render rich HTML cards (diagrams, charts, tables, animations) directly in the chat, not just text
- **Web UI & CLI** — Browser-based interface with streaming, syntax highlighting, and file editing; plus a terminal REPL mode
- **Multi-Provider LLM** — 智谱 GLM、通义千问、DeepSeek、百川，以及所有 OpenAI/Anthropic 兼容 API，支持自动 fallback 链
- **17 Built-in Tools** — Read, Write, Edit, Bash, Grep, Glob, WebSearch, WebFetch, Curl, Card, TaskCreate, TaskUpdate, TaskList, AskUserQuestion, WriteMemory, ClearStaging, RemoveUnnecessary
- **Agent System** — Multiple named agents with per-agent system prompts, tool whitelists, and isolated project workspaces
- **MCP Support** — Connect external tools and data sources via Model Context Protocol
- **Three-Tier Memory** — Persistent memory at user, agent, and project (folder) scope across conversations
- **Context Management** — Automatic and manual context compaction for long sessions
- **Permission System** — Ask-before-execute for destructive operations; auto-approve for read-only tools
- **Cross-Platform** — macOS, Linux, and Windows with automatic ripgrep installation

## Quick Start

### Prerequisites

- Java 17+

### Install (macOS / Linux)

```bash
curl -fsSL https://nebflow.space/install.sh | sh
```

### Install (Windows)

Open PowerShell and run:

```powershell
irm https://nebflow.space/install.ps1 | iex
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
      "default": "zhipu/glm-5.1"
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
nebflow/
├── agent/          # Agent actor system, definitions, session routing
├── core/           # Tools, permissions, REPL, LLM client, file history
├── gateway/        # HTTP server, WebSocket routes, web UI serving
├── service/        # Session store, config, runtime preferences
└── shared/         # Shared types, defaults, HTTP utilities
```

Built with **Scala 3**, **Cats Effect 3**, and **Pekko Actors**. The web UI is served from embedded resources — no separate frontend build step required.

## Links

- **Website:** [nebflow.space](https://nebflow.space)
- **Releases:** [GitHub Releases](https://github.com/MashiroKai/Nebflow/releases)

## License

[Apache License 2.0](LICENSE)
