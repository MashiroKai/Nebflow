<div align="center">

# Nebflow

Self-hosted AI coding assistant with inline HTML card rendering.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/MashiroKai/Nebflow-Release?label=stable)](https://github.com/MashiroKai/Nebflow-Release/releases/latest)

</div>

---

Nebflow is a self-hosted AI coding assistant that runs entirely on your machine. It features a browser-based chat interface with streaming responses, native HTML card rendering, multi-provider LLM support, and a built-in agent system — all in a single JAR.

## Features

- **Inline Card Rendering** — Agents render rich HTML cards (diagrams, charts, tables, animations) directly in chat, not just text
- **Web UI & CLI** — Browser-based interface with streaming, syntax highlighting, and file editing, plus a terminal mode
- **Multi-Provider LLM** — Anthropic Claude, OpenAI, and any OpenAI/Anthropic-compatible API with automatic fallback
- **17 Built-in Tools** — File read/write/edit, bash execution, web search & fetch, code search (grep/glob), task management, curl, and more
- **Agent System** — Customizable agents with per-agent system prompts, tool whitelists, and isolated sessions
- **MCP Support** — Connect external tools and data sources via Model Context Protocol
- **Memory System** — Persistent memory at user, agent, and project level across conversations
- **Context Management** — Automatic and manual context compaction for long sessions
- **Permission System** — Ask-before-execute for destructive operations, auto-approve for reads
- **Cross-Platform** — macOS, Linux, and Windows support

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

### Beta Channel

```bash
# macOS / Linux
curl -fsSL https://nebflow.space/install.sh | sh -s -- --beta
```

```powershell
# Windows PowerShell
$env:CHANNEL='beta'; iwr https://nebflow.space/install.ps1 | iex
```

### Docker

```bash
docker build -t nebflow .
docker run -p 8080:8080 -v ~/.nebflow:/root/.nebflow nebflow
```

## Usage

```bash
# Start web server (default port 8080)
nebflow start

# Start with custom port
nebflow start --port 3000

# CLI mode
nebflow

# Show help
nebflow --help
```

## Configuration

Nebflow looks for configuration at `~/.nebflow/nebflow.json`. A template is created automatically on first run.

```json
{
  "llm": {
    "providers": {
      "anthropic": {
        "baseUrl": "https://api.anthropic.com",
        "apiKey": "${ANTHROPIC_API_KEY}",
        "protocol": "anthropic"
      },
      "openai": {
        "baseUrl": "https://api.openai.com",
        "apiKey": "${OPENAI_API_KEY}"
      }
    },
    "model": {
      "default": "anthropic/claude-sonnet-4-6"
    }
  },
  "mcpServers": {}
}
```

API keys can be set via environment variables (`${VAR_NAME}` syntax) or directly in the config.

## Building from Source

### Prerequisites

- Java 17+
- sbt 1.x

```bash
sbt compile          # Compile
sbt assembly         # Build fat JAR
make install         # Install to ~/.local/bin
sbt test             # Run tests
make check           # Run all quality checks (compile + scalafmt + scalafix)
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
- **Releases:** [Nebflow-Release](https://github.com/MashiroKai/Nebflow-Release)

## License

[Apache License 2.0](LICENSE)
