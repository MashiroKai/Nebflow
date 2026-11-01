# Contributing

This guide is for developers preparing changes to this repository. It covers environment setup, building, testing, and the branch/commit/PR conventions. Development happens on an internal USTC Git instance (`https://git.ustc.edu.cn/kaiyu/nebflow`) — external pull requests are not accepted. The GitHub repository (`github.com/MashiroKai/Nebflow`) stays frozen read-only as the historical archive; it no longer receives pushes, issues, or pull requests.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21 | CI (`.github/workflows/ci.yml`) builds and tests on Temurin 21; the JVM flags in `build.sbt` (generational ZGC) also require 21 |
| sbt | 1.10.10 | Pinned in `project/build.properties` |
| Node.js | 20 | Only needed for the web bundle build, frontend tests, and frontend checks |

No `.jvmopts` or `.sdkmanrc` is used; toolchain versions live in `project/build.properties` and the CI workflow.

**Two constants, not one number — the build JDK and the artifact's bytecode target are deliberately different.** `-release:17` in `build.sbt` sets the *artifact* target (class-file major 61), while the *runtime floor* stays 21. Do not "fix" `-release:17` back to `-release:21`:

- A major-65 artifact cannot even be loaded by a 17 JVM, so the human-readable `requires Java 21` refusal (`src/main/scala/nebflow/core/JvmRequirement.scala`) becomes unreachable — the start gate never runs — and `stand update | doctor | version`, the self-repair path a user needs *on the machine being upgraded*, are welded shut.
- It does not loosen the API surface: `-release` pins both the API surface and the bytecode target, and release 17's API surface is a subset of 21's. The 21 floor is enforced at runtime by the `GatewayMain` start gate, `doctor`, the installers, the Dockerfile, and `scripts/check-jdk-baseline.sh`.
- Scala 3.5.2 cannot split "API surface 21 / bytecode 17" (`-release` and `-java-output-version` are the same setting; supplying both yields `Flag -java-output-version set repeatedly`, and `-java-output-version:17` alone lowers the API surface too).

Consequently `scripts/check-jdk-baseline.sh` asserts `-release == JDK_BYTECODE_TARGET` (A-5), that the target is strictly below the floor (A-7a), and that the built jar's `nebflow/Main.class` is major 61 (A-7b) with the 17-side behaviour checked at runtime (A-6) — see the script header.

## Build and run locally

```bash
sbt compile        # compile
sbt assembly       # fat JAR -> target/scala-3.5.2/
sbt run            # start the web server at http://localhost:8080
```

To isolate a dev server from anything already on 8080, give it its own home and port (`--home` / `--port` are global flags):

```bash
sbt "run --home /tmp/nb-dev --port 8090"
```

The web frontend is vanilla JS served from `src/main/resources/web` — a plain `sbt run` serves live sources with no npm step. The production bundle is built separately:

```bash
npm ci
node scripts/build-web.mjs    # esbuild bundle -> build/web-dist
```

