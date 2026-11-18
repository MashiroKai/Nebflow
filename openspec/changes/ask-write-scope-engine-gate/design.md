# ask-write-scope-engine-gate — design

## Goal and acceptance

Author instruction (verbatim, 2026-11-18 21:49): "约束一下 canvas 的 ask 它只有它所对应
的文档的编辑能力 其他复杂任务要转交给主会话 同时也要在引擎上限制它编辑其他文件".

Acceptance: (1) the ask write face is narrowed to exactly one document, proven by an
over-permission matrix; (2) work beyond that one document is handed to the main session as a
structured request that visibly reaches it; (3) the restriction is enforced at the engine's
authorization point, not in prompt prose; (4) the checkout gate that guards all of this is
proven to detect, by injecting the bypass back and restoring it; (5) no new red versus the
baseline; (6) the gate skips with an explicit exit code outside a Stand source tree; (7) the
handed-off state is visible in the UI in desktop-dark, desktop-light and a real full-screen
mobile viewport.

## Decisions

### D1 — One verdict point, and it compares identity, not spelling

The previous target gate asked a string question (`normalized != target`) and therefore
answered the wrong one. Two distinct questions were fused:

- *which spellings of the bound document are legitimate* — relative forms, `.`, `..`, `./`,
  symlinks, hard links, alias mounts, case variants;
- *which targets are out of scope* — every other file.

String equality answers "the second file" correctly but also kills legitimate spellings of the
first, and it does so without a why or a way out. The new verdict
(`core/ask/AskPathBoundary.scala`) normalises first (relative spellings resolved against the
bound document's directory, `.`/`..` consumed), then compares identity
(`toRealPath()` + `Files.isSameFile`). Consequences: `..` stops being a blacklisted token and
becomes a consistent normalisation step; equivalent spellings are accepted; every other target
is refused. `AskEditReviews.capture` and `.applyChange` route **only** through this point — the
gate forbids re-deriving a path spelling or re-introducing a string predicate inside the gate's
block.

*Alternative rejected:* keep string equality and add a `..` allow-list. Blacklisting spellings
is exactly the failure mode that produced the incident — the bypass surface is closed by
identity, never by a wider word list.

*Residual risk:* this is an application-level identity check, not an OS sandbox; the window
between verdict and write is covered by the existing snapshot/stale discipline, not by this
change.

### D2 — A refusal carries the route out

Refusal text is generated at one place (`AskPathBoundary.refusal`) in three parts: *reason* +
"本会话只能编辑它对应的那份文档" + the sanctioned route (hand it to the main session via
`send_message_to_main_chat`). An out-of-scope request is not a failure but a *wrong channel*;
putting the route in the refusal converts a wall into a lane change.

### D3 — Every verdict is audited, and the shell is not a second write channel

`AskWriteAudit.record` appends one JSON line per verdict (time, identity, tool, requested
spelling, normalised path, result, reason) to `<dataRoot>/ask/audit.jsonl` and mirrors it on the
`nebflow.ask.audit` log channel. Denials matter more than allows: a refused attempt is the only
evidence that the boundary is being probed. The audit sink fails open **only** toward refusal
(a failing sink logs a warning; it never turns a denial into a write). The Bash leg
(`AskBashFace.runAskBash`) audits every refusal with `tool = "Bash"`, so the query-only shell
cannot become an unrecorded second write path.

### D4 — The handoff is structured, and it is visible

`send_message_to_main_chat` moves from free text to four fields — `task` (required),
`paths`, `reason`, `expected` — over the existing main-session channel; `question` stays as a
compatibility alias. One encoding (`AskHandoffWire`) serves the human-readable injected body,
the machine-readable injection metadata, and the `askHandoffAck` panel frame. The Ask dock
renders a dedicated status row ("已转交主会话" + summary) instead of silently consuming the ack:
a handoff the user cannot see is a handoff the user cannot trust.

### D5 — The tool inventory is derived, never hand-copied

`buildToolList` already sends the model the real schema and description of every granted tool.
A second hand-written list in the ask prompt is a second source of truth and will drift. The
prompt's `[Tools]` section is therefore generated from the registry
(`AskContextAssembler.toolListSection()` over `ToolRegistry.TOOL_MAP`, intersected with
`AskSessions.AllowedTools`), and carries two hard constraints: *this session edits only its
bound document* and *complex tasks are handed to the main session*. Per-identity tool-face
readings reuse the single enumeration point introduced by the peer batch
(`src/test/scala/nebflow/agent/ToolFaceReadings.scala`, `has(label, tool)`); this change adds no
second enumerator.

### D6 — The guard is a checkout-only static gate

`scripts/check-ask-write-scope.mjs` is registered in the existing `check:static` profile
(no second harness). It asserts: the single verdict point exists and normalises by identity;
the gate routes only through it and audits every verdict; the ask authorization face carries no
direct write tool and the shell leg passes through the query-only validator before any local or
remote routing (`AgentSessionExecution.buildAllowedToolSet` still early-returns the closed ask
face); the handoff schema requires `task`, declares `paths`/`reason`/`expected` and emits a
panel frame; the prompt derives its list and states both constraints. Exit codes are
`0` pass / `1` violations / `2` SKIP (root is not a Stand source tree). Detection power is
asserted by `scripts/tests/check-ask-write-scope.test.mjs`, which feeds the gate the injected
bypasses (string equality, blanket `..`, `startsWith("/")`, removed audit call, removed
single-point routing, hand-written tool list) and requires each to be reported.

## Integration points (declared, not silently edited)

- `core/ask/AskSessions.scala` is a landing point shared with the peer Card batch
  (#2585): this batch only reads `AllowedTools` / the ask early-return; the peer's
  `AskExclusiveTools += "Card"` line is untouched. Landing order is peer first, this batch
  second.
- `openspec/changes/canvas-ask-restricted-edit` (active, in flight) still states the old
  wording "reject traversal and symbolic links" for CAE-REQ-001/CAE-SCN-002. This change
  declares the revision in its own delta with new ids (CAE-REQ-101..104 / CAE-SCN-101..108)
  rather than editing the peer's scenario ids, which would collide across active changes. When
  that change lands, CAE-SCN-002 must be re-worded to "refuse targets that are not the bound
  document" (identity, not spelling).
- Pre-existing reds observed in the baseline and reproduced unchanged with the same names:
  `AskGateSpec` (`/vault/paper.pdf` was made askable by another in-flight batch) and
  `AskToolFaceSpec` F1/F3 (asserts the pre-Card ask tool set). This batch revises only F5,
  whose prompt contract it owns.

## Rollback

Reverting the change restores the string-equality verdict and the free-text handoff; the audit
log and the gate are additive and inert once removed. No data migration, no deployment, no
remote push.
