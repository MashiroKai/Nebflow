## ADDED Requirements

### Requirement: [FRM-REQ-001] Preserve provider drafts
Validation failure, a rejected save, a missing acknowledgement and a closed connection MUST leave a provider form and its draft open and MUST report the failure. Only the acknowledgement of that very save closes the form, and submission is single-flight. A refused save MUST NOT end the running conversation turn or add an error to the conversation.

#### Scenario: [FRM-SCN-001] Invalid and failed provider save
- **WHEN** validation rejects a form or persistence fails, including a network failure while saving a JeV provider
- **THEN** the form remains open with its inputs intact, the failure is reported, and it can be retried

#### Scenario: [FRM-SCN-008] Unrelated or missing acknowledgement
- **WHEN** an acknowledgement for another request arrives, the connection is closed, or no answer arrives within the timeout
- **THEN** the form remains open with its inputs intact, the save is not confirmed, and it can be retried

#### Scenario: [FRM-SCN-012] Refused provider save
- **WHEN** the gateway refuses a provider save while a conversation turn is running
- **THEN** the form stays open with its inputs intact and reports the failure, the turn keeps running, and no error is added to the conversation

### Requirement: [FRM-REQ-002] Preserve task edits
Todo tag removal MUST be submitted explicitly, and refresh MUST NOT overwrite open task drafts. A draft belongs to one target: editing another task, or starting a new one, MUST show that target's values and never the previous draft.

#### Scenario: [FRM-SCN-002] Clear todo tag
- **WHEN** a user removes an existing todo tag and saves
- **THEN** the update includes a null tag, and an unchanged tag sends no tag change

#### Scenario: [FRM-SCN-003] Refresh while editing
- **WHEN** an unrelated refresh arrives while a task draft is open
- **THEN** the draft remains intact with its value and focus

#### Scenario: [FRM-SCN-009] Change of edit target
- **WHEN** a user starts editing another task, or a new task, or reopens the same task, while a draft form is open or was just dismissed
- **THEN** the form shows the values of that target and not a previous draft, including in the mobile scheduled-task drawer

### Requirement: [FRM-REQ-003] Probe current JeV values
JeV probes MUST use the adapter identifier, current endpoint, current model, and current key or existing key reference without persisting the probe. The model is an optional additive request field; requests without it keep the stored or default resolution. A probe without a chosen adapter MUST NOT be sent.

#### Scenario: [FRM-SCN-004] Probe unsaved JeV provider
- **WHEN** an unsaved named provider is tested
- **THEN** the adapter rather than the instance name and the current form fields reach the probe

#### Scenario: [FRM-SCN-013] Probe without an adapter
- **WHEN** the adapter is not chosen and the test is requested
- **THEN** no request is sent, the user is asked to choose an adapter, and choosing one makes the same form probe with it

### Requirement: [FRM-REQ-004] Honor wakeability
Background delegate controls MUST NOT offer wake when the authoritative state says it is not wakeable. Without an authoritative state, every delegate-frozen reason is wakeable, whichever frame described the row.

#### Scenario: [FRM-SCN-005] Non-wakeable delegate
- **WHEN** a delegate is frozen and wakeable is false
- **THEN** no wake button is displayed

#### Scenario: [FRM-SCN-014] Wake does not depend on the frame source
- **WHEN** a delegate frozen for a provider failure, quota, tool failure or engine fault is described by a live frame without the authoritative state
- **THEN** wake is offered, as it is for the same reason when a snapshot carries the state and says wakeable

### Requirement: [FRM-REQ-005] Scope model category updates
A category update MUST clear only the pins owned by that category whose models differ from the newly effective preferred model, and MUST keep every other pin. A session choice made after the update has priority until that category is edited again.

#### Scenario: [FRM-SCN-006] Scoped category update
- **WHEN** the kernel category model changes
- **THEN** main, other and unknown categories retain their pins, in memory and as the persisted session model, and only differing kernel pins are cleared

#### Scenario: [FRM-SCN-010] Main chain update
- **WHEN** the main category model changes
- **THEN** differing pins of ordinary main conversations and ask sessions are cleared in memory and in the persisted session model, while kernel, dispatcher, node, subagent and slotless background pins remain

#### Scenario: [FRM-SCN-016] Session choice after an update
- **WHEN** a session chooses a model after its category was updated, and another category is then edited
- **THEN** the choice is kept, in memory and as the persisted session model, until its own category is edited again

### Requirement: [FRM-REQ-006] Distinguish background counts
The active badge MUST count active rows independently of retained visible history and structural parents.

#### Scenario: [FRM-SCN-007] Active and retained rows
- **WHEN** a running child and a completed child remain beneath a structural parent
- **THEN** the badge counts one active row while both children remain available

#### Scenario: [FRM-SCN-015] Archived and flow-step children
- **WHEN** archived children are running or restarting, or a flow step is running, beneath a group
- **THEN** each counts as active, while completed archived children and pending flow steps stay listed without counting

### Requirement: [FRM-REQ-007] Keep mobile background chrome
The mobile background conversation SHALL keep a top status capsule, one details control and one bottom-right stop control, with no duplicate close control.

#### Scenario: [FRM-SCN-011] Mobile background conversation chrome
- **WHEN** a background conversation opens on a mobile layout and its details are opened
- **THEN** the status capsule, the details control and a single stop control are present, there is no extra close control, and the opened details can be dismissed
