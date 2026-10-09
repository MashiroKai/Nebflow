# vendor/logos — executor brand assets (self-hosted)

Official/native logo assets for the unified delegate face (`js/executorIcons.js`).
Self-hosted on purpose: zero runtime external links. Source registry:

| File | Executor id | Source | Fetched |
|---|---|---|---|
| `claude-code.svg` | `claude-code` | simple-icons slug [`claudecode`](https://cdn.simpleicons.org/claudecode) (official Claude Code mark, brand fill `#D97757` baked by upstream) | 2026-10-10 |
| `codex.svg` | `codex` | simple-icons v13 slug [`openai`](https://cdn.jsdelivr.net/npm/simple-icons@13/icons/openai.svg) (official OpenAI knot; slug retired upstream after v13, path untouched; only `fill` set to `currentColor` for theme parity) | 2026-10-10 |

Nebflow's own mark is **not** stored here: the app reuses the shipped canonical
pixel-logo pair `css/logo-dark-4.png` / `css/logo-bright-4.png` — the same
artwork the header avatar serves (`index.html` `<picture>` pattern, derived from
the website canonical `public/logo/bright.png`, see the favicon comment in
`index.html`).

No official asset found (kept on legacy in-house marks, declared):
- `zcode` — simple-icons has no `zcode` slug (404); no public official ZCode logo.
- `hermes` — simple-icons' `hermes` is an unrelated logistics/fashion brand, not
  the Hermes CLI agent; no applicable official logo.
