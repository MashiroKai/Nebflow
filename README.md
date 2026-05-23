# Nebflow

A self-hosted AI coding assistant with a built-in web UI and extensible agent system.

## Features

- **Web UI** — Browser-based chat interface with streaming, code highlighting, and file editing
- **CLI mode** — Run directly in the terminal
- **Multi-provider LLM** — Anthropic Claude, OpenAI, and any OpenAI-compatible API with automatic fallback
- **Agent system** — Built-in agents with customizable system prompts and tool whitelists
- **MCP (Model Context Protocol)** — Connect external tools and data sources
- **18 built-in tools** — File read/write/edit, bash execution, web search, code search, task management, and more
- **Memory system** — Persistent session, agent, and user-level memory across conversations
- **Context management** — Automatic and manual context compaction for long sessions
- **Permission system** — Ask-before-execute for destructive operations with auto-approve for reads

## Quick Start

### Prerequisites

- Java 17 or higher

### Install (macOS / Linux)

```bash
curl -fsSL https://nebflow.space/install.sh | sh
```

### Install (Windows)

Open PowerShell and run:

```powershell
iwr -useb https://nebflow.space/install.ps1 | iex
```

### Install Beta

To install the latest beta release:

```bash
# macOS / Linux
curl -fsSL https://nebflow.space/install.sh | sh -s -- --beta
```

```powershell
# Windows PowerShell
iwr -useb https://nebflow.space/install.ps1 -OutFile $env:TEMP\nebflow-install.ps1
& $env:TEMP\nebflow-install.ps1 -Beta
```

### Docker

```bash
docker build -t nebflow .
docker run -p 8080:8080 -v ~/.config/nebflow:/root/.config/nebflow nebflow
```

## Usage

```bash
# Start web server (default port 8080)
nebflow --server

# Start with custom port
nebflow --server --port 3000

# CLI mode
nebflow

# Show help
nebflow --help
```

### Windows

On Windows, after installation, use the following commands:

```powershell
# PowerShell
nebflow --server
nebflow --help

# CMD
nebflow.cmd --server
nebflow.cmd --help
```

## Configuration

Nebflow looks for configuration at `~/.config/nebflow/nebflow.json`. A template is created automatically on first run.

Example configuration:

```json
{
  "llm": {
    "providers": {
      "anthropic": {
        "baseUrl": "https://api.anthropic.com",
        "apiKey": "${ANTHROPIC_API_KEY}",
        "protocol": "anthropic"
      }
    },
    "model": {
      "default": "anthropic/claude-sonnet-4-6"
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
# Compile
sbt compile

# Build fat JAR
sbt assembly

# Install locally
make install

# Run tests
sbt test

# Code quality checks
make check
```

The assembled JAR is output to `target/scala-3.5.2/nebflow-assembly-1.0.0.jar`.

## Architecture

```
nebflow/
├── agent/          # Agent actor system, definitions, core logic
├── core/           # Tools, permissions, repl, LLM client
├── gateway/        # HTTP server, WebSocket routes, web UI
├── service/        # Session store, config service, runtime preferences
└── shared/         # Shared types, defaults, HTTP utilities
```

- Built with **Scala 3**, **Cats Effect 3**, **Pekko Actors**
- Web UI served from embedded resources (no separate frontend build)
- LLM communication via provider-agnostic gateway with fallback chains

## License

All rights reserved.
