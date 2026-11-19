# Design

The decision-card surface is a read-only projection of `ScheduledTask(kind="todo")`: completion is
decided by `completedAt`, and no migration, dual write, entry switch or entry deletion happens.
`src/main/resources/web/js/scheduled-task.js` holds the legacy-surface exclusion
(`t.project !== '__decision_card_mvp__'` around lines 154-155 and 1701) and
`src/main/resources/web/js/todoStore.js` mirrors the same exclusion (line 313). New cards are
written only as `ScheduledTask` todos marked with project `__decision_card_mvp__`, the name is the
title and `detail` is a JSON string holding `summary`/`content`; the legacy inbox will therefore
show those rows and their encoded content, which is not a formal field contract. `project` is a
nullable free-text tag, not a project id, so project-matched badges are out of scope. Card replies
are not wired and must not be reported as confirmed; the mobile single-card browse and the desktop
list are the only interaction surfaces in this change.