The `Makefile` wraps the common invocations: `make compile`, `make run`, `make assembly`, `make install` (JAR to `~/.local/bin`), and `make check` (compile + format/lint checks, see [Code style](#code-style)).

## Testing

### Backend (Scala, munit)

```bash
sbt test                                  # full suite: ~2000+ cases, expect roughly 35–40 minutes
sbt "testOnly nebflow.core.BrandingSpec"  # directed run — fast inner loop
```

Tests execute sequentially (`Test / parallelExecution := false`) because suites share some global state — don't re-enable parallelism casually. Scope with `testOnly <package>.<SpecName>` for iteration; run the full suite before opening a PR.

### Frontend (Playwright)

Browser specs live in `tests/*.spec.mjs` and come in two kinds:

- **Self-contained specs** stub their own backend on an ephemeral port (e.g. `tests/orbit-anim.spec.mjs`) — no running instance needed.
- **Instance-backed specs** (e.g. `tests/smoke.spec.mjs`) drive a real server: point them at it with `BASE_URL` and authenticate with `NEBFLOW_TOKEN` (falls back to the instance's `~/.nebflow/auth.json`).

```bash
# one-time setup (Playwright is not a package.json dependency; CI installs it the same way)
npm install --no-save --no-package-lock @playwright/test@1.62.0
npx playwright install chromium        # only if no browser is cached yet

npx playwright test tests/orbit-anim.spec.mjs
BASE_URL=http://localhost:8090 NEBFLOW_TOKEN=... npx playwright test tests/smoke.spec.mjs
```

CI runs the smoke suite against a freshly built JAR, so a green local run plus green CI is the bar for frontend changes.

## Branches and commits

- Branch off `main` with a short descriptive name; keep a branch scoped to one change.
- Stage files explicitly (`git add <file>`); avoid `git add -A`.
- Commit subjects in practice carry an optional `type(scope):` prefix — types like `fix` / `feat` / `test` / `docs` / `refactor`, scopes like `web` / `tools` / `engine` / `project` / `gateway`. The subject may be English or Chinese and should state the cause or intent, not just the symptom. Merge commits to `main` follow `Merge <branch>: <summary>`.

## Pull requests

- PRs target `main`. Keep the diff minimal and scoped to one change.
- Before opening: `sbt compile` and the affected tests green. For Scala changes also run `make check` (scalafmt + scalafix); for frontend changes run the type/cycle gates and the affected Playwright specs (see below).
- CI (`.github/workflows/ci.yml`) runs the JS type/cycle gates, compile + full test suite (Ubuntu and Windows), JAR assembly, smoke + Playwright frontend suite, and a Docker build. A PR is mergeable when CI is green.

## Code style

- **Scala 3** (3.5.2, significant indentation). Formatting is enforced by scalafmt and scalafix — `make check` runs `sbt scalafmtCheckAll "scalafix --check"`. The compiler runs with `-Xfatal-warnings`: fix the warning, don't suppress it.
- **Frontend** is vanilla JS with no TS build step; correctness is gated by two static checks (both run in CI):

```bash
node scripts/check-js-types.mjs    # checkJs vs tests/type-baseline.json (baseline only goes down)
node scripts/check-circular.mjs    # static-import cycle gate
node scripts/check-provider-modellist.mjs   # provider model-list endpoints stay per-face declared
```

`check-js-types.mjs --update` regenerates the baseline after intentional type fixes. Dynamic `import()` is the sanctioned way to break a genuine cycle; static import cycles fail the gate. `check-provider-modellist.mjs` is hermetic (`--live` adds the environment/proxy, provider and gateway layers; see its header comment and the `ModelListFaces` scaladoc).

### Visual regression baseline

`tests/visual/` + `scripts/visual-baseline.mjs` (adopted 2026-11-01) render six scenarios (`--list`) in dark and light through a **zero-port** harness — `page.route` serves `src/main/resources/web` from disk with a MockWS injection, so it structurally cannot reach a running instance (or the host gateway port) — and pixel-compare each shot against a committed baseline PNG.

```bash
make check-visual              # = node scripts/visual-baseline.mjs --check --max-ratio 0.0005 (gate)
make check-visual VISUAL_MAX_RATIO=0   # strict zero-diff run
make visual-baseline          # = node scripts/visual-baseline.mjs --update  (re-record)
node scripts/visual-baseline.mjs --check --filter canvas --determinism
```

Baselines are stored **per host platform key**: `tests/visual/baseline/<process.platform>-<browserLabel>/` (e.g. `win32-msedge-156`, `darwin-chromium-151`). Pixels are not comparable across OS/browser builds, so a host without its own baseline directory reports `MISS` on every shot — a red, never a silent pass. `win32-msedge-156/` was recorded on the author's Windows + Edge host and belongs to the **Windows runner leg**; on another host record your own set with `make visual-baseline`, eyeball the shots under `.nebflow/visual-baseline/<platform>/`, then commit `tests/visual/baseline/<platform>/`. Tolerances are strict zero by default in the script (`--tolerance` / `--max-ratio` widen them explicitly — loosening a baseline is not a fix for a real pixel change); `make check-visual` passes `--max-ratio 0.0005` because both recorded hosts showed 12–16 px of random antialiasing drift on the 1 px pane border of the two monaco scenarios (0.002–0.003%, deterministic within a run, so it is host rendering noise rather than product jitter). Use `make check-visual VISUAL_MAX_RATIO=0` for a strict zero-diff run — recommended on the Windows runner leg, whose baselines were captured noise-free. Run it after frontend changes that can move layout; the GitHub CI legs do not carry it yet (no matching platform baselines there).
