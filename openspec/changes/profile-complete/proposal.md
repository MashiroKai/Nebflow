# Complete the personal profile readings and give the avatar a website entry

## Why

The author opened the personal profile on the phone (2026-11-18) and read the account group
verbatim: `用户名 → 未知`, `昵称 → Mashiro`, `邮箱 → kaimashiro@qq.com`,
`GitHub 绑定 → 未知`, `Google 绑定 → 未知`. The instruction was
「我们个人主页里的信息很多都没获取全 比如github绑定信息 这些都[需要]补全
另外在个人主页再次点击头像要能跳转官网」.

The readings were not missing — two of them were **already in the client and never rendered**,
and one was never probed at all:

- **Username.** The only authoritative read path is the self-probe
  (`GET /api/users/search?q=<email>` with `relation_status === 'self'`), and that probe was
  started only when the settings identity card or the desktop profile face was opened. The
  mobile profile page (`js/mobile/meFace.js`) reads `getIdentityReadout()` but never started
  it, so `selfState` stayed `idle` and the username row could only ever render 未知.
- **GitHub binding.** `GET /api/neblink/status` has always returned `device.githubLogin`
  (the server's `github_username`, persisted into this device's `device.json` at enrollment),
  and `js/neblink.js` deliberately dropped it on the floor because no consumer existed.
  It was a "fetched but not rendered" gap, not a missing upstream field.
- **Google binding.** There is genuinely no readable source on this side, and this change
  records exactly which layer lacks it instead of leaving an unexplained 未知.

The avatar had no behaviour at all on the profile page: no click listener, no zoom, no
expansion. The website entry is therefore an addition, not a replacement.

## What Changes

- **Start the identity probe from the profile page itself.** `js/settingsIdentity.js` exports
  one entry point, `ensureIdentityLive()` (probe + status subscription, idempotent and
  single-flight), and `js/mobile/meFace.js` calls it when the page is built and on every
  repaint. Username stops being permanently unknown on mobile.
- **Render the GitHub binding that was already arriving.** `js/neblink.js` maps
  `device.githubLogin` into the device readout, and `getIdentityReadout()` exposes it as
  `github`. Both profile surfaces render it.
- **Explain every remaining unknown.** A single text source,
  `settingsIdentity.bindingGapNote(id)`, produces the per-field gap sentence; the mobile page
  (`js/mobile/meFace.js`, `.mf-me-gapnote`) and the desktop face
  (`js/settingsIdentity.js`, `#settings-profile-bindnote`) both render that one string. The
  Google row names who maintains the reading (the website account page) and where it is
  absent (this machine's account service and the sign-in credential).
- **Give the profile avatar the website entry.** The mobile profile card's avatar frame
  becomes `<a href="https://mystand.space" target="_blank" rel="noopener noreferrer">`
  (`js/brand.js` grows `getHomeUrl()`, derived from the same injected profile URL as the
  other website entries, never from the display-only `brand.domain`). Geometry, markup and
  the two-state avatar painting are unchanged.
- **Update the judgments that this reverses.** `tests/profile-multidevice.spec.mjs`,
  `tests/mobile-topbar.spec.mjs` (T3) and `tests/profile-settings-batch-acceptance.spec.mjs`
  (⑤) asserted "unknown GitHub binding" and "zero `<a href>`" for the mobile profile page;
  they are narrowed to the new product — GitHub is a real reading, and the mobile profile
  page has exactly one outbound anchor, the avatar, while the settings identity card and the
  desktop profile face stay anchor-free.

### Scope

- Frontend: `js/neblink.js`, `js/settingsIdentity.js`, `js/mobile/meFace.js`, `js/brand.js`,
  `css/mobileLayer.css`, locales `zh-CN` / `en`.
- Tests: `tests/profile-complete.spec.mjs` (new) plus the three narrowed suites above, and
  `tests/profile-complete-shots.mjs` (new, before/after evidence).
- Backend: **none.** The reading this batch renders was already in the status payload.

### Non-goals

- **A Google (or any other provider) binding read.** No layer reachable from this repository
  carries it: the sign-in id_token's claims are `sub / email / name / username /
  preferred_username / picture` with no `identities` and no provider claim; the social
  provider identity is discarded after the OIDC round trip
  (`AuthRoutes.beginPkceLogin`); the upstream user profile contract carries only
  `username` / `display_name` / `avatar`; and the account service that owns bindings is the
  website, which this change does not enter. The row therefore stays 未知 and says why.
- **In-app editing of any identity field.** Still no write endpoint (the 2026-11-02 probe:
  `PUT /api/users/me/avatar`, `/api/users/me`, `/api/users/me/bindings` → 404/502).
- **Editing `openspec/changes/profile-multidevice`.** Its proposal records "GitHub / Google
  binding reads — all still absent upstream" as of that batch; that sentence is superseded
  here rather than rewritten there.
- **Landing, pushing or tagging.** The branch is handed back; landing is a separate decision.

## Placement (why these files)

- `js/settingsIdentity.js` — already the single readout (`getIdentityReadout`) for the
  settings card, the desktop face and the mobile page, and already the owner of the self-probe.
  The probe entry point and the gap text belong next to the reading they describe; a second
  probe or a second gap sentence in `meFace.js` would be a second source of truth.
- `js/neblink.js` — the status normalization is the only place `device.*` is mapped; adding
  the `githubLogin` mapping there keeps the raw wire shape out of every consumer.
- `js/mobile/meFace.js` — the mobile profile body; the avatar anchor and the gap row are
  page content.
- `js/brand.js` — the only place a navigable website URL is decided; `brand.domain` is
  display-only (it ships the `neblink.example` placeholder), so the origin is derived from
  the injected profile URL exactly like the two existing website entries.

## Verification limits

`tests/profile-complete.spec.mjs` drives a stubbed REST/WS surface in a real browser at
390 × 844 (mobile full-screen page) and 1280 × 800 (desktop face): it proves the rendered
readings, the gap sentence, the anchor's three attributes, that a click opens a new tab at
`https://mystand.space/` while the profile page does not navigate, and that no editable
control appears. `tests/profile-complete-shots.mjs` proves the before/after difference against
the base-commit bytes of the four changed files under the same fixture. Neither proves a live
account's real binding state, the live website, or the device's own `githubLogin` freshness —
`device.githubLogin` is the last value the server sent at enrollment, and this batch does not
re-verify it against GitHub. That boundary is stated in the delivery report as "待真机亲验".
