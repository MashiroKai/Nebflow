## ADDED Requirements

### Requirement: [AMC-REQ-001] Merge question contract on the ask wire

`AskUserQuestion` SHALL accept an optional `merge` object on a question and SHALL declare it in the
question schema. The object carries `target`, `targets`, `candidates`, `operations` and `branches`;
each branch carries `name`, an optional `note`, `selected` and per-target `relations`. The field MUST
be absent from every pre-existing payload: a question without `merge` SHALL serialize exactly as
before, and this change SHALL NOT introduce a second answer wire.

#### Scenario: [AMC-SCN-001] Schema declares the merge question

- **WHEN** the `AskUserQuestion` input schema is inspected
- **THEN** the question item declares a `merge` object property next to `question` and `options`

#### Scenario: [AMC-SCN-002] Absence of merge keeps the payload unchanged

- **WHEN** a question with no `merge` field is built into an ask event frame
- **THEN** the serialized frame is byte-identical to the pre-change shape, with no `merge` key emitted

### Requirement: [AMC-REQ-002] Parse rules for the merge question

The parser SHALL retain the merge object verbatim, SHALL clear `multiple` on a question that carries
`merge` (the answer is one JSON object, not a set of option labels), and MUST refuse merge on a
`secret` question — a secret question never renders a card body, so a merge payload there SHALL be
dropped rather than stored.

#### Scenario: [AMC-SCN-003] Merge survives parse and forces single-select

- **WHEN** a question carrying both `multiple: true` and `merge` is parsed
- **THEN** the parsed item keeps the merge object unchanged and reports `multiple` as false

#### Scenario: [AMC-SCN-004] Secret questions do not carry merge

- **WHEN** a question carrying `secret: true` and `merge` is parsed
- **THEN** the parsed item has no merge object and stays a secret question

### Requirement: [AMC-REQ-003] Merge configuration reaches the frontend through the ask event

The ask event frame SHALL forward the merge configuration to the connected client, and the answer
SHALL return through the existing hub and answer bridge with no new channel, no new port and no new
wire type.

#### Scenario: [AMC-SCN-005] Merge configuration is forwarded in the ask event

- **WHEN** an agent asks a merge question
- **THEN** the emitted `askUser` frame carries the merge configuration at `items[0].merge`

#### Scenario: [AMC-SCN-006] Merge answer reaches the model through the hub and answer bridge

- **WHEN** the client answers a merge question with a decision JSON string
- **THEN** that exact string is injected as the user reply on the model's next turn, over the
  pre-existing answer path

### Requirement: [AMC-REQ-004] Merge answer is one existing answer string

The answer SHALL be the single answer string per question that the wire already carries, holding
`{"decision":"approve|reject|supplement|defer","branches":[{"name":"…","note":"…"}],"target":"…","text":"…"}`,
with branches in displayed order. Approve SHALL require at least one selected branch and reject MUST
require non-blank text. The card SHALL NOT execute Git, SHALL NOT read repository state, and SHALL
render a missing relation for the selected target as unknown rather than inferring one. The tool
SHALL NOT gain merge authority from this change.

#### Scenario: [AMC-SCN-007] Decision JSON round-trips verbatim

- **WHEN** an approve answer JSON with two ordered branches is formatted for the model
- **THEN** the formatted answer is that exact JSON string, branch order preserved

## Boundaries

The browser interaction proof (`tests/askuser-merge-browser.cjs`) drives the real frontend modules
against a static harness with a mocked socket; it is NOT registered in the static profile and was NOT
run as part of this landing. Selection gating, add/delete/reorder, target-dependent relation rendering
and the four light/dark screenshots are consequently NOT covered by an automated gate here — see
`tasks.md` for the unclosed items. This change does not merge, deploy or push anything.

## Amendment 2026-11-21 (author review 17:56)

- **Branch field set (legal keys).** A `branches[]` entry accepts only: `name` (required), `note`, `selected`, `relations`. A relation accepts only `status` and `conflict`. Any other key is outside the contract; the card ignores it and does not render it.
- **Status label fail-silent.** The card renders a status label only when `relations[target].status` is a non-blank string. A missing, empty, or non-string status renders no label. This supersedes the earlier "render unknown" wording in AMC-REQ-004: the card no longer shows the text 未知 for missing relations.
- **No receipt preview.** The card does not render a receipt/answer preview. The JSON answer goes to the calling agent only.
- **Add branch.** Add works by button and by Enter. An empty name or a duplicate shows a visible hint and keeps the typed draft. A successful add clears the draft and refocuses the input.
