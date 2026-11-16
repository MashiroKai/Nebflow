## ADDED Requirements

### Requirement: External executor model defaults

The system SHALL let an owner edit and immediately save a model ID per supported external executor, independently of built-in provider model references. An empty value follows the CLI default. The dialog SHALL state that the setting affects future new dispatches and SHALL mark executors without a verified headless model flag unsupported.

#### Scenario: Dispatch and continuation

- **WHEN** a new external delegate starts
- **THEN** it reads the current executor model setting without restarting and persists that selection with its delegate record
- **AND** subsequent continuation turns use the persisted selection rather than the current default

#### Scenario: Unsupported CLI

- **WHEN** an external CLI has no verified headless model flag
- **THEN** its dialog states that limitation and does not save or pass a model flag
