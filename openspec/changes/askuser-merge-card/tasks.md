# Tasks

- [x] Add the optional `merge` field to `AskItem` (absent for every pre-existing question, so old payloads stay byte-identical).
- [x] Declare `merge` on the `AskUserQuestion` question schema.
- [x] Parse `merge`: refuse it on `secret` questions, force single-select when present.
- [x] Forward the `merge` configuration in the `askUser` event frame.
- [x] Keep the answer on the existing single-string answer wire, serializing the decision JSON verbatim.
- [x] Render the card inside the existing `.option-box` shell with the shared `role="checkbox"` row face (not a native checkbox — the toggle-unification gate rejects that shape in `chat.js`).
- [x] Cover schema, parse, event forwarding and reply serialization with red-first tests.
- [ ] Browser interaction proof (`tests/askuser-merge-browser.cjs`) against the static harness — NOT registered in the static profile and NOT run as part of this landing.
- [ ] Selection gating, add/delete/reorder, target-dependent relation/conflict and the light/dark at 390/1280 screenshot set — NOT implemented as automated tests; the card behaviour rests on a manual browser run.
- [ ] Mutation check (dropping branch order or renaming a field makes a test fail) — NOT performed.
