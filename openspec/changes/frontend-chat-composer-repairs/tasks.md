## Implementation and acceptance

Implemented, with consumer, route or unit evidence only (not accepted):

- [x] FCC-REQ-001/FCC-SCN-001/002 history keeps attachments: store, ledger, vault keys, recall, caps, reload and lost-payload cases (Playwright consumer tests on Chromium and WebKit, node unit tests for the ledger rules).
- [x] FCC-REQ-002/FCC-SCN-003/004 one failed item retries on its own: shared failure face, stacked layer, conversation card, input thumbnail (Chromium; the stacked-layer case is skipped on WebKit because Playwright's WebKit cannot load blob: images while request interception is active).
- [x] FCC-REQ-003/FCC-SCN-005 reply attachment chips open their preview, with remove/retry/secret cases (Chromium and WebKit).
- [x] FCC-REQ-004/FCC-SCN-006 Ask history is never dimmed, light and dark (Chromium and WebKit); three older oracles rewritten.
- [x] FCC-REQ-005/FCC-SCN-007 input-method Enter pinned with the browser's own composition pipeline for the main composer, the Ask dock and the friend, group and device composers (Chromium only).
- [x] FCC-REQ-006/FCC-SCN-008 single return entry for the main conversation and for the Agent/conversation pair (Chromium and WebKit).
- [x] FCC-REQ-007/FCC-SCN-009/010 desktop conversations in the main chat area, header switch, avatar flip and return, per-conversation draft and scroll, late response discarded, scoped header search (Chromium and WebKit).
- [x] Independent-review follow-up (Chromium and WebKit, except where noted): a conversation's local copy is never written for a conversation that is not shown and an unsent text returns to its own conversation (FCC-SCN-010); the collapsed front layer retries on a real pointer click and both faces recover (FCC-SCN-003; Chromium only); reply chips have no nested interactive control (FCC-SCN-005); an Agent session selection or creation brings the Agent view back (FCC-SCN-012) and an Escape aimed at another layer or an image paste does not reach the conversation or the hidden Agent composer (FCC-SCN-015); the avatar flip runs to its end and keeps its tooltips (FCC-SCN-013); the retry of a lost connection never does nothing silently (FCC-SCN-014).
- [x] Targeted older-spec regression at consumer level: the older specs that exercise the desktop conversation window, Ask, history, media and return pills were run on this tree and on an export of the recorded baseline; their failing checks are the same on both (see the report for the list and for the oracles that were adapted).

Acceptance still required:

- [x] FCC-SCN-011 real desktop light/dark screenshots and a mobile regression on a real isolated instance, reviewed by the user; the desktop host is not merged before that confirmation. (author confirmed 2026-11-15 没问题, prior to merge; evidence .zcode/fcc-scn-011 + 工件卡)
- [ ] A real input method on WebKit/Safari, a real soft keyboard on mobile and a real friends gateway, including its WebSocket echo of a sent message (the consumer tests use a mocked friends surface, a mock WebSocket and Playwright's WebKit).
- [ ] Real isolated instance resources check of the desktop conversation window, and the older specs that could not run here (they need a fixture server or write to fixed paths outside the isolated root).
- [ ] A reload/quota check of the history vault on real browsers (Safari private mode, storage pressure) and an owner decision on the stored-payload caps.
