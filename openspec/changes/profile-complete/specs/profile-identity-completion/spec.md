## ADDED Requirements

### Requirement: [PIC-REQ-001] The identity read is started by the surface that shows it

A surface that renders the account identity readout SHALL start the identity read itself, and the read path SHALL be one: the self-probe that self-certifies with `relation_status === 'self'`. A surface that renders a reading it never started SHALL be treated as a defect, and the probe SHALL be idempotent and single-flight so that several mounted surfaces share one read.

#### Scenario: [PIC-SCN-001] The profile page reads the username it displays

- **WHEN** the personal profile page is built on a signed-in device whose account is resolvable
- **THEN** the username row is painted from the self-probe result and shows the account name, not the unknown reading
- **AND** opening the profile page starts that probe without the settings face being opened
- **AND** the page shows no editable control, because no write endpoint for identity exists

### Requirement: [PIC-REQ-002] The GitHub binding renders the login the server sent

A binding reading that the account service already returns SHALL be rendered by the surfaces that show it. The reading SHALL be labelled by provenance — the GitHub account name this device was enrolled with — and SHALL NOT be presented as a live authorisation.

#### Scenario: [PIC-SCN-002] GitHub renders a real login; an absent one is unknown

- **WHEN** the device readout carries a GitHub login for this account
- **THEN** both profile surfaces render that login as the GitHub binding
- **AND** when the readout carries no login, the row renders the unknown reading and the gap sentence names this device's enrollment value as the source, never a fabricated login

### Requirement: [PIC-REQ-003] An unreadable binding names the layer that owns it

A binding this machine cannot read SHALL render the unknown reading together with a sentence naming the layer that owns the reading and where the value can be read. An unexplained unknown SHALL be treated as an incomplete delivery, and the sentence SHALL come from one source shared by every identity surface.

#### Scenario: [PIC-SCN-003] The Google row explains its own absence

- **WHEN** a profile surface lists a binding for which this client has no source
- **THEN** the row renders the unknown reading and a gap sentence naming the account page that owns it
- **AND** the same sentence is produced by the shared gap-text function on both surfaces, and the signed-out state produces no gap sentence because the page already reads as signed out

### Requirement: [PIC-REQ-004] The profile avatar opens the official website in a new tab

The personal profile's avatar SHALL be an entry to the official website. It SHALL open in a new tab with `rel="noopener noreferrer"`, SHALL NOT navigate the profile page away, and SHALL NOT displace any behaviour the avatar already had. The destination SHALL be derived from the injected website URL, never from a display-only placeholder.

#### Scenario: [PIC-SCN-004] The avatar carries the website entry

- **WHEN** the personal profile page is inspected
- **THEN** the avatar element is an anchor whose `href` is the official website origin, whose `target` is `_blank`, and whose `rel` carries both `noopener` and `noreferrer`
- **AND** geometry, classes and painting of the avatar are unchanged from the anchor-free form, and no second outbound anchor appears on the profile page

#### Scenario: [PIC-SCN-005] Activating the avatar leaves the profile page in place

- **WHEN** the user activates the avatar on the personal profile page
- **THEN** a new tab opens at the official website while the profile page stays where it was
- **AND** the profile page's own address is unchanged by the activation

### Requirement: [PIC-REQ-005] A binding reading states its chain and its boundary

A change that renders or refuses a binding reading SHALL state, in writing, where that reading comes from layer by layer, and SHALL name the layer that owns any reading it cannot reach. A binding it cannot read SHALL NOT be invented, guessed, or silently left as the unknown reading.

#### Scenario: [PIC-SCN-006] The chain and the boundary are written down

- **WHEN** the delivered readings and the still-unknown ones are described
- **THEN** the writing names each field's source with file:line and says which layer owns the ones this client cannot read
- **AND** the unreadable binding's boundary (which service owns it, and why this client has no source) is stated rather than left implicit
