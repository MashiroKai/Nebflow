## Implementation

- [x] Give the identity readout one live entry point: `ensureIdentityLive()` in
      `js/settingsIdentity.js` (probe + status subscription, idempotent, single-flight), and
      call it from the mobile profile page (`js/mobile/meFace.js`) so the self-probe that
      produces the username actually starts there.
- [x] Map `device.githubLogin` in the status normalization (`js/neblink.js`) and expose it as
      `github` from `getIdentityReadout()`; render it on both profile surfaces.
- [x] Add the single gap-text source `bindingGapNote(id)` and render it on the mobile profile
      page (`.mf-me-gapnote`) and the desktop profile face (`#settings-profile-bindnote`),
      with the Google row naming the layer that owns it.
- [x] Make the mobile profile card's avatar a website entry:
      `<a href="https://mystand.space" target="_blank" rel="noopener noreferrer">`, backed by
      `getHomeUrl()` in `js/brand.js` (origin of the injected account-page URL, never
      `brand.domain`). Tag-only change; classes, geometry and the two-state paint untouched.
- [x] Narrow the judgments this reverses: `tests/profile-multidevice.spec.mjs`,
      `tests/mobile-topbar.spec.mjs` T3, `tests/profile-settings-batch-acceptance.spec.mjs` ⑤
      — GitHub is a real reading, and the mobile profile page has exactly one outbound anchor
      (the avatar), while the settings identity card and the desktop profile face stay
      anchor-free; the outbound-link audit in ⑤ is scoped to those two surfaces instead of the
      whole document.

## Verification and acceptance

- [x] New browser spec `tests/profile-complete.spec.mjs` (stubbed REST/WS, zero instance, zero
      protected port): 390 × 844 mobile full-screen page and 1280 × 800 desktop face; asserts
      the rendered readings, the gap sentence, the anchor's `href` / `target` / `rel`, that a
      click opens a new tab at `https://mystand.space/` while the profile page does not
      navigate, that no editable control appears, and zero console errors — 4/4 passed.
- [x] Narrowed suites re-run: `tests/mobile-topbar.spec.mjs`, `tests/profile-multidevice.spec.mjs`,
      `tests/settings-identity-card.spec.mjs` — 17/17 passed.
- [x] Before/after evidence: `tests/profile-complete-shots.mjs` renders both viewports twice —
      "before" serving the **base-commit bytes** of the four changed files (`git show
      <base>:<path>`), "after" serving this branch — under the author's real 2026-11-18
      readout fixture; produces `delivery/profile-complete/{before,after,desktop-before,
      desktop-after}.png`, `screenshots.md`, and the animated evidence
      (`fields-before-after.gif`, `avatar-jump.gif`) — all judgments passed, zero console errors.
- [x] Break/restore self-proof of the judgment: reverting the `ensureIdentityLive()` call makes
      the new spec red (exit 1); restoring it makes it green (exit 0).
- [x] Static gates `check:static` / `check:frontend` run on the branch tip with the same
      pre-existing failures as the base commit and no new ones (see the delivery report for the
      exact exit codes and the named pre-existing reds).
- [ ] Live proof on a real account and device: that this device's `githubLogin` is still the
      account's current GitHub handle, and that the live website reaches the account page from
      the profile avatar. Requires a real instance and a real account — stated as
      "待真机亲验" in the delivery report, not claimed here.
- [ ] Author word-for-word review of the batch. Not implied by these edits.
- [ ] Landing / pushing / tagging. Explicitly outside this batch's authority.
