# Contributing

This guide is for developers preparing changes to this repository. It covers environment setup, building, testing, and the branch/commit/PR conventions. The repository is currently private — there is no external contribution process.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21 | CI (`.github/workflows/ci.yml`) builds and tests on Temurin 21; the JVM flags in `build.sbt` (generational ZGC) also require 21 |
| sbt | 1.10.10 | Pinned in `project/build.properties` |
| Node.js | 20 | Only needed for the web bundle build, frontend tests, and frontend checks |

No `.jvmopts` or `.sdkmanrc` is used; toolchain versions live in `project/build.properties` and the CI workflow.

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
```

`check-js-types.mjs --update` regenerates the baseline after intentional type fixes. Dynamic `import()` is the sanctioned way to break a genuine cycle; static import cycles fail the gate.
