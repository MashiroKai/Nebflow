# UI backlog baseline verification

## Scope and disposition

Baseline is `main` at `5d9012036` (same commit as `delegate/ui-backlog-fix` at inspection). No product code was changed: the prioritized concerns tested in this pass either already pass their current acceptance suite or remain audit/coverage gaps without a reproducible failure that justifies a CSS/JS change. No site-wide no-bug claim is made.

Priority inventory (P0 > P1 > P2):

| Priority | Finding | Evidence / source | This batch |
|---|---|---|---|
| P0 | No reproducible UI data-loss, wrong-data or blocking failure found in tested key-reveal/queue paths. | `tests/settings-key-reveal.spec.mjs:184-304`; `tests/queuebar-informative.spec.mjs:17-29,153-198` | Verified; no fix indicated. |
| P1 | Settings API key reveal: current five acceptance cases pass, including plaintext provider readback, save/reload, empty state and 403 fallback. | `tests/settings-key-reveal.spec.mjs:184-304`; current Chromium run 5/5 pass. | Verified; existing behavior, not an open bug. |
| P1 | Queue bar visible + collapse/expand and row actions work in real Chromium in both color schemes. | `css/input.css:1286-1357`; `css/glass.css:409-430`; queue suite Q1-Q9, S1-S8. | Verified; no fix indicated. |
| P1 | Composer actual typing/editing appearance has no new dedicated visual acceptance in this batch; existing input-surface coverage checks focus and computed styles, not a full manual real-input visual signoff. | `tests/input-surface.spec.mjs:165-212`; `css/input.css` | Follow-up coverage/audit; no concrete defect established. |
| P2 | Global glass consumer coverage is incomplete for top floating surfaces, panels and circular controls; gate `check-glass-unify` passes its registered surface inventory, but does not prove every consumer visually correct at desktop/mobile × light/dark. | `css/glass.css:108-171,409-430,433-`; `scripts/check-glass-unify.mjs` (inventory gate); `tests/uifix-glass.spec.mjs` | Follow-up matrix audit; do not create a second glass implementation. |
| P2 | Existing scroll-under-glass test `G7[手机]` fails: it expects a mask on mobile although `mobileLayer.css:3887-3906` explicitly documents and enforces releasing that mask to remove a stacking context. This is a stale/conflicting assertion, not evidence of a user-visible defect; assertion was not altered. | `tests/uifix-glass.spec.mjs` run: 28 PASS / 1 FAIL; mobile rule `mobileLayer.css:3887-3906`. | Not fixed; reconcile intended contract in a separate test/spec review. |
| P2 | `make frontend-gates` fails at unrelated freeze replay assertion `resumeSweep` ordering (`collapse must run before visibility()`); all preceding gates in that run pass, including glass-unify. | Gate output: `check-freeze-replay-silence.mjs`, R1; task scope explicitly excludes this subsystem. | Not changed; baseline red disclosed. |

## Commands and readings

- `node node_modules/@playwright/test/cli.js test tests/settings-key-reveal.spec.mjs --workers=1 --reporter=line` — **5 passed** in Chromium.
- `node tests/queuebar-informative.spec.mjs reports-evidence/ui-backlog-fix/queue` — **ALL PASS**, both light/dark; includes queue expansion/collapse behavior and screenshots.
- `PORT=8159 node tests/uifix-glass.spec.mjs` — **28 PASS / 1 FAIL**, the pre-existing/conflicting mobile G7 mask assertion above. No assertion/baseline changed.
- `make frontend-gates` — **exit 2**, freeze replay R1 ordering mismatch. `check-glass-unify` passes; `ws.js baseline-color` is an improvement (10→5), not a failure in this run.
- The test runtime and Chromium were installed locally for verification only. No tracked dependency manifest/lockfile change.

## Evidence files

- `queue/Q-queuebar-rows-light.png`, `queue/Q-queuebar-rows-light-closeup.png`
- `queue/Q-queuebar-rows-dark.png`, `queue/Q-queuebar-rows-dark-closeup.png`

These are the real Chromium screenshots of the visible queue bar while busy. No before/after fix screenshots are claimed because no product fix was warranted by reproduced behavior. The image generator's original `evidence/` copies are local test output; the copies above are the deliverable evidence.

## Open items / assumptions

- Complete a separate consumer-by-consumer glass visual audit (especially top-floating surfaces/panels/circular controls) across light/dark and desktop/mobile, using existing tokens and single-source glass recipes.
- Add a real input-edit-state screenshot/manual review for the composer; no defect was inferred from computed styles alone.
- Resolve whether mobile `#chat` mask release is still intended; align the G7 test with the documented stacking-context rationale only after spec review, not by silently changing the assertion.
- Fix/assign the unrelated freeze-replay gate failure outside this UI scope.
- Assumption: the current `main` ref at this isolated worktree is the requested latest-main baseline; it matches the worktree's initial commit.
