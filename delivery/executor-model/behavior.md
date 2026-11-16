# Dispatch behavior

## Before

```mermaid
sequenceDiagram
    participant Owner
    participant Stand
    participant CLI
    Owner->>Stand: Start external delegate
    Stand->>CLI: Launch without model argument
    CLI-->>Stand: Use CLI default
    Owner->>Stand: Continue delegate
    Stand->>CLI: Resume without model argument
```

## After

```mermaid
sequenceDiagram
    participant Owner
    participant Stand
    participant Registry as Delegate record
    participant CLI
    Owner->>Stand: Save executor model for future dispatches
    Owner->>Stand: Start external delegate
    Stand->>Stand: Hot-read executor model
    Stand->>Registry: Persist selected value
    Stand->>CLI: Launch with model when supported and nonempty
    Owner->>Stand: Continue delegate
    Stand->>Registry: Read original selection
    Stand->>CLI: Resume with original selection
```

For an empty selection Stand sends no model argument, so the CLI itself resolves its current default. ZCode has no verified headless model argument and is presented as unsupported.
