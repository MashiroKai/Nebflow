# Tasks

- [x] Let a session's explicit model override its role setting until following is restored.
- [x] Keep session choices across a role-setting save, reload and restart (override map is re-applied to the reload).
- [x] Send only the explicit session model for both streaming and non-streaming requests.
- [x] Report an unavailable selected model with its reason instead of selecting another model.
- [ ] Verify the actual outgoing model against an isolated fake provider across follow, override, restore and failure — NOT executed in this batch.
- [ ] Session header and delegate-row follow/override affordance with a follow-again action — NOT verified in this batch.
