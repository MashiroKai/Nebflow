# Tasks

- [x] Project `ScheduledTask(kind="todo")` read-only, deciding completion by `completedAt`.
- [x] Write new cards only as `ScheduledTask` todos marked with project `__decision_card_mvp__`; exclude those rows from the legacy inbox surfaces.
- [x] Count entries by visible, undecided unique id; the entry layout itself belongs to `ui-unify-panels`.
- [ ] Wire card replies — NOT implemented; no reply may be reported as confirmed.
- [ ] Project-matched badges — NOT implemented (no authoritative current-project context; `project` is a free-text tag).
- [ ] Formal field contract and one-shot migration — NOT decided; the summary/content encoding is temporary.
- [ ] Summary 40–100 characters / content at most 300 characters is provisional Unicode code-point counting, pending the author's field sheet — NOT final.
