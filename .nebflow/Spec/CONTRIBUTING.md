# Contributing to Nebflow

Thanks for your interest in contributing! This guide covers everything you need to build, test, and submit changes. Nebflow is licensed under the [Apache License 2.0](LICENSE).

For a product overview, see the [README](README.md).

## What is Nebflow?

Nebflow is a self-hosted AI coding assistant (an agent harness) that runs entirely on your machine: a browser-based chat UI with streaming, multi-agent orchestration, a flow engine, skills, and MCP support — packaged as a single fat JAR with no runtime dependency beyond Java. The production implementation is **Scala 3 on the JVM, built with sbt**. A Rust port lives in [`nebflow-rs/`](nebflow-rs/) and is covered separately (see [A note on the Rust port](#a-note-on-the-rust-port)).

## Development environment

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17+ | CI builds and tests on Temurin 21 |
| sbt | 1.10.10 | Pinned in `project/build.properties`; any standard sbt launcher picks it up |
| Node.js | 20 | Only needed for the web bundle build and frontend checks |
| Git | any recent | |

```bash
git clone https://github.com/MashiroKai/Nebflow.git
cd Nebflow
sbt compile        # compile
sbt run            # start the web server at http://localhost:8080
```

`sbt run` starts the gateway on port **8080** by default. If you already run a production instance (or anything else) on 8080, isolate your dev server with its own home directory and port:

```bash
sbt "run --home /tmp/nb-dev --port 8090"
```

The `Makefile` wraps the common sbt invocations — `make compile`, `make run`, `make assembly`, `make check` (see [Code style](#code-style)). The fat JAR is built with `sbt assembly` and installed to `~/.local/bin` with `make install`.

## Architecture overview

Backend module map (under `src/main/scala/nebflow/`):

```
actor/     Actor system (ActorRef, ActorSystem, Behavior)
agent/     Agent lifecycle, session routing, agent library
cli/       CLI commands, REPL
core/      Tools, permissions, LLM glue, context compaction
  flow/      Flow engine (multi-step workflows)
  skill/     Skill loading (SKILL.md + YAML frontmatter)
  mcp/       Model Context Protocol client & manager
  project/   Project / Node / Flow Map orchestration
  task/      Task list state
  tools/     Built-in tools
gateway/   HTTP server, WebSocket routes, static assets
llm/       Provider abstraction (GLM, DeepSeek, Qwen, OpenAI/Anthropic-compatible)
neblink/   Cross-device mesh sync
service/   Session store, configuration
shared/    Shared types and utilities
```

Two architectural rules worth knowing before you write code:

- **Actors and cats-effect don't mix.** Actors own message passing and state; cats-effect `IO` owns side-effect orchestration. Keep each layer in its own lane.
- **Brand strings are single-sourced** in [`brand.conf`](brand.conf) and read via `nebflow.core.Branding` (backend) and `window.__BRAND__` (frontend). Never hard-code product name/URLs — that's what `scripts/rebrand.sh` is for.

### The Project/Node orchestration model

- **Nebula** is the root, always-present agent — the single entry point a user talks to.
- A **Project** (`core/project/`) is a workspace on disk described by `project.json` (name, workspace path, agent file). Each project has a **Flow Map** (`flow-map.json`) — a DAG of **Nodes**.
- A **Node** is an ephemeral, leaf-level agent run (no mail identity, no memory). Nodes declare `in`/`out` edges; when a node finishes, its result is delivered along its `out` edges to downstream nodes. A node may optionally run in an isolated **git worktree**, and each node carries its own config: which agent definition, skill, MCP servers, and preset to use.
- Agent definitions are converging on three shapes: the root **Nebula** agent, per-project **dispatchers** that decompose tasks into the Flow Map, and a **generic node template**.

## Branches and releases

| Branch | Role |
|---|---|
| `main` | Development branch. All feature work lands here via PRs. CI runs on every push and PR. |
| `beta` | Prerelease channel. Pushing here triggers the **Beta Release** workflow: full test run, fat JAR, and desktop packages (macOS `.dmg` arm64/x64, Windows `.msi`, Linux `.deb` + AppImage), then tags `v<VERSION>` and publishes a GitHub prerelease. |
| `release` | Stable channel. Pushing here triggers the **Auto Release** workflow (same packaging, stable release) and syncs `VERSION` back to `main`. |

- `VERSION` at the repo root is the single source of truth for the version number; `build.sbt` reads it at build time.
- Cutting releases (merging `main` → `beta` → `release`) is done by the maintainers. As an external contributor you only need to get your change into `main` — releases are cut from there.

## Development workflow

1. Branch from `main` — the repo uses `feat/`, `fix/`, `refactor/`, `test/` prefixes.
2. Make your change with the smallest precise diff that solves the problem.
3. Compile, run the relevant tests, and run the formatting gates (below).
4. Open a PR against `main`.

### Commit messages

The repo follows Conventional Commits:

```
feat(project): short summary in imperative mood
fix(web): another summary
```

- Types in use: `feat`, `fix`, `test`, `docs`, `chore` (merge commits appear as `Merge branch '...'`).
- A scope is optional but common (`feat(project):`, `fix(web):`, `fix(llm):`).
- Summaries are written in English or Chinese — both are fine in this repo.
- Reference issues as `#123` in the summary when applicable.

If you work on several things in parallel, prefer separate checkouts (`git worktree add ../Nebflow-feat-x main -b feat/x`) over switching branches in one working directory.

## Code style

### Scala

Formatting and linting are enforced by **scalafmt** (`.scalafmt.conf`, Scala 3 dialect, 120-column limit, 2-space indent) and **scalafix** (`.scalafix.conf`: `DisableSyntax` with `noNull`/`noReturns`/`noXmlLiterals`/`noFinalize`, `OrganizeImports`, and others). The compiler runs with `-Xfatal-warnings`.

```bash
make lint          # check formatting + scalafix (what CI-style gates check)
make check         # compile + format checks + lint
make fmt           # auto-format
make fix           # auto-apply scalafix
```

Run `make check` before pushing — it is the repo's quality gate (compile + format checks + lint).

### Frontend (vanilla JS)

The web UI is vanilla ES modules served straight from `src/main/resources/web/` — **no build step is needed for development**. There is no framework and no npm runtime dependency.

Two static gates run in CI on every PR:

- a **checkJs baseline gate** (`jsconfig.json` + `tests/type-baseline.json`): TypeScript-checked JS where the error baseline may only shrink — new errors fail the build;
- a **circular-import gate**: the static import graph must stay acyclic (dynamic `import()` is the sanctioned escape hatch).

For production packaging the sources are bundled with esbuild:

```bash
npm ci                      # installs esbuild (the only dev dependency)
node scripts/build-web.mjs  # bundles into build/web-dist/
```

CI mounts that bundle into the JAR; a plain local `sbt run` always serves the live sources, never a stale dist. Any change under `web/` must pass the static-asset gate (`scripts/verify-web-assets.mjs` against a running instance) before merge — every new file must actually be served with HTTP 200.

## Testing

### Backend (Scala, munit)

```bash
sbt test                                   # full suite (expect roughly 25–30 minutes)
sbt "testOnly nebflow.core.BrandingSpec"   # directed run — fast inner loop
```

Tests execute **sequentially** (`Test / parallelExecution := false`) because suites share some global state — don't re-enable parallelism casually. For quick iteration, always scope with `testOnly <package>.<SpecName>` instead of running the full suite.

### Frontend (Playwright)

Browser tests live in `tests/*.spec.mjs` and come in two kinds:

- **Self-contained specs** stub their own backend with a throwaway HTTP server on an ephemeral port — they need no Nebflow instance and never touch port 8080. Example: `tests/orbit-anim.spec.mjs`.
- **Instance-backed specs** (e.g. `tests/smoke.spec.mjs`) drive a real running server; point them at it with `BASE_URL` and authenticate with `NEBFLOW_TOKEN`.

```bash
# one-time setup (Playwright is not a package.json dependency; CI installs it the same way)
npm install --no-save --no-package-lock @playwright/test@1.62.0
npx playwright install chromium        # only if no browser is cached yet

npx playwright test tests/orbit-anim.spec.mjs      # self-contained
BASE_URL=http://localhost:8090 NEBFLOW_TOKEN=... npx playwright test tests/smoke.spec.mjs
```

CI runs the smoke suite against a freshly built JAR, so a green local run plus green CI is the bar for frontend changes.

## Submitting a pull request

Before opening the PR, make sure:

- [ ] `sbt compile` and your targeted tests pass (`make check` for the format gates)
- [ ] frontend changes pass the static gates and, for `web/` edits, the asset-availability check
- [ ] the diff is minimal and focused — one concern per PR

In the PR description, cover: **what** changed and **why**, how you verified it (which commands/tests you ran), and screenshots for UI changes. CI will run the full pipeline — compile on Linux and Windows, the full test suite, the JS gates, a JAR smoke test, and the Playwright suite against the built JAR. A PR is mergeable when the pipeline is green.

## A note on the Rust port

Nebflow is migrating from Scala to Rust; the port lives in [`nebflow-rs/`](nebflow-rs/) with its own CI. Until the port reaches validated feature parity, the Scala version remains the running production build — treat the two as independent: Rust changes belong in `nebflow-rs/`, everything else in the Scala tree.

## Repository instructions for coding agents

`AGENTS.md` at the repo root holds this project's instructions for AI coding agents (auto-injected as system context by harnesses that follow the [AGENTS.md](https://agents.md) convention). If you use an agent to contribute, make sure it reads that file — it encodes the same conventions as this guide, plus working rules (branch safety, worktree discipline, process safety).

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
