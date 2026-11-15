## ADDED Requirements

### Requirement: [CB-REQ-001] Share the official terminal lockup

Every decorative CLI logo SHALL use the official horizontal Stand symbol-and-wordmark lockup. Installer and runtime image and character payloads SHALL come from the same generator. Independent standalone-S artwork SHALL NOT be rendered.

#### Scenario: [CB-SCN-001] Verify artwork parity

- **WHEN** the logo parity checker runs
- **THEN** the seven runtime payloads match both installers and generated vector artwork
- **AND** production launcher and CLI sources contain no independent logo artwork

### Requirement: [CB-REQ-002] Render safely across runtime entrypoints

Admitted user-facing startup and interactive CLI sessions SHALL share a single renderer. Redirected, CI, quiet and JSON startup output SHALL NOT include decorative artwork. Supported image terminals SHALL use the image payload; other terminals SHALL use the official character fallback without an alternate logo.

#### Scenario: [CB-SCN-002] Select the terminal rendering path

- **WHEN** runtime terminal facts select a theme and image or character mode
- **THEN** the renderer uses the corresponding installer payload
- **AND** no-color and narrow-terminal behavior is explicit and deterministic

#### Scenario: [CB-SCN-003] Delegate startup rendering

- **WHEN** a launcher passes startup arguments to the JVM
- **THEN** it emits no separate logo
- **AND** runtime startup prints at most one logo after the port guard admits startup
