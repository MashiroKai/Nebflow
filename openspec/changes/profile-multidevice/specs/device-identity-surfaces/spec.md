## Purpose

The console reaches several devices but used to present itself as one. Device-scoped things (credentials, sign-out) and console-scoped things (a browser session) must be named as what they are, on the surface that owns them: the personal profile. This capability fixes the vocabulary and the invariants for any Stand surface that lists devices.

## Requirements

### Requirement: [DIS-REQ-001] A multi-device surface names the device it acts on

A surface that can show more than one device SHALL NOT present an account-wide session control without saying which device it acts on. Every per-device action SHALL carry that row's own device identity and nothing else, and signing out one device SHALL never terminate another device's session.

#### Scenario: [DIS-SCN-001] Per-device sign-out is device-scoped

- **WHEN** the user activates the sign-out key of one row in the device list
- **THEN** the request carries exactly that row's device id and no other device's id
- **AND** the other rows keep their readings verbatim
- **AND** acting on a peer row does not invoke the local end-session chain, and acting on the current-device row does not invoke the device-scoped endpoint

#### Scenario: [DIS-SCN-002] A capability that does not exist answers truthfully

- **WHEN** a device-scoped action is requested for a device this client cannot reach
- **THEN** the gateway answers an explicit failure code instead of a fabricated success
- **AND** the row shows a visible reading naming where the action can actually be taken, and never reports success

### Requirement: [DIS-REQ-002] One readout and one markup source per device list

A device list rendered by more than one surface SHALL be built from one readout and one markup source. Two lists assembled from two reads SHALL be treated as a defect, not as an implementation detail.

#### Scenario: [DIS-SCN-003] Every live list follows one status tick

- **WHEN** the upstream device readout changes while more than one device list is mounted
- **THEN** every mounted list repaints to the new reading in the same tick
- **AND** no list is left holding the reading it was built with

#### Scenario: [DIS-SCN-004] The list is built by the shared light-DOM builder

- **WHEN** the device list markup is produced
- **THEN** it is produced once, by `js/profileDevices.js`, through the shared light-DOM builder with text written as text
- **AND** consumers mount that node instead of assembling their own markup

### Requirement: [DIS-REQ-003] Status is three-valued and unknown is never guessed

A device readout SHALL render online, offline, or unknown. A missing or unreadable value SHALL render as unknown; it SHALL NOT be rendered as offline and SHALL NOT be replaced by a guessed value.

#### Scenario: [DIS-SCN-005] An unreadable field renders as unknown

- **WHEN** the upstream status omits `online`, `lastSeen` or `platform` for a device
- **THEN** that field renders the explicit unknown reading
- **AND** no relative-time or online value is fabricated for it

### Requirement: [DIS-REQ-004] The personal profile is one page

The personal profile SHALL be one page. Read-only identity SHALL name the place where identity is edited instead of offering an in-app control that cannot write, and the profile SHALL NOT expose a subpage whose only content is a repeat of the same readout.

#### Scenario: [DIS-SCN-006] Retired subpages are unreachable, not hidden

- **WHEN** the personal profile is inspected
- **THEN** the retired subpage ids are absent from the navigation table, so no push reaches them
- **AND** no edit control, no external navigation pretending to be the edit path, and no empty action entry exists on the profile
- **AND** the guidance naming the editing location is plain text

### Requirement: [DIS-REQ-005] Console sessions and device credentials are different domains

A change touching sessions SHALL state which domain it touches. A browser console session may be transparent to which device it reaches while credentials and sign-out stay device-scoped; the console SHALL NOT be described as belonging to one device.

#### Scenario: [DIS-SCN-007] The scopes are stated with their mechanism

- **WHEN** the device list and its per-device action are described
- **THEN** the writing names where the device identity comes from and what the sign-out scope is, with source locations
- **AND** console-session transparency is named as the boundary of the change rather than left implicit
