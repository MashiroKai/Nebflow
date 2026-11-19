# State machine

The executor process lifecycle is unchanged. The following diagrams show the model-selection data lifecycle, including the distinction between a global default and a persisted delegate value.

## Before

```mermaid
stateDiagram-v2
    [*] --> CLIDefault: new dispatch
    CLIDefault --> CLIDefault: continuation
```

## After

```mermaid
stateDiagram-v2
    [*] --> SavedDefault: edit executor setting
    SavedDefault --> DelegateSnapshot: new dispatch reads setting
    DelegateSnapshot --> DelegateSnapshot: continuation uses saved choice
    SavedDefault --> SavedDefault: edit for later dispatches
```

An empty snapshot means no model flag; the CLI resolves its own default on each invocation. The diagrams do not imply a new runtime process state machine.
