<div align="center">

# Nebflow

**One entry. Every agent.**

A self-hosted AI agent orchestration platform — bring all your work to one entry, and Nebflow dispatches it across agents, projects, and devices.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey.svg)](https://github.com/MashiroKai/Nebflow/releases)
[![Scala](https://img.shields.io/badge/Scala-3.5.2-red.svg)](https://www.scala-lang.org/)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)

<p align="center">
  <img src="docs/assets/readme/hero-flow-map.png" alt="Nebflow Flow Map — a task chain executing live" width="960">
</p>

**[Website](https://nebflow.space)** · **[Releases](https://github.com/MashiroKai/Nebflow/releases)**

</div>

Nebflow runs entirely on your own machine: a single self-contained JAR serves a browser-based workspace where you chat, orchestrate multi-agent projects, and automate recurring work. There is no cloud account and no external service dependency beyond the LLM providers you choose to configure.

## Features

### One entry, every agent — task dispatch on a live Flow Map

Describe the goal once. A project dispatcher decomposes it into a DAG of nodes, runs each node in its own isolated git worktree, streams results back along the edges, and merges the finished chain — all of it visible live on the Flow Map.

<p align="center">
  <img src="docs/assets/readme/flow-map-demo.gif" alt="Task dispatch on the Nebflow Flow Map" width="800">
</p>

### A chat that renders, not just text

Streaming markdown, tables, and rich inline cards — diagrams, charts, animations — rendered directly in the conversation, with per-message model badges.

<p align="center">
  <img src="docs/assets/readme/message-stream.png" alt="Nebflow chat with rendered markdown tables" width="800">
</p>

### Plugins and skills

Extend Nebflow with plugin packages and reusable skills — YAML-frontmatter prompt templates compatible with the Claude Code and Codex ecosystems. Toggle, preview, and manage them from the built-in panel.

<p align="center">
  <img src="docs/assets/readme/plugins-panel.png" alt="Nebflow plugins panel" width="800">
</p>

### Voice in, hands free

A floating voice orb lives on your desktop for voice input and hands-free agent interaction.

<p align="center">
  <img src="docs/assets/readme/micorb-idle.png" alt="Nebflow voice orb" width="340">
</p>

### Friends — talk to your friends' agents

Add a friend by **username or email**, and keep the conversation in the workspace: friend messages open beside your own sessions, and an orchestrated run can hand a turn over to that friend's agents.

<p align="center">
  <img src="docs/assets/readme/friends-panel.png" alt="Nebflow activity bar with the Messages and Contacts entries, and the Contacts panel open" width="800">
</p>

Friends run on your **nebflow account** — the same sign-in that pairs your machines over Device Interconnect. Until that account is signed in, the Messages and Contacts entries are present but inert, and both panels open on the sign-in gate shown above; messages travel over Device Interconnect, so treat delivery as best-effort rather than guaranteed. Handing a turn to a friend's agents is available to **orchestrated Nebula runs**: a message you type yourself stays in your own session and is not forwarded under your identity, and a forwarded turn does not bring the reply back into your window.

<p align="center">
  <img src="docs/assets/readme/friends-demo.gif" alt="Opening the Contacts panel from the Nebflow activity bar" width="720">
</p>

### And more

- **Multi-provider LLM** — OpenAI- and Anthropic-compatible APIs, with health monitoring and automatic fallback chains
- **MCP support** — connect external tools and data sources via the Model Context Protocol
- **Three-tier memory** — persistent memory at user, agent, and project scope across conversations
- **Permission system** — ask-before-execute for destructive operations, auto-approve for read-only tools
- **Hooks & scheduled tasks** — pre/post tool-execution callbacks and cron-style recurring work
- **Device Interconnect** — connect your devices over a synchronized mesh; run commands and transfer files across machines
- **Friends** — add a friend by username or email and message them from the same workspace; an orchestrated run can hand a turn to their agents (needs a signed-in nebflow account)
- **Cross-platform** — macOS, Linux, and Windows, with automatic Java and ripgrep setup

## Quick Start

macOS / Linux:

```bash
curl -fsSL https://nebflow.space/install.sh | sh
```

Windows (PowerShell):

```powershell
irm https://nebflow.space/install.ps1 | iex
```

Then start the workspace:

```bash
nebflow start    # serves the web UI at http://localhost:8080
```

Releases on [GitHub Releases](https://github.com/MashiroKai/Nebflow/releases) ship a single self-contained JAR; the install scripts above add Java 21 and ripgrep for you when either is missing.

To build from source (Java 21+, sbt):

```bash
sbt assembly
```

## Weixin (iLink) channel

Nebflow connects to Weixin through the official ClawBot plugin, which runs attached to your OpenClaw host. Nebflow itself hosts only the seam: it does not implement the iLink protocol (long polling, auth headers, media decryption, cursors and session backoff all live in the official plugin).

**Requirements**

- Node.js **22.13.0 or newer** on the host that runs the plugin.
- OpenClaw host **2026.5.12 or newer** (the plugin's declared minimum).
- The plugin package **`@tencent-weixin/openclaw-weixin` at version 2.4.9**, installed and enabled in that host.

**How it runs**

The plugin process is resident in the host and owns the whole Weixin side, including account credentials: it keeps them under the host state directory (`~/.openclaw/openclaw-weixin/accounts/<ilink_bot_id>.json`). Nebflow keeps **no copy of any credential** — the `weixin-ilink` channel card stores the `bot_token` behind a path reference and never renders, echoes or serves a secret value; every probe answers only whether the stored path exists, is correctly permissioned, and is readable. A session pause on the Weixin side (a `-14` response, which parks that account for one hour) is contained to the plugin's own leg and never interrupts other Nebflow work.

**Enable it — scan, like Feishu**

1. Install and enable the plugin in the host. **Run the host on the same machine as the Nebflow gateway** — the credentials it produces are read from that machine's host state directory.
2. Open the social panel and select the **Weixin (iLink)** card: the QR appears inside the card (auto-loaded when the panel opens). Scan it with WeChat and confirm on the phone — the credential triple (`bot_token`, `ilink_bot_id`, `ilink_user_id`) is written through the normal save path (`bot_token` lands in `secrets/` as an owner-only file, the config keeps only a path), the card flips to its created face and the ingress seam registers. This is the same begin/status scan-bind flow the Feishu card ships; a completed scan can be undone with the card's **Unbind** action (credentials kept, nothing deleted).
3. The QR itself is produced by the official plugin's side-car. The scan-bind leg meets it at a **control endpoint**: `sidecar_url` on the card (optional, default `http://127.0.0.1:8787`) must be served by the side-car with
   - `POST {sidecar_url}/login/begin` → `200 {"qrUrl": …, "userCode"?: …, "expireIn"?: seconds}` — starts (or restarts) the side-car's login session;
   - `GET {sidecar_url}/login/status` → `200 {"state": "starting"|"qr_ready"|"polling"|"done"|"failed", "qrUrl"?, "userCode"?, "expireIn"?, "error"?, "credentials"?}` — polled until terminal; on `done` the body carries `"credentials": {"bot_token", "ilink_bot_id", "ilink_user_id"}` exactly once. The side-car performs no sender admission of its own; the token never appears in any other response.
4. Fill `allowed_ilink_user_ids` (REST `POST /api/social/channels/weixin-ilink`) with the sender ids that may talk to the bot, comma-separated. **Leaving it empty allows every sender that can reach the plugin; filling it makes the gate fail-closed** — only listed senders pass, and a message whose sender identity cannot be resolved is dropped like any non-member. The card reports as connected once the channel is enabled and verified.

The inbound leg is `POST /api/social/channels/weixin-ilink/ingress`, behind the gateway's normal auth gate: the plugin-side side-car posts one message at a time, and the response is the observable verdict (`injected` with the target session, or `dropped` with a reason). Restricting senders at this seam matters because the official plugin performs no sender admission of its own.

## Documentation

Full documentation lives at [nebflow.space](https://nebflow.space).

## Architecture

Nebflow is written in **Scala 3** on **self-developed actors** (message passing and state management) and **http4s / cats-effect** (HTTP, WebSocket, and side-effect orchestration), with a deliberate boundary keeping the two layers apart. Work is organized as **Projects → Nodes**: a dispatcher turns a task into a Flow Map DAG, executes each node in an isolated worktree, and collects results along the out-edges for merge. Everything ships as a single self-contained JAR you host yourself — the web UI is served from embedded resources, with no separate frontend build step.

## License

[MIT License](LICENSE)
