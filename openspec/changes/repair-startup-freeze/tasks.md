# Tasks

- [x] Reproduce orbit-less terminal transition feedback with bounded observer delivery.
- [x] Exclude incomplete or absent orbits and preserve valid orbit lifecycle coverage.
- [x] Reproduce unchanged historical task snapshot notifications and suppress redundant updates.
- [x] Run focused Node regressions and frontend type/component checks.
- [x] Verify all 625 web resources on an isolated real Stand server.
- [x] Reproduce first-snapshot terminal reconciliation refreshing root indicators 1,070 times.
- [x] Batch root indicator refreshes and skip closed dropdown list construction.
- [x] Preserve merged-carrier opening order and current-record rendering on open.
- [x] Reproduce and repair empty single-line attachment observer feedback.
- [ ] Complete full authenticated browser acceptance with the user's restored workspace and original browser state.

Follow-up evidence (2026-11-15): the user supplied Safari `Network process crashed`; a read-only WebContent sample recorded 17.8 GB footprint and 31.1 GB peak. A bounded authenticated browser probe identified repeated allocation in `notifyTasks -> main.js terminal loop -> updateBgAgentIndicator -> renderBgAgentDropdown -> bgTaskRowHtml`. The live initial history frame was 50 messages / 837,008 bytes, not the entire transcript. Focused regression tests passed 25/25; checkJs reported zero errors; component-contract passed; an isolated real server served all 625 resources with HTTP 200. The protected live server still serves its pre-fix classpath copies, so source edits are not activation. No host restart, installation, commit or merge was performed. The initial official OpenSpec download timed out. During merge preflight, an alternate HTTP client retrieved the pinned 2.0.0 archive, its SHA-256 matched `openspec/toolchain.json`, and `check-openspec.mjs --binary` passed strict validation for all 16 items. A controlled browser consumer test on the isolated server retained all 1,070 synthetic records: closed list mutations=0 and rows=0; after opening, rows=1,070 and reported total=1,070 (248 ms including a 30 ms settle wait in this sample). Text input remained usable without sending a message. The empty nowrap attachment strip allowed its timer to fire. The full frontend profile passed 22/22 and diff-scoped SDD linkage passed. These are controlled consumer results, not full authenticated post-fix acceptance of the original Safari state, which remains unverified.

A native browser probe confirmed the repaired orbit-less transition returns with zero tracked/coasting nodes and its timer fires. Full-page acceptance remains unverified: the available in-app browser encountered guest detachment and stylesheet-load failures during the real-data replay. These failures must not be reported as a pass.
