# Personal profile completion + avatar website entry

- **Viewport:** mobile full-screen page 390 × 844 CSS px (device scale factor 1, dark) for
  `before.png` / `after.png`; desktop profile face 1280 × 800 CSS px for
  `desktop-before.png` / `desktop-after.png`. Real pixel sizes are printed by
  `ffprobe` in the evidence `readings.json`.
- **Scenario:** the same stubbed signed-in device readout on both sides — device
  `displayName` / `email` / `githubLogin` and the self-probe answer are the author's
  real 2026-11-18 readings (captured from this machine's credential store), not invented
  sample values. `before*.png` serves the **base-commit bytes of the four behaviour files**
  this change rewrites (`js/brand.js`, `js/neblink.js`, `js/settingsIdentity.js`,
  `js/mobile/meFace.js` — `git show <base>:<path>`); `after*.png` serves this branch. The
  other two touched files stay at branch state in both legs because their delta is purely
  additive and inert without those four (a `css/mobileLayer.css` rule whose element does not
  exist in the base bytes, and two new locale keys). Only the dashed caption strip is added by
  the evidence harness — it is annotation, not product UI.
- **Fixture or command:** `PROFILE_COMPLETE_BASE=<base-commit> node tests/profile-complete-shots.mjs`
  (zero-port harness: local file serving + Playwright route stubs, page only ever visits
  `http://localhost:1/`). The same judgments run mechanically in
  `tests/profile-complete.spec.mjs`.

These screenshots demonstrate the rendered profile readouts and the avatar entry target.
They do not prove live-account data, a real device's own GitHub binding state, or the live
website's behaviour.

## Real pixel measurements (`ffprobe`, this run)

| File | Bytes | px | frames | frame rate | duration |
| --- | --- | --- | --- | --- | --- |
| `before.png` | 64083 | 390 × 844 | still | — | — |
| `after.png` | 73625 | 390 × 844 | still | — | — |
| `desktop-before.png` | 134345 | 1280 × 800 | still | — | — |
| `desktop-after.png` | 145054 | 1280 × 800 | still | — | — |
| `fields-before-after.gif` | 63956 | 390 × 844 | 2 | 1/1 (avg) | 2 s |
| `avatar-jump.gif` | 86466 | 390 × 844 | 3 | 1/1 (avg) | 3 s |

Both animations are well under 10 MB, so neither needed an MP4 rendition.

## Animated evidence

- `fields-before-after.gif` — the same mobile profile scroll, frame 1 = base bytes, frame 2 =
  this branch: `用户名 未知 → Mashiro`, `GitHub 绑定 未知 → Mashiro`, `Google 绑定` stays
  `未知` and gains the gap sentence naming the account page that owns it.
- `avatar-jump.gif` — frame 1 the mobile profile page at rest; frame 2 the tab that the
  activation opened, addressed `https://mystand.space/` and served by a local stand-in page
  (the harness never contacts the live site — the frame says so itself); frame 3 the profile
  page after the activation, still on its own address with its readouts intact. The new tab is
  intercepted with `context.route`: a `page.route` cannot see a target-`_blank` popup
  (measured, not assumed — the first attempt leaked to the real site).

The animated files, `readings.json` and the break/restore gate logs live in the worktree
manifest staging area (default
`<primary checkout>/.stand/tmp/stand-profile-complete-20261118/evidence/profile-complete/`,
overridable with `PROFILE_COMPLETE_EVIDENCE`).
