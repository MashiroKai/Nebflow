# Canvas document mention negative controls

Run `ENGINE=chromium node tests/canvas-document-mentions-real.mjs` and with `ENGINE=webkit`; both must pass. Set `FAULT=duplicate`, `FAULT=heading`, `FAULT=link`, or `FAULT=code` to remove the corresponding guard at response time; each must fail its named assertion. The code fault also asserts the injected predicate executed and checks inline, fenced and indented code.

Baseline new-feature negative: run the same test with `NEBFLOW_WEB_ROOT` set to an unmodified checkout at `4164dd49f7043fbf6da5ba07420ec2599b645519`. Both engines fail waiting for the new mention, as expected. No baseline red is required for protection assertions: those protections are tested by fault injection on the feature branch.

## S2 (reveal)

Run `node tests/canvas-document-mentions-reveal-real.mjs <engine> <viewport>` for each of `chromium|webkit` × `desktop|mobile`; all four must pass. The desktop green leg also covers the default-root basis (no persisted explorer root; the basis comes from the root listing's `resolvedPath`).

Fault injection at response time (`node tests/canvas-document-mentions-reveal-real.mjs chromium desktop <fault>`), each must fail its named assertion:

- `noreveal` — the mention-click reveal call is disabled; the level-by-level walk never happens, so the `awaitReveal` wait times out at state ①. The injected predicate's execution is itself asserted first (visit counter > 0).
- `silent-reroot` — the outside-root branch silently calls `openExplorerAt(dir)` before the toast; state ② front goes red on `root NOT changed without the explicit user action` (and on `no listing of the away root before the user opts in`).
- `missing-silent` — the missing-target toast is disabled; state ③ goes red waiting for `未找到该文件` to appear. The injected predicate's execution is itself asserted first.
- `nowait` — the level-by-level wait (`await awaitDirChildren(dir)`) resolves immediately; the walk races ahead of the listings, finds no child wrapper, and state ① goes red at `awaitReveal`. In green runs the `child listing waits for the parent reply` assertion additionally protects the no-prefetch ordering on the real timing.

Baseline new-feature negative: run with argv web-root override pointing at the `4164dd49f7043fbf6da5ba07420ec2599b645519` checkout:

```
node tests/canvas-document-mentions-reveal-real.mjs chromium desktop '' /Users/kaiyu/Claude code/Stand/.stand/worktrees/canvas-doc-terms-baseline/src/main/resources/web
node tests/canvas-document-mentions-reveal-real.mjs webkit   desktop '' /Users/kaiyu/Claude code/Stand/.stand/worktrees/canvas-doc-terms-baseline/src/main/resources/web
```

Both engines fail at the first new-feature assertion (no mention link exists before S1), which is the same red point for every S2 state — state-level assertions cannot exist without the mention link, so no further baseline reds are required (same rationale as S1: protections are covered by fault injection on the feature branch).
