## Implementation and acceptance

Implemented, with consumer, route or unit evidence only (not accepted):

- [x] Provider save confirmation with correlated acknowledgement, single flight, draft retention and a reported failure, including a refused save that leaves the running turn and the conversation alone (gateway echo and refusal naming covered by a Scala test that drives the real `updateConfig` handler; consumer tests on Chromium and WebKit).
- [x] Todo tag clearing, including the frame a real todo list sends, and todo/scheduled draft retention, including target changes, focus and the mobile scheduled drawer (consumer tests on Chromium and WebKit).
- [x] JeV probe parameters and the no-adapter case (consumer tests on the real provider form; route-level Scala test with a local upstream).
- [x] Category pin ownership, including main-conversation pins, the persisted session model and a session choice made after an update (Scala route and unit tests).
- [x] Active versus retained background counts including archived and flow-step children, wakeability independent of the frame source, and the mobile background chrome (consumer and unit tests; Chromium and WebKit).

Acceptance still required on a real isolated instance:

- [ ] Provider confirmation over the real WebSocket, including reconnect, a rejected config and concurrent saves.
- [ ] Todo and scheduled drafts, focus and persistence in a real instance, including the mobile scheduled drawer.
- [ ] JeV form through the authenticated route against a mock upstream, including secret handling.
- [ ] The model used by the next turn of a session after a category update on a running instance.
- [ ] Mobile and desktop light/dark screenshots, reduced motion, long background panel scrolling and manual GUI acceptance.
